package com.unsilence.app.data.blossom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageCompressor @Inject constructor() {

    /**
     * Decode, resize, and re-compress an image.
     * @param bytes        raw image bytes (JPEG, PNG, WebP)
     * @param maxDimension longest side cap in px (0 = no resize)
     * @param quality      JPEG quality 0-100
     * @return compressed JPEG bytes (or PNG if input has alpha and fits within maxDimension)
     */
    suspend fun compressImage(
        bytes: ByteArray,
        maxDimension: Int,
        quality: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val options = BitmapFactory.Options().apply { inMutable = true }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@withContext bytes

        // Apply EXIF orientation
        bitmap = applyOrientation(bitmap, orientation)

        // Resize if needed
        if (maxDimension > 0) {
            val w = bitmap.width
            val h = bitmap.height
            val longest = maxOf(w, h)
            if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                val newW = (w * scale).toInt().coerceAtLeast(1)
                val newH = (h * scale).toInt().coerceAtLeast(1)
                val scaled = bitmap.scale(newW, newH, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
        }

        // Compress
        val hasAlpha = bitmap.hasAlpha()
        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val out = ByteArrayOutputStream(bytes.size / 2)
        bitmap.compress(format, quality, out)
        bitmap.recycle()
        out.toByteArray()
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
                matrix.postRotate(90f); matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
