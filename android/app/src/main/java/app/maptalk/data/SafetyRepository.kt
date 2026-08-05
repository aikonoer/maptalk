package app.maptalk.data

import app.maptalk.data.model.Author
import app.maptalk.data.model.ReportReason
import app.maptalk.data.model.ReportTargetType
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Viewer-private blocks and append-only reports. Blocks filter the map and open threads
 * on the client; reports are write-and-forget for later admin review.
 */
class SafetyRepository private constructor(
    private val backend: Backend,
) {

    private sealed interface Backend {
        data class Firestore(
            val db: FirebaseFirestore,
            val currentUid: () -> String?,
        ) : Backend

        data class Local(val store: LocalDemoStore) : Backend
    }

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    constructor(firestore: FirebaseFirestore, currentUid: () -> String?) :
        this(Backend.Firestore(firestore, currentUid))

    constructor(local: LocalDemoStore) : this(Backend.Local(local))

    fun blockedUids(): Flow<Set<String>> = when (val backend = backend) {
        is Backend.Firestore -> callbackFlow {
            val uid = backend.currentUid()
            if (uid == null) {
                trySend(emptySet())
                awaitClose { }
                return@callbackFlow
            }
            val registration = backend.db.collection(Fs.USERS).document(uid)
                .collection(Fs.BLOCKS)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        _errors.tryEmit(error)
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.documents?.map { it.id }?.toSet().orEmpty())
                }
            awaitClose { registration.remove() }
        }
        is Backend.Local -> backend.store.blockedUids()
    }

    fun block(blockedUid: String, author: Author) {
        if (blockedUid.isEmpty() || blockedUid == author.uid) return
        when (val backend = backend) {
            is Backend.Firestore -> {
                backend.db.collection(Fs.USERS).document(author.uid)
                    .collection(Fs.BLOCKS).document(blockedUid)
                    .set(
                        mapOf(
                            Fs.BLOCKED_UID to blockedUid,
                            Fs.CREATED_AT to FieldValue.serverTimestamp(),
                        ),
                    )
                    .addOnFailureListener { _errors.tryEmit(it) }
            }
            is Backend.Local -> backend.store.block(blockedUid)
        }
    }

    fun unblock(blockedUid: String, author: Author) {
        when (val backend = backend) {
            is Backend.Firestore -> {
                backend.db.collection(Fs.USERS).document(author.uid)
                    .collection(Fs.BLOCKS).document(blockedUid)
                    .delete()
                    .addOnFailureListener { _errors.tryEmit(it) }
            }
            is Backend.Local -> backend.store.unblock(blockedUid)
        }
    }

    fun report(
        type: ReportTargetType,
        targetId: String,
        threadId: String,
        targetAuthorId: String,
        reason: ReportReason,
        author: Author,
    ) {
        if (targetAuthorId == author.uid) return
        when (val backend = backend) {
            is Backend.Firestore -> {
                backend.db.collection(Fs.USERS).document(author.uid)
                    .collection(Fs.REPORTS).document()
                    .set(
                        mapOf(
                            Fs.TARGET_TYPE to type.id,
                            Fs.TARGET_ID to targetId,
                            Fs.THREAD_ID to if (type == ReportTargetType.MESSAGE) threadId else "",
                            Fs.TARGET_AUTHOR_ID to targetAuthorId,
                            Fs.REASON to reason.id,
                            Fs.CREATED_AT to FieldValue.serverTimestamp(),
                        ),
                    )
                    .addOnFailureListener { _errors.tryEmit(it) }
            }
            is Backend.Local -> backend.store.report(
                type = type,
                targetId = targetId,
                threadId = threadId,
                targetAuthorId = targetAuthorId,
                reason = reason,
            )
        }
    }
}
