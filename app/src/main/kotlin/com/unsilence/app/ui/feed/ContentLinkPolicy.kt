package com.unsilence.app.ui.feed

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val OPENABLE_SCHEMES = setOf("http", "https", "nostr", "mailto", "lightning", "bitcoin")
private const val LINK_PATH_LABEL_LIMIT = 24
private const val DOMAIN_CLAIM = """(?:[\p{L}\p{N}-]+\.)+(?:xn--[a-z0-9-]+|\p{L}{2,})"""

// Whole web URLs consume userinfo/path together; bare claims can occur inside prose.
// A two-letter alphabetic (or punycode) TLD excludes version text such as v2.0/e.g.
private val ADDRESS_CLAIMS = Regex(
    """https?://[^\s<>]+|(?<![\p{L}\p{N}_.-])(?:\[[0-9a-f:.]+\]|(?:[0-9]{1,3}\.){3}[0-9]{1,3}|$DOMAIN_CLAIM)\.?(?::[0-9]+)?(?:[/?#][^\s<>]*)?(?![\p{L}\p{N}_-])""",
    RegexOption.IGNORE_CASE,
)
// Recognition only, not email validation. The domain is a web claim only when
// the destination is HTTP(S); a dotted local part is never a separate claim.
private val EMAIL_ADDRESSES = Regex(
    """[\p{L}\p{N}.!#$%&'*+/=?^_`{|}~-]+@($DOMAIN_CLAIM)(?![\p{L}\p{N}_-])""",
    RegexOption.IGNORE_CASE,
)

private data class ContentLinkDestination(val scheme: String, val web: WebLink?)

/** One activation boundary for inline spans, preview planning and direct cards. */
private fun parseContentLink(url: String): ContentLinkDestination? {
    if (url.any { it.isWhitespace() || it.isISOControl() }) return null
    val colon = url.indexOf(':')
    if (colon <= 0 || colon == url.lastIndex) return null
    val scheme = url.substring(0, colon).lowercase()
    if (scheme !in OPENABLE_SCHEMES) return null
    val web = if (scheme == "http" || scheme == "https") parseWebLink(url) ?: return null else null
    return ContentLinkDestination(scheme, web)
}

internal fun isOpenableContentLink(url: String): Boolean = parseContentLink(url) != null

/** Strict display identity only; never selects favicon, image or navigation requests. */
internal fun previewHostLabel(url: String): String? {
    val destination = parseContentLink(url)?.web?.parsed ?: return null
    val host = if (':' in destination.host) "[${destination.host}]" else destination.host
    return host + if (destination.port == HttpUrl.defaultPort(destination.scheme)) "" else ":${destination.port}"
}

/** Null means plain text: no link annotation and no link styling. Never resolves DNS. */
internal fun contentLinkLabel(url: String, text: String = url): String? {
    val link = parseContentLink(url) ?: return null
    val scheme = link.scheme
    val destination = link.web
    if (destination == null) {
        return if (text != url && webAddressClaims(text, ignoreEmailClaims = true).any()) "$text ($scheme:)" else text
    }
    if (text == url) return compactLinkLabel(destination)
    val misleading = webAddressClaims(text).any { !it.sameOrigin(destination) }
    return if (misleading) "$text (${destination.origin})" else text
}

private fun webAddressClaims(text: String, ignoreEmailClaims: Boolean = false): Sequence<WebLink> {
    val emails = if ('@' in text) EMAIL_ADDRESSES.findAll(text).toList() else emptyList()
    return ADDRESS_CLAIMS.findAll(text).mapNotNull { match ->
        val email = emails.firstOrNull { match.range.first in it.range }
        if (email != null && (ignoreEmailClaims || match.range.first != email.groups[1]!!.range.first)) {
            return@mapNotNull null
        }
        val token = match.value.trimEnd('.', ',', ';', '!', ')', '}', '"', '\'')
        val candidate = if (token.startsWith("http://", true) || token.startsWith("https://", true)) token else "https://$token"
        parseWebLink(candidate)
    }
}

