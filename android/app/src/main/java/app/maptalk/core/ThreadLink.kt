package app.maptalk.core

import android.net.Uri
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shareable / inbound links: `maptalk://thread/{id}`. */
object ThreadLink {
    const val SCHEME = "maptalk"
    const val HOST = "thread"

    fun url(threadId: String): String = "$SCHEME://$HOST/$threadId"

    fun threadId(uri: Uri?): String? {
        if (uri == null) return null
        if (uri.scheme?.equals(SCHEME, ignoreCase = true) != true) return null
        val host = uri.host.orEmpty()
        if (host.equals(HOST, ignoreCase = true)) {
            val id = uri.pathSegments.firstOrNull().orEmpty()
            return id.takeIf { it.isNotBlank() }
        }
        val segments = uri.pathSegments
        if (segments.size >= 2 && segments[0].equals(HOST, ignoreCase = true)) {
            return segments[1].takeIf { it.isNotBlank() }
        }
        return null
    }
}

/** Recently active threads get a "Live" mark on the map — no backend field required. */
object LiveNow {
    private val WINDOW: Duration = Duration.ofMinutes(20)

    fun isLive(instant: Instant?, now: Instant = Instant.now()): Boolean {
        if (instant == null) return false
        val age = Duration.between(instant, now)
        return !age.isNegative && age <= WINDOW
    }
}

/** Holds a thread id from a deep link or notification tap until MapScreen can open the sheet. */
object DeepLinkBus {
    private val _pendingThreadId = MutableStateFlow<String?>(null)
    val pendingThreadId: StateFlow<String?> = _pendingThreadId.asStateFlow()

    fun offer(threadId: String?) {
        val trimmed = threadId?.trim().orEmpty()
        _pendingThreadId.value = trimmed.ifEmpty { null }
    }

    fun consume(): String? {
        val id = _pendingThreadId.value
        _pendingThreadId.value = null
        return id
    }
}
