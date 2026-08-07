package com.unsilence.app.ui.compose

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.blossom.AttachmentQuality
import com.unsilence.app.data.blossom.BlossomBlob
import com.unsilence.app.data.blossom.BlossomClient
import com.unsilence.app.data.blossom.BlossomServersStore
import com.unsilence.app.data.blossom.ImageCompressor
import com.unsilence.app.data.blossom.VideoTranscoder
import com.unsilence.app.data.drafts.Draft
import com.unsilence.app.data.drafts.DraftBlock
import com.unsilence.app.data.drafts.DraftContext
import com.unsilence.app.data.drafts.DraftStore
import com.unsilence.app.data.drafts.DraftPoll
import com.unsilence.app.data.drafts.DraftPollOption
import com.unsilence.app.data.drafts.toBlob
import com.unsilence.app.data.drafts.toDraftAttachment
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.resolveDisplayModel
import com.unsilence.app.data.settings.SettingsStore
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.sortMentionsByWotRank
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.repository.UserRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "ComposeViewModel"
private const val MENTION_SEARCH_RESULT_LIMIT = 50

internal class ComposeSessionGate(initialSessionKey: String? = null) {
    var activeSessionKey: String? = initialSessionKey
        private set

    fun begin(sessionKey: String): Boolean {
        if (activeSessionKey == sessionKey) return false
        activeSessionKey = sessionKey
        return true
    }

    fun finish(sessionKey: String): Boolean {
        if (activeSessionKey != sessionKey) return false
        activeSessionKey = null
        return true
    }
}

/** Small generation gate for the external-signer round trip. It rejects a
 * duplicate Confirm tap and makes a cancelled attempt unable to publish later. */
internal class SigningAttemptGate {
    private var generation = 0L
    private var activeToken: Long? = null

    fun begin(): Long? {
        if (activeToken != null) return null
        return (++generation).also { activeToken = it }
    }

    fun isCurrent(token: Long): Boolean = activeToken == token

    fun complete(token: Long) {
        if (activeToken == token) activeToken = null
    }

    fun cancel() {
        generation++
        activeToken = null
    }
}

@androidx.compose.runtime.Immutable
data class PollDraftOption(val id: String, val label: String = "")

@androidx.compose.runtime.Immutable
data class PollDraft(
    val enabled: Boolean = false,
    val options: List<PollDraftOption> = emptyList(),
    val multipleChoice: Boolean = false,
    val durationSeconds: Long? = null,
)

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
        val phase: AttachmentUploadPhase,
        val progressPercent: Int? = null,
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

enum class AttachmentUploadPhase { Preparing, Transcoding, Uploading }

// ── Relay publish status ────────────────────────────────────────────────────

enum class RelayPublishStatus { Pending, Accepted, Rejected, TimedOut }

// ── Send state (confirm step) ───────────────────────────────────────────────

