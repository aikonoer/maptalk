package app.maptalk

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings

/**
 * Firebase pointed at the local emulators, under fake credentials in a secondary app so no real
 * project is needed and the one the app ships with is never touched.
 *
 * Two callers: the app itself when built with `-Pmaptalk.emulator=true`, which is how you browse
 * the seeded mock data (`scripts/mock-data.sh`), and the cross-device suite in `androidTest`.
 */
object FirebaseEmulator {

    /** How the AVD reaches the host machine. A physical device needs your machine's LAN address. */
    const val DEFAULT_HOST = "10.0.2.2"

    private const val APP_NAME = "maptalk-emulator"
    private const val AUTH_PORT = 9099
    private const val FIRESTORE_PORT = 8080
    private const val PROJECT_ID = "maptalk-qa"

    fun connect(context: Context, host: String = DEFAULT_HOST): Connection {
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
        val app = existing ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(PROJECT_ID)
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("emulator-only-key")
                .build(),
            APP_NAME,
        )

        val auth = FirebaseAuth.getInstance(app)
        val firestore = FirebaseFirestore.getInstance(app)
        if (existing == null) {
            auth.useEmulator(host, AUTH_PORT)
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setHost("$host:$FIRESTORE_PORT")
                .setSslEnabled(false)
                // Reads must come from the emulator, not a cache left by an earlier run.
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
        return Connection(auth, firestore)
    }

    data class Connection(val auth: FirebaseAuth, val firestore: FirebaseFirestore)
}
