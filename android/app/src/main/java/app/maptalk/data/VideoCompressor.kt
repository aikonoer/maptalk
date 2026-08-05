package app.maptalk.data

import android.content.Context
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
 * MP4 / H.264 + AAC, long edge ≤720p class, ≤30s, ≤12 MB.
 */
@OptIn(UnstableApi::class)
object VideoCompressor {

    const val MAX_DURATION_MS = 30_000
    const val MAX_BYTES = 12 * 1024 * 1024
    const val MAX_HEIGHT = 720

    sealed class Result {
        data class Ok(val video: PreparedVideo) : Result()
        data class Err(val message: String) : Result()
    }

    suspend fun prepare(context: Context, uri: Uri): Result {
        val durationMs = readDurationMs(context, uri)
            ?: return Result.Err("That video could not be read")
        if (durationMs <= 0) return Result.Err("That video could not be read")
        if (durationMs > MAX_DURATION_MS) {
            return Result.Err("Keep videos under 30 seconds")
        }

        val outFile = File(context.cacheDir, "maptalk-video-${System.currentTimeMillis()}.mp4")
        return try {
            transform(context, uri, outFile)
            if (!outFile.exists() || outFile.length() == 0L) {
                return Result.Err("Video compression failed")
            }
            if (outFile.length() > MAX_BYTES) {
                return Result.Err("Video is still too large after compression")
            }
            val bytes = outFile.readBytes()
            if (!looksLikeMp4(bytes)) {
                return Result.Err("Video compression failed")
            }
            val (width, height) = readSize(outFile)
                ?: return Result.Err("Video compression failed")
            Result.Ok(
                PreparedVideo(
                    bytes = bytes,
                    durationMs = durationMs,
                    width = width,
                    height = height,
                ),
            )
        } catch (e: ExportException) {
            Result.Err("Video compression failed")
        } catch (_: Exception) {
            Result.Err("Video compression failed")
        } finally {
            outFile.delete()
        }
    }

    private suspend fun transform(context: Context, uri: Uri, outFile: File) {
        // Transformer must be created/started on the app main thread.
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val mediaItem = MediaItem.fromUri(uri)
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(
                        Effects(
                            /* audioProcessors = */ emptyList(),
                            /* videoEffects = */ listOf(Presentation.createForHeight(MAX_HEIGHT)),
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

    /** ISO BMFF ftyp box — required by the Worker magic sniff. */
    private fun looksLikeMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }
}
