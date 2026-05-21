package com.unsilence.app.data.blossom

import android.util.Log
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import com.unsilence.app.data.relay.NostrJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BlossomClient"

data class BlossomBlob(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
    val dimensions: Pair<Int, Int>? = null,
)

sealed class BlossomException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(message: String) : BlossomException(message)
    class QuotaExceeded(message: String) : BlossomException(message)
    class HashMismatch(expected: String, actual: String) :
        BlossomException("SHA-256 mismatch: expected $expected, got $actual")
    class ServerError(code: Int, body: String) : BlossomException("Server error $code: $body")
    class NetworkError(cause: Throwable) : BlossomException("Network error: ${cause.message}", cause)
}

@Singleton
class BlossomClient @Inject constructor(
    baseClient: OkHttpClient,
    private val authSigner: BlossomAuthSigner,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    /**
     * Upload bytes to a Blossom server.
     * @param bytes     raw file content
     * @param mimeType  MIME type (e.g. "image/jpeg")
     * @param serverUrl base server URL (e.g. "https://blossom.primal.net")
     * @return [BlossomBlob] on success
     */
    suspend fun upload(
        bytes: ByteArray,
        mimeType: String,
        serverUrl: String,
    ): Result<BlossomBlob> = withContext(Dispatchers.IO) {
        try {
            val sha256hex = sha256(bytes)
            val uploadUrl = serverUrl.trimEnd('/') + "/upload"

            val authHeader = authSigner.authHeader(sha256Hex = sha256hex)
                ?: return@withContext Result.failure(BlossomException.AuthFailed("Signing unavailable"))

            val request = Request.Builder()
                .url(uploadUrl)
                .put(bytes.toRequestBody(mimeType.toMediaType()))
                .header("Authorization", authHeader)
                .build()

            Log.d(TAG, "Uploading ${bytes.size} bytes to $uploadUrl")
            val response = client.newCall(request).execute()

            val body = response.body?.string() ?: ""
            Log.d(TAG, "Response ${response.code}: $body")
            when {
                response.code == 401 || response.code == 403 ->
                    return@withContext Result.failure(BlossomException.AuthFailed("Auth rejected: ${response.code}: $body"))
                response.code == 413 ->
                    return@withContext Result.failure(BlossomException.QuotaExceeded("File too large"))
                response.code == 429 ->
                    return@withContext Result.failure(BlossomException.QuotaExceeded("Rate limited"))
                !response.isSuccessful ->
                    return@withContext Result.failure(BlossomException.ServerError(response.code, body))
            }

            val json = NostrJson.parseToJsonElement(body).jsonObject
            val blobUrl = json["url"]?.jsonPrimitive?.content ?: ""
            val blobSha256 = json["sha256"]?.jsonPrimitive?.content ?: ""
            val blobSize = json["size"]?.jsonPrimitive?.long ?: bytes.size.toLong()
            val blobType = json["type"]?.jsonPrimitive?.content ?: mimeType

            if (blobSha256.isNotEmpty() && blobSha256 != sha256hex) {
                Log.w(TAG, "Server returned different SHA-256 (server may re-encode): expected $sha256hex, got $blobSha256")
            }

            Log.d(TAG, "Upload success: $blobUrl ($blobSize bytes)")
            Result.success(
                BlossomBlob(
                    url = blobUrl,
                    sha256 = blobSha256.ifEmpty { sha256hex },
                    sizeBytes = blobSize,
                    mimeType = blobType,
                )
            )
        } catch (e: BlossomException) {
            Log.e(TAG, "Upload failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Upload network error", e)
            Result.failure(BlossomException.NetworkError(e))
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
