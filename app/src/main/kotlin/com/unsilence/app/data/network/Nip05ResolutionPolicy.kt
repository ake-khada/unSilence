package com.unsilence.app.data.network

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

internal const val NIP05_POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
internal const val NIP05_NEGATIVE_TTL_MS = 6L * 60 * 60 * 1_000
internal const val NIP05_CACHE_CAP = 2_048
internal const val NIP05_CACHE_RECORD_MAX_BYTES = 4 * 1_024

private const val MAX_CACHE_FUTURE_DRIFT_MS = 5 * 60 * 1_000L
private val HEX_PUBKEY = Regex("^[0-9a-fA-F]{64}$")
private val NIP05_LOCAL_PART = Regex("^[A-Za-z0-9._-]+$")

enum class Nip05VerificationStatus {
    UNKNOWN,
    VERIFIED,
    UNVERIFIED,
}

internal data class Nip05VerificationCacheKey(
    val pubkey: String,
    val nip05: String,
)

internal data class Nip05VerificationCacheEntry(
    val key: Nip05VerificationCacheKey,
    val status: Nip05VerificationStatus,
    val checkedAtMs: Long,
    /** Mapping returned by `names[local]`; required and rechecked for VERIFIED. */
    val resolvedPubkey: String?,
)

internal data class Nip05LookupTarget(
    val local: String,
    val domain: String,
    val url: HttpUrl,
)

internal fun nip05VerificationCacheKey(
    pubkey: String,
    nip05: String,
): Nip05VerificationCacheKey? {
    val normalizedPubkey = normalizeNip05Pubkey(pubkey) ?: return null
    val normalizedAddress = nip05.trim()
    if (normalizedAddress.isEmpty()) return null
    return Nip05VerificationCacheKey(normalizedPubkey, normalizedAddress)
}

internal fun normalizeNip05Pubkey(pubkey: String): String? =
    pubkey.trim().lowercase().takeIf(HEX_PUBKEY::matches)

internal fun nip05LookupTarget(nip05: String): Nip05LookupTarget? {
    val trimmed = nip05.trim()
    if (trimmed.isEmpty()) return null
    val at = trimmed.indexOf('@')
    val local: String
    val domain: String
    when {
        at < 0 -> {
            local = "_"
            domain = trimmed
        }
        at == 0 || at != trimmed.lastIndexOf('@') || at == trimmed.lastIndex -> return null
        else -> {
            local = trimmed.substring(0, at)
            domain = trimmed.substring(at + 1)
        }
    }
    if (!NIP05_LOCAL_PART.matches(local)) return null
    val normalizedDomain = domain.lowercase()
    val scheme = if (normalizedDomain.endsWith(".onion")) "http" else "https"
    val url = runCatching {
        HttpUrl.Builder()
            .scheme(scheme)
            .host(normalizedDomain)
            .addPathSegment(".well-known")
            .addPathSegment("nostr.json")
            .addQueryParameter("name", local)
            .build()
    }.getOrNull() ?: return null
    if (!isAllowedUntrustedHttpUrl(url)) return null
    return Nip05LookupTarget(local = local, domain = url.host, url = url)
}

internal fun nip05StatusFromHttpResponse(
    statusCode: Int,
    body: String?,
    target: Nip05LookupTarget,
    expectedPubkey: String,
): Nip05VerificationStatus {
    if (statusCode != 200 || body == null) return Nip05VerificationStatus.UNVERIFIED
    val mapped = runCatching {
        Json.parseToJsonElement(body)
            .jsonObject["names"]
            ?.jsonObject
            ?.get(target.local)
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()?.trim()
    if (mapped == null || !HEX_PUBKEY.matches(mapped)) {
        return Nip05VerificationStatus.UNVERIFIED
    }
    return if (mapped.equals(expectedPubkey, ignoreCase = true)) {
        Nip05VerificationStatus.VERIFIED
    } else {
        Nip05VerificationStatus.UNVERIFIED
    }
}

internal fun Nip05VerificationCacheEntry.isValidAt(nowMs: Long): Boolean {
    if (key != nip05VerificationCacheKey(key.pubkey, key.nip05)) return false
    if (nip05LookupTarget(key.nip05) == null) return false
    if (checkedAtMs < 0L || checkedAtMs > nowMs + MAX_CACHE_FUTURE_DRIFT_MS) return false
    val ttl = when (status) {
        Nip05VerificationStatus.VERIFIED -> {
            val mapped = resolvedPubkey?.trim()
            if (mapped == null || !HEX_PUBKEY.matches(mapped) ||
                !mapped.equals(key.pubkey, ignoreCase = true)
            ) {
                return false
            }
            NIP05_POSITIVE_TTL_MS
        }
        Nip05VerificationStatus.UNVERIFIED -> {
            if (resolvedPubkey != null) return false
            NIP05_NEGATIVE_TTL_MS
        }
        Nip05VerificationStatus.UNKNOWN -> return false
    }
    return nowMs - checkedAtMs < ttl
}
