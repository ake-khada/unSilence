package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.FeedRow
import java.util.Locale

private const val MIN_CLUSTER_AUTHORS = 3
private const val MIN_CONTENT_WORDS = 24
private const val MIN_SIGNAL_TOKENS = 16
private const val MIN_SHARED_TOKENS = 14
private const val MIN_JACCARD_SIMILARITY = 0.52f
private const val MIN_OVERLAP_SIMILARITY = 0.72f
private const val MAX_CONTENT_CHARS = 4_000
private const val MAX_SIGNAL_TOKENS = 96
private const val MAX_CLUSTER_REPRESENTATIVES_PER_PARENT = 64
private const val CLUSTER_WINDOW_SECONDS = 12L * 60L * 60L

/** Shared flattened-row model for threaded notes and article comments. */
data class ModeratedReplyRow(
    val row: FeedRow,
    val depth: Int,
    val muted: Boolean = false,
    val spamClusterId: String? = null,
)

/**
 * A lazy-list item derived from a flattened reply tree. Suspected spam remains
 * in the source rows and is replaced by one reversible cluster item only at
 * render time, so this policy never deletes content or changes tree structure.
 */
internal sealed interface ReplyListItem {
    val key: String

    data class Reply(val depthRow: ModeratedReplyRow) : ReplyListItem {
        override val key: String = depthRow.row.id
    }

    data class SpamCluster(
        val clusterId: String,
        val replyCount: Int,
        val depth: Int,
        val revealed: Boolean,
    ) : ReplyListItem {
        override val key: String = "reply-spam:$clusterId"
    }
}

/**
 * Mark two high-confidence structural shapes: bare comma-separated word lists,
 * and long order-independent near-duplicates posted by at least three distinct
 * untrusted authors beneath the same parent within a bounded time window. Only
 * leaves are eligible: hiding a parent could orphan a live descendant or
 * distort the tree.
 *
 * The near-duplicate path deliberately ignores short replies. Links are
 * stripped before either detector evaluates the remaining text, so appending
 * a relay or web URL cannot turn an otherwise matching payload into a bypass.
 * Both paths detect shape rather than a campaign word list, so bots cannot
 * evade the rule merely by swapping vocabulary.
 */
internal fun markLikelyCoordinatedSpam(
    rows: List<ModeratedReplyRow>,
    protectedEventIds: Set<String> = emptySet(),
    isTrustedAuthor: (String) -> Boolean = { false },
): List<ModeratedReplyRow> {
    if (rows.isEmpty()) return rows

    val referencedParents = rows.mapNotNullTo(HashSet(rows.size)) { it.row.replyToId }
    val seedShapeCandidates = ArrayList<ShapeCandidate>()
    val duplicateCandidates = ArrayList<SpamCandidate>()
    rows.forEachIndexed { index, depthRow ->
        val row = depthRow.row
        val hasFlattenedDescendant = rows.getOrNull(index + 1)?.depth?.let { it > depthRow.depth } == true
        if (
            depthRow.muted ||
            row.id in protectedEventIds ||
            row.id in referencedParents ||
            hasFlattenedDescendant ||
            isTrustedAuthor(row.pubkey)
        ) {
            return@forEachIndexed
        }

        val parent = ParentKey(
            id = row.replyToId ?: row.rootId ?: REPLY_ROOT_PARENT,
            depth = depthRow.depth,
        )
        if (isSeedPhraseShape(row.content)) {
            seedShapeCandidates += ShapeCandidate(index, parent)
            return@forEachIndexed
        }

        val signature = tokenSignature(row.content) ?: return@forEachIndexed
        duplicateCandidates += SpamCandidate(
            index = index,
            parent = parent,
            pubkey = row.pubkey,
            createdAt = row.createdAt,
            signature = signature,
        )
    }

    val clusterByRowIndex = HashMap<Int, String>()
    seedShapeCandidates.groupBy { it.parent }.forEach { (parent, siblings) ->
        val clusterId = stableClusterId("seed-shape", parent)
        siblings.forEach { clusterByRowIndex[it.index] = clusterId }
    }

    duplicateCandidates.groupBy { it.parent }.values.forEach { siblings ->
        if (siblings.size < MIN_CLUSTER_AUTHORS) return@forEach

        // Newest-first plus a representative cap bounds worst-case work on a
        // huge tree without giving old singleton prose priority over a live wave.
        val clusters = ArrayList<WorkingCluster>()
        siblings.sortedByDescending { it.createdAt }.forEach { candidate ->
            // Representative-only matching is deliberate: it prevents a chain
            // of borderline pairs from drifting into one unrelated cluster.
            val match = clusters.firstOrNull { cluster -> cluster.accepts(candidate) }
            if (match != null) {
                match.add(candidate)
            } else if (clusters.size < MAX_CLUSTER_REPRESENTATIVES_PER_PARENT) {
                clusters += WorkingCluster(candidate)
            }
        }

        clusters.forEach clusterLoop@{ cluster ->
            if (cluster.members.mapTo(HashSet()) { it.pubkey }.size < MIN_CLUSTER_AUTHORS) {
                return@clusterLoop
            }
            val clusterId = stableNearDuplicateClusterId(cluster)
            cluster.members.forEach { clusterByRowIndex[it.index] = clusterId }
        }
    }
    if (clusterByRowIndex.isEmpty()) return rows

    return rows.mapIndexed { index, row ->
        clusterByRowIndex[index]?.let { row.copy(spamClusterId = it) } ?: row
    }
}

