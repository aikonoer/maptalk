package app.maptalk.data

import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.PreparedVideo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
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

        override suspend fun upload(threadId: String, messageId: String, image: PreparedImage): String =
            post(imageEndpoint, threadId, messageId, image.jpegBytes, "image/jpeg")

        override suspend fun upload(threadId: String, messageId: String, audio: PreparedAudio): String =
            post(audioEndpoint, threadId, messageId, audio.bytes, audio.contentType)

        override suspend fun upload(threadId: String, messageId: String, video: PreparedVideo): String =
            post(videoEndpoint, threadId, messageId, video.bytes, video.contentType)

        private suspend fun post(
            endpoint: String,
            threadId: String,
            messageId: String,
            body: ByteArray,
            contentType: String,
        ): String = withContext(Dispatchers.IO) {
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
            }
            try {
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) {
                    throw IOException("Media upload failed ($code): $responseBody")
                }
                JSONObject(responseBody).getString("url")
            } finally {
                connection.disconnect()
            }
        }

        private fun enc(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
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
            putBytes(ref, video.bytes, metadata)
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

        private suspend fun downloadUrl(ref: com.google.firebase.storage.StorageReference): String =
            suspendCancellableCoroutine { cont ->
                ref.downloadUrl
                    .addOnSuccessListener { uri -> cont.resume(uri.toString()) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
    }
}
