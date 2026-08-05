package app.maptalk

import android.app.Application
import android.content.Context
import app.maptalk.data.AuthRepository
import app.maptalk.data.LocalDemoStore
import app.maptalk.data.MediaUploader
import app.maptalk.data.PushRepository
import app.maptalk.data.SafetyRepository
import app.maptalk.data.ThreadRepository
import app.maptalk.location.LocationProvider
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.storage
import java.io.File

/**
 * Hand rolled dependency container. The app has three collaborators, so a DI framework would
 * be more ceremony than the wiring it replaces.
 *
 * Mode (BuildConfig.MAPTALK_MODE):
 * - `local` — on-device Cebu seed, no network (default Debug)
 * - `emulator` — Firebase emulators on the host (`-Pmaptalk.emulator=true`)
 * - `live` — real Firebase project (Release default)
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Application context for reading gallery URIs and resolving local media paths. */
    val context: Context get() = appContext

    val isLocalDemo: Boolean = BuildConfig.MAPTALK_MODE == "local"

    /** Open the map on the seeded Cebu neighbourhood (local demo or live Debug). */
    val opensOnCebu: Boolean =
        isLocalDemo || BuildConfig.MAPTALK_MODE == "live" || BuildConfig.MAPTALK_MODE == "emulator"

    private val localStore: LocalDemoStore? by lazy {
        if (isLocalDemo) LocalDemoStore(appContext) else null
    }

    private val firebase by lazy {
        if (BuildConfig.MAPTALK_MODE == "emulator") {
            FirebaseEmulator.connect(appContext)
        } else {
            FirebaseEmulator.Connection(
                auth = Firebase.auth,
                firestore = Firebase.firestore,
                storage = Firebase.storage,
            )
        }
    }

    val authRepository: AuthRepository by lazy {
        localStore?.let { AuthRepository(it) }
            ?: AuthRepository(firebase.auth, firebase.firestore)
    }

    val threadRepository: ThreadRepository by lazy {
        localStore?.let { ThreadRepository(it) }
            ?: ThreadRepository(
                firestore = firebase.firestore,
                mediaUploader = when (BuildConfig.MAPTALK_MODE) {
                    "emulator" -> MediaUploader.Firebase(firebase.storage)
                    else -> MediaUploader.R2(
                        auth = firebase.auth,
                        imageEndpoint = BuildConfig.MAPTALK_MEDIA_UPLOAD_URL,
                    )
                },
            )
    }

    val safetyRepository: SafetyRepository by lazy {
        localStore?.let { SafetyRepository(it) }
            ?: SafetyRepository(
                firestore = firebase.firestore,
                currentUid = { firebase.auth.currentUser?.uid },
            )
    }

    val pushRepository: PushRepository by lazy {
        localStore?.let { PushRepository(it) }
            ?: PushRepository(
                firestore = firebase.firestore,
                currentUid = { firebase.auth.currentUser?.uid },
            )
    }

    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    /** Resolves a local-demo image filename to a file on disk, or null outside local mode. */
    fun resolveLocalMedia(relativePath: String): File? =
        localStore?.mediaFile(relativePath)
}

class MapTalkApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MapTalkApplication).container
