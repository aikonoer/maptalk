package app.maptalk.data

import app.maptalk.data.model.Author
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
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
 * Profile fields the account screen and the map avatar both read. `photoURL` is an HTTPS URL
 * live, or a local-demo relative media path.
 */
data class UserProfile(val displayName: String? = null, val photoURL: String? = null)

/**
 * Anonymous bootstrap, then optional link to Google (Android) so the uid stays put.
 *
 * Local demo mode skips Firebase entirely and keeps the name in SharedPreferences.
 */
class AuthRepository private constructor(
    private val backend: Backend,
) {

    private sealed interface Backend {
        data class Firebase(
            val auth: FirebaseAuth,
            val firestore: FirebaseFirestore,
            val storage: FirebaseStorage,
        ) : Backend

        data class Local(val store: LocalDemoStore) : Backend
    }

    constructor(auth: FirebaseAuth, firestore: FirebaseFirestore, storage: FirebaseStorage) :
        this(Backend.Firebase(auth, firestore, storage))

    constructor(local: LocalDemoStore) : this(Backend.Local(local))

    val currentUid: String?
        get() = when (val backend = backend) {
            is Backend.Firebase -> backend.auth.currentUser?.uid
            is Backend.Local -> backend.store.uid
        }

    val isAnonymous: Boolean
        get() = when (val backend = backend) {
            is Backend.Firebase -> backend.auth.currentUser?.isAnonymous ?: true
            is Backend.Local -> true
        }

    /** Short label for Settings. */
    val providerLabel: String
        get() = when (val backend = backend) {
            is Backend.Firebase -> {
                val user = backend.auth.currentUser ?: return "Signed out"
                if (user.isAnonymous) return "Signed in anonymously"
                val ids = user.providerData.map { it.providerId }.toSet()
                when {
                    "google.com" in ids -> "Signed in with Google"
                    "apple.com" in ids -> "Signed in with Apple"
                    else -> "Signed in"
                }
            }
            is Backend.Local -> "Local demo"
        }

    /** Providers already tied to this uid, for the account screen's sign-in section. */
    val linkedProviderNames: List<String>
        get() = when (val backend = backend) {
            is Backend.Firebase -> {
                val user = backend.auth.currentUser
                if (user == null || user.isAnonymous) {
                    emptyList()
                } else {
                    user.providerData.mapNotNull {
                        when (it.providerId) {
                            "google.com" -> "Google"
                            "apple.com" -> "Apple"
                            else -> null
                        }
                    }
                }
            }
            is Backend.Local -> emptyList()
        }

    /** Name + photo, for the account screen and the map avatar button. */
    fun profile(uid: String): Flow<UserProfile> = when (val backend = backend) {
        is Backend.Firebase -> callbackFlow {
            val registration = backend.firestore.collection(Fs.USERS).document(uid)
                .addSnapshotListener { snapshot, _ ->
                    trySend(
                        UserProfile(
                            displayName = snapshot?.getString(Fs.DISPLAY_NAME),
                            photoURL = snapshot?.getString(Fs.PHOTO_URL),
                        ),
                    )
                }
            awaitClose { registration.remove() }
        }
        is Backend.Local -> backend.store.profile
    }

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

    /** Emits whenever Auth provider linkage changes (anonymous → Google). */
    fun providerLabelFlow(): Flow<String> = when (val backend = backend) {
        is Backend.Firebase -> callbackFlow {
            val listener = FirebaseAuth.AuthStateListener {
                trySend(providerLabel)
            }
            backend.auth.addAuthStateListener(listener)
            awaitClose { backend.auth.removeAuthStateListener(listener) }
        }
        is Backend.Local -> flowOf("Local demo")
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
                        Fs.UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
            }
            is Backend.Local -> backend.store.saveDisplayName(name)
        }
    }

    /** Compresses and uploads a square-ish avatar; returns the URL (or local path) to show. */
    suspend fun saveAvatar(bytes: ByteArray): String {
        val jpeg = ImageCompressor.prepareAvatar(bytes)
            ?: throw LinkException.Failed("Couldn’t get that photo ready.")
        return when (val backend = backend) {
            is Backend.Firebase -> {
                val uid = backend.auth.currentUser?.uid ?: throw LinkException.NotSignedIn
                val path = avatarPath(uid)
                val reference = backend.storage.reference.child(path)
                reference.putBytes(
                    jpeg,
                    StorageMetadata.Builder().setContentType("image/jpeg").build(),
                ).await()
                val url = reference.downloadUrl.await().toString()
                backend.firestore.collection(Fs.USERS).document(uid).set(
                    mapOf(
                        Fs.PHOTO_URL to url,
                        Fs.PHOTO_PATH to path,
                        Fs.UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
                url
            }
            is Backend.Local -> backend.store.saveAvatarJpeg(jpeg)
        }
    }

    suspend fun removeAvatar() {
        when (val backend = backend) {
            is Backend.Firebase -> {
                val uid = backend.auth.currentUser?.uid ?: throw LinkException.NotSignedIn
                // The object may already be gone; the profile fields are what matter.
                runCatching { backend.storage.reference.child(avatarPath(uid)).delete().await() }
                backend.firestore.collection(Fs.USERS).document(uid).set(
                    mapOf(
                        Fs.PHOTO_URL to FieldValue.delete(),
                        Fs.PHOTO_PATH to FieldValue.delete(),
                        Fs.UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
            }
            is Backend.Local -> backend.store.removeAvatar()
        }
    }

    private fun avatarPath(uid: String) = "users/$uid/avatar.jpg"

    /** Links the current anonymous user to Google. Same uid afterwards. */
    suspend fun linkWithGoogle(idToken: String) {
        when (val backend = backend) {
            is Backend.Firebase -> {
                val user = backend.auth.currentUser
                    ?: throw LinkException.NotSignedIn
                if (!user.isAnonymous) throw LinkException.AlreadyLinked
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                try {
                    user.linkWithCredential(credential).await()
                } catch (e: FirebaseAuthUserCollisionException) {
                    throw LinkException.CredentialInUse
                } catch (e: Exception) {
                    throw LinkException.Failed(e.message ?: "Link failed")
                }
            }
            is Backend.Local -> throw LinkException.Failed("Account linking isn’t available in local demo.")
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

sealed class LinkException(message: String) : Exception(message) {
    data object NotSignedIn : LinkException("You’re not signed in yet.")
    data object AlreadyLinked : LinkException("This account is already saved.")
    data object CredentialInUse :
        LinkException("That Google account is already linked to another MapTalk account.")
    data object Cancelled : LinkException("Sign in was cancelled.")
    class Failed(message: String) : LinkException(message)
}
