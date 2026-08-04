package app.maptalk.data

import app.maptalk.data.model.PreparedImage
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Uploads a compressed JPEG to Firebase Storage and returns a download URL for
 * [Message.imagePath]. Local demo mode never calls this — it writes files on device instead.
 *
 * Object layout: `threads/{threadId}/{messageId}.jpg`. The same URL shape can later point at
 * Cloudflare R2 without changing the Firestore schema.
 */
class MediaUploader(private val storage: FirebaseStorage) {

    suspend fun upload(threadId: String, messageId: String, image: PreparedImage): String {
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
