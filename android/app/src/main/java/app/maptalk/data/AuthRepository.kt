package app.maptalk.data

import app.maptalk.data.model.Author
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** Where the user is in the two step sign-in: anonymous account, then a display name. */
sealed interface Session {
    data object SignedOut : Session
    data class NeedsDisplayName(val uid: String) : Session
    data class Ready(val author: Author) : Session
}

/**
 * v1 has no real accounts. Everyone gets an anonymous Firebase account, and the display name
 * they pick on first launch is stamped onto the threads and messages they write.
 *
 * Local demo mode skips Firebase entirely and keeps the name in SharedPreferences.
 */
class AuthRepository private constructor(
    private val backend: Backend,
) {

    private sealed interface Backend {
        data class Firebase(val auth: FirebaseAuth, val firestore: FirebaseFirestore) : Backend
        data class Local(val store: LocalDemoStore) : Backend
    }

    constructor(auth: FirebaseAuth, firestore: FirebaseFirestore) : this(Backend.Firebase(auth, firestore))

    constructor(local: LocalDemoStore) : this(Backend.Local(local))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun session(): Flow<Session> = when (val backend = backend) {
        is Backend.Firebase -> currentUid(backend.auth).flatMapLatest { uid ->
            if (uid == null) {
                flowOf(Session.SignedOut)
            } else {
                displayName(backend.firestore, uid).map { name ->
                    if (name.isNullOrBlank()) {
                        Session.NeedsDisplayName(uid)
                    } else {
                        Session.Ready(Author(uid, name))
                    }
                }
            }
        }
        is Backend.Local -> backend.store.displayName.map { name ->
            if (name.isNullOrBlank()) {
                Session.NeedsDisplayName(backend.store.uid)
            } else {
                Session.Ready(Author(backend.store.uid, name))
            }
        }
    }

    suspend fun signInAnonymously() {
        when (val backend = backend) {
            is Backend.Firebase -> {
                if (backend.auth.currentUser == null) {
                    backend.auth.signInAnonymously().await()
                }
            }
            is Backend.Local -> Unit
        }
    }

    suspend fun saveDisplayName(name: String) {
        when (val backend = backend) {
            is Backend.Firebase -> {
                val uid = backend.auth.currentUser?.uid ?: return
                backend.firestore.collection(Fs.USERS).document(uid).set(
                    mapOf(
                        Fs.DISPLAY_NAME to name.trim(),
                        Fs.CREATED_AT to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
            }
            is Backend.Local -> backend.store.saveDisplayName(name)
        }
    }

    private fun currentUid(auth: FirebaseAuth): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    private fun displayName(firestore: FirebaseFirestore, uid: String): Flow<String?> = callbackFlow {
        val registration = firestore.collection(Fs.USERS).document(uid)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.getString(Fs.DISPLAY_NAME))
            }
        awaitClose { registration.remove() }
    }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 24
    }
}
