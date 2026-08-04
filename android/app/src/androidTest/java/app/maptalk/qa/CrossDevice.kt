package app.maptalk.qa

import androidx.test.platform.app.InstrumentationRegistry
import app.maptalk.FirebaseEmulator
import app.maptalk.data.AuthRepository
import app.maptalk.data.MediaUploader
import app.maptalk.data.ThreadRepository
import app.maptalk.geo.GeoPoint
import com.google.firebase.firestore.FirebaseFirestore

/**
 * The half of the cross-device check that runs on Android. `scripts/cross-device-qa.sh` boots
 * the Firebase emulators and then runs, in order: [CrossDeviceWriteTest], the iOS
 * `CrossDeviceSyncTests`, and [CrossDeviceVerifyTest]. Android writes a thread, iOS finds it
 * through its own geohash query and replies, and Android sees the reply.
 *
 * Every constant below is duplicated in `ios/MapTalkTests/CrossDeviceSyncTests.swift`; the two
 * files are the handshake, so a change here needs the same change there.
 */
object CrossDevice {

    val center = GeoPoint(lat = -33.8688, lng = 151.2093)
    const val RADIUS_KM = 2.0

    const val ANDROID_AUTHOR = "Android QA"
    const val ANDROID_TITLE = "Bubble from Android"
    const val ANDROID_MESSAGE = "Posted on Android"

    const val IOS_TITLE = "Bubble from iOS"
    const val IOS_REPLY = "Replying from iOS"

    /** Long enough for a listener to make the round trip, short enough to fail a stuck test. */
    const val TIMEOUT_MS = 30_000L

    fun repositories(): Repositories {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firebase = FirebaseEmulator.connect(context)
        return Repositories(
            auth = AuthRepository(firebase.auth, firebase.firestore),
            threads = ThreadRepository(
                firestore = firebase.firestore,
                mediaUploader = MediaUploader(firebase.storage),
            ),
            firestore = firebase.firestore,
        )
    }

    data class Repositories(
        val auth: AuthRepository,
        val threads: ThreadRepository,
        /**
         * Only the tests need this: Firestore serves a write back to its own listeners long
         * before the backend has seen it, so a step is not finished until
         * [FirebaseFirestore.waitForPendingWrites] says the server took it.
         */
        val firestore: FirebaseFirestore,
    )
}
