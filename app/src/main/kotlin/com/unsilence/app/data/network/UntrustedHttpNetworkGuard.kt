package com.unsilence.app.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Shared SSRF policy for HTTP requests whose destination comes from untrusted
 * content. The network interceptor validates the address of the socket OkHttp
 * actually connected on every hop, including redirects and connection reuse.
 */
internal object UntrustedHttpNetworkGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val requestUrl = chain.request().url
        val connectedAddress = chain.connection()?.socket()?.inetAddress
        requireAllowedUntrustedNetworkHop(requestUrl, connectedAddress)
        return chain.proceed(chain.request())
    }
}

/** Parse an untrusted URL without DNS and reject schemes/hosts that are
 *  definitely unsafe before any client tries to open a connection. */
internal fun parseAllowedUntrustedHttpUrl(rawUrl: String?): HttpUrl? {
    val parsed = rawUrl?.trim()?.takeIf(String::isNotEmpty)?.toHttpUrlOrNull() ?: return null
    return parsed.takeIf(::isAllowedUntrustedHttpUrl)
}

internal fun isAllowedUntrustedHttpUrl(url: HttpUrl): Boolean {
    val host = url.host.lowercase()
    val allowedScheme = url.scheme == "https" || (url.scheme == "http" && host.endsWith(".onion"))
    if (!allowedScheme) return false

    // Avoid even opening a socket for literal private/special-use addresses.
    // Hostnames still require the network interceptor below: resolving here
    // would both block the caller and remain vulnerable to DNS rebinding.
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
    val literalAddress = host.toLiteralInetAddressOrNull()
    return literalAddress?.isPublicInternetAddress() != false
}

/** Fails closed when OkHttp cannot expose the connected clearnet address. */
internal fun requireAllowedUntrustedNetworkHop(url: HttpUrl, address: InetAddress?) {
    if (!isAllowedUntrustedHttpUrl(url)) {
        throw IOException("blocked untrusted URL scheme")
    }
    if (url.host.lowercase().endsWith(".onion")) return
    if (address == null || !address.isPublicInternetAddress()) {
        throw IOException("blocked non-public untrusted address")
    }
}

internal fun InetAddress.isPublicInternetAddress(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress ||
        isSiteLocalAddress || isMulticastAddress
    ) {
        return false
    }
    if (this is Inet4Address) {
        val bytes = address.map { it.toInt() and 0xff }
        // RFC 6598 carrier-grade NAT space is not globally reachable.
        if (bytes[0] == 100 && bytes[1] in 64..127) return false
    }
    if (this is Inet6Address) {
        val first = address[0].toInt() and 0xff
        // RFC 4193 unique-local addresses (fc00::/7).
        if ((first and 0xfe) == 0xfc) return false
    }
    return true
}

private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

/** [HttpUrl.host] strips IPv6 brackets. Calling InetAddress only for an
 *  unmistakable literal avoids accidental DNS on this cheap policy path. */
private fun String.toLiteralInetAddressOrNull(): InetAddress? {
    if (':' !in this && !IPV4_LITERAL.matches(this)) return null
    return runCatching { InetAddress.getByName(this) }.getOrNull()
}
