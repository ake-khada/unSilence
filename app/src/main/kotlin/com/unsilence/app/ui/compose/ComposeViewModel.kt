package com.unsilence.app.ui.compose

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.blossom.AttachmentQuality
import com.unsilence.app.data.blossom.BlossomBlob
import com.unsilence.app.data.blossom.BlossomClient
import com.unsilence.app.data.blossom.BlossomServersStore
import com.unsilence.app.data.blossom.ImageCompressor
import com.unsilence.app.data.blossom.VideoTranscoder
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.settings.SettingsStore
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.repository.UserRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "ComposeViewModel"

// ── Attachment state ────────────────────────────────────────────────────────

sealed interface AttachmentState {
    val uri: Uri
    val id: String
    val displayName: String
    val originalBytes: Long
    val quality: AttachmentQuality

    data class Idle(
        override val uri: Uri,
        override val id: String,
        override val displayName: String,
        override val originalBytes: Long,
        override val quality: AttachmentQuality,
    ) : AttachmentState

    data class Uploading(
        override val uri: Uri,
        override val id: String,
        override val displayName: String,
        override val originalBytes: Long,
        override val quality: AttachmentQuality,
    ) : AttachmentState

    data class Uploaded(
        override val uri: Uri,
        override val id: String,
        override val displayName: String,
        override val originalBytes: Long,
        override val quality: AttachmentQuality,
        val blob: BlossomBlob,
    ) : AttachmentState

    data class Failed(
        override val uri: Uri,
        override val id: String,
        override val displayName: String,
        override val originalBytes: Long,
        override val quality: AttachmentQuality,
        val message: String,
    ) : AttachmentState
}

// ── Relay publish status ────────────────────────────────────────────────────

enum class RelayPublishStatus { Pending, Accepted, Rejected, TimedOut }

// ── Send state (confirm step) ───────────────────────────────────────────────

sealed interface SendState {
    data object Composing : SendState
    data class Confirming(
        val isReply: Boolean,
        val previewContent: String,
        val previewTagsJson: String,
        val notifyCandidates: List<NotifyCandidate>,
        val notifyActive: Set<String>,
    ) : SendState
    data class Publishing(
        val statuses: Map<String, RelayPublishStatus>,
    ) : SendState
    data class Failed(val reason: String) : SendState
    data object Sent : SendState
}

enum class NotifySource { ReplyParent, QuoteAuthor, Mention }

