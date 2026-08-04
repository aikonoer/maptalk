package app.maptalk.qa

import app.maptalk.geo.Viewport
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step three of the cross-device check, run after the iOS suite: the reply typed on iOS is on
 * the Android side, and the thread iOS created is on the map here.
 */
class CrossDeviceVerifyTest {

    @Test
    fun androidSeesTheIosReplyAndTheThreadIosCreated() = runBlocking {
        val repositories = CrossDevice.repositories()
        repositories.auth.signInAnonymously()

        val threads = withTimeout(CrossDevice.TIMEOUT_MS) {
            repositories.threads
                .threads(Viewport.queryFor(CrossDevice.center, CrossDevice.RADIUS_KM))
                .first { threads ->
                    threads.any { it.title == CrossDevice.ANDROID_TITLE && it.messageCount >= 2L } &&
                        threads.any { it.title == CrossDevice.IOS_TITLE }
                }
        }

        val androidThread = threads.first { it.title == CrossDevice.ANDROID_TITLE }
        val messages = withTimeout(CrossDevice.TIMEOUT_MS) {
            repositories.threads.messages(androidThread.id).first { it.size >= 2 }
        }
        assertEquals(
            listOf(CrossDevice.ANDROID_MESSAGE, CrossDevice.IOS_REPLY),
            messages.map { it.text },
        )
        // The reply is attributed to the other platform's anonymous user, and the thread's
        // activity counter moved with it under the rules that allow nothing else to change.
        assertNotEquals(androidThread.authorId, messages.last().authorId)
        assertEquals(2L, androidThread.messageCount)
        assertTrue(androidThread.lastMessageAt!! >= messages.last().createdAt!!.minusSeconds(5))

        // The hash the Swift port wrote for this point is the one the real library computes:
        // proof the two apps put the same place in the same geohash cell.
        val iosThread = threads.first { it.title == CrossDevice.IOS_TITLE }
        assertEquals(
            GeoFireUtils.getGeoHashForLocation(
                GeoLocation(CrossDevice.center.lat, CrossDevice.center.lng),
                10,
            ),
            iosThread.geohash,
        )

        // Both are also in the zoomed-out worldwide query.
        val global = withTimeout(CrossDevice.TIMEOUT_MS) {
            repositories.threads
                .threads(Viewport.queryFor(CrossDevice.center, radiusKm = 2_000.0))
                .first { it.size >= 2 }
        }
        assertTrue(global.map { it.title }.containsAll(
            listOf(CrossDevice.ANDROID_TITLE, CrossDevice.IOS_TITLE),
        ))
    }
}
