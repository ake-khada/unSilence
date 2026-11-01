package com.unsilence.app.data.blossom

import com.unsilence.app.data.network.parseAllowedUntrustedHttpUrl
import com.unsilence.app.data.network.UntrustedHttpNetworkGuard
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume

internal data class VerifiedAvatar(val bytes: ByteArray, val contentType: String?)

/** Every hop retains the image network guard. No source cookies, credentials or query are copied. */
internal class AvatarTransport(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .apply { if (UntrustedHttpNetworkGuard !in client.networkInterceptors) addNetworkInterceptor(UntrustedHttpNetworkGuard) }
        .build()

    suspend fun fetch(raw: String, hash: String, hostBlocked: (String) -> Boolean = { false },
        onTransportFailure: (String) -> Unit = {}): VerifiedAvatar? {
        var url = parseAllowedUntrustedHttpUrl(raw) ?: return null
        repeat(4) { hop ->
            if (hostBlocked(url.host)) return null
            val result = get(url.toString(), hash) { onTransportFailure(url.host) }
            result.image?.let { return it }
            if (hop == 3 || result.redirect == null) return null
            val resolved = url.resolve(result.redirect) ?: return null
            url = parseAllowedUntrustedHttpUrl(resolved.toString()) ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        }
        return null
    }

    private data class Attempt(val image: VerifiedAvatar? = null, val redirect: String? = null)
    private suspend fun get(url: String, hash: String, onTransportFailure: () -> Unit): Attempt = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) { onTransportFailure(); continuation.resume(Attempt()) }
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        when {
                            it.code in listOf(301, 302, 303, 307, 308) -> { Attempt(redirect = it.header("Location")) }
                            it.code != 200 -> { Attempt() }
                            else -> {
                                val body = it.body
                                if (body.contentLength() > MAX_BYTES) { Attempt() } else {
                                    val source = body.source()
                                    val buffer = Buffer()
                                    while (buffer.size <= MAX_BYTES && source.read(buffer, minOf(8192L, MAX_BYTES + 1 - buffer.size)) != -1L) {
                                        if (continuation.isCancelled) throw IOException("cancelled")
                                    }
                                    val bytes = if (buffer.size <= MAX_BYTES) buffer.readByteArray() else null
                                    when {
                                        bytes == null -> { Attempt() }
                                        !matches(bytes, hash) -> { Attempt() }
                                        else -> Attempt(VerifiedAvatar(bytes, body.contentType()?.toString()))
                                    }
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    if (!continuation.isCancelled) { onTransportFailure() }
                    Attempt()
                }
                if (!continuation.isCancelled) continuation.resume(result)
            }
        })
    }

    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        fun matches(bytes: ByteArray, hash: String): Boolean =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == hash
    }
}
