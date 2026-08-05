package app.maptalk.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Registers FCM tokens and thread subscriptions for push.
 * Local demo is a no-op.
 */
class PushRepository private constructor(
    private val backend: Backend,
) {

    private sealed interface Backend {
        data class Firestore(
            val db: FirebaseFirestore,
            val currentUid: () -> String?,
        ) : Backend

        data object Local : Backend
    }

    var onError: ((Throwable) -> Unit)? = null

    constructor(
        firestore: FirebaseFirestore,
        currentUid: () -> String?,
    ) : this(Backend.Firestore(firestore, currentUid))

    constructor(local: LocalDemoStore) : this(Backend.Local)

    fun registerDevice(deviceId: String, token: String, platform: String) {
        val firestore = backend as? Backend.Firestore ?: return
        val uid = firestore.currentUid() ?: return
        if (token.isEmpty()) return
        firestore.db.collection(Fs.USERS).document(uid)
            .collection(Fs.DEVICES).document(deviceId)
            .set(
                mapOf(
                    Fs.TOKEN to token,
                    Fs.PLATFORM to platform,
                    Fs.UPDATED_AT to FieldValue.serverTimestamp(),
                ),
            )
            .addOnFailureListener { onError?.invoke(it) }
    }

    fun subscribe(toThreadId: String) {
        val firestore = backend as? Backend.Firestore ?: return
        val uid = firestore.currentUid() ?: return
        if (toThreadId.isEmpty()) return
        firestore.db.collection(Fs.THREADS).document(toThreadId)
            .collection(Fs.SUBSCRIBERS).document(uid)
            .set(mapOf(Fs.SUBSCRIBED_AT to FieldValue.serverTimestamp()))
            .addOnFailureListener { onError?.invoke(it) }
    }
}
