package com.unsilence.app.ui.compose

/**
 * Target for composing a NIP-22 (kind-1111) comment on a long-form article.
 * A top-level article comment has [parentId] == null; a reply to an existing
 * comment carries the parent comment's id/kind/pubkey. The article is ALWAYS the
 * root scope (uppercase A/K/P), per NIP-22.
 */
data class ArticleCommentTarget(
    val articleId: String?,            // article event id (null for boosted/embedded absent from MES)
    val articleCoord: String,          // 30023:<pubkey>:<d>
    val articlePubkey: String,
    val articleRelayHint: String? = null,
    val parentId: String? = null,      // null = top-level article comment
    val parentKind: Int? = null,       // parent comment kind (1111 or legacy 1)
    val parentPubkey: String? = null,
    val parentRelayHint: String? = null,
)

/** Root and parent scope for a NIP-22 comment on a NIP-71 video. */
data class VideoCommentTarget(
    val rootEventId: String?,
    val rootCoord: String?,
    val rootKind: Int,
    val rootPubkey: String,
    val rootRelayHint: String? = null,
    val parentId: String? = null,
    val parentKind: Int? = null,
    val parentPubkey: String? = null,
    val parentRelayHint: String? = null,
)

/**
 * Pure NIP-22 tag construction for kind-1111 article comments — extracted for
 * unit testing. See https://github.com/nostr-protocol/nips/blob/master/22.md
 *
 * Root scope is uppercase (always the article): `A`/`K`/`P`. Parent scope is
 * lowercase: for a top-level comment the parent IS the article (`a`/`e`/`k`/`p`);
 * for a reply, the parent is the comment being replied to.
 */
object Nip22Tags {
    fun articleComment(target: ArticleCommentTarget): List<Array<String>> {
        val tags = mutableListOf<Array<String>>()
        val coord = target.articleCoord
        val aHint = target.articleRelayHint.orEmpty()

        // ── Root scope (uppercase) — the article ─────────────────────────────
        tags.add(arrayOf("A", coord, aHint))
        tags.add(arrayOf("K", "30023"))
        tags.add(arrayOf("P", target.articlePubkey, aHint))

        // ── Parent scope (lowercase) ─────────────────────────────────────────
        if (target.parentId == null) {
            // Top-level: parent == root == the article.
            tags.add(arrayOf("a", coord, aHint))
            target.articleId?.let { tags.add(arrayOf("e", it, aHint, target.articlePubkey)) }
            tags.add(arrayOf("k", "30023"))
            tags.add(arrayOf("p", target.articlePubkey, aHint))
        } else {
            // Reply: parent is the comment.
            val pHint = target.parentRelayHint.orEmpty()
            tags.add(arrayOf("e", target.parentId, pHint, target.parentPubkey.orEmpty()))
            tags.add(arrayOf("k", (target.parentKind ?: 1111).toString()))
            target.parentPubkey?.let { tags.add(arrayOf("p", it, pHint)) }
        }
        return tags
    }

    /** NIP-22 scopes for event-addressed (21/22) and coordinate-addressed videos. */
    fun videoComment(target: VideoCommentTarget): List<Array<String>> {
        require(target.rootKind in setOf(21, 22, 34235, 34236))
        val tags = mutableListOf<Array<String>>()
        val rootHint = target.rootRelayHint.orEmpty()
        val coordinate = target.rootCoord

        if (coordinate != null) {
            tags.add(arrayOf("A", coordinate, rootHint))
        } else {
            val rootId = requireNotNull(target.rootEventId)
            tags.add(arrayOf("E", rootId, rootHint, target.rootPubkey))
        }
        tags.add(arrayOf("K", target.rootKind.toString()))
        tags.add(arrayOf("P", target.rootPubkey, rootHint))

        if (target.parentId == null) {
            if (coordinate != null) {
                tags.add(arrayOf("a", coordinate, rootHint))
            } else {
                tags.add(arrayOf("e", requireNotNull(target.rootEventId), rootHint, target.rootPubkey))
            }
            tags.add(arrayOf("k", target.rootKind.toString()))
            tags.add(arrayOf("p", target.rootPubkey, rootHint))
        } else {
            val parentHint = target.parentRelayHint.orEmpty()
            tags.add(arrayOf("e", target.parentId, parentHint, target.parentPubkey.orEmpty()))
            tags.add(arrayOf("k", (target.parentKind ?: 1111).toString()))
            target.parentPubkey?.let { tags.add(arrayOf("p", it, parentHint)) }
        }
        return tags
    }
}
