import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.maptalk.data.model.PreparedVideo

/**
 * Reads a gallery clip and accepts it when it already fits the Worker/Firestore caps
 * (MP4-ish container, ≤30s, ≤12 MB). No re-encode in this slice.
 */
object VideoCompressor {

    const val MAX_DURATION_MS = 30_000
    const val MAX_BYTES = 12 * 1024 * 1024

    fun prepare(context: Context, uri: Uri): PreparedVideo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toIntOrNull()
                ?: return null
            if (durationMs <= 0 || durationMs > MAX_DURATION_MS) return null

            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: return null
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: return null
            if (width <= 0 || height <= 0) return null

            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            if (!looksLikeMp4(bytes)) return null

            PreparedVideo(
                bytes = bytes,
                durationMs = durationMs,
                width = width,
                height = height,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** ISO BMFF ftyp box — required by the Worker magic sniff. */
    private fun looksLikeMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }
}
