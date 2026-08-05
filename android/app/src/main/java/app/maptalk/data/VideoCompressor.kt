package app.maptalk.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.maptalk.data.model.PreparedVideo
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Re-encodes a gallery clip into the shared MapTalk envelope:
 * MP4 / H.264 + AAC, 720p-class, ≤15s, ≤12 MB.
 * Prefer sharpness over length — shorten before crushing quality.
 */
@OptIn(UnstableApi::class)
object VideoCompressor {

    const val MAX_DURATION_MS = 15_000
    const val MAX_BYTES = 12 * 1024 * 1024

    /** Kid-friendly — no "compression" jargon. */
    const val ERR_UNREADABLE = "Hmm, we couldn’t open that video. Try another one?"
    const val ERR_FAILED = "Couldn’t get that video ready. Try another one?"
    const val ERR_TOO_LARGE = "That part is still a bit big. Try a shorter bit!"

    /** Sharp first. Never drop to mushy 360p. */
    private val heightLadder = listOf(720, 540)
    /** If still too big at a given height, shorten before lowering resolution. */
    private val durationLadderMs = listOf(15_000, 12_000, 10_000)

    sealed class Result {
        data class Ok(val video: PreparedVideo) : Result()
        data class NeedsTrim(val uri: Uri, val durationMs: Int) : Result()
        data class Err(val message: String) : Result()
    }

    suspend fun prepare(context: Context, uri: Uri, clipStartMs: Int? = null): Result {
        val durationMs = readDurationMs(context, uri)
            ?: return Result.Err(ERR_UNREADABLE)
        if (durationMs <= 0) return Result.Err(ERR_UNREADABLE)
        if (clipStartMs == null && durationMs > MAX_DURATION_MS) {
            return Result.NeedsTrim(uri, durationMs)
        }

        val start = (clipStartMs ?: 0).coerceIn(0, maxOf(0, durationMs - 1))

        for (height in heightLadder) {
            for (windowMs in durationLadderMs) {
                val end = minOf(durationMs, start + windowMs)
                if (end - start <= 500) continue
                val outFile = File(
                    context.cacheDir,
                    "maptalk-video-${System.currentTimeMillis()}-$height.mp4",
                )
                try {
                    transform(
                        context,
                        uri,
                        outFile,
                        startMs = start.toLong(),
                        endMs = end.toLong(),
                        heightPx = height,
                    )
                    if (!outFile.exists() || outFile.length() == 0L) {
                        outFile.delete()
                        continue
                    }
                    if (outFile.length() > MAX_BYTES) {
                        outFile.delete()
                        continue
                    }
                    val head = outFile.inputStream().use { stream ->
                        val buf = ByteArray(12)
                        val n = stream.read(buf)
                        if (n < 12) null else buf
                    }
                    if (head == null || !looksLikeMp4(head)) {
                        outFile.delete()
                        continue
                    }
                    val size = readSize(outFile)
                    if (size == null) {
                        outFile.delete()
                        continue
                    }
                    return Result.Ok(
                        PreparedVideo(
                            file = outFile,
                            durationMs = (end - start),
                            width = size.first,
                            height = size.second,
                        ),
                    )
                } catch (_: ExportException) {
                    outFile.delete()
                } catch (_: Exception) {
                    outFile.delete()
                }
            }
        }
        return Result.Err(ERR_TOO_LARGE)
    }

    /** Thumbnails for the trimmer filmstrip. */
    fun filmstrip(context: Context, uri: Uri, count: Int = 8): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return emptyList()
            if (durationMs <= 0L) return emptyList()
            buildList {
                for (i in 0 until count) {
                    val tUs = ((durationMs * (i + 0.5) / count) * 1_000).toLong()
                    val frame = retriever.getFrameAtTime(
                        tUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                    if (frame != null) add(frame)
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun transform(
        context: Context,
        uri: Uri,
        outFile: File,
        startMs: Long,
        endMs: Long,
        heightPx: Int,
    ) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build(),
                    )
                    .build()
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(
                        Effects(
                            /* audioProcessors = */ emptyList(),
                            /* videoEffects = */ listOf(Presentation.createForHeight(heightPx)),
                        ),
                    )
                    .build()

                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        },
                    )
                    .build()

                cont.invokeOnCancellation { transformer.cancel() }
                if (outFile.exists()) outFile.delete()
                transformer.start(edited, outFile.absolutePath)
            }
        }
    }

    private fun readDurationMs(context: Context, uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readSize(file: File): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: return null
            if (width <= 0 || height <= 0) null else width to height
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun looksLikeMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }
}
