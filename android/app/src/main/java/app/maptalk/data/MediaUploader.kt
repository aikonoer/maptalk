package app.maptalk.data

import app.maptalk.data.model.PreparedImage
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
 * Uploads a compressed JPEG and returns the public URL stored on [Message.imagePath].
 *
 * Live builds POST to the Cloudflare Worker (R2, zero egress). Emulator builds use Firebase
 * Storage so photos still work against `scripts/mock-data.sh` without Cloudflare.
 */
sealed class MediaUploader {
    abstract suspend fun upload(threadId: String, messageId: String, image: PreparedImage): String

    class R2(
        private val auth: FirebaseAuth,
        private val endpoint: String,
    ) : MediaUploader() {
        override suspend fun upload(threadId: String, messageId: String, image: PreparedImage): String =
            withContext(Dispatchers.IO) {
                val token = auth.currentUser?.getIdToken(false)?.await()?.token
                    ?: throw IllegalStateException("Sign in before sending a photo")
                val url = URL(
                    "$endpoint?threadId=${enc(threadId)}&messageId=${enc(messageId)}",
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "image/jpeg")
                    setRequestProperty("Content-Length", image.jpegBytes.size.toString())
                }
                try {
                    connection.outputStream.use { it.write(image.jpegBytes) }
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                    if (code !in 200..299) {
                        throw IOException("Photo upload failed ($code): $body")
                    }
                    val parsed = JSONObject(body)
                    parsed.getString("url")
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
            suspendCancellableCoroutine { cont ->
                val task = ref.putBytes(image.jpegBytes, metadata)
                cont.invokeOnCancellation { task.cancel() }
                task
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            return suspendCancellableCoroutine { cont ->
                ref.downloadUrl
                    .addOnSuccessListener { uri -> cont.resume(uri.toString()) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }
    }
}
