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

/**
 * How fresh a chat feels — map bubbles use glow intensity instead of a "Live" label.
 */
enum class ActivityHeat {
    /** Active in the last 20 minutes — strong accent glow. */
    HOT,
    /** Active in the last 2 hours — soft accent glow. */
    WARM,
    /** Quieter — hairline only. */
    COOL,
    ;

    companion object {
        private val HOT_WINDOW: Duration = Duration.ofMinutes(20)
        private val WARM_WINDOW: Duration = Duration.ofHours(2)

        fun of(instant: Instant?, now: Instant = Instant.now()): ActivityHeat {
            if (instant == null) return COOL
            val age = Duration.between(instant, now)
            if (age.isNegative) return COOL
            if (age <= HOT_WINDOW) return HOT
            if (age <= WARM_WINDOW) return WARM
            return COOL
        }
    }
}

/** Recently active threads get a glow on the map — no backend field required. */
object LiveNow {
    fun isLive(instant: Instant?, now: Instant = Instant.now()): Boolean =
        ActivityHeat.of(instant, now) == ActivityHeat.HOT
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
