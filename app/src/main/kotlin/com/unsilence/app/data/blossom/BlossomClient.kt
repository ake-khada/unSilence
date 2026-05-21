package com.unsilence.app.data.blossom

import android.util.Log
import kotlinx.serialization.json.jsonArray
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
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
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
    val blurhash: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
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
            val body = bytes.toRequestBody(mimeType.toMediaType())
            executeUpload(body, sha256hex, mimeType, bytes.size.toLong(), serverUrl)
        } catch (e: BlossomException) {
            Log.e(TAG, "Upload failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Upload network error", e)
            Result.failure(BlossomException.NetworkError(e))
        }
    }

    /**
     * Upload a file to a Blossom server — streams content without loading into memory.
     * Used for large files (video).
     */
    suspend fun upload(
        file: File,
        mimeType: String,
        serverUrl: String,
    ): Result<BlossomBlob> = withContext(Dispatchers.IO) {
        try {
            val sha256hex = sha256Streaming(file)
            val body = object : RequestBody() {
                override fun contentType() = mimeType.toMediaType()
                override fun contentLength() = file.length()
                override fun writeTo(sink: BufferedSink) {
                    file.inputStream().use { input ->
                        val buf = ByteArray(8192)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            sink.write(buf, 0, read)
                        }
                    }
                }
            }
            executeUpload(body, sha256hex, mimeType, file.length(), serverUrl)
        } catch (e: BlossomException) {
            Log.e(TAG, "Upload failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Upload network error", e)
            Result.failure(BlossomException.NetworkError(e))
        }
    }

    private suspend fun executeUpload(
        body: RequestBody,
        sha256hex: String,
        mimeType: String,
        sizeBytes: Long,
        serverUrl: String,
    ): Result<BlossomBlob> {
        val uploadUrl = serverUrl.trimEnd('/') + "/upload"

        val authHeader = authSigner.authHeader(sha256Hex = sha256hex)
            ?: return Result.failure(BlossomException.AuthFailed("Signing unavailable"))

        val request = Request.Builder()
            .url(uploadUrl)
            .put(body)
            .header("Authorization", authHeader)
            .build()

        Log.d(TAG, "Uploading $sizeBytes bytes to $uploadUrl")
        val response = client.newCall(request).execute()

        val responseBody = response.body?.string() ?: ""
        Log.d(TAG, "Response ${response.code}: $responseBody")
        when {
            response.code == 401 || response.code == 403 ->
                return Result.failure(BlossomException.AuthFailed("Auth rejected: ${response.code}: $responseBody"))
            response.code == 413 ->
                return Result.failure(BlossomException.QuotaExceeded("File too large"))
            response.code == 429 ->
                return Result.failure(BlossomException.QuotaExceeded("Rate limited"))
            !response.isSuccessful ->
                return Result.failure(BlossomException.ServerError(response.code, responseBody))
        }

        val json = NostrJson.parseToJsonElement(responseBody).jsonObject
        val blobUrl = json["url"]?.jsonPrimitive?.content ?: ""
        val blobSha256 = json["sha256"]?.jsonPrimitive?.content ?: ""
        val blobSize = json["size"]?.jsonPrimitive?.long ?: sizeBytes
        val blobType = json["type"]?.jsonPrimitive?.content ?: mimeType

        if (blobSha256.isNotEmpty() && blobSha256 != sha256hex) {
            Log.w(TAG, "Server returned different SHA-256 (server may re-encode): expected $sha256hex, got $blobSha256")
        }

        // Extract NIP-94 metadata from response (nested under "nip94" array)
        val nip94 = json["nip94"]?.jsonArray
            ?.associate { entry ->
                val arr = entry.jsonArray
                val key = arr.getOrNull(0)?.jsonPrimitive?.content ?: ""
                val value = arr.getOrNull(1)?.jsonPrimitive?.content ?: ""
                key to value
            } ?: emptyMap()

        val dimensions = parseDimensions(nip94["dim"])
        val blurhash = nip94["blurhash"]
        val thumbnailUrl = nip94["thumb"]

        Log.d(TAG, "Upload success: $blobUrl ($blobSize bytes, dim=${dimensions}, blurhash=${blurhash != null})")
        return Result.success(
            BlossomBlob(
                url = blobUrl,
                sha256 = blobSha256.ifEmpty { sha256hex },
                sizeBytes = blobSize,
                mimeType = blobType,
                dimensions = dimensions,
                blurhash = blurhash,
                thumbnailUrl = thumbnailUrl,
            )
        )
    }

    /** Parse "WxH" dimension string (e.g. "100x100") to Pair(width, height). */
    private fun parseDimensions(dim: String?): Pair<Int, Int>? {
        if (dim == null) return null
        val parts = dim.split("x", ignoreCase = true)
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Streaming(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
