package com.unsilence.app.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.unsilence.app.di.MediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

sealed interface SaveResult {
    data class Success(val uri: Uri) : SaveResult
    data class Failure(val message: String, val cause: Throwable? = null) : SaveResult
}

@Singleton
class MediaSaver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:MediaClient private val okHttpClient: OkHttpClient,
) {
    suspend fun saveToGallery(
        url: String,
        kind: SaveMediaKind,
    ): SaveResult = withContext(Dispatchers.IO) {
        if (kind == SaveMediaKind.VIDEO && !isSavableVideoSource(url)) {
            return@withContext SaveResult.Failure("Streaming videos cannot be saved")
        }

        var insertedUri: Uri? = null
        try {
            val response = okHttpClient.newCall(
                Request.Builder().url(url).build()
            ).execute()
            response.use {
                if (!it.isSuccessful) {
                    return@withContext SaveResult.Failure("Save failed (${it.code})")
                }
                val body = it.body
                val contentType = it.header("Content-Type") ?: body.contentType()?.toString()
                if (kind == SaveMediaKind.VIDEO && !isSavableVideoSource(url, contentType)) {
                    return@withContext SaveResult.Failure("Streaming videos cannot be saved")
                }

                val mimeType = resolveMediaMimeType(contentType, url, kind)
                val filename = deriveMediaFilename(url, mimeType, kind)
                val collection = collectionUri(kind)
                val values = contentValues(filename, mimeType, kind)
                val targetUri = context.contentResolver.insert(collection, values)
                    ?: return@withContext SaveResult.Failure("Save failed")
                insertedUri = targetUri

                val outputStream = context.contentResolver.openOutputStream(targetUri)
                    ?: error("ContentResolver returned a null output stream")
                outputStream.use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publishedValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(targetUri, publishedValues, null, null)
                }

                SaveResult.Success(targetUri)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            insertedUri?.let {
                runCatching { context.contentResolver.delete(it, null, null) }
            }
            SaveResult.Failure("Save failed", t)
        }
    }

    private fun collectionUri(kind: SaveMediaKind): Uri = when (kind) {
        SaveMediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        SaveMediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun contentValues(
        filename: String,
        mimeType: String,
        kind: SaveMediaKind,
    ): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(kind))
        } else {
            @Suppress("DEPRECATION")
            put(
                MediaStore.MediaColumns.DATA,
                legacyFile(kind, filename).absolutePath,
            )
        }
    }

    private fun relativePath(kind: SaveMediaKind): String = when (kind) {
        SaveMediaKind.IMAGE -> "${Environment.DIRECTORY_PICTURES}/unSilence"
        SaveMediaKind.VIDEO -> "${Environment.DIRECTORY_MOVIES}/unSilence"
    }

    private fun legacyFile(kind: SaveMediaKind, filename: String): File {
        @Suppress("DEPRECATION")
        val baseDir = Environment.getExternalStoragePublicDirectory(
            when (kind) {
                SaveMediaKind.IMAGE -> Environment.DIRECTORY_PICTURES
                SaveMediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
            }
        )
        val directory = File(baseDir, "unSilence").apply { mkdirs() }
        val name = filename.substringBeforeLast('.', missingDelimiterValue = filename)
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "")
        var candidate = File(directory, filename)
        var suffix = 1
        while (candidate.exists()) {
            val display = if (extension.isBlank()) {
                "$name-$suffix"
            } else {
                "$name-$suffix.$extension"
            }
            candidate = File(directory, display)
            suffix += 1
        }
        return candidate
    }
}
