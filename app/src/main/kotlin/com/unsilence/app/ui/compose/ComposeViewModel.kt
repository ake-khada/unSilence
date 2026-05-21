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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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

    data class Idle(override val uri: Uri, override val id: String) : AttachmentState
    data class Uploading(override val uri: Uri, override val id: String) : AttachmentState
    data class Uploaded(
        override val uri: Uri,
        override val id: String,
        val blob: BlossomBlob,
    ) : AttachmentState
    data class Failed(
        override val uri: Uri,
        override val id: String,
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

    // ── Attachments ─────────────────────────────────────────────────────────

    private val _attachments = MutableStateFlow<List<AttachmentState>>(emptyList())
    val attachments: StateFlow<List<AttachmentState>> = _attachments.asStateFlow()

    /** Text content flow for canPublish — updated from ComposeScreen. */
    private val _composeText = MutableStateFlow("")

    val canPublish: StateFlow<Boolean> = combine(
        _composeText, _attachments,
    ) { text, atts ->
        val hasContent = text.isNotBlank() || atts.any { it is AttachmentState.Uploaded }
        val noneInFlight = atts.none { it is AttachmentState.Uploading || it is AttachmentState.Idle }
        hasContent && noneInFlight
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun updateComposeText(text: String) {
        _composeText.value = text
    }

    init {
        viewModelScope.launch { blossomServersStore.initialize() }
        // Auto-upload idle attachments sequentially.
        viewModelScope.launch {
            _attachments
                .map { list -> list.firstOrNull { it is AttachmentState.Idle } }
                .filterNotNull()
                .distinctUntilChanged { a, b -> a.id == b.id }
                .collect { idle -> uploadAttachment(idle as AttachmentState.Idle) }
        }
    }

    fun addAttachments(uris: List<Uri>) {
        val newOnes = uris.map { uri ->
            AttachmentState.Idle(uri = uri, id = UUID.randomUUID().toString())
        }
        _attachments.update { it + newOnes }
    }

    fun removeAttachment(id: String) {
        _attachments.update { list -> list.filterNot { it.id == id } }
    }

    fun retryAttachment(id: String) {
        val target = _attachments.value.firstOrNull { it.id == id } ?: return
        if (target !is AttachmentState.Failed) return
        _attachments.update { list ->
            list.map { if (it.id == id) AttachmentState.Idle(target.uri, target.id) else it }
        }
    }

    private suspend fun uploadAttachment(idle: AttachmentState.Idle) {
        _attachments.update { list ->
            list.map { if (it.id == idle.id) AttachmentState.Uploading(idle.uri, idle.id) else it }
        }

        val server = blossomServersStore.selectedServer.value
        val sourceMime = contentResolver.getType(idle.uri) ?: "application/octet-stream"

        val result = runCatching {
            if (sourceMime.startsWith("video/")) {
                uploadVideo(idle.uri, server)
            } else {
                uploadImage(idle.uri, sourceMime, server)
            }
        }

        _attachments.update { list ->
            list.map {
                if (it.id != idle.id) return@map it
                result.fold(
                    onSuccess = { blob -> AttachmentState.Uploaded(idle.uri, idle.id, blob) },
                    onFailure = { ex ->
                        Log.e(TAG, "Upload failed for ${idle.uri}", ex)
                        AttachmentState.Failed(idle.uri, idle.id, ex.message ?: "Upload failed")
                    },
                )
            }
        }
    }

    private suspend fun uploadImage(uri: Uri, sourceMime: String, server: String): BlossomBlob {
        val maxDim = blossomServersStore.imageMaxDim.value
        val quality = blossomServersStore.imageQuality.value

        val rawBytes = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read attachment")
        }

        val (compressed, uploadMime) = if (maxDim == 0) {
            rawBytes to sourceMime
        } else {
            imageCompressor.compressImage(
                bytes = rawBytes,
                maxDimension = maxDim,
                quality = quality,
            ) to "image/jpeg"
        }

        return blossomClient.upload(
            bytes = compressed,
            mimeType = uploadMime,
            serverUrl = server,
        ).getOrThrow()
    }

    private suspend fun uploadVideo(uri: Uri, server: String): BlossomBlob {
        val videoQuality = blossomServersStore.videoQuality.value
        val transcoded = videoTranscoder.transcode(uri, videoQuality)
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
        _attachments.value = emptyList()
        _composeText.value = ""
    }

    // ── Reply mode ──────────────────────────────────────────────────────────

    /** Parent note for reply mode, looked up from MES. */
    var replyToRow by mutableStateOf<com.unsilence.app.data.memory.FeedRow?>(null)
        private set

    fun loadReplyTo(eventId: String) {
        val rows = memoryEventStore.feedRowsByIds(setOf(eventId))
        replyToRow = rows.firstOrNull()
    }

    // ── Publishing ──────────────────────────────────────────────────────────

    fun publishReply(content: String) {
        val parent = replyToRow ?: return
        publishError = null
        viewModelScope.launch {
            val threadRootId = parent.rootId ?: parent.id
            val replyToId = parent.id
            val replyToPubkey = parent.pubkey

            val uploaded = _attachments.value.filterIsInstance<AttachmentState.Uploaded>()
            val finalContent = buildFinalContent(content, uploaded)
            val imetaTags = buildImetaTags(uploaded)

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

            _attachments.value = emptyList()
            published = true
        }
    }

    fun publishNote(content: String) {
        publishError = null
        viewModelScope.launch {
            val uploaded = _attachments.value.filterIsInstance<AttachmentState.Uploaded>()
            val finalContent = buildFinalContent(content, uploaded)
            val imetaTags = buildImetaTags(uploaded)

            val template = TextNoteEvent.build(note = finalContent) {
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
                        replyToId = null,
                        rootId = null,
                        hasContentWarning = false,
                        contentWarningReason = null,
                        firstSeenAt = nowMs,
                        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("local") },
                    )
                )
            }

            _attachments.value = emptyList()
            published = true
        }
    }

    private fun buildFinalContent(text: String, uploaded: List<AttachmentState.Uploaded>): String {
        if (uploaded.isEmpty()) return text
        val urls = uploaded.joinToString("\n") { it.blob.url }
        return if (text.isBlank()) urls else "$text\n\n$urls"
    }

    private fun buildImetaTags(uploaded: List<AttachmentState.Uploaded>): List<Array<String>> =
        uploaded.map { att ->
            val blob = att.blob
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