data class NotifyCandidate(
    val pubkey: String,
    val source: NotifySource,
    val displayName: String?,
    val picture: String?,
)

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val relayPool: RelayPool,
    private val memoryEventStore: MemoryEventStore,
    private val userRepository: UserRepository,
    private val contentResolver: ContentResolver,
    private val blossomClient: BlossomClient,
    private val blossomServersStore: BlossomServersStore,
    private val settingsStore: SettingsStore,
    private val imageCompressor: ImageCompressor,
    private val videoTranscoder: VideoTranscoder,
    val ogFetcher: com.unsilence.app.data.relay.OgFetcher,
    val imageDimensionCache: com.unsilence.app.ui.feed.ImageDimensionCache,
    val videoThumbnailCache: com.unsilence.app.ui.feed.VideoThumbnailCache,
) : ViewModel() {

    /** Pubkey for the avatar in the compose UI. */
    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    /** Signed-in user's avatar URL, for the compose avatar. */
    val userAvatarUrl: StateFlow<String?> = pubkeyHex?.let { pk ->
        userRepository.userFlow(pk)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    /** Signed-in user's profile for the compose header. */
    val userEntity: StateFlow<com.unsilence.app.data.memory.UserEntity?> = pubkeyHex?.let { pk ->
        userRepository.userFlow(pk)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    /** True once the note has been signed, published, and inserted into MES. */
    var published by mutableStateOf(false)
        private set

    /** Non-null when signing or publishing failed — shown to the user. */
    var publishError by mutableStateOf<String?>(null)
        private set

    // ── Block-based content model ───────────────────────────────────────────

    private val _blocks = MutableStateFlow<List<ComposeBlock>>(
        listOf(ComposeBlock.Text(""))
    )
    val blocks: StateFlow<List<ComposeBlock>> = _blocks.asStateFlow()

    /** Derived attachment list — preserves existing ComposeScreen binding. */
    val attachments: StateFlow<List<AttachmentState>> = _blocks
        .map { blocks ->
            blocks.filterIsInstance<ComposeBlock.Attachment>()
                .map { it.state }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val canPublish: StateFlow<Boolean> = _blocks
        .map { blocks ->
            val hasText = blocks.any { it is ComposeBlock.Text && it.content.isNotBlank() }
            val hasUploaded = blocks.any {
                it is ComposeBlock.Attachment && it.state is AttachmentState.Uploaded
            }
            val inFlight = blocks.any {
                it is ComposeBlock.Attachment &&
                (it.state is AttachmentState.Idle || it.state is AttachmentState.Uploading)
            }
            (hasText || hasUploaded) && !inFlight
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── Send state (confirm step) ────────────────────────────────────────

    private val _sendState = MutableStateFlow<SendState>(SendState.Composing)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    // ── NIP-36 sensitive toggle ─────────────────────────────────────────────

    private val _isSensitive = MutableStateFlow(false)
    val isSensitive: StateFlow<Boolean> = _isSensitive.asStateFlow()

    fun toggleSensitive() { _isSensitive.value = !_isSensitive.value }

    // ── Focus tracking + mention insertion ──────────────────────────────────

    private val _focusedBlockId = MutableStateFlow<String?>(null)

    fun setFocusedBlock(id: String?) { _focusedBlockId.value = id }

    private val _pendingMentionInsert = MutableStateFlow<Pair<String, String>?>(null) // (blockId, text)
    val pendingMentionInsert: StateFlow<Pair<String, String>?> = _pendingMentionInsert.asStateFlow()

    fun consumeMentionInsert() { _pendingMentionInsert.value = null }

    // ── Mention picker ────────────────────────────────────────────────────

    private val _mentionPickerOpen = MutableStateFlow(false)
    val mentionPickerOpen: StateFlow<Boolean> = _mentionPickerOpen.asStateFlow()

    private val _mentionQuery = MutableStateFlow("")
    val mentionQuery: StateFlow<String> = _mentionQuery.asStateFlow()

    val mentionFollows: StateFlow<List<com.unsilence.app.data.memory.UserEntity>> =
        flow {
            val pk = pubkeyHex ?: ""
            val followPubkeys = memoryEventStore.getFollows(pk) ?: emptySet()
            val users = followPubkeys
                .mapNotNull { memoryEventStore.getUserEntity(it) }
                .sortedBy { it.displayName?.lowercase() ?: it.name?.lowercase() ?: "" }
            emit(users)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mentionSearchResults: StateFlow<List<com.unsilence.app.data.memory.UserEntity>> =
        _mentionQuery
            .debounce(150)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.isBlank()) flowOf(emptyList())
                else memoryEventStore.searchUsersFlow(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openMentionPicker() { _mentionPickerOpen.value = true; _mentionQuery.value = "" }
    fun closeMentionPicker() { _mentionPickerOpen.value = false }
    fun setMentionQuery(q: String) { _mentionQuery.value = q }

    fun selectMention(user: com.unsilence.app.data.memory.UserEntity) {
        val npub = runCatching { user.pubkey.hexToByteArray().toNpub() }.getOrNull() ?: return
        val mentionText = "nostr:$npub "
        val blockId = _focusedBlockId.value
            ?: _blocks.value.firstOrNull { it is ComposeBlock.Text }?.id
            ?: return
        _pendingMentionInsert.value = blockId to mentionText
        closeMentionPicker()
    }

    // ── Emoji picker ──────────────────────────────────────────────────────

    private val _emojiPickerOpen = MutableStateFlow(false)
    val emojiPickerOpen: StateFlow<Boolean> = _emojiPickerOpen.asStateFlow()

    private val _pendingEmojiInsert = MutableStateFlow<Pair<String, String>?>(null) // (blockId, text)
    val pendingEmojiInsert: StateFlow<Pair<String, String>?> = _pendingEmojiInsert.asStateFlow()

    fun consumeEmojiInsert() { _pendingEmojiInsert.value = null }

    val resolvedEmojis: StateFlow<List<CustomEmoji>> =
        pubkeyHex?.let { pk ->
            memoryEventStore.resolvedEmojisFlow(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } ?: MutableStateFlow(emptyList())

    val emojiCategories: StateFlow<List<Pair<String, List<CustomEmoji>>>> =
        pubkeyHex?.let { pk ->
            memoryEventStore.resolvedEmojisBySetFlow(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } ?: MutableStateFlow(emptyList())

    val pinnedEmojiShortcodes: StateFlow<Set<String>> = settingsStore.pinnedEmojiShortcodes

    fun openEmojiPicker() { _emojiPickerOpen.value = true }
    fun closeEmojiPicker() { _emojiPickerOpen.value = false }

    fun selectEmoji(emoji: CustomEmoji) {
        val normalized = normalizeShortcode(emoji.shortcode) ?: run {
            closeEmojiPicker()
            return
        }
        val insertText = ":$normalized: "
        val blockId = _focusedBlockId.value
            ?: _blocks.value.firstOrNull { it is ComposeBlock.Text }?.id
            ?: return
        _pendingEmojiInsert.value = blockId to insertText
        closeEmojiPicker()
    }

    fun toggleEmojiPin(shortcode: String) {
        viewModelScope.launch {
            val current = settingsStore.pinnedEmojiShortcodes.value
            val updated = if (shortcode in current) current - shortcode else current + shortcode
            settingsStore.setPinnedEmojiShortcodes(updated)
        }
    }

    // ── ContentFlow lookup delegates ───────────────────────────────────────

    suspend fun lookupProfile(pubkey: String): com.unsilence.app.data.memory.UserEntity? =
        memoryEventStore.getUserEntity(pubkey)

    fun lookupEvent(eventId: String): com.unsilence.app.data.memory.EventEntity? =
        memoryEventStore.getEventEntity(eventId)

    fun lookupModel(eventId: String): com.unsilence.app.data.model.EventModel? =
        memoryEventStore.getOrParseEventModel(eventId)

    suspend fun fetchOgMetadata(url: String): com.unsilence.app.data.relay.OgMetadata? =
        ogFetcher.fetch(url)

    fun updateTextBlock(id: String, text: String) {
        _blocks.update { blocks ->
            blocks.map { block ->
                if (block is ComposeBlock.Text && block.id == id) {
                    block.copy(content = text)
                } else block
            }
        }
    }

    init {
        viewModelScope.launch { blossomServersStore.initialize() }
    }

    /** Manually trigger upload for a single idle attachment. */
    fun startUpload(id: String) {
        val block = _blocks.value.firstOrNull {
            it is ComposeBlock.Attachment && it.state.id == id
        } as? ComposeBlock.Attachment ?: return
        val idle = block.state as? AttachmentState.Idle ?: return
        viewModelScope.launch { uploadAttachment(idle) }
    }

    private fun defaultImageQuality(): AttachmentQuality {
        // Settings slider uses dimSteps: 1024 / 1600 / 2048 / 0(original).
        val maxDim = blossomServersStore.imageMaxDim.value
        return when {
            maxDim == 0 -> AttachmentQuality.ORIGINAL
            maxDim <= 1024 -> AttachmentQuality.SMALL
            maxDim <= 1600 -> AttachmentQuality.STANDARD
            else -> AttachmentQuality.HIGH
        }
    }

    private fun defaultVideoQuality(): AttachmentQuality {
        return when (blossomServersStore.videoQuality.value) {
            VideoTranscoder.Quality.SMALL -> AttachmentQuality.SMALL
            VideoTranscoder.Quality.STANDARD -> AttachmentQuality.STANDARD
            VideoTranscoder.Quality.HIGH -> AttachmentQuality.HIGH
        }
    }

    fun addAttachments(uris: List<Uri>) {
        val newAttachments = uris.map { uri ->
            val mime = contentResolver.getType(uri) ?: ""
            val defaultQuality = if (mime.startsWith("video/"))
                defaultVideoQuality() else defaultImageQuality()
            ComposeBlock.Attachment(
                state = AttachmentState.Idle(
                    uri = uri,
                    id = UUID.randomUUID().toString(),
                    displayName = queryDisplayName(uri),
                    originalBytes = queryFileSize(uri),
                    quality = defaultQuality,
                )
            )
        }
        _blocks.update { blocks ->
            val lastBlock = blocks.lastOrNull()
            if (lastBlock is ComposeBlock.Text && lastBlock.content.isEmpty()) {
                // Trailing empty Text already exists — insert new
                // attachments BEFORE it, keep that Text as the
                // type-here target.
                blocks.dropLast(1) + newAttachments + lastBlock
            } else {
                // No trailing empty Text — append attachments + a
                // fresh empty Text block for continued typing.
                blocks + newAttachments + ComposeBlock.Text("")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else null
            } ?: uri.lastPathSegment ?: "attachment"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "attachment"
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun removeAttachment(id: String) {
        _blocks.update { blocks ->
            blocks.filterNot {
                it is ComposeBlock.Attachment && it.state.id == id
            }
        }
    }

    fun retryAttachment(id: String) {
        _blocks.update { blocks ->
            blocks.map { block ->
                if (
                    block is ComposeBlock.Attachment &&
                    block.state.id == id &&
                    block.state is AttachmentState.Failed
                ) {
                    val f = block.state as AttachmentState.Failed
                    block.copy(
                        state = AttachmentState.Idle(
                            uri = f.uri,
                            id = f.id,
                            displayName = f.displayName,
                            originalBytes = f.originalBytes,
                            quality = f.quality,
                        )
                    )
                } else block
            }
        }
    }

    fun updateAttachmentQuality(id: String, quality: AttachmentQuality) {
        _blocks.update { blocks ->
            blocks.map { block ->
                if (block is ComposeBlock.Attachment && block.state.id == id) {
                    val newState = when (val s = block.state) {
                        is AttachmentState.Idle -> s.copy(quality = quality)
                        is AttachmentState.Failed -> s.copy(quality = quality)
                        else -> s // Uploading and Uploaded are locked
                    }
                    block.copy(state = newState)
                } else block
            }
        }
    }

    private suspend fun uploadAttachment(idle: AttachmentState.Idle) {
        updateAttachmentState(idle.id) {
            AttachmentState.Uploading(
                uri = idle.uri,
                id = idle.id,
                displayName = idle.displayName,
                originalBytes = idle.originalBytes,
                quality = idle.quality,
            )
        }

        val server = blossomServersStore.selectedServer.value
        val sourceMime = contentResolver.getType(idle.uri) ?: "application/octet-stream"

        val result = runCatching {
            if (sourceMime.startsWith("video/")) {
                uploadVideo(idle.uri, server, idle.quality)
            } else {
                uploadImage(idle.uri, sourceMime, server, idle.quality)
            }
        }

        updateAttachmentState(idle.id) {
            result.fold(
                onSuccess = { blob ->
                    AttachmentState.Uploaded(
                        uri = idle.uri,
                        id = idle.id,
                        displayName = idle.displayName,
                        originalBytes = idle.originalBytes,
                        quality = idle.quality,
                        blob = blob,
                    )
                },
                onFailure = { ex ->
                    Log.e(TAG, "Upload failed for ${idle.uri}", ex)
                    AttachmentState.Failed(
                        uri = idle.uri,
                        id = idle.id,
                        displayName = idle.displayName,
                        originalBytes = idle.originalBytes,
                        quality = idle.quality,
                        message = ex.message ?: "Upload failed",
                    )
                },
            )
        }
    }

    private fun updateAttachmentState(
        id: String,
        newState: () -> AttachmentState,
    ) {
        _blocks.update { blocks ->
            blocks.map { block ->
                if (block is ComposeBlock.Attachment && block.state.id == id) {
                    block.copy(state = newState())
                } else block
            }
        }
    }

    private suspend fun uploadImage(
        uri: Uri,
        sourceMime: String,
        server: String,
        quality: AttachmentQuality,
    ): BlossomBlob {
        val rawBytes = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read attachment")
        }

        val (maxDim, jpegQuality) = quality.imageSettings()

        val (compressed, uploadMime) = if (quality == AttachmentQuality.ORIGINAL || maxDim == 0) {
            rawBytes to sourceMime
        } else {
            imageCompressor.compressImage(
                bytes = rawBytes,
                maxDimension = maxDim,
                quality = jpegQuality,
            ) to "image/jpeg"
        }

        return blossomClient.upload(
            bytes = compressed,
            mimeType = uploadMime,
            serverUrl = server,
        ).getOrThrow()
    }

    private suspend fun uploadVideo(
        uri: Uri,
        server: String,
        quality: AttachmentQuality,
    ): BlossomBlob {
        val transcoded = videoTranscoder.transcode(uri, quality.videoQuality())
        try {
            val blob = blossomClient.upload(
                file = transcoded.file,
                mimeType = transcoded.mimeType,
                serverUrl = server,
            ).getOrThrow()
            return blob.copy(
                durationMs = transcoded.durationMs,
                dimensions = transcoded.width to transcoded.height,
            )
        } finally {
            transcoded.file.delete()
        }
    }

    /** Reset state for reuse — ViewModel is activity-scoped, survives recomposition. */
    fun reset() {
        published = false
        publishError = null
        replyToRow = null
        quoteRow = null
        quoteEventId = null
        _sendState.value = SendState.Composing
        _focusedBlockId.value = null
        _pendingMentionInsert.value = null
        _mentionPickerOpen.value = false
        _mentionQuery.value = ""
        _emojiPickerOpen.value = false
        _pendingEmojiInsert.value = null
        _isSensitive.value = false
        _blocks.value = listOf(ComposeBlock.Text(""))
    }

    // ── Reply mode ──────────────────────────────────────────────────────────

    /** Parent note for reply mode, looked up from MES. */
    var replyToRow by mutableStateOf<com.unsilence.app.data.memory.FeedRow?>(null)
        private set

    fun loadReplyTo(eventId: String) {
        val rows = memoryEventStore.feedRowsByIds(setOf(eventId))
        replyToRow = rows.firstOrNull()
    }

    // ── Quote mode ─────────────────────────────────────────────────────────

    /** Quoted note preview, looked up from MES. */
    var quoteRow by mutableStateOf<com.unsilence.app.data.memory.FeedRow?>(null)
        private set

    /** Raw event ID for the nevent reference appended at publish time. */
    var quoteEventId by mutableStateOf<String?>(null)
        private set

    fun loadQuoteTo(eventId: String) {
        quoteEventId = eventId
        val rows = memoryEventStore.feedRowsByIds(setOf(eventId))
        quoteRow = rows.firstOrNull()
    }

    // ── Confirm flow ────────────────────────────────────────────────────────

    fun requestPublish(isReply: Boolean) {
        if (_sendState.value !is SendState.Composing) return
        if (!canPublish.value) return

        val current = _blocks.value
        val content = buildPreviewContent(current, isReply)
        val tags = buildPreviewTags(current, isReply)
        val tagsJsonStr = tagsToJson(tags.map { it.toList() })
        val candidates = buildNotifyCandidates(current, isReply)

        _sendState.value = SendState.Confirming(
            isReply = isReply,
            previewContent = content,
            previewTagsJson = tagsJsonStr,
            notifyCandidates = candidates,
            notifyActive = candidates.map { it.pubkey }.toSet(),
        )
    }

    fun confirmPublish() {
        val state = _sendState.value as? SendState.Confirming ?: return
        if (state.isReply) publishReply(state.notifyActive) else publishNote(state.notifyActive)
    }

    fun retryPublish() {
        val state = _sendState.value as? SendState.Failed ?: return
        // Re-enter Composing then re-request — blocks + reply state are intact
        _sendState.value = SendState.Composing
        val isReply = replyToRow != null
        requestPublish(isReply)
        val confirming = _sendState.value as? SendState.Confirming ?: return
        if (confirming.isReply) publishReply(confirming.notifyActive) else publishNote(confirming.notifyActive)
    }

    fun toggleNotify(pubkey: String) {
        val state = _sendState.value as? SendState.Confirming ?: return
        val newActive = if (pubkey in state.notifyActive)
            state.notifyActive - pubkey
        else
            state.notifyActive + pubkey
        _sendState.value = state.copy(notifyActive = newActive)
    }

    private fun buildNotifyCandidates(
        blocks: List<ComposeBlock>,
        isReply: Boolean,
    ): List<NotifyCandidate> {
        val ownPubkey = pubkeyHex ?: ""
        val candidates = mutableListOf<NotifyCandidate>()
        val seen = mutableSetOf(ownPubkey)

        if (isReply) {
            val parent = replyToRow
            if (parent != null && parent.pubkey !in seen) {
                candidates.add(buildCandidate(parent.pubkey, NotifySource.ReplyParent))
                seen.add(parent.pubkey)
            }
        } else {
            val quoted = quoteRow?.pubkey
            if (quoted != null && quoted !in seen) {
                candidates.add(buildCandidate(quoted, NotifySource.QuoteAuthor))
                seen.add(quoted)
            }
        }

        val content = blocksToContent(blocks)
        extractMentionPubkeys(content).forEach { pk ->
            if (pk !in seen) {
                candidates.add(buildCandidate(pk, NotifySource.Mention))
                seen.add(pk)
            }
        }
        return candidates
    }

    private fun buildCandidate(pubkey: String, source: NotifySource): NotifyCandidate {
        val user = memoryEventStore.getUserEntity(pubkey)
        return NotifyCandidate(
            pubkey = pubkey,
            source = source,
            displayName = user?.displayName?.takeIf { it.isNotBlank() }
                ?: user?.name?.takeIf { it.isNotBlank() },
            picture = user?.picture,
        )
    }

    fun cancelSend() {
        // Cannot cancel during Publishing — event already broadcast
        if (_sendState.value is SendState.Publishing) return
        _sendState.value = SendState.Composing
    }

    private fun buildPreviewContent(blocks: List<ComposeBlock>, isReply: Boolean): String {
        var content = blocksToContent(blocks)
        val qId = quoteEventId
        val quotedAuthor = quoteRow?.pubkey
        if (!isReply && qId != null) {
            val nevent = com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
                .create(qId, quotedAuthor, null, null as com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl?)
            content = if (content.isBlank()) "nostr:$nevent"
                else "$content\n\nnostr:$nevent"
        }
        return content
    }

    private fun buildPreviewTags(blocks: List<ComposeBlock>, isReply: Boolean): List<Array<String>> {
        val tags = mutableListOf<Array<String>>()
        val existingPTags = mutableSetOf<String>()
        tags.addAll(blocksToImetaTags(blocks))
        if (isReply) {
            val parent = replyToRow ?: return tags
            val threadRootId = parent.rootId ?: parent.id
            tags.add(arrayOf("e", threadRootId, "", "root"))
            if (parent.id != threadRootId) {
                tags.add(arrayOf("e", parent.id, "", "reply"))
            }
            tags.add(arrayOf("p", parent.pubkey))
            existingPTags.add(parent.pubkey)
        } else {
            val qId = quoteEventId
            val quotedAuthor = quoteRow?.pubkey
            if (qId != null) {
                tags.add(arrayOf("q", qId, "", quotedAuthor ?: ""))
                if (quotedAuthor != null) {
                    tags.add(arrayOf("p", quotedAuthor))
                    existingPTags.add(quotedAuthor)
                }
            }
        }
        // Add p-tags for @mentions in content
        val content = blocksToContent(blocks)
        extractMentionPubkeys(content).forEach { pk ->
            if (pk !in existingPTags) tags.add(arrayOf("p", pk))
        }
        // Add emoji tags for :shortcode: tokens
        tags.addAll(extractEmojiTags(content))
        if (_isSensitive.value) tags.add(arrayOf("content-warning", ""))
        return tags
    }

    // ── Publishing ──────────────────────────────────────────────────────────

    private fun publishReply(activeNotifyPubkeys: Set<String>) {
        val parent = replyToRow ?: return
        publishError = null
        viewModelScope.launch {
            val current = _blocks.value
            val threadRootId = parent.rootId ?: parent.id
            val replyToId = parent.id
            val replyToPubkey = parent.pubkey

            val finalContent = blocksToContent(current)
            val imetaTags = blocksToImetaTags(current)

            val mentionPubkeys = extractMentionPubkeys(finalContent)
            val emojiTags = extractEmojiTags(finalContent)
            val template = TextNoteEvent.build(note = finalContent, createdAt = System.currentTimeMillis() / 1000L) {
                add(arrayOf("e", threadRootId, "", "root"))
                if (replyToId != threadRootId) {
                    add(arrayOf("e", replyToId, "", "reply"))
                }
                if (replyToPubkey in activeNotifyPubkeys) {
                    add(arrayOf("p", replyToPubkey))
                }
                mentionPubkeys.forEach { pk ->
                    if (pk != replyToPubkey && pk in activeNotifyPubkeys) {
                        add(arrayOf("p", pk))
                    }
                }
                imetaTags.forEach { add(it) }
                emojiTags.forEach { add(it) }
                if (_isSensitive.value) add(arrayOf("content-warning", ""))
            }
            val signed = signingManager.sign(template) ?: run {
                _sendState.value = SendState.Failed("Signing failed — check your key or Amber connection")
                return@launch
            }

            val eventJson = toEventJson(signed)
            publishAndTrack(signed.id, eventJson, replyToId, threadRootId) {
                val nowMs = System.currentTimeMillis()
                val parsedTags = signed.tags.map { it.toList() }
                memoryEventStore.insert(
                    NostrEvent(
                        id = signed.id,
                        pubkey = signed.pubKey,
                        kind = signed.kind,
                        content = signed.content,
                        createdAt = signed.createdAt,
                        tags = parsedTags,
                        tagsJson = tagsToJson(parsedTags),
                        sig = signed.sig,
                        relayUrl = "local",
                        replyToId = replyToId,
                        rootId = threadRootId,
                        hasContentWarning = false,
                        contentWarningReason = null,
                        firstSeenAt = nowMs,
                        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("local") },
                    )
                )
            }
        }
    }

    private fun publishNote(activeNotifyPubkeys: Set<String>) {
        publishError = null
        viewModelScope.launch {
            val current = _blocks.value
            var finalContent = blocksToContent(current)
            val imetaTags = blocksToImetaTags(current)

            // Append nostr:nevent reference for quote posts
            val qId = quoteEventId
            val quotedAuthor = quoteRow?.pubkey
            if (qId != null) {
                val nevent = com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
                    .create(qId, quotedAuthor, null, null as com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl?)
                finalContent = if (finalContent.isBlank()) "nostr:$nevent"
                    else "$finalContent\n\nnostr:$nevent"
            }

            val mentionPubkeys = extractMentionPubkeys(finalContent)
            val emojiTags = extractEmojiTags(finalContent)
            val existingPTags = mutableSetOf<String>()
            val template = TextNoteEvent.build(note = finalContent) {
                imetaTags.forEach { add(it) }
                if (qId != null) {
                    add(arrayOf("q", qId, "", quotedAuthor ?: ""))
                    if (quotedAuthor != null && quotedAuthor in activeNotifyPubkeys) {
                        add(arrayOf("p", quotedAuthor))
                        existingPTags.add(quotedAuthor)
                    }
                }
                mentionPubkeys.forEach { pk ->
                    if (pk !in existingPTags && pk in activeNotifyPubkeys) {
                        add(arrayOf("p", pk))
                    }
                }
                emojiTags.forEach { add(it) }
                if (_isSensitive.value) add(arrayOf("content-warning", ""))
            }
            val signed = signingManager.sign(template) ?: run {
                _sendState.value = SendState.Failed("Signing failed — check your key or Amber connection")
                return@launch
            }

            val eventJson = toEventJson(signed)
            publishAndTrack(signed.id, eventJson, null, null) {
                val nowMs = System.currentTimeMillis()
                val parsedTags = signed.tags.map { it.toList() }
                memoryEventStore.insert(
                    NostrEvent(
                        id = signed.id,
                        pubkey = signed.pubKey,
                        kind = signed.kind,
                        content = signed.content,
                        createdAt = signed.createdAt,
                        tags = parsedTags,
                        tagsJson = tagsToJson(parsedTags),
                        sig = signed.sig,
                        relayUrl = "local",
                        replyToId = null,
                        rootId = null,
                        hasContentWarning = false,
                        contentWarningReason = null,
                        firstSeenAt = nowMs,
                        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("local") },
                    )
                )
            }
        }
    }

    /**
     * Shared publish-and-track: broadcasts to write relays, enters Publishing state,
     * tracks per-relay OK responses with 6s timeout, then inserts into MES on success.
     */
    private suspend fun publishAndTrack(
        eventId: String,
        eventJson: String,
        replyToId: String?,
        rootId: String?,
        insertIntoMes: suspend () -> Unit,
    ) {
        val ownPk = pubkeyHex ?: ""
        val writeRelays = memoryEventStore.writeRelaysFor(ownPk)
            .mapNotNull { com.unsilence.app.data.relay.normalizeRelayUrl(it) }
            .ifEmpty { com.unsilence.app.data.relay.GLOBAL_RELAY_URLS }

        // Enter Publishing state with all relays Pending
        val statusMap = ConcurrentHashMap<String, RelayPublishStatus>()
        writeRelays.forEach { statusMap[it] = RelayPublishStatus.Pending }
        _sendState.value = SendState.Publishing(statuses = HashMap(statusMap))

        // Register OK callback before sending
        relayPool.registerPublishCallback(eventId) { relayUrl, success, _ ->
            val normalized = com.unsilence.app.data.relay.normalizeRelayUrl(relayUrl)
            val key = normalized ?: relayUrl
            if (statusMap.containsKey(key)) {
                statusMap[key] = if (success) RelayPublishStatus.Accepted else RelayPublishStatus.Rejected
                _sendState.value = SendState.Publishing(statuses = HashMap(statusMap))
            }
        }

        try {
            // Broadcast to relays
            withContext(Dispatchers.IO) {
                relayPool.publish(eventJson)
            }

            // Wait up to 6s for all relays to respond
            val deadline = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < deadline) {
                if (statusMap.values.none { it == RelayPublishStatus.Pending }) break
                delay(200)
            }

            // Time out any remaining Pending relays
            for ((url, status) in statusMap) {
                if (status == RelayPublishStatus.Pending) {
                    statusMap[url] = RelayPublishStatus.TimedOut
                }
            }
            _sendState.value = SendState.Publishing(statuses = HashMap(statusMap))

            val acceptedCount = statusMap.values.count { it == RelayPublishStatus.Accepted }
            if (acceptedCount > 0) {
                // Insert into MES for local state
                withContext(Dispatchers.IO) { insertIntoMes() }
                // Brief pause to show final state
                delay(800)
                _blocks.value = listOf(ComposeBlock.Text(""))
                published = true
                _sendState.value = SendState.Sent
            } else {
                _sendState.value = SendState.Failed("No relays accepted the event")
            }
        } finally {
            relayPool.unregisterPublishCallback(eventId)
        }
    }

    // ── Block → content/tags ────────────────────────────────────────────────

    private fun blocksToContent(blocks: List<ComposeBlock>): String {
        val parts = blocks.mapNotNull { block ->
            when (block) {
                is ComposeBlock.Text ->
                    block.content.takeIf { it.isNotBlank() }
                is ComposeBlock.Attachment ->
                    (block.state as? AttachmentState.Uploaded)?.blob?.url
            }
        }
        return parts.joinToString("\n\n")
    }

    private val NOSTR_MENTION_REGEX = Regex("nostr:n(?:pub|profile)1[a-z0-9]+", RegexOption.IGNORE_CASE)

    /** Extract pubkeys from nostr:npub/nprofile URIs in content text. */
    private fun extractMentionPubkeys(content: String): Set<String> {
        val pubkeys = mutableSetOf<String>()
        NOSTR_MENTION_REGEX.findAll(content).forEach { match ->
            val bech32 = match.value.removePrefix("nostr:")
            runCatching {
                when (val entity = Nip19Parser.uriToRoute(bech32)?.entity) {
                    is NPub -> pubkeys.add(entity.hex)
                    is NProfile -> pubkeys.add(entity.hex)
                    else -> {}
                }
            }
        }
        return pubkeys
    }

    /** Extract ["emoji", shortcode, url] tags for resolved :shortcode: tokens in content. */
    private fun extractEmojiTags(content: String): List<Array<String>> {
        val pk = pubkeyHex ?: return emptyList()
        val resolved = memoryEventStore.resolvedEmojisFor(pk)
        if (resolved.isEmpty()) return emptyList()
        // Key by normalized shortcode so content tokens (already normalized
        // at insert time) match against the original emoji set.
        val byNormalized = resolved
            .mapNotNull { emoji ->
                normalizeShortcode(emoji.shortcode)?.let { norm -> norm to emoji }
            }
            .toMap()
        val tags = mutableListOf<Array<String>>()
        val seen = mutableSetOf<String>()
        var i = 0
        while (i < content.length) {
            if (content[i] == ':' && i + 2 < content.length) {
                val end = content.indexOf(':', i + 1)
                if (end > i + 1) {
                    val shortcode = content.substring(i + 1, end)
                    val emoji = byNormalized[shortcode]
                    if (emoji != null && seen.add(shortcode)) {
                        tags.add(arrayOf("emoji", shortcode, emoji.url))
                    }
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return tags
    }

    /** NIP-30 requires shortcodes to be [a-zA-Z0-9_]+. Normalize by
     *  replacing whitespace/hyphens/dots with underscores and stripping
     *  all other non-conforming chars. Empty result returns null. */
    private fun normalizeShortcode(raw: String): String? {
        val normalized = buildString {
            for (c in raw) {
                when {
                    c.isLetterOrDigit() -> append(c)
                    c.isWhitespace() || c == '-' || c == '.' || c == '_' -> append('_')
                    // Drop everything else (punctuation, emoji, etc.)
                }
            }
        }
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun blocksToImetaTags(blocks: List<ComposeBlock>): List<Array<String>> {
        return blocks
            .filterIsInstance<ComposeBlock.Attachment>()
            .mapNotNull { (it.state as? AttachmentState.Uploaded)?.blob }
            .map { blob ->
                buildList {
                    add("imeta")
                    add("url ${blob.url}")
                    add("m ${blob.mimeType}")
                    add("x ${blob.sha256}")
                    add("size ${blob.sizeBytes}")
                    blob.dimensions?.let { (w, h) -> add("dim ${w}x${h}") }
                    blob.blurhash?.let { add("blurhash $it") }
                    blob.thumbnailUrl?.let { add("thumb $it") }
                    blob.durationMs?.let { ms ->
                        if (ms > 0) add("duration ${ms / 1000}")
                    }
                }.toTypedArray()
            }
    }
}
