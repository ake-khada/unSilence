package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.WotLookup
import java.security.MessageDigest

internal const val WOT_ASSERTION_CHUNK_SIZE = 200
internal const val WOT_FETCH_TIMEOUT_MS = 15_000L
internal const val WOT_HYDRATION_WINDOW_MS = 500L
internal const val WOT_STALENESS_MS = 12L * 60L * 60L * 1000L
internal const val WOT_STALENESS_SECONDS = WOT_STALENESS_MS / 1000L

internal fun normalizeWotPubkey(pubkey: String?): String? {
    val normalized = pubkey?.trim()?.lowercase() ?: return null
    if (normalized.length != 64) return null
    return normalized.takeIf { value -> value.all { it in '0'..'9' || it in 'a'..'f' } }
}

internal fun wotSubjectChunks(
    subjects: Collection<String>,
    chunkSize: Int = WOT_ASSERTION_CHUNK_SIZE,
): List<List<String>> =
    subjects
        .mapNotNull(::normalizeWotPubkey)
        .distinct()
        .sorted()
        .chunked(chunkSize)

internal fun markWotChunkIfEosed(
    chunk: List<String>,
    eosed: Boolean,
    markQueried: (List<String>) -> Unit,
): Boolean {
    if (eosed) markQueried(chunk)
    return eosed
}

internal fun wotTargetsHash(providerPubkey: String, targets: Collection<String>): String {
    val normalizedProvider = normalizeWotPubkey(providerPubkey).orEmpty()
    val body = buildString {
        append(normalizedProvider)
        append('\n')
        wotSubjectChunks(targets).flatten().forEach { target ->
            append(target)
            append('\n')
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun shouldSkipBootstrapWotFetch(
    hasWotData: Boolean,
    ageMs: Long,
    lastTargetsHash: String,
    currentTargetsHash: String,
    stalenessMs: Long = WOT_STALENESS_MS,
): Boolean =
    hasWotData && ageMs < stalenessMs && lastTargetsHash == currentTargetsHash

internal fun selectWotHydrationCandidates(
    pubkeys: Collection<String>,
    lookup: (String) -> WotLookup,
    nowSeconds: Long,
    refreshStaleScored: Boolean,
    staleAfterSeconds: Long = WOT_STALENESS_SECONDS,
): List<String> {
    val selected = LinkedHashSet<String>()
    for (pubkey in pubkeys.mapNotNull(::normalizeWotPubkey).distinct()) {
        when (val state = lookup(pubkey)) {
            WotLookup.Pending -> selected.add(pubkey)
            is WotLookup.Scored -> {
                if (refreshStaleScored && nowSeconds - state.assertion.updatedAt >= staleAfterSeconds) {
                    selected.add(pubkey)
                }
            }
            WotLookup.Absent -> Unit
        }
    }
    return selected.toList()
}
