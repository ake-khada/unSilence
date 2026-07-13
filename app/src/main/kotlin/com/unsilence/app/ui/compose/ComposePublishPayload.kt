package com.unsilence.app.ui.compose

import androidx.compose.runtime.Immutable
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.model.ContentParser
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

@Immutable
sealed interface PublishBlock {
    @Immutable
    data class Text(val content: String) : PublishBlock

    @Immutable
    data class Media(val attachment: PublishMedia) : PublishBlock
}

@Immutable
data class PublishMedia(
    val url: String,
    val mimeType: String,
    val sha256: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val blurhash: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
)

@Immutable
data class PublishQuote(
    val eventId: String,
    val authorPubkey: String?,
    val relayHint: String,
    val inlineReference: String,
)

@Immutable
data class PublishPollOption(val id: String, val label: String)

@Immutable
data class PublishPoll(
    val options: List<PublishPollOption>,
    val responseRelays: List<String>,
    val multipleChoice: Boolean,
    val durationSeconds: Long?,
)

@Immutable
sealed interface PublishTarget {
    @Immutable
    data class Note(val quote: PublishQuote? = null) : PublishTarget

    @Immutable
    data class Reply(
        val rootEventId: String,
        val parentEventId: String,
        val parentPubkey: String,
    ) : PublishTarget

    @Immutable
    data class Poll(val poll: PublishPoll) : PublishTarget

    @Immutable
    data class ArticleComment(val target: ArticleCommentTarget) : PublishTarget
}

/** Immutable editor snapshot used to create or deliberately regenerate a payload. */
@Immutable
data class PublishPayloadState(
    val createdAt: Long,
    val blocks: List<PublishBlock>,
    val target: PublishTarget,
    val mentionPubkeys: Set<String>,
    val activeNotifyPubkeys: Set<String>,
    val customEmojis: Map<String, String>,
    val isSensitive: Boolean,
)

/**
 * The exact unsigned event held by the confirmation step. Preview and signing both
 * consume this object; no editor state is consulted after it has been created.
 */
@Immutable
data class PublishPayload(
    val createdAt: Long,
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
    val replyToId: String? = null,
    val rootId: String? = null,
) {
    val hasContentWarning: Boolean
        get() = tags.any { it.firstOrNull() == "content-warning" }

    fun tagsJson(): String = tagsToJson(tags)

    /** A reply context is shown only when the held tags actually describe a thread. */
    fun threadedReplyTargetId(): String? {
        val hasThreadTags = when (kind) {
            1 -> tags.any {
                it.firstOrNull() == "e" && it.getOrNull(3) in setOf("root", "reply")
            }
            1111 -> tags.any { it.firstOrNull() == "A" } &&
                tags.any { it.firstOrNull() in setOf("a", "e") }
            else -> false
        }
        return replyToId?.takeIf { hasThreadTags }
    }

    fun signingTemplateSnapshot(): PublishTemplateSnapshot = PublishTemplateSnapshot(
        createdAt = createdAt,
        kind = kind,
        content = content,
        tags = tags,
    )

    fun toEventTemplate(): EventTemplate<Event> {
        val template = signingTemplateSnapshot()
        return EventTemplate(
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags.map { it.toTypedArray() }.toTypedArray(),
            content = template.content,
        )
    }
}

/** Pure mirror of the Quartz template fields, usable by JDK-17 unit tests. */
@Immutable
data class PublishTemplateSnapshot(
    val createdAt: Long,
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
)

/** Single canonical construction path for every event produced by ComposeScreen. */
fun buildPublishPayload(state: PublishPayloadState): PublishPayload {
    val baseContent = publishContent(state.blocks)
    val imetaTags = publishImetaTags(state.blocks)

    return when (val target = state.target) {
        is PublishTarget.Note -> {
            val quote = target.quote
            val content = quote?.let {
                if (baseContent.isBlank()) it.inlineReference
                else "$baseContent\n\n${it.inlineReference}"
            } ?: baseContent
            val tags = mutableListOf<List<String>>()
            val existingPTags = mutableSetOf<String>()
            tags += imetaTags
            if (quote != null) {
                tags += listOf("q", quote.eventId, quote.relayHint, quote.authorPubkey.orEmpty())
                quote.authorPubkey?.takeIf { it in state.activeNotifyPubkeys }?.let { pubkey ->
                    tags += listOf("p", pubkey)
                    existingPTags += pubkey
                }
            }
            appendContentTags(tags, content, state, existingPTags)
            PublishPayload(state.createdAt, 1, content, tags)
        }

        is PublishTarget.Reply -> {
            val tags = mutableListOf<List<String>>()
            tags += listOf("e", target.rootEventId, "", "root")
            if (target.parentEventId != target.rootEventId) {
                tags += listOf("e", target.parentEventId, "", "reply")
            }
            val existingPTags = mutableSetOf<String>()
            if (target.parentPubkey in state.activeNotifyPubkeys) {
                tags += listOf("p", target.parentPubkey)
                existingPTags += target.parentPubkey
            }
            tags += imetaTags
            appendContentTags(tags, baseContent, state, existingPTags)
            PublishPayload(
                createdAt = state.createdAt,
                kind = 1,
                content = baseContent,
                tags = tags,
                replyToId = target.parentEventId,
                rootId = target.rootEventId,
            )
        }

        is PublishTarget.Poll -> {
            val poll = target.poll
            val tags = mutableListOf<List<String>>()
            poll.options.filter { it.label.isNotBlank() }.take(10).forEach { option ->
                tags += listOf("option", option.id, option.label.trim())
            }
            poll.responseRelays.distinct().take(6).forEach { relay ->
                tags += listOf("relay", relay)
            }
            tags += listOf(
                "polltype",
                if (poll.multipleChoice) "multiplechoice" else "singlechoice",
            )
            poll.durationSeconds?.takeIf { it > 0L }?.let { duration ->
                tags += listOf("endsAt", (state.createdAt + duration).toString())
            }
            tags += imetaTags
            appendContentTags(tags, baseContent, state, emptySet())
            PublishPayload(state.createdAt, 1068, baseContent, tags)
        }

        is PublishTarget.ArticleComment -> {
            val article = target.target
            val tags = Nip22Tags.articleComment(article)
                .mapTo(mutableListOf()) { it.toList() }
            tags += imetaTags
            appendContentTags(
                tags = tags,
                content = baseContent,
                state = state,
                existingPTags = buildSet {
                    add(article.articlePubkey)
                    article.parentPubkey?.let(::add)
                },
            )
            PublishPayload(
                createdAt = state.createdAt,
                kind = 1111,
                content = baseContent,
                tags = tags,
                replyToId = article.parentId ?: article.articleId,
                rootId = article.articleId,
            )
        }
    }
}

