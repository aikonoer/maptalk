package app.maptalk.qa

import app.maptalk.data.Session
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.Viewport
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step one of the cross-device check: Android puts a bubble on the map. */
class CrossDeviceWriteTest {

    @Test
    fun androidSignsInAndPinsAThreadWithItsFirstMessage() = runBlocking {
        val repositories = CrossDevice.repositories()

        // A write rejected by the rules is still visible to local listeners for a moment, so
        // failures are collected and checked rather than trusted to surface as a wrong value.
        val failures = mutableListOf<Throwable>()
        val collector = launch { repositories.threads.errors.collect { failures += it } }

        repositories.auth.signInAnonymously()
        repositories.auth.saveDisplayName(CrossDevice.ANDROID_AUTHOR)

        val author = withTimeout(CrossDevice.TIMEOUT_MS) {
            repositories.auth.session().filterIsInstance<Session.Ready>().first().author
        }
        assertEquals(CrossDevice.ANDROID_AUTHOR, author.displayName)

        val threadId = repositories.threads.createThread(
            title = CrossDevice.ANDROID_TITLE,
            kind = ThreadKind.EVENT,
            position = CrossDevice.center,
            author = author,
        )
        // The first message bumps the thread's counters, and the rules check that bump against the
        // stored thread. Nobody types this fast, but the test does, so it waits for the backend to
        // have the thread rather than racing its own write.
        withTimeout(CrossDevice.TIMEOUT_MS) {
            repositories.firestore.waitForPendingWrites().await()
        }
        repositories.threads.postMessage(threadId, CrossDevice.ANDROID_MESSAGE, author)

        // Read it back through the query the map itself uses, so this covers the geohash bounds
        // and the distance filter rather than just the write.
        val thread = withTimeout(CrossDevice.TIMEOUT_MS) {
            repositories.threads
                .threads(Viewport.queryFor(CrossDevice.center, CrossDevice.RADIUS_KM))
                .mapNotNull { threads ->
                    threads.firstOrNull { it.id == threadId && it.messageCount >= 1L }
                }
                .first()
        }
        assertEquals(CrossDevice.ANDROID_TITLE, thread.title)
        assertEquals(ThreadKind.EVENT, thread.kind)
        assertEquals(1L, thread.messageCount)

        // Everything above could have been served from this device's own cache, and the iOS step
        // that follows reads the backend, so the step only counts once the backend has the data.
        withTimeout(CrossDevice.TIMEOUT_MS) {
            repositories.firestore.waitForPendingWrites().await()
        }
        collector.cancel()
        assertEquals(emptyList<Throwable>(), failures)
    }
}
