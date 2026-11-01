package com.unsilence.app.data.blossom

import com.unsilence.app.data.network.parseAllowedUntrustedHttpUrl

/** Local retrieval preferences, independent of the published upload-server list. */
data class AvatarFallbackPreferences(
    val enabled: Boolean = false,
    val servers: List<String> = listOf("https://blossom.primal.net"),
)

internal data class AvatarBlob(val hash: String, val filename: String)

internal object AvatarFallbackPolicy {
    const val MAX_SERVERS = 3
    private val filename = Regex("([0-9a-fA-F]{64})(?:\\.[a-zA-Z0-9]{1,10})?")

    fun blob(raw: String): AvatarBlob? {
        if (raw.length > 4096 || raw.any { it.isWhitespace() || it.code < 32 }) return null
        val url = parseAllowedUntrustedHttpUrl(raw) ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        val name = url.pathSegments.lastOrNull() ?: return null
        val match = filename.matchEntire(name) ?: return null
        return AvatarBlob(match.groupValues[1].lowercase(), name)
    }

    /** Server roots only; never forward credentials, query strings or source paths. */
    fun server(raw: String): String? {
        if (raw.length > 2048 || raw.any { it.isWhitespace() || it.code < 32 }) return null
        val url = parseAllowedUntrustedHttpUrl(if ("://" in raw) raw else "https://$raw") ?: return null
        if ('.' !in url.host && ':' !in url.host) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null || url.encodedPath != "/") return null
        return url.toString().removeSuffix("/")
    }

    fun atServer(server: String, blob: AvatarBlob): String? = server(server)?.let { "$it/${blob.filename}" }

    fun candidates(blob: AvatarBlob, original: String, author: List<String>, preferences: AvatarFallbackPreferences): List<String> =
        (author.take(MAX_SERVERS) + if (preferences.enabled) preferences.servers.take(MAX_SERVERS) else emptyList())
            .mapNotNull { atServer(it, blob) }.filter { it != original }.distinct()
}
