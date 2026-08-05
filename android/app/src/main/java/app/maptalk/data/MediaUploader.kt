package app.maptalk.data

import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.PreparedVideo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Uploads chat media and returns the public URL stored on the message document.
 *
 * Live builds POST to the Cloudflare Worker (R2, zero egress). Emulator builds use Firebase
 * Storage so media still works against `scripts/mock-data.sh` without Cloudflare.
 */
sealed class MediaUploader {
    abstract suspend fun upload(threadId: String, messageId: String, image: PreparedImage): String
    abstract suspend fun upload(threadId: String, messageId: String, audio: PreparedAudio): String
    abstract suspend fun upload(threadId: String, messageId: String, video: PreparedVideo): String

    class R2(
        private val auth: FirebaseAuth,
        private val imageEndpoint: String,
    ) : MediaUploader() {
        private val uploadSlots = Semaphore(MAX_CONCURRENT_UPLOADS)

        private val audioEndpoint: String =
            if (imageEndpoint.endsWith("/v1/images")) {
                imageEndpoint.removeSuffix("/v1/images") + "/v1/audio"
            } else {
                "https://maptalk-media.hhypkfpshg.workers.dev/v1/audio"
            }

        private val videoEndpoint: String =
            if (imageEndpoint.endsWith("/v1/images")) {
                imageEndpoint.removeSuffix("/v1/images") + "/v1/video"
            } else {
                "https://maptalk-media.hhypkfpshg.workers.dev/v1/video"
            }

        private val videoPresignEndpoint: String = "$videoEndpoint/presign"
        private val videoConfirmEndpoint: String = "$videoEndpoint/confirm"

        override suspend fun upload(threadId: String, messageId: String, image: PreparedImage): String =
            uploadSlots.withPermit {
                post(imageEndpoint, threadId, messageId, image.jpegBytes, "image/jpeg")
            }

        override suspend fun upload(threadId: String, messageId: String, audio: PreparedAudio): String =
            uploadSlots.withPermit {
                post(audioEndpoint, threadId, messageId, audio.bytes, audio.contentType)
            }

        override suspend fun upload(threadId: String, messageId: String, video: PreparedVideo): String =
            uploadSlots.withPermit {
                uploadVideo(threadId, messageId, video)
            }

        private suspend fun uploadVideo(
            threadId: String,
            messageId: String,
            video: PreparedVideo,
        ): String = withContext(Dispatchers.IO) {
            val durationHeader = mapOf("X-MapTalk-Duration-Ms" to video.durationMs.toString())
            val length = video.file.length()
            if (length <= 0L) throw IOException("Video file is empty")

            val presign = tryPresign(threadId, messageId, length, durationHeader)
            if (presign != null) {
                putToPresignedUrl(presign.uploadUrl, video.file, presign.contentType)
                return@withContext postConfirm(threadId, messageId, durationHeader)
            }

            // Worker without R2 S3 secrets (or old deploy): stream through Worker.
            putFile(
                videoEndpoint,
                threadId,
                messageId,
                video.file,
                video.contentType,
                extraHeaders = durationHeader,
            )
        }

        private suspend fun tryPresign(
            threadId: String,
            messageId: String,
            contentLength: Long,
            extraHeaders: Map<String, String>,
        ): PresignResponse? {
            val token = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: throw IllegalStateException("Sign in before sending media")
            val url = URL(
                "$videoPresignEndpoint?threadId=${enc(threadId)}&messageId=${enc(messageId)}",
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = false
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-MapTalk-Content-Length", contentLength.toString())
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code == 503 && body.contains("presign_unavailable")) {
                    return null
                }
                if (code !in 200..299) {
                    throw IOException("Media upload failed ($code): $body")
                }
                val json = JSONObject(body)
                return PresignResponse(
                    uploadUrl = json.getString("uploadUrl"),
                    contentType = json.optString("contentType", "video/mp4"),
                )
            } finally {
                connection.disconnect()
            }
        }

