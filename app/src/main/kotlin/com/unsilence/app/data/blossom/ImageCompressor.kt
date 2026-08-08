package com.unsilence.app.data.blossom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

internal const val MAX_IMAGE_SOURCE_BYTES = 30L * 1024L * 1024L
internal const val ORIGINAL_PRIVACY_REENCODE_MAX_DIMENSION = 2_560
internal const val ORIGINAL_PRIVACY_REENCODE_QUALITY = 95
private const val TAG = "ImageCompressor"

internal fun calculateImageSampleSize(
    width: Int,
    height: Int,
    targetDimension: Int,
): Int {
    if (width <= 0 || height <= 0 || targetDimension <= 0) return 1
    val longest = maxOf(width, height).toLong()
    val decodeCeiling = targetDimension.toLong() * 2L
    var sampleSize = 1
    while (
        (longest + sampleSize - 1L) / sampleSize > decodeCeiling &&
        sampleSize <= Int.MAX_VALUE / 2
    ) {
        sampleSize = sampleSize shl 1
    }
    return sampleSize
}

data class PreparedImage(
    val file: File,
    val mimeType: String,
    val dimensions: Pair<Int, Int>,
)

class ImageSourceTooLargeException : IOException("Image is larger than 30 MB")

class OriginalImagePrivacyException(cause: Throwable) : IOException(
    "Original image metadata could not be removed safely. Choose High quality and retry.",
    cause,
)

private data class ImageBounds(
    val width: Int,
    val height: Int,
    val mimeType: String?,
)

@Singleton
class ImageCompressor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val contentResolver get() = context.contentResolver

    /**
     * Prepares an image for file-backed upload without retaining the source bytes.
     * ORIGINAL preserves encoded pixels where the container supports a verified,
     * orientation-only metadata rewrite. Read-only metadata containers are safely
     * re-encoded. Other modes sample-decode at no more than twice the target, scale
     * once to the exact output size, and compress directly into a cache file.
     */
    suspend fun prepareImage(
        uri: Uri,
        sourceMime: String,
        maxDimension: Int,
        quality: Int,
    ): PreparedImage = withContext(Dispatchers.IO) {
        validateSourceSize(uri)
        val bounds = decodeBounds(uri)
        val orientation = readOrientation(uri)
        val displayBounds = orientedDimensions(bounds.width, bounds.height, orientation)
        val effectiveSourceMime = bounds.mimeType
            ?.takeIf { it.startsWith("image/") }
            ?: sourceMime

        if (maxDimension <= 0) {
            when (originalImageMetadataMode(effectiveSourceMime)) {
                OriginalImageMetadataMode.REENCODE -> {
                    return@withContext reencodeForPrivacy(uri, bounds, orientation)
                }
                OriginalImageMetadataMode.COPY,
                OriginalImageMetadataMode.EXIF_ORIENTATION_ONLY,
                -> {
                    val output = copyOriginal(uri, effectiveSourceMime)
                    if (
                        originalImageMetadataMode(effectiveSourceMime) ==
                        OriginalImageMetadataMode.EXIF_ORIENTATION_ONLY
                    ) {
                        try {
                            scrubExifToOrientationOnly(output)
                        } catch (error: Exception) {
                            Log.w(
                                TAG,
                                "Lossless metadata scrub failed; using privacy re-encode",
                                error,
                            )
                            output.delete()
                            return@withContext reencodeForPrivacy(
                                uri = uri,
                                bounds = bounds,
                                orientation = orientation,
                                scrubFailure = error,
                            )
                        }
                    }
                    return@withContext PreparedImage(
                        file = output,
                        mimeType = effectiveSourceMime,
                        dimensions = displayBounds,
                    )
                }
            }
        }

        encodeImage(uri, bounds, orientation, maxDimension, quality)
    }

    private fun copyOriginal(uri: Uri, sourceMime: String): File {
        val output = createOutputFile(suffixForMime(sourceMime))
        try {
            openBoundedStream(uri).use { input ->
                output.outputStream().buffered().use { sink -> input.copyTo(sink) }
            }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun reencodeForPrivacy(
        uri: Uri,
        bounds: ImageBounds,
        orientation: Int,
        scrubFailure: Throwable? = null,
    ): PreparedImage = try {
        encodeImage(
            uri = uri,
            bounds = bounds,
            orientation = orientation,
            maxDimension = minOf(
                maxOf(bounds.width, bounds.height),
                ORIGINAL_PRIVACY_REENCODE_MAX_DIMENSION,
            ).coerceAtLeast(1),
            quality = ORIGINAL_PRIVACY_REENCODE_QUALITY,
        )
    } catch (error: Exception) {
        scrubFailure?.let(error::addSuppressed)
        throw OriginalImagePrivacyException(error)
    }

    private fun encodeImage(
        uri: Uri,
        bounds: ImageBounds,
        orientation: Int,
        maxDimension: Int,
        quality: Int,
    ): PreparedImage {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = calculateImageSampleSize(
                bounds.width,
                bounds.height,
                maxDimension,
            )
        }
        var bitmap = openBoundedStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("Could not decode image")

        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxDimension) {
            val scale = maxDimension.toFloat() / longest
            val scaled = bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        bitmap = applyOrientation(bitmap, orientation)

        val useWebp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val mimeType = if (useWebp) "image/webp" else "image/jpeg"
        val format = if (useWebp) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val output = createOutputFile(if (useWebp) ".webp" else ".jpg")
        return try {
            val dimensions = bitmap.width to bitmap.height
            output.outputStream().buffered().use { sink ->
                check(bitmap.compress(format, quality.coerceIn(0, 100), sink)) {
                    "Could not compress image"
                }
            }
            PreparedImage(output, mimeType, dimensions)
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private fun validateSourceSize(uri: Uri) {
        val declaredSize = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (declaredSize > MAX_IMAGE_SOURCE_BYTES) throw ImageSourceTooLargeException()
        if (declaredSize < 0L) {
            openBoundedStream(uri).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (input.read(buffer) != -1) Unit
            }
        }
    }

    private fun decodeBounds(uri: Uri): ImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openBoundedStream(uri).use { input -> BitmapFactory.decodeStream(input, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) error("Could not decode image")
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType,
        )
    }

    private fun readOrientation(uri: Uri): Int = runCatching {
        openBoundedStream(uri).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun openBoundedStream(uri: Uri): InputStream {
        val input = contentResolver.openInputStream(uri) ?: error("Could not read image")
        return SizeLimitedInputStream(input, MAX_IMAGE_SOURCE_BYTES)
    }

    private fun createOutputFile(suffix: String): File {
        val directory = File(context.cacheDir, "image_upload").apply { mkdirs() }
        return File.createTempFile("image-", suffix, directory)
    }

    private fun suffixForMime(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/heic", "image/heif" -> ".heic"
        else -> ".jpg"
    }

    private fun orientedDimensions(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
            -> height to width
            else -> width to height
        }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}

private class SizeLimitedInputStream(
    input: InputStream,
    private val byteLimit: Long,
) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = super.read()
        if (value != -1) recordRead(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) recordRead(count)
        return count
    }

    private fun recordRead(count: Int) {
        bytesRead += count
        if (bytesRead > byteLimit) throw ImageSourceTooLargeException()
    }
}