/** Build the reversible lazy-list projection for collapsed/revealed clusters. */
internal fun replyListItems(
    rows: List<ModeratedReplyRow>,
    revealedClusterIds: Set<String>,
): List<ReplyListItem> {
    val counts = rows.mapNotNull { it.spamClusterId }.groupingBy { it }.eachCount()
    if (counts.isEmpty()) return rows.map { ReplyListItem.Reply(it) }

    val emittedClusters = HashSet<String>(counts.size)
    return buildList(rows.size) {
        rows.forEach { depthRow ->
            val clusterId = depthRow.spamClusterId
            if (clusterId == null) {
                add(ReplyListItem.Reply(depthRow))
                return@forEach
            }

            val revealed = clusterId in revealedClusterIds
            if (emittedClusters.add(clusterId)) {
                add(
                    ReplyListItem.SpamCluster(
                        clusterId = clusterId,
                        replyCount = counts.getValue(clusterId),
                        depth = depthRow.depth,
                        revealed = revealed,
                    ),
                )
            }
            if (revealed) add(ReplyListItem.Reply(depthRow))
        }
    }
}

private data class ParentKey(val id: String, val depth: Int)

private data class ShapeCandidate(val index: Int, val parent: ParentKey)

private data class TokenSignature(val tokens: Set<String>)

private data class SpamCandidate(
    val index: Int,
    val parent: ParentKey,
    val pubkey: String,
    val createdAt: Long,
    val signature: TokenSignature,
)

private class WorkingCluster(first: SpamCandidate) {
    val representative: SpamCandidate = first
    val members = mutableListOf(first)
    private var earliestAt = first.createdAt
    private var latestAt = first.createdAt

    fun accepts(candidate: SpamCandidate): Boolean {
        val nextEarliest = minOf(earliestAt, candidate.createdAt)
        val nextLatest = maxOf(latestAt, candidate.createdAt)
        return nextLatest - nextEarliest <= CLUSTER_WINDOW_SECONDS &&
            signaturesMatch(representative.signature, candidate.signature)
    }

    fun add(candidate: SpamCandidate) {
        members += candidate
        earliestAt = minOf(earliestAt, candidate.createdAt)
        latestAt = maxOf(latestAt, candidate.createdAt)
    }
}

private fun tokenSignature(content: String): TokenSignature? {
    val withoutMention = contentWithoutPersonMentions(content)
    val structuralContent = LINK_REFERENCE_REGEX.replace(withoutMention, " ")
    if (structuralContent.length < MIN_CONTENT_WORDS * 4) return null

    val bounded = structuralContent.take(MAX_CONTENT_CHARS).lowercase(Locale.ROOT)
    val signalTokens = LinkedHashSet<String>(MAX_SIGNAL_TOKENS)
    var wordCount = 0
    var tokenStart = -1

    fun finishToken(endExclusive: Int) {
        if (tokenStart < 0) return
        val length = endExclusive - tokenStart
        wordCount += 1
        if (length >= 4 && signalTokens.size < MAX_SIGNAL_TOKENS) {
            signalTokens += bounded.substring(tokenStart, endExclusive)
        }
        tokenStart = -1
    }

    bounded.forEachIndexed { index, char ->
        if (char.isLetterOrDigit()) {
            if (tokenStart < 0) tokenStart = index
        } else {
            finishToken(index)
        }
    }
    finishToken(bounded.length)

    return if (wordCount >= MIN_CONTENT_WORDS && signalTokens.size >= MIN_SIGNAL_TOKENS) {
        TokenSignature(signalTokens)
    } else {
        null
    }
}

