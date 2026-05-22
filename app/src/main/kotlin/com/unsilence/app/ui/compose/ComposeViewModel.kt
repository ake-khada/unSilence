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
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.repository.UserRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    private val imageCompressor: ImageCompressor,
    private val videoTranscoder: VideoTranscoder,
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

    // ── Publishing ──────────────────────────────────────────────────────────

    fun publishReply() {
        val parent = replyToRow ?: return
        publishError = null
        viewModelScope.launch {
            val current = _blocks.value
            val threadRootId = parent.rootId ?: parent.id
            val replyToId = parent.id
            val replyToPubkey = parent.pubkey

            val finalContent = blocksToContent(current)
            val imetaTags = blocksToImetaTags(current)

            val template = TextNoteEvent.build(note = finalContent, createdAt = System.currentTimeMillis() / 1000L) {
                add(arrayOf("e", threadRootId, "", "root"))
                if (replyToId != threadRootId) {
                    add(arrayOf("e", replyToId, "", "reply"))
                }
                add(arrayOf("p", replyToPubkey))
                imetaTags.forEach { add(it) }
            }
            val signed = signingManager.sign(template) ?: run {
                publishError = "Signing failed — check your key or Amber connection"
                return@launch
            }

            withContext(Dispatchers.IO) {
                relayPool.publish(toEventJson(signed))

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

            _blocks.value = listOf(ComposeBlock.Text(""))
            published = true
        }
    }

    fun publishNote() {
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

            val template = TextNoteEvent.build(note = finalContent) {
                imetaTags.forEach { add(it) }
                if (qId != null) {
                    add(arrayOf("q", qId, "", quotedAuthor ?: ""))
                    if (quotedAuthor != null) add(arrayOf("p", quotedAuthor))
                }
            }
            val signed = signingManager.sign(template) ?: run {
                publishError = "Signing failed — check your key or Amber connection"
                return@launch
            }

            withContext(Dispatchers.IO) {
                relayPool.publish(toEventJson(signed))

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

            _blocks.value = listOf(ComposeBlock.Text(""))
            published = true
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
