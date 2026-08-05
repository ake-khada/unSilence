package com.unsilence.app.data.network

import com.unsilence.app.data.BROWSER_USER_AGENT
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Nip05HttpClient @Inject constructor(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = MAX_CONCURRENT_RESOLUTIONS
            maxRequestsPerHost = MAX_CONCURRENT_RESOLUTIONS
        })
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(UntrustedHttpNetworkGuard)
        .build()

    internal suspend fun resolve(key: Nip05VerificationCacheKey): Nip05VerificationStatus? {
        val target = nip05LookupTarget(key.nip05) ?: return Nip05VerificationStatus.UNVERIFIED
        val request = Request.Builder()
            .url(target.url)
            .header("Accept", "application/json")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()
        return executeAndParse(client.newCall(request)) { response ->
            val body = if (response.code == 200) {
                val declaredLength = response.body.contentLength()
                if (declaredLength > MAX_BODY_BYTES) null
                else readBoundedUtf8(response.body.source(), MAX_BODY_BYTES)
            } else {
                null
            }
            nip05StatusFromHttpResponse(response.code, body, target, key.pubkey)
        }
    }

    private suspend fun executeAndParse(
        call: Call,
        parse: (Response) -> Nip05VerificationStatus,
    ): Nip05VerificationStatus? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { closedResponse ->
                    val result = runCatching { parse(closedResponse) }
                        .getOrDefault(Nip05VerificationStatus.UNVERIFIED)
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        })
    }

    companion object {
        internal const val MAX_BODY_BYTES = 16L * 1_024
        internal const val MAX_CONCURRENT_RESOLUTIONS = 2
    }
}

internal fun readBoundedUtf8(source: BufferedSource, maxBytes: Long): String? {
    require(maxBytes >= 0L)
    val buffer = Buffer()
    while (buffer.size <= maxBytes) {
        val remaining = maxBytes + 1L - buffer.size
        val read = source.read(buffer, remaining)
        if (read == -1L) return buffer.readUtf8()
    }
    return null
}