internal fun publishContent(blocks: List<PublishBlock>): String = blocks.mapNotNull { block ->
    when (block) {
        is PublishBlock.Text -> block.content.takeIf { it.isNotBlank() }
        is PublishBlock.Media -> block.attachment.url
    }
}.joinToString("\n\n")

internal fun extractPublishMentionPubkeys(content: String): Set<String> {
    val pubkeys = linkedSetOf<String>()
    NOSTR_MENTION_REGEX.findAll(content).forEach { match ->
        val bech32 = match.value.substringAfter("nostr:", match.value)
        runCatching {
            when (val entity = Nip19Parser.uriToRoute(bech32)?.entity) {
                is NPub -> pubkeys += entity.hex
                is NProfile -> pubkeys += entity.hex
                else -> Unit
            }
        }
    }
    return pubkeys
}

internal fun normalizeComposeShortcode(raw: String): String? {
    val normalized = buildString {
        for (character in raw) {
            when {
                character.isLetterOrDigit() -> append(character)
                character.isWhitespace() || character in setOf('-', '.', '_') -> append('_')
            }
        }
    }
    return normalized.takeIf { it.isNotEmpty() }
}

/**
 * Snapshots every emoji URL the editor can resolve, with picker selections taking
 * precedence. A selected emoji must remain publishable even if its backing set is
 * replaced or briefly unavailable before the confirmation payload is built.
 */
internal fun composeEmojiUrls(
    knownEmojis: Iterable<CustomEmoji>,
    selectedEmojis: Map<String, String>,
): Map<String, String> = buildMap {
    knownEmojis.forEach { emoji ->
        val shortcode = normalizeComposeShortcode(emoji.shortcode) ?: return@forEach
        if (emoji.url.isNotBlank()) put(shortcode, emoji.url)
    }
    selectedEmojis.forEach { (rawShortcode, url) ->
        val shortcode = normalizeComposeShortcode(rawShortcode) ?: return@forEach
        if (url.isNotBlank()) put(shortcode, url)
    }
}

private fun appendContentTags(
    tags: MutableList<List<String>>,
    content: String,
    state: PublishPayloadState,
    existingPTags: Set<String>,
) {
    state.mentionPubkeys.forEach { pubkey ->
        if (pubkey !in existingPTags && pubkey in state.activeNotifyPubkeys) {
            tags += listOf("p", pubkey)
        }
    }
    tags += extractPublishEmojiTags(content, state.customEmojis)
    tags += extractPublishHashtagTags(content)
    if (state.isSensitive) tags += listOf("content-warning", "")
}

private fun extractPublishEmojiTags(
    content: String,
    customEmojis: Map<String, String>,
): List<List<String>> {
    if (customEmojis.isEmpty()) return emptyList()
    val tags = mutableListOf<List<String>>()
    val seen = mutableSetOf<String>()
    var index = 0
    while (index < content.length) {
        if (content[index] == ':' && index + 2 < content.length) {
            val end = content.indexOf(':', index + 1)
            if (end > index + 1) {
                val shortcode = content.substring(index + 1, end)
                val url = customEmojis[shortcode]
                if (url != null && seen.add(shortcode)) {
                    tags += listOf("emoji", shortcode, url)
                }
                index = end + 1
                continue
            }
        }
        index++
    }
    return tags
}

private fun extractPublishHashtagTags(content: String): List<List<String>> {
    val seen = mutableSetOf<String>()
    return ContentParser.findHashtags(content).mapNotNull { (_, _, hashtag) ->
        val normalized = hashtag.lowercase()
        if (seen.add(normalized)) listOf("t", normalized) else null
    }
}

private fun publishImetaTags(blocks: List<PublishBlock>): List<List<String>> = blocks
    .filterIsInstance<PublishBlock.Media>()
    .map { block ->
        val media = block.attachment
        buildList {
            add("imeta")
            add("url ${media.url}")
            add("m ${media.mimeType}")
            add("x ${media.sha256}")
            add("size ${media.sizeBytes}")
            if (media.width != null && media.height != null) {
                add("dim ${media.width}x${media.height}")
            }
            media.blurhash?.let { add("blurhash $it") }
            media.thumbnailUrl?.let { add("thumb $it") }
            media.durationMs?.takeIf { it > 0L }?.let { add("duration ${it / 1000L}") }
        }
    }

private val NOSTR_MENTION_REGEX =
    Regex("nostr:n(?:pub|profile)1[a-z0-9]+", RegexOption.IGNORE_CASE)