        private fun putToPresignedUrl(uploadUrl: String, file: java.io.File, contentType: String) {
            val length = file.length()
            val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 120_000
                setFixedLengthStreamingMode(length)
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Content-Length", length.toString())
            }
            try {
                connection.outputStream.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IOException("Media upload failed ($code): $err")
                }
            } finally {
                connection.disconnect()
            }
        }

        private suspend fun postConfirm(
            threadId: String,
            messageId: String,
            extraHeaders: Map<String, String>,
            maxAttempts: Int = 3,
        ): String {
            var lastError: Exception? = null
            repeat(maxAttempts) { attempt ->
                try {
                    return postEmpty(videoConfirmEndpoint, threadId, messageId, extraHeaders)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (!isTransientUploadError(e) || attempt == maxAttempts - 1) throw e
                    delay(400L * (1L shl attempt))
                }
            }
            throw lastError ?: IOException("Media upload failed")
        }

        private suspend fun postEmpty(
            endpoint: String,
            threadId: String,
            messageId: String,
            extraHeaders: Map<String, String>,
        ): String {
            val token = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: throw IllegalStateException("Sign in before sending media")
            val url = URL(
                "$endpoint?threadId=${enc(threadId)}&messageId=${enc(messageId)}",
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $token")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                return readUploadUrl(connection)
            } finally {
                connection.disconnect()
            }
        }

        private data class PresignResponse(
            val uploadUrl: String,
            val contentType: String,
        )

        private suspend fun post(
            endpoint: String,
            threadId: String,
            messageId: String,
            body: ByteArray,
            contentType: String,
            extraHeaders: Map<String, String> = emptyMap(),
            maxAttempts: Int = 3,
        ): String = withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(maxAttempts) { attempt ->
                ensureActive()
                try {
                    return@withContext postOnce(
                        endpoint,
                        threadId,
                        messageId,
                        body,
                        contentType,
                        extraHeaders,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (!isTransientUploadError(e) || attempt == maxAttempts - 1) throw e
                    delay(400L * (1L shl attempt))
                }
            }
            throw lastError ?: IOException("Media upload failed")
        }

        private suspend fun putFile(
            endpoint: String,
            threadId: String,
            messageId: String,
            file: java.io.File,
            contentType: String,
            extraHeaders: Map<String, String> = emptyMap(),
            maxAttempts: Int = 3,
        ): String = withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(maxAttempts) { attempt ->
                ensureActive()
                try {
                    return@withContext putFileOnce(
                        endpoint,
                        threadId,
                        messageId,
                        file,
                        contentType,
                        extraHeaders,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (!isTransientUploadError(e) || attempt == maxAttempts - 1) throw e
                    delay(400L * (1L shl attempt))
                }
            }
            throw lastError ?: IOException("Media upload failed")
        }

        private suspend fun postOnce(
            endpoint: String,
            threadId: String,
            messageId: String,
            body: ByteArray,
            contentType: String,
            extraHeaders: Map<String, String>,
        ): String {
            val token = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: throw IllegalStateException("Sign in before sending media")
            val url = URL(
                "$endpoint?threadId=${enc(threadId)}&messageId=${enc(messageId)}",
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Content-Length", body.size.toString())
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                connection.outputStream.use { it.write(body) }
                return readUploadUrl(connection)
            } finally {
                connection.disconnect()
            }
        }

        private suspend fun putFileOnce(
            endpoint: String,
            threadId: String,
            messageId: String,
            file: java.io.File,
            contentType: String,
            extraHeaders: Map<String, String>,
        ): String {
            val token = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: throw IllegalStateException("Sign in before sending media")
            val url = URL(
                "$endpoint?threadId=${enc(threadId)}&messageId=${enc(messageId)}",
            )
            val length = file.length()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 120_000
                setFixedLengthStreamingMode(length)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Content-Length", length.toString())
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                connection.outputStream.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
                return readUploadUrl(connection)
            } finally {
                connection.disconnect()
            }
        }

        private fun readUploadUrl(connection: HttpURLConnection): String {
            val code = connection.responseCode
            val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IOException("Media upload failed ($code): $responseBody")
            }
            return JSONObject(responseBody).getString("url")
        }

        private fun isTransientUploadError(error: Exception): Boolean {
            val msg = error.message.orEmpty()
            return msg.contains("Media upload failed (5") ||
                msg.contains("timeout", ignoreCase = true) ||
                error is SocketTimeoutException ||
                error is UnknownHostException
        }

        private fun enc(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        companion object {
            private const val MAX_CONCURRENT_UPLOADS = 2
        }
    }

    class Firebase(
        private val storage: FirebaseStorage,
    ) : MediaUploader() {
        override suspend fun upload(threadId: String, messageId: String, image: PreparedImage): String {
            val ref = storage.reference
                .child("threads")
                .child(threadId)
                .child("$messageId.jpg")
            val metadata = StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .build()
            putBytes(ref, image.jpegBytes, metadata)
            return downloadUrl(ref)
        }

        override suspend fun upload(threadId: String, messageId: String, audio: PreparedAudio): String {
            val ref = storage.reference
                .child("threads")
                .child(threadId)
                .child("$messageId.m4a")
            val metadata = StorageMetadata.Builder()
                .setContentType(audio.contentType)
                .build()
            putBytes(ref, audio.bytes, metadata)
            return downloadUrl(ref)
        }

        override suspend fun upload(threadId: String, messageId: String, video: PreparedVideo): String {
            val ref = storage.reference
                .child("threads")
                .child(threadId)
                .child("$messageId.mp4")
            val metadata = StorageMetadata.Builder()
                .setContentType(video.contentType)
                .build()
            putFile(ref, video.file, metadata)
            return downloadUrl(ref)
        }

        private suspend fun putBytes(
            ref: com.google.firebase.storage.StorageReference,
            bytes: ByteArray,
            metadata: StorageMetadata,
        ) {
            suspendCancellableCoroutine { cont ->
                val task = ref.putBytes(bytes, metadata)
                cont.invokeOnCancellation { task.cancel() }
                task
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }

        private suspend fun putFile(
            ref: com.google.firebase.storage.StorageReference,
            file: java.io.File,
            metadata: StorageMetadata,
        ) {
            suspendCancellableCoroutine { cont ->
                val task = ref.putFile(android.net.Uri.fromFile(file), metadata)
                cont.invokeOnCancellation { task.cancel() }
                task
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }

        private suspend fun downloadUrl(ref: com.google.firebase.storage.StorageReference): String =
            suspendCancellableCoroutine { cont ->
                ref.downloadUrl
                    .addOnSuccessListener { uri -> cont.resume(uri.toString()) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
    }
}
