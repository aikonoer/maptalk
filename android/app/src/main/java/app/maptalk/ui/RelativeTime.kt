package app.maptalk.ui

import java.time.Duration
import java.time.Instant

/** Short, chat style timestamps: "now", "4m", "3h", "2d". */
fun relativeTime(instant: Instant?, now: Instant = Instant.now()): String {
    if (instant == null) return "sending\u2026"
    val elapsed = Duration.between(instant, now)
    val minutes = elapsed.toMinutes()
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        elapsed.toHours() < 24 -> "${elapsed.toHours()}h"
        elapsed.toDays() < 7 -> "${elapsed.toDays()}d"
        else -> "${elapsed.toDays() / 7}w"
    }
}
