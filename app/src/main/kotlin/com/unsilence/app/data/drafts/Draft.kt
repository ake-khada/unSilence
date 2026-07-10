package com.unsilence.app.data.drafts

import com.unsilence.app.data.blossom.BlossomBlob
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val MAX_DRAFTS_PER_PUBKEY = 50

@Serializable
sealed interface DraftContext {
    val key: String

    @Serializable
    @SerialName("new")
    data object New : DraftContext {
        override val key: String = "new"
    }

    @Serializable
    @SerialName("reply")
    data class Reply(
        val parentId: String,
        val parentPubkey: String? = null,
    ) : DraftContext {
        override val key: String get() = "reply:$parentId"
    }

    @Serializable
    @SerialName("quote")
    data class Quote(
        val eventId: String,
        val quotedPubkey: String? = null,
    ) : DraftContext {
        override val key: String get() = "quote:$eventId"
    }

    @Serializable
    @SerialName("article_comment")
    data class ArticleComment(
        val articleId: String?,
        val articleCoord: String,
        val articlePubkey: String,
        val articleRelayHint: String? = null,
        val parentId: String? = null,
        val parentKind: Int? = null,
        val parentPubkey: String? = null,
        val parentRelayHint: String? = null,
    ) : DraftContext {
        override val key: String get() = "article:$articleCoord:${parentId ?: "root"}"
    }
}

@Serializable
sealed interface DraftBlock {
    @Serializable
    @SerialName("text")
    data class Text(val content: String) : DraftBlock

    @Serializable
    @SerialName("attachment")
    data class Attachment(
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val mimeType: String,
        val width: Int? = null,
        val height: Int? = null,
        val blurhash: String? = null,
        val thumbnailUrl: String? = null,
        val durationMs: Long? = null,
    ) : DraftBlock
}

@Serializable
data class Draft(
    val key: String,
    val blocks: List<DraftBlock>,
    val isSensitive: Boolean,
    val updatedAt: Long,
    val context: DraftContext,
    val hadUnsavedMedia: Boolean = false,
    val poll: DraftPoll? = null,
) {
    val previewText: String
        get() = blocks.filterIsInstance<DraftBlock.Text>()
            .firstOrNull { it.content.isNotBlank() }
            ?.content
            ?.trim()
            .orEmpty()

    val attachmentCount: Int get() = blocks.count { it is DraftBlock.Attachment }
}

@Serializable
data class DraftPoll(
    val options: List<DraftPollOption>,
    val multipleChoice: Boolean = false,
    val durationSeconds: Long? = null,
)

@Serializable
data class DraftPollOption(
    val id: String,
    val label: String,
)

fun BlossomBlob.toDraftAttachment(): DraftBlock.Attachment = DraftBlock.Attachment(
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

fun DraftBlock.Attachment.toBlob(): BlossomBlob = BlossomBlob(
    url = url,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    dimensions = if (width != null && height != null) width to height else null,
    blurhash = blurhash,
    thumbnailUrl = thumbnailUrl,
    durationMs = durationMs,
)

object DraftMutations {
    fun upsert(
        existing: List<Draft>,
        draft: Draft,
        max: Int = MAX_DRAFTS_PER_PUBKEY,
    ): List<Draft> =
        (existing.filterNot { it.key == draft.key } + draft)
            .sortedByDescending { it.updatedAt }
            .take(max)

    fun delete(existing: List<Draft>, key: String): List<Draft> =
        existing.filterNot { it.key == key }

    fun find(existing: List<Draft>, key: String): Draft? =
        existing.firstOrNull { it.key == key }
}
