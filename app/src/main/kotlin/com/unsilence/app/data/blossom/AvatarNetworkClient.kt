package com.unsilence.app.data.blossom

import coil3.Extras
import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import okio.Buffer
import okhttp3.Response
import java.io.IOException

internal data class AvatarRequest(val author: String, val original: String)
internal val AvatarRequestKey = Extras.Key<AvatarRequest?>(null)

/** Marked avatars only; all other Coil network requests keep the existing client path. */
internal class AvatarNetworkClient(
    private val delegate: NetworkClient,
    private val recover: suspend (String, String, AvatarBlob) -> VerifiedAvatar?,
) : NetworkClient {
    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        val avatar = request.extras[AvatarRequestKey] ?: return delegate.executeRequest(request, block)
        val blob = AvatarFallbackPolicy.blob(avatar.original) ?: return delegate.executeRequest(request, block)
        var delivered = false
        var canRecover = true
        try {
            return delegate.executeRequest(request) { response ->
                // Preserve streaming/cache/animation semantics for a successful origin.
                canRecover = response.code in listOf(404, 410) || response.code in 500..599
                // nostr.build can return its missing-image placeholder as HTTP 200,
                // including after an origin redirect. Use the final response host.
                if (response.code == 200 && response.headers["x-status"] == "404") {
                    val host = (response.delegate as? Response)?.request?.url?.host
                    canRecover = host == "nostr.build" || host?.endsWith(".nostr.build") == true
                }
                if (canRecover) throw IOException("avatar origin unavailable")
                delivered = true
                block(response)
            }
        } catch (error: IOException) {
            if (delivered || !canRecover) throw error
        }
        val recovered = recover(avatar.author, avatar.original, blob) ?: throw IOException("avatar unavailable")
        return verifiedResponse(recovered, block)
    }

    private suspend fun <T> verifiedResponse(image: VerifiedAvatar, block: suspend (NetworkResponse) -> T): T {
        val headers = NetworkHeaders.Builder().apply {
            image.contentType?.let { set("Content-Type", it) }
            set("Content-Length", image.bytes.size.toString())
        }.build()
        return NetworkResponseBody(Buffer().write(image.bytes)).use { body ->
            block(NetworkResponse(code = 200, headers = headers, body = body))
        }
    }
}