/**
 * BIP-39-shaped payload without depending on a fixed word list: eight or more
 * comma-separated lowercase ASCII words. Person mentions and link-only
 * segments are structural metadata and are ignored; adding more references
 * cannot make an otherwise matching payload legitimate. Digits and general
 * sentence punctuation in the remaining payload still reject the shape.
 */
internal fun isSeedPhraseShape(content: String): Boolean {
    var wordCount = 0
    content.split(',').forEach { segment ->
        var segmentMentions = 0
        val withoutMention = PERSON_MENTION_REGEX.replace(segment) {
            segmentMentions += 1
            ""
        }

        var linkCount = 0
        val word = LINK_REFERENCE_REGEX.replace(withoutMention) {
            linkCount += 1
            ""
        }.trim()
        if (word.isEmpty()) {
            if (segmentMentions == 0 && linkCount == 0) return false
        } else {
            if (word.any { it !in 'a'..'z' }) return false
            wordCount += 1
        }
    }
    return wordCount >= 8
}

/**
 * Person references are structural metadata for clustering, not prose. Strip
 * all of them so adding more tagged accounts cannot disable detection.
 */
private fun contentWithoutPersonMentions(content: String): String =
    PERSON_MENTION_REGEX.replace(content, " ")

private fun signaturesMatch(first: TokenSignature, second: TokenSignature): Boolean {
    val shared = first.tokens.count { it in second.tokens }
    if (shared < MIN_SHARED_TOKENS) return false
    val smaller = minOf(first.tokens.size, second.tokens.size)
    val union = first.tokens.size + second.tokens.size - shared
    return shared.toFloat() / smaller >= MIN_OVERLAP_SIMILARITY &&
        shared.toFloat() / union >= MIN_JACCARD_SIMILARITY
}

private fun stableNearDuplicateClusterId(cluster: WorkingCluster): String {
    val minimumFrequency = (cluster.members.size * 2 + 2) / 3
    val frequencies = HashMap<String, Int>()
    cluster.members.forEach { member ->
        member.signature.tokens.forEach { token ->
            frequencies[token] = frequencies.getOrDefault(token, 0) + 1
        }
    }
    val sharedCore = frequencies.asSequence()
        .filter { it.value >= minimumFrequency }
        .map { it.key }
        .sorted()
        .take(MAX_CLUSTER_ID_TOKENS)
        .toList()
        .ifEmpty { cluster.representative.signature.tokens.sorted().take(MAX_CLUSTER_ID_TOKENS) }
    return stableClusterId("near-duplicate", cluster.representative.parent, sharedCore)
}

private fun stableClusterId(
    prefix: String,
    parent: ParentKey,
    tokens: List<String> = emptyList(),
): String {
    var hash = FNV64_OFFSET_BASIS
    fun append(value: String) {
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= FNV64_PRIME
        }
        hash = hash xor 0xffL
        hash *= FNV64_PRIME
    }
    append(parent.id)
    append(parent.depth.toString())
    tokens.forEach(::append)
    return "$prefix:${java.lang.Long.toUnsignedString(hash, 16)}"
}

private const val REPLY_ROOT_PARENT = "<reply-root>"
private const val MAX_CLUSTER_ID_TOKENS = 24
private const val FNV64_OFFSET_BASIS = -3750763034362895579L
private const val FNV64_PRIME = 1099511628211L
private val PERSON_MENTION_REGEX = Regex(
    pattern = "(?:nostr:n(?:pub|profile)1[a-z0-9]{10,200}|@n(?:pub|profile)1[a-z0-9]{10,200}|@[a-z0-9._-]{1,64}|#\\[\\d{1,3}])",
    option = RegexOption.IGNORE_CASE,
)
private val LINK_REFERENCE_REGEX = Regex(
    pattern = "(?:wss?|https?)://\\S+|nostr:\\S+",
    option = RegexOption.IGNORE_CASE,
)