private data class WebLink(val raw: String, val parsed: HttpUrl, val authorityEnd: Int, val explicitPort: String?) {
    private val normalizedHost get() = parsed.host.trimEnd('.')
    private fun displayHost(host: String) = if (':' in host) "[$host]" else host
    val authority: String get() = displayHost(parsed.host) + explicitPort.orEmpty()
    val origin: String get() = "${parsed.scheme}://${displayHost(normalizedHost)}" +
        if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
    fun sameOrigin(other: WebLink): Boolean = parsed.scheme == other.parsed.scheme &&
        normalizedHost.removePrefix("www.") == other.normalizedHost.removePrefix("www.") && parsed.port == other.parsed.port
}

/** HttpUrl supplies IDNA/IPv6 normalization; reject its forgiving authority repairs. */
private fun parseWebLink(value: String): WebLink? {
    if (value.any { it.isWhitespace() || it.isISOControl() }) return null
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0 || value.substring(0, schemeEnd).lowercase() !in setOf("http", "https")) return null
    val start = schemeEnd + 3
    val end = value.indexOfAny(charArrayOf('/', '\\', '?', '#'), start).let { if (it < 0) value.length else it }
    val authority = value.substring(start, end)
    val hostPort = authority.substringAfterLast('@')
    if (hostPort.isEmpty() || '%' in hostPort) return null
    val portSuffix = if (hostPort.startsWith('[')) {
        val bracket = hostPort.indexOf(']')
        if (bracket < 0) return null
        hostPort.substring(bracket + 1)
    } else {
        if ('[' in hostPort || ']' in hostPort || hostPort.count { it == ':' } > 1) return null
        hostPort.indexOf(':').let { if (it < 0) "" else hostPort.substring(it) }
    }
    if (portSuffix.isNotEmpty() && (!portSuffix.startsWith(':') || portSuffix.length == 1 ||
            portSuffix.drop(1).any { it !in '0'..'9' })) return null
    val parsed = value.toHttpUrlOrNull() ?: return null
    val host = parsed.host.removeSuffix(".")
    if (host.isEmpty()) return null
    if (':' !in host) {
        val labels = host.split('.')
        if (labels.any { label -> label.isEmpty() || label.startsWith('-') || label.endsWith('-') ||
                label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' } }) return null
        // Browsers can reinterpret noncanonical numeric hosts as IPv4. Do not
        // disclose one origin while handing the browser an alternate spelling.
        val last = labels.last()
        if (last.all { it in '0'..'9' } || last.startsWith("0x")) {
            if (labels.size != 4 || labels.any { part -> part.any { it !in '0'..'9' } ||
                    (part.length > 1 && part.startsWith('0')) || part.toIntOrNull() !in 0..255 }) return null
        }
    }
    return WebLink(value, parsed, end, portSuffix.takeIf { it.isNotEmpty() })
}

/** Render-only compaction using the same parsed host as activation and disclosure. */
private fun compactLinkLabel(link: WebLink): String {
    val suffix = link.raw.substring(link.authorityEnd)
    // Preserve the prior full-label fallback for malformed percent escapes, but
    // still replace Unicode hosts with ASCII and never display userinfo as host.
    if (suffix.indices.any { i -> suffix[i] == '%' &&
            (i + 2 >= suffix.length || suffix[i + 1].digitToIntOrNull(16) == null || suffix[i + 2].digitToIntOrNull(16) == null) }) {
        return "${link.parsed.scheme}://${link.authority}$suffix"
    }
    val path = if (suffix.startsWith('/') || suffix.startsWith('\\')) link.parsed.encodedPath else ""
    val clippedPath = if (path.length > LINK_PATH_LABEL_LIMIT) {
        var end = LINK_PATH_LABEL_LIMIT
        val slash = path.lastIndexOf('/', end - 1)
        if (slash > 0) end = slash + 1
        val percent = path.lastIndexOf('%', end - 1)
        if (percent >= 0 && end - percent < 3) end = percent
        if (end > 0 && path[end - 1].isHighSurrogate()) end--
        path.take(end) + "…"
    } else path
    val omittedTail = link.parsed.encodedQuery != null || link.parsed.encodedFragment != null
    return link.authority + clippedPath + if (omittedTail && path.length <= LINK_PATH_LABEL_LIMIT) "…" else ""
}
