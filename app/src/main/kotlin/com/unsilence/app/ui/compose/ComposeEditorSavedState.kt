package com.unsilence.app.ui.compose

import android.net.Uri
import com.unsilence.app.data.blossom.AttachmentQuality
import com.unsilence.app.data.blossom.BlossomBlob
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Saved editor state is one small JSON string in the Activity saved-state Bundle.
 * It contains descriptors only—never attachment bytes—and is hard-capped well
 * below the transaction limit shared by the rest of the Activity.
 */
internal const val COMPOSE_SAVED_STATE_MAX_BYTES = 256 * 1024

@Serializable
internal data class SavedComposeEditor(
    val blocks: List<SavedComposeBlock>,
    val poll: SavedComposePoll = SavedComposePoll(),
    val isSensitive: Boolean = false,
    val selectedEmojiUrls: Map<String, String> = emptyMap(),
)

@Serializable
internal data class SavedComposeBlock(
    val id: String,
    val text: String? = null,
    val attachment: SavedComposeAttachment? = null,
)

@Serializable
internal data class SavedComposeAttachment(
    val uri: String,
    val displayName: String,
    val originalBytes: Long,
    val quality: String,
    val status: SavedAttachmentStatus,
    val failureMessage: String? = null,
    val blob: SavedComposeBlob? = null,
)

@Serializable
internal enum class SavedAttachmentStatus { IDLE, UPLOADED, FAILED }

@Serializable
internal data class SavedComposeBlob(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val blurhash: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
)

@Serializable
internal data class SavedComposePoll(
    val enabled: Boolean = false,
    val options: List<SavedComposePollOption> = emptyList(),
    val multipleChoice: Boolean = false,
    val durationSeconds: Long? = null,
)

@Serializable
internal data class SavedComposePollOption(
    val id: String,
    val label: String,
)

private val SavedStateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun captureComposeEditor(
    blocks: List<ComposeBlock>,
    poll: PollDraft,
    isSensitive: Boolean,
    selectedEmojiUrls: Map<String, String>,
): SavedComposeEditor = SavedComposeEditor(
    blocks = blocks.map { block ->
        when (block) {
            is ComposeBlock.Text -> SavedComposeBlock(
                id = block.id,
                text = block.content,
            )
            is ComposeBlock.Attachment -> SavedComposeBlock(
                id = block.id,
                attachment = block.state.toSavedAttachment(),
            )
        }
    },
    poll = SavedComposePoll(
        enabled = poll.enabled,
        options = poll.options.map { SavedComposePollOption(it.id, it.label) },
        multipleChoice = poll.multipleChoice,
        durationSeconds = poll.durationSeconds,
    ),
    isSensitive = isSensitive,
    selectedEmojiUrls = selectedEmojiUrls,
)

internal fun SavedComposeEditor.restoreBlocks(): List<ComposeBlock> {
    val restored = blocks.mapNotNull { saved ->
        when {
            saved.text != null -> ComposeBlock.Text(saved.text, saved.id)
            saved.attachment != null -> saved.attachment.toAttachmentState(saved.id)
                ?.let(ComposeBlock::Attachment)
            else -> null
        }
    }
    return when {
        restored.isEmpty() -> listOf(ComposeBlock.Text(""))
        restored.last() is ComposeBlock.Text -> restored
        else -> restored + ComposeBlock.Text("")
    }
}

internal fun SavedComposeEditor.restorePoll(): PollDraft = PollDraft(
    enabled = poll.enabled,
    options = poll.options.map { PollDraftOption(it.id, it.label) },
    multipleChoice = poll.multipleChoice,
    durationSeconds = poll.durationSeconds,
)

internal fun encodeComposeEditor(
    editor: SavedComposeEditor,
    maxBytes: Int = COMPOSE_SAVED_STATE_MAX_BYTES,
): String? {
    require(maxBytes >= 0)
    val encoded = SavedStateJson.encodeToString(editor)
    return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= maxBytes }
}

internal fun decodeComposeEditor(encoded: String?): SavedComposeEditor? {
    if (encoded.isNullOrEmpty()) return null
    return runCatching { SavedStateJson.decodeFromString<SavedComposeEditor>(encoded) }.getOrNull()
}

private fun AttachmentState.toSavedAttachment(): SavedComposeAttachment = when (this) {
    is AttachmentState.Idle -> SavedComposeAttachment(
        uri = uri.toString(),
        displayName = displayName,
        originalBytes = originalBytes,
        quality = quality.name,
        status = SavedAttachmentStatus.IDLE,
    )
    is AttachmentState.Uploading -> SavedComposeAttachment(
        uri = uri.toString(),
        displayName = displayName,
        originalBytes = originalBytes,
        quality = quality.name,
        // Upload jobs cannot survive process death. Restore as retryable idle.
        status = SavedAttachmentStatus.IDLE,
    )
    is AttachmentState.Uploaded -> SavedComposeAttachment(
        uri = uri.toString(),
        displayName = displayName,
        originalBytes = originalBytes,
        quality = quality.name,
        status = SavedAttachmentStatus.UPLOADED,
        blob = blob.toSavedBlob(),
    )
    is AttachmentState.Failed -> SavedComposeAttachment(
        uri = uri.toString(),
        displayName = displayName,
        originalBytes = originalBytes,
        quality = quality.name,
        status = SavedAttachmentStatus.FAILED,
        failureMessage = message,
    )
}

private fun SavedComposeAttachment.toAttachmentState(id: String): AttachmentState? {
    val parsedUri = Uri.parse(uri)
    val parsedQuality = runCatching { AttachmentQuality.valueOf(quality) }
        .getOrDefault(AttachmentQuality.ORIGINAL)
    return when (status) {
        SavedAttachmentStatus.IDLE -> AttachmentState.Idle(
            uri = parsedUri,
            id = id,
            displayName = displayName,
            originalBytes = originalBytes,
            quality = parsedQuality,
        )
        SavedAttachmentStatus.UPLOADED -> blob?.let { savedBlob ->
            AttachmentState.Uploaded(
                uri = parsedUri,
                id = id,
                displayName = displayName,
                originalBytes = originalBytes,
                quality = parsedQuality,
                blob = savedBlob.toBlossomBlob(),
            )
        }
        SavedAttachmentStatus.FAILED -> AttachmentState.Failed(
            uri = parsedUri,
            id = id,
            displayName = displayName,
            originalBytes = originalBytes,
            quality = parsedQuality,
            message = failureMessage ?: "Upload failed",
        )
    }
}

private fun BlossomBlob.toSavedBlob(): SavedComposeBlob = SavedComposeBlob(
    url = url,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    width = dimensions?.first,
    height = dimensions?.second,
    blurhash = blurhash,
    thumbnailUrl = thumbnailUrl,
    durationMs = durationMs,
)

private fun SavedComposeBlob.toBlossomBlob(): BlossomBlob = BlossomBlob(
    url = url,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    dimensions = if (width != null && height != null) width to height else null,
    blurhash = blurhash,
    thumbnailUrl = thumbnailUrl,
    durationMs = durationMs,
)
