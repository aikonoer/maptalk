package app.maptalk.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import app.maptalk.data.model.PreparedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a camera/library photo before it is stored. Cap long edge at 1280 px and encode
 * JPEG ~0.72 so a multi-megabyte original becomes a few hundred KB.
 */
object ImageCompressor {

    const val MAX_EDGE = 1_280
    const val JPEG_QUALITY = 72
    const val MAX_BYTES = 1_500_000

    fun prepare(bytes: ByteArray): PreparedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val oriented = decoded.oriented(bytes)
        if (oriented !== decoded) decoded.recycle()

        val longest = max(oriented.width, oriented.height)
        val scaled = if (longest > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longest
            val w = (oriented.width * scale).roundToInt().coerceAtLeast(1)
            val h = (oriented.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(oriented, w, h, true).also {
                if (it !== oriented) oriented.recycle()
            }
        } else {
            oriented
        }

        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            scaled.recycle()
            return null
        }
        val jpeg = out.toByteArray()
        val width = scaled.width
        val height = scaled.height
        scaled.recycle()
        if (jpeg.size > MAX_BYTES) return null
        return PreparedImage(jpegBytes = jpeg, width = width, height = height)
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (max(w, h) / sample > maxEdge * 2) {
            sample *= 2
        }
        return sample
    }

    private fun Bitmap.oriented(source: ByteArray): Bitmap {
        val exif = runCatching {
            ExifInterface(ByteArrayInputStream(source))
        }.getOrNull() ?: return this
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return this
        }
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}