sealed interface SendState {
    data object Composing : SendState
    data class Confirming(
        val payloadState: PublishPayloadState,
        val payload: PublishPayload,
        val notifyCandidates: List<NotifyCandidate>,
        val notifyActive: Set<String>,
    ) : SendState
    data class Publishing(
        val statuses: Map<String, RelayPublishStatus>,
        val confirmation: Confirming,
    ) : SendState
    data class Failed(
        val reason: String,
        val confirmation: Confirming,
    ) : SendState
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
    private val savedStateHandle: SavedStateHandle,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val relayPool: RelayPool,
    private val memoryEventStore: MemoryEventStore,
    private val userRepository: UserRepository,
    private val contentResolver: ContentResolver,
    private val blossomClient: BlossomClient,
    private val blossomServersStore: BlossomServersStore,
    private val settingsStore: SettingsStore,
    private val draftStore: DraftStore,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val imageCompressor: ImageCompressor,
    private val videoTranscoder: VideoTranscoder,
    val ogFetcher: com.unsilence.app.data.relay.OgFetcher,
    val imageDimensionCache: com.unsilence.app.ui.feed.ImageDimensionCache,
    val videoThumbnailCache: com.unsilence.app.ui.feed.VideoThumbnailCache,
) : ViewModel() {

    private val restoredEditor = decodeComposeEditor(
        savedStateHandle.get<Bundle>(EDITOR_STATE_KEY)?.getString(EDITOR_JSON_KEY),
    )
    private val composeSessionGate = ComposeSessionGate(
        savedStateHandle.get<String>(ACTIVE_SESSION_KEY).takeIf { restoredEditor != null },
    )

    internal fun beginComposeSession(sessionKey: String): Boolean {
        val beginsNewSession = composeSessionGate.begin(sessionKey)
        if (beginsNewSession) {
            savedStateHandle[ACTIVE_SESSION_KEY] = sessionKey
            savedStateHandle.remove<Bundle>(EDITOR_STATE_KEY)
        }
        return beginsNewSession
    }

    internal fun finishComposeSession(sessionKey: String) {
        if (composeSessionGate.finish(sessionKey)) {
            savedStateHandle.remove<String>(ACTIVE_SESSION_KEY)
            savedStateHandle.remove<Bundle>(EDITOR_STATE_KEY)
        }
    }

    /** Pubkey for the avatar in the compose UI. */
    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    /** Signed-in user's profile for the compose header. */
    val userEntity: StateFlow<com.unsilence.app.data.memory.UserEntity?> = pubkeyHex?.let { pk ->
        userRepository.userFlow(pk)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    /** Signed-in user's avatar URL, for the compose avatar — derived from [userEntity]
     *  so there's a single upstream userFlow subscription. */
    val userAvatarUrl: StateFlow<String?> = userEntity
        .map { it?.picture }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True once the note has been signed, published, and inserted into MES. */
    var published by mutableStateOf(false)
        private set

    /** Non-null when signing or publishing failed — shown to the user. */
    var publishError by mutableStateOf<String?>(null)
        private set

    // ── Block-based content model ───────────────────────────────────────────

    private val _blocks = MutableStateFlow(
        restoredEditor?.restoreBlocks() ?: listOf(ComposeBlock.Text("")),
    )
    val blocks: StateFlow<List<ComposeBlock>> = _blocks.asStateFlow()
    private val attachmentUploadJobs = mutableMapOf<String, Job>()

    private val _pollDraft = MutableStateFlow(restoredEditor?.restorePoll() ?: PollDraft())
    val pollDraft: StateFlow<PollDraft> = _pollDraft.asStateFlow()

    private fun newPollOption() = PollDraftOption(UUID.randomUUID().toString().replace("-", "").take(10))

    fun togglePoll() {
        _pollDraft.update { current ->
            if (current.enabled) PollDraft()
            else PollDraft(
                enabled = true,
                options = listOf(newPollOption(), newPollOption()),
                durationSeconds = 86_400L,
            )
        }
    }

    fun updatePollOption(id: String, label: String) {
        _pollDraft.update { draft ->
            draft.copy(options = draft.options.map { if (it.id == id) it.copy(label = label.take(300)) else it })
        }
    }

    fun addPollOption() {
        _pollDraft.update { draft ->
            if (!draft.enabled || draft.options.size >= 10) draft
            else draft.copy(options = draft.options + newPollOption())
        }
    }

    fun removePollOption(id: String) {
        _pollDraft.update { draft ->
            if (draft.options.size <= 2) draft
            else draft.copy(options = draft.options.filterNot { it.id == id })
        }
    }

    fun setPollMultipleChoice(enabled: Boolean) {
        _pollDraft.update { it.copy(multipleChoice = enabled) }
    }

    fun setPollDuration(seconds: Long?) {
        _pollDraft.update { it.copy(durationSeconds = seconds?.takeIf { value -> value > 0L }) }
    }

    /** Derived attachment list — preserves existing ComposeScreen binding. */
    val attachments: StateFlow<List<AttachmentState>> = _blocks
        .map { blocks ->
            blocks.filterIsInstance<ComposeBlock.Attachment>()
                .map { it.state }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasAnyUserContent: StateFlow<Boolean> = _blocks
        .map { blocks ->
            blocks.any { block ->
                when (block) {
                    is ComposeBlock.Text -> block.content.isNotBlank()
                    is ComposeBlock.Attachment -> true
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasDraftableContent: StateFlow<Boolean> = combine(_blocks, _pollDraft) { blocks, poll ->
            draftBlocksFrom(blocks).isNotEmpty() || poll.enabled
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasUnsavedMedia: StateFlow<Boolean> = _blocks
        .map { blocks ->
            blocks.any {
                it is ComposeBlock.Attachment && it.state !is AttachmentState.Uploaded
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val canPublish: StateFlow<Boolean> = combine(_blocks, _pollDraft) { blocks, poll ->
            val hasText = blocks.any { it is ComposeBlock.Text && it.content.isNotBlank() }
            val hasUploaded = blocks.any {
                it is ComposeBlock.Attachment && it.state is AttachmentState.Uploaded
            }
            val inFlight = blocks.any {
                it is ComposeBlock.Attachment &&
                (it.state is AttachmentState.Idle || it.state is AttachmentState.Uploading)
            }
            if (poll.enabled) {
                hasText && poll.options.count { it.label.isNotBlank() } >= 2 && !inFlight
            } else {
                (hasText || hasUploaded) && !inFlight
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── Send state (confirm step) ────────────────────────────────────────

    private val _sendState = MutableStateFlow<SendState>(SendState.Composing)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()
    private val signingAttemptGate = SigningAttemptGate()
    private var signingJob: Job? = null

    // ── NIP-36 sensitive toggle ─────────────────────────────────────────────

    private val _isSensitive = MutableStateFlow(restoredEditor?.isSensitive ?: false)
    val isSensitive: StateFlow<Boolean> = _isSensitive.asStateFlow()

    private val _savedDraftFingerprint = MutableStateFlow<String?>(null)
    val hasUnsavedDraftChanges: StateFlow<Boolean> = combine(
        _blocks,
        _isSensitive,
        _savedDraftFingerprint,
        _pollDraft,
    ) { blocks, isSensitive, savedFingerprint, poll ->
        (hasAnyContent(blocks) || poll.enabled) &&
            currentContentFingerprint(blocks, isSensitive, poll) != savedFingerprint
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
    private val _mentionWotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val mentionWotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_mentionWotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val mentionFollows: StateFlow<List<UserEntity>> = pubkeyHex?.let { pk ->
        combine(
            memoryEventStore.followsFlow(pk),
            memoryEventStore.profileSignalFlow,
            memoryEventStore.wotSignalFlow,
        ) { followPubkeys, _, _ ->
            val users = followPubkeys
                .mapNotNull { memoryEventStore.getUserEntity(it) }
            val sorted = sortMentionsByWotRank(users, memoryEventStore::wotFor)
            updateMentionWotSubjects(sorted)
            sorted
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    } ?: MutableStateFlow(emptyList())

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mentionSearchResults: StateFlow<List<UserEntity>> =
        _mentionQuery
            .debounce(150)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.isBlank()) flowOf(emptyList())
                else combine(
                    memoryEventStore.searchUsersFlow(q),
                    memoryEventStore.wotSignalFlow,
                ) { users, _ ->
                    val sorted = sortMentionsByWotRank(users, memoryEventStore::wotFor)
                        .take(MENTION_SEARCH_RESULT_LIMIT)
                    updateMentionWotSubjects(sorted)
                    sorted
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openMentionPicker() { _mentionPickerOpen.value = true; _mentionQuery.value = "" }
    fun closeMentionPicker() { _mentionPickerOpen.value = false }
    fun setMentionQuery(q: String) { _mentionQuery.value = q }

    private fun updateMentionWotSubjects(users: List<UserEntity>) {
        val subjects = users.map { it.pubkey }.toSet()
        _mentionWotSubjects.value = subjects
        wotHydrationCoalescer.requestHydration(subjects)
    }

    fun selectMention(user: UserEntity) {
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

    // Picker selections are payload data, not just editor text. Retain their URLs so
    // an emoji-set hydration/replacement race cannot publish an untagged shortcode.
    private val _selectedEmojiUrls = MutableStateFlow(
        restoredEditor?.selectedEmojiUrls.orEmpty(),
    )

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
        val normalized = normalizeComposeShortcode(emoji.shortcode) ?: run {
            closeEmojiPicker()
            return
        }
        val insertText = ":$normalized: "
        val blockId = _focusedBlockId.value
            ?: _blocks.value.firstOrNull { it is ComposeBlock.Text }?.id
            ?: return
        _selectedEmojiUrls.update { it + (normalized to emoji.url) }
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

    fun hasCachedOgMetadata(url: String): Boolean =
        ogFetcher.hasCached(url)

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
        savedStateHandle.setSavedStateProvider(EDITOR_STATE_KEY) {
            Bundle().apply {
                if (composeSessionGate.activeSessionKey != null) {
                    encodeComposeEditor(
                        captureComposeEditor(
                            blocks = _blocks.value,
                            poll = _pollDraft.value,
                            isSensitive = _isSensitive.value,
                            selectedEmojiUrls = _selectedEmojiUrls.value,
                        ),
                    )?.let { putString(EDITOR_JSON_KEY, it) }
                }
            }
        }
    }

    fun restoreDraft(draft: Draft) {
        _selectedEmojiUrls.value = emptyMap()
        val restored = draft.blocks.map { block ->
            when (block) {
                is DraftBlock.Text -> ComposeBlock.Text(block.content)
                is DraftBlock.Attachment -> {
                    val blob = block.toBlob()
                    ComposeBlock.Attachment(
                        AttachmentState.Uploaded(
                            uri = Uri.parse(blob.url),
                            id = UUID.randomUUID().toString(),
                            displayName = blob.url.substringAfterLast('/').ifBlank { "attachment" },
                            originalBytes = blob.sizeBytes,
                            quality = AttachmentQuality.ORIGINAL,
                            blob = blob,
                        )
                    )
                }
            }
        }.ifEmpty { listOf(ComposeBlock.Text("")) }
        _blocks.value = if (restored.lastOrNull() is ComposeBlock.Text) {
            restored
        } else {
            restored + ComposeBlock.Text("")
        }
        _isSensitive.value = draft.isSensitive
        _pollDraft.value = draft.poll?.let { saved ->
            PollDraft(
                enabled = true,
                options = saved.options.map { PollDraftOption(it.id, it.label) },
                multipleChoice = saved.multipleChoice,
                durationSeconds = saved.durationSeconds,
            )
        } ?: PollDraft()
        _savedDraftFingerprint.value = currentContentFingerprint(_blocks.value, _isSensitive.value, _pollDraft.value)
        _sendState.value = SendState.Composing
    }

    fun saveCurrentDraft(): Boolean {
        val pk = pubkeyHex ?: return false
        val draft = buildCurrentDraft() ?: return false
        draftStore.save(pk, draft)
        _savedDraftFingerprint.value = currentContentFingerprint(_blocks.value, _isSensitive.value, _pollDraft.value)
        return true
    }

    fun discardCurrentDraft() {
        val pk = pubkeyHex ?: return
        draftStore.delete(pk, currentContext().key)
    }

    fun deleteDraft(draft: Draft) {
        pubkeyHex?.let { draftStore.delete(it, draft.key) }
    }

    /** Manually trigger upload for a single idle attachment. */
    fun startUpload(id: String) {
        val block = _blocks.value.firstOrNull {
            it is ComposeBlock.Attachment && it.state.id == id
        } as? ComposeBlock.Attachment ?: return
        val idle = block.state as? AttachmentState.Idle ?: return
        attachmentUploadJobs.remove(id)?.cancel()
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                uploadAttachment(idle)
            } finally {
                if (attachmentUploadJobs[id] === job) {
                    attachmentUploadJobs.remove(id)
                }
            }
        }
        attachmentUploadJobs[id] = job
        job.start()
    }

    private fun defaultImageQuality(): AttachmentQuality {
        return AttachmentQuality.fromImageMaxDimension(blossomServersStore.imageMaxDim.value)
    }

    private fun defaultVideoQuality(): AttachmentQuality {
        return when (blossomServersStore.videoQuality.value) {
            VideoTranscoder.Quality.SMALL -> AttachmentQuality.SMALL
            VideoTranscoder.Quality.STANDARD -> AttachmentQuality.STANDARD
            VideoTranscoder.Quality.HIGH -> AttachmentQuality.HIGH
            VideoTranscoder.Quality.ORIGINAL -> AttachmentQuality.ORIGINAL
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
        val uploading = _blocks.value
            .filterIsInstance<ComposeBlock.Attachment>()
            .firstOrNull { it.state.id == id }
            ?.state as? AttachmentState.Uploading
        if (uploading != null) {
            attachmentUploadJobs.remove(id)?.cancel()
            updateAttachmentState(id) {
                AttachmentState.Idle(
                    uri = uploading.uri,
                    id = uploading.id,
                    displayName = uploading.displayName,
                    originalBytes = uploading.originalBytes,
                    quality = uploading.quality,
                )
            }
            return
        }
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
                    val f = block.state
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
        val sourceMime = contentResolver.getType(idle.uri) ?: "application/octet-stream"
        val isVideo = sourceMime.startsWith("video/")
        updateAttachmentState(idle.id) {
            AttachmentState.Uploading(
                uri = idle.uri,
                id = idle.id,
                displayName = idle.displayName,
                originalBytes = idle.originalBytes,
                quality = idle.quality,
                phase = if (isVideo) {
                    AttachmentUploadPhase.Preparing
                } else {
                    AttachmentUploadPhase.Uploading
                },
            )
        }

        val server = blossomServersStore.selectedServer.value
        try {
            val blob = if (isVideo) {
                uploadVideo(idle.id, idle.uri, server, idle.quality) { progress ->
                    updateUploadProgress(
                        id = idle.id,
                        phase = AttachmentUploadPhase.Transcoding,
                        progressPercent = progress,
                    )
                }
            } else {
                uploadImage(idle.uri, sourceMime, server, idle.quality)
            }
            updateAttachmentState(idle.id) {
                AttachmentState.Uploaded(
                    uri = idle.uri,
                    id = idle.id,
                    displayName = idle.displayName,
                    originalBytes = idle.originalBytes,
                    quality = idle.quality,
                    blob = blob,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ex: Exception) {
            Log.e(TAG, "Upload failed for ${idle.uri}", ex)
            updateAttachmentState(idle.id) {
                AttachmentState.Failed(
                    uri = idle.uri,
                    id = idle.id,
                    displayName = idle.displayName,
                    originalBytes = idle.originalBytes,
                    quality = idle.quality,
                    message = ex.message ?: "Upload failed",
                )
            }
        }
    }

    private fun updateUploadProgress(
        id: String,
        phase: AttachmentUploadPhase,
        progressPercent: Int?,
    ) {
        _blocks.update { blocks ->
            blocks.map { block ->
                if (block is ComposeBlock.Attachment && block.state.id == id) {
                    val uploading = block.state as? AttachmentState.Uploading
                    if (uploading != null) {
                        block.copy(
                            state = uploading.copy(
                                phase = phase,
                                progressPercent = progressPercent?.coerceIn(0, 100),
                            )
                        )
                    } else block
                } else block
            }
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
        val (maxDim, jpegQuality) = quality.imageSettings()
        val prepared = imageCompressor.prepareImage(uri, sourceMime, maxDim, jpegQuality)
        try {
            val blob = blossomClient.upload(
                file = prepared.file,
                mimeType = prepared.mimeType,
                serverUrl = server,
            ).getOrThrow()
            return blob.copy(dimensions = blob.dimensions ?: prepared.dimensions)
        } finally {
            prepared.file.delete()
        }
    }

    private suspend fun uploadVideo(
        id: String,
        uri: Uri,
        server: String,
        quality: AttachmentQuality,
        onTranscodeProgress: (Int) -> Unit,
    ): BlossomBlob {
        val transcoded = videoTranscoder.transcode(
            uri = uri,
            quality = quality.videoQuality(),
            onProgress = onTranscodeProgress,
        )
        try {
            updateUploadProgress(
                id = id,
                phase = AttachmentUploadPhase.Uploading,
                progressPercent = null,
            )
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
        cancelPendingSigning()
        attachmentUploadJobs.values.forEach { it.cancel() }
        attachmentUploadJobs.clear()
        published = false
        publishError = null
        replyToEventId = null
        replyToRow = null
        replyToModel = null
        quoteRow = null
        quoteModel = null
        quoteEventId = null
        articleCommentTarget = null
        _sendState.value = SendState.Composing
        _focusedBlockId.value = null
        _pendingMentionInsert.value = null
        _mentionPickerOpen.value = false
        _mentionQuery.value = ""
        _emojiPickerOpen.value = false
        _pendingEmojiInsert.value = null
        _selectedEmojiUrls.value = emptyMap()
        _isSensitive.value = false
        _blocks.value = listOf(ComposeBlock.Text(""))
        _pollDraft.value = PollDraft()
        _savedDraftFingerprint.value = null
    }

    // ── Reply mode ──────────────────────────────────────────────────────────

    /** Parent note for reply mode, looked up from MES. */
    private var replyToEventId by mutableStateOf<String?>(null)

    var replyToRow by mutableStateOf<com.unsilence.app.data.memory.FeedRow?>(null)
        private set

    /** Authenticated target model used by the reply preview; never wrapper JSON. */
    var replyToModel by mutableStateOf<EventModel?>(null)
        private set

    private fun displayModelFor(eventId: String): EventModel? {
        val source = memoryEventStore.getOrParseEventModel(eventId) ?: return null
        return source.resolveDisplayModel(modelProvider = memoryEventStore::getOrParseEventModel)
    }

    fun loadReplyTo(eventId: String) {
        replyToEventId = eventId
        val rows = memoryEventStore.feedRowsByIds(setOf(eventId))
        replyToRow = rows.firstOrNull()
        replyToModel = displayModelFor(eventId)
    }

    // ── Article comment mode (NIP-22 kind-1111) ──────────────────────────────

    /** Target when composing a comment on a long-form article (null otherwise). */
    var articleCommentTarget by mutableStateOf<ArticleCommentTarget?>(null)
        private set

    fun loadArticleComment(target: ArticleCommentTarget) {
        articleCommentTarget = target
        // Preview the thing being commented on: the parent comment for a reply,
        // else the article itself. Reuses the reply-preview card (replyToRow).
        val previewId = target.parentId ?: target.articleId
        if (previewId != null) {
            replyToRow = memoryEventStore.feedRowsByIds(setOf(previewId)).firstOrNull()
            replyToModel = displayModelFor(previewId)
        }
    }

    // ── Quote mode ─────────────────────────────────────────────────────────

    /** Quoted note preview, looked up from MES. */
    var quoteRow by mutableStateOf<com.unsilence.app.data.memory.FeedRow?>(null)
        private set

    /** Authenticated target model used by the quote preview; never wrapper JSON. */
    var quoteModel by mutableStateOf<EventModel?>(null)
        private set

    /** Raw event ID for the nevent reference appended at publish time. */
    var quoteEventId by mutableStateOf<String?>(null)
        private set

    fun loadQuoteTo(eventId: String) {
        quoteEventId = eventId
        val rows = memoryEventStore.feedRowsByIds(setOf(eventId))
        quoteRow = rows.firstOrNull()
        quoteModel = displayModelFor(eventId)
    }

    private fun currentContext(): DraftContext {
        val article = articleCommentTarget
        return when {
            article != null -> DraftContext.ArticleComment(
                articleId = article.articleId,
                articleCoord = article.articleCoord,
                articlePubkey = article.articlePubkey,
                articleRelayHint = article.articleRelayHint,
                parentId = article.parentId,
                parentKind = article.parentKind,
                parentPubkey = article.parentPubkey,
                parentRelayHint = article.parentRelayHint,
            )
            replyToEventId != null -> DraftContext.Reply(
                parentId = replyToEventId!!,
                parentPubkey = replyToRow?.pubkey,
            )
            quoteEventId != null -> DraftContext.Quote(
                eventId = quoteEventId!!,
                quotedPubkey = quoteRow?.pubkey,
            )
            else -> DraftContext.New
        }
    }

    private fun buildCurrentDraft(): Draft? {
        val blocks = _blocks.value
        val draftBlocks = draftBlocksFrom(blocks)
        val poll = _pollDraft.value
        if (draftBlocks.isEmpty() && !poll.enabled) return null
        val context = currentContext()
        return Draft(
            key = context.key,
            blocks = draftBlocks,
            isSensitive = _isSensitive.value,
            updatedAt = System.currentTimeMillis(),
            context = context,
            hadUnsavedMedia = hasUnsavedMediaBlocks(blocks),
            poll = poll.takeIf { it.enabled }?.let { active ->
                DraftPoll(
                    options = active.options.map { DraftPollOption(it.id, it.label) },
                    multipleChoice = active.multipleChoice,
                    durationSeconds = active.durationSeconds,
                )
            },
        )
    }

    private fun draftBlocksFrom(blocks: List<ComposeBlock>): List<DraftBlock> =
        blocks.mapNotNull { block ->
            when (block) {
                is ComposeBlock.Text -> block.content
                    .takeIf { it.isNotBlank() }
                    ?.let { DraftBlock.Text(it) }
                is ComposeBlock.Attachment ->
                    (block.state as? AttachmentState.Uploaded)?.blob?.toDraftAttachment()
            }
        }

    private fun hasUnsavedMediaBlocks(blocks: List<ComposeBlock>): Boolean =
        blocks.any { it is ComposeBlock.Attachment && it.state !is AttachmentState.Uploaded }

    private fun hasAnyContent(blocks: List<ComposeBlock>): Boolean =
        blocks.any { block ->
            when (block) {
                is ComposeBlock.Text -> block.content.isNotBlank()
                is ComposeBlock.Attachment -> true
            }
        }

    private fun currentContentFingerprint(
        blocks: List<ComposeBlock>,
        isSensitive: Boolean,
        poll: PollDraft = _pollDraft.value,
    ): String {
        val blockFingerprint = blocks.joinToString(separator = "\u001E") { block ->
            when (block) {
                is ComposeBlock.Text -> "t:${block.content}"
                is ComposeBlock.Attachment -> when (val state = block.state) {
                    is AttachmentState.Uploaded -> {
                        val blob = state.blob
                        "u:${blob.url}:${blob.sha256}:${blob.sizeBytes}:${blob.mimeType}:${blob.dimensions}:${blob.blurhash}:${blob.thumbnailUrl}:${blob.durationMs}"
                    }
                    is AttachmentState.Idle -> "pending:${state.id}:${state.displayName}:${state.originalBytes}:${state.quality}"
                    is AttachmentState.Uploading -> "pending:${state.id}:${state.displayName}:${state.originalBytes}:${state.quality}"
                    is AttachmentState.Failed -> "failed:${state.id}:${state.displayName}:${state.originalBytes}:${state.quality}:${state.message}"
                }
            }
        }
        val pollFingerprint = if (poll.enabled) {
            poll.options.joinToString("\u001F") { "${it.id}:${it.label}" } +
                ":${poll.multipleChoice}:${poll.durationSeconds}"
        } else "none"
        return "${currentContext().key}|$isSensitive|$blockFingerprint|$pollFingerprint"
    }

    private fun deleteCurrentDraft() {
        val pk = pubkeyHex ?: return
        draftStore.delete(pk, currentContext().key)
    }

    private companion object {
        const val ACTIVE_SESSION_KEY = "compose_active_session"
        const val EDITOR_STATE_KEY = "compose_editor_state"
        const val EDITOR_JSON_KEY = "editor_json"
    }

    // ── Confirm flow ────────────────────────────────────────────────────────

    fun requestPublish(isReply: Boolean) {
        if (_sendState.value !is SendState.Composing) return
        if (!canPublish.value) return

        val current = _blocks.value
        val payloadState = capturePublishPayloadState(
            blocks = current,
            isReply = isReply,
            createdAt = System.currentTimeMillis() / 1000L,
        ) ?: return
        val candidates = buildNotifyCandidates(payloadState)
        val active = candidates.mapTo(linkedSetOf()) { it.pubkey }
        val activePayloadState = payloadState.copy(activeNotifyPubkeys = active)

        _sendState.value = SendState.Confirming(
            payloadState = activePayloadState,
            payload = buildPublishPayload(activePayloadState),
            notifyCandidates = candidates,
            notifyActive = active,
        )
    }

    fun confirmPublish() {
        val state = _sendState.value as? SendState.Confirming ?: return
        publishPayload(state)
    }

    fun retryPublish() {
        val state = _sendState.value as? SendState.Failed ?: return
        publishPayload(state.confirmation)
    }

    fun toggleNotify(pubkey: String) {
        val state = _sendState.value as? SendState.Confirming ?: return
        val newActive = if (pubkey in state.notifyActive)
            state.notifyActive - pubkey
        else
            state.notifyActive + pubkey
        val updatedPayloadState = state.payloadState.copy(activeNotifyPubkeys = newActive)
        _sendState.value = state.copy(
            payloadState = updatedPayloadState,
            payload = buildPublishPayload(updatedPayloadState),
            notifyActive = newActive,
        )
    }

    private fun capturePublishPayloadState(
        blocks: List<ComposeBlock>,
        isReply: Boolean,
        createdAt: Long,
    ): PublishPayloadState? {
        val publishBlocks = blocks.mapNotNull { block ->
            when (block) {
                is ComposeBlock.Text -> PublishBlock.Text(block.content)
                is ComposeBlock.Attachment -> {
                    val blob = (block.state as? AttachmentState.Uploaded)?.blob
                        ?: return@mapNotNull null
                    PublishBlock.Media(
                        PublishMedia(
                            url = blob.url,
                            mimeType = blob.mimeType,
                            sha256 = blob.sha256,
                            sizeBytes = blob.sizeBytes,
                            width = blob.dimensions?.first,
                            height = blob.dimensions?.second,
                            blurhash = blob.blurhash,
                            thumbnailUrl = blob.thumbnailUrl,
                            durationMs = blob.durationMs,
                        )
                    )
                }
            }
        }

        val target = when {
            articleCommentTarget != null -> PublishTarget.ArticleComment(articleCommentTarget!!)
            isReply -> {
                val parent = replyToRow ?: return null
                val parentTags = memoryEventStore.getNostrEvent(parent.id)?.tags.orEmpty()
                buildReplyPublishTarget(
                    rootEventId = parent.rootId ?: parent.id,
                    parentEventId = parent.id,
                    parentPubkey = parent.pubkey,
                    parentKind = parent.kind,
                    parentRelayHint = parent.relayUrl.takeIf { it.isNotBlank() },
                    parentTags = parentTags,
                )
            }
            _pollDraft.value.enabled -> {
                val poll = _pollDraft.value
                PublishTarget.Poll(
                    PublishPoll(
                        options = poll.options.map { PublishPollOption(it.id, it.label) },
                        responseRelays = pollResponseRelays(),
                        multipleChoice = poll.multipleChoice,
                        durationSeconds = poll.durationSeconds,
                    )
                )
            }
            else -> {
                val quote = quoteEventId?.let { eventId ->
                    val author = quoteRow?.pubkey
                    val hint = quoteRelayHintRaw(eventId)
                    val normalizedHint = hint.takeIf { it.isNotBlank() }?.let {
                        runCatching {
                            com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer.normalize(it)
                        }.getOrNull()
                    }
                    val nevent = com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
                        .create(eventId, author, null, normalizedHint)
                    PublishQuote(
                        eventId = eventId,
                        authorPubkey = author,
                        relayHint = hint,
                        inlineReference = "nostr:$nevent",
                    )
                }
                PublishTarget.Note(quote)
            }
        }

        val knownEmojis = buildList {
            addAll(pubkeyHex?.let(memoryEventStore::resolvedEmojisFor).orEmpty())
            // This is the same hydrated snapshot currently rendered by the picker.
            addAll(resolvedEmojis.value)
        }
        val customEmojis = composeEmojiUrls(
            knownEmojis = knownEmojis,
            selectedEmojis = _selectedEmojiUrls.value,
        )

        return PublishPayloadState(
            createdAt = createdAt,
            blocks = publishBlocks,
            target = target,
            mentionPubkeys = extractPublishMentionPubkeys(publishContent(publishBlocks)),
            activeNotifyPubkeys = emptySet(),
            customEmojis = customEmojis,
            isSensitive = _isSensitive.value,
        )
    }

    private fun buildNotifyCandidates(
        state: PublishPayloadState,
    ): List<NotifyCandidate> {
        val ownPubkey = pubkeyHex ?: ""
        val candidates = mutableListOf<NotifyCandidate>()
        val seen = mutableSetOf(ownPubkey)

        when (val target = state.target) {
            is PublishTarget.ArticleComment -> {
                // NIP-22 P/p tags are protocol-mandated and are not user toggles.
                seen += target.target.articlePubkey
                target.target.parentPubkey?.let(seen::add)
            }
            is PublishTarget.VideoComment -> {
                // NIP-22 P/p tags are protocol scope, not optional notifications.
                seen += target.target.rootPubkey
                target.target.parentPubkey?.let(seen::add)
            }
            is PublishTarget.Reply -> {
                if (target.parentPubkey !in seen) {
                    candidates += buildCandidate(target.parentPubkey, NotifySource.ReplyParent)
                    seen += target.parentPubkey
                }
            }
            is PublishTarget.Note -> target.quote?.authorPubkey?.let { quoted ->
                if (quoted !in seen) {
                    candidates += buildCandidate(quoted, NotifySource.QuoteAuthor)
                    seen += quoted
                }
            }
            is PublishTarget.Poll -> Unit
        }

        state.mentionPubkeys.forEach { pk ->
            if (pk !in seen) {
                candidates += buildCandidate(pk, NotifySource.Mention)
                seen += pk
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
        // Once Publishing starts, the signed event has already been inserted and
        // broadcast. The preceding Amber/signing round trip remains cancellable.
        if (_sendState.value is SendState.Publishing) return
        cancelPendingSigning()
        _sendState.value = SendState.Composing
    }

    private fun cancelPendingSigning() {
        signingAttemptGate.cancel()
        signingJob?.cancel()
        signingJob = null
    }

    /** Relay hint for the quoted event — where others can fetch it. Prefers the
     *  relay the quote was seen on, falls back to MES e-tag hints, else blank. */
    private fun quoteRelayHintRaw(qId: String): String =
        quoteRow?.relayUrl?.takeIf { it.isNotBlank() }
            ?: memoryEventStore.relayHintsForEvent(qId).firstOrNull()
            ?: ""

    // ── Publishing ──────────────────────────────────────────────────────────

    private fun pollResponseRelays(): List<String> {
        val ownPk = pubkeyHex.orEmpty()
        return memoryEventStore.writeRelaysFor(ownPk)
            .mapNotNull { com.unsilence.app.data.relay.normalizeRelayUrl(it) }
            .distinct()
            .take(6)
            .ifEmpty { com.unsilence.app.data.relay.GLOBAL_RELAY_URLS.take(6) }
    }

    private fun publishPayload(confirmation: SendState.Confirming) {
        val attemptToken = signingAttemptGate.begin() ?: return
        publishError = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val payload = confirmation.payload
                val signed = signingManager.sign(payload.toEventTemplate()) ?: run {
                    if (signingAttemptGate.isCurrent(attemptToken)) {
                        _sendState.value = SendState.Failed(
                            reason = "Signing failed - check your key or Amber connection",
                            confirmation = confirmation,
                        )
                    }
                    return@launch
                }
                // Back/Close may cancel while Amber is in front. A late result must
                // never publish a draft the user already returned to editing/discarded.
                if (!signingAttemptGate.isCurrent(attemptToken)) return@launch
                signingAttemptGate.complete(attemptToken)
                signingJob = null

                publishAndTrack(
                    eventId = signed.id,
                    eventJson = toEventJson(signed),
                    replyToId = payload.replyToId,
                    rootId = payload.rootId,
                    confirmation = confirmation,
                ) {
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
                            sig = signed.sig,
                            relayUrl = "local",
                            replyToId = payload.replyToId,
                            rootId = payload.rootId,
                            hasContentWarning = payload.hasContentWarning,
                            contentWarningReason = null,
                            firstSeenAt = nowMs,
                            relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("local") },
                        )
                    )
                    if (payload.kind == 1068) {
                        relayPool.refreshOwnNotificationSubscription(signed.pubKey)
                    }
                }
            } finally {
                signingAttemptGate.complete(attemptToken)
                if (signingJob === coroutineContext[Job]) signingJob = null
            }
        }
        signingJob = job
        job.start()
    }

    /**
     * Shared publish-and-track: inserts the signed event locally first, then publishes
     * to write relays and tracks per-relay OK responses with a 6s timeout.
     */
    private suspend fun publishAndTrack(
        eventId: String,
        eventJson: String,
        replyToId: String?,
        rootId: String?,
        confirmation: SendState.Confirming,
        insertIntoMes: suspend () -> Unit,
    ) {
        val ownPk = pubkeyHex ?: ""
        val writeRelays = memoryEventStore.writeRelaysFor(ownPk)
            .mapNotNull { com.unsilence.app.data.relay.normalizeRelayUrl(it) }
            .ifEmpty { com.unsilence.app.data.relay.GLOBAL_RELAY_URLS }

        // H20 Commit 1 instrumentation: record the INTENDED outbox target set (own
        // write relays). Contrast with RelayPool.publish()'s "actually-sent-to" line —
        // publish broadcasts to every open socket, so a gap here exposes the
        // outbox-model violation + bandwidth over-broadcast on record.
        Log.w(TAG, "PUBLISH: event=$eventId targets=write$writeRelays (count=${writeRelays.size})")

        // Enter Publishing state with all relays Pending
        val statusMap = ConcurrentHashMap<String, RelayPublishStatus>()
        writeRelays.forEach { statusMap[it] = RelayPublishStatus.Pending }
        _sendState.value = SendState.Publishing(
            statuses = HashMap(statusMap),
            confirmation = confirmation,
        )

        // Register OK callback before sending
        relayPool.registerPublishCallback(eventId) { relayUrl, success, message ->
            val normalized = com.unsilence.app.data.relay.normalizeRelayUrl(relayUrl)
            val key = normalized ?: relayUrl
            Log.w(TAG, "PUBLISH ${if (success) "OK" else "FAIL"} $relayUrl" +
                (if (message.isNotBlank()) " — $message" else "") +
                (if (!statusMap.containsKey(key)) " (non-target relay)" else ""))
            if (statusMap.containsKey(key)) {
                statusMap[key] = if (success) RelayPublishStatus.Accepted else RelayPublishStatus.Rejected
                _sendState.value = SendState.Publishing(
                    statuses = HashMap(statusMap),
                    confirmation = confirmation,
                )
            }
        }

        try {
            // The signature is authoritative local data. Insert before any network wait so
            // feed/thread flows update immediately; relay failure remains surfaced below.
            withContext(Dispatchers.IO) { insertIntoMes() }

            // Publish to the user's own write relays only — connectAndAwait handles any
            // that aren't open (bypassing the degraded defer, explicit user intent). No
            // more spraying note content to every open socket. (H20c)
            withContext(Dispatchers.IO) {
                relayPool.publish(eventJson, writeRelays)
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
            _sendState.value = SendState.Publishing(
                statuses = HashMap(statusMap),
                confirmation = confirmation,
            )

            val acceptedCount = statusMap.values.count { it == RelayPublishStatus.Accepted }
            Log.w(TAG, "PUBLISH verdict: $acceptedCount/${writeRelays.size} accepted " +
                "(event=$eventId, statuses=${statusMap.values.groupingBy { it }.eachCount()})")
            if (acceptedCount > 0) {
                deleteCurrentDraft()
                // Brief pause to show final state
                delay(800)
                _blocks.value = listOf(ComposeBlock.Text(""))
                _selectedEmojiUrls.value = emptyMap()
                _pollDraft.value = PollDraft()
                published = true
                _sendState.value = SendState.Sent
            } else {
                _sendState.value = SendState.Failed(
                    reason = "No relays accepted the event",
                    confirmation = confirmation,
                )
            }
        } finally {
            relayPool.unregisterPublishCallback(eventId)
        }
    }

}
