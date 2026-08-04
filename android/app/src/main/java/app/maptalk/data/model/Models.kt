package app.maptalk.data.model

import app.maptalk.geo.GeoPoint
import java.time.Instant

/**
 * What a thread is about. Purely presentational: it picks the bubble colour and icon and is
 * never used to filter queries, which is why no composite index is needed.
 */
enum class ThreadKind(val id: String, val label: String, val glyph: String) {
    EVENT("event", "Happening now", "\uD83C\uDF89"),
    NOTICE("notice", "Notice", "\uD83D\uDCE3"),
    TRAFFIC("traffic", "Traffic", "\uD83D\uDEA7"),
    GENERAL("general", "Just talking", "\uD83D\uDCAC"),
    ;

    companion object {
        fun fromId(id: String?): ThreadKind = entries.firstOrNull { it.id == id } ?: GENERAL
    }
}

/** A conversation pinned to one point on the map. */
data class ChatThread(
    val id: String,
    val title: String,
    val kind: ThreadKind,
    val position: GeoPoint,
    val geohash: String,
    val authorId: String,
    val authorName: String,
    val createdAt: Instant?,
    val lastMessageAt: Instant?,
    val messageCount: Long,
)

enum class MessageKind(val id: String) {
    TEXT("text"),
    IMAGE("image"),
    ;

    companion object {
        fun fromId(id: String?): MessageKind = entries.firstOrNull { it.id == id } ?: TEXT
    }
}

/**
 * One reply inside a thread. Text messages carry only [text]; image messages also carry a
 * local or remote path to the compressed bytes (and optional caption in [text]).
 */
data class Message(
    val id: String,
    val kind: MessageKind = MessageKind.TEXT,
    val text: String,
    val authorId: String,
    val authorName: String,
    val createdAt: Instant?,
    val imagePath: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
) {
    val hasImage: Boolean get() = kind == MessageKind.IMAGE && imagePath != null
}

/** A photo already resized and JPEG-encoded on the device, ready to store. */
data class PreparedImage(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
)

/** Who is writing, denormalised onto every thread and message. */
data class Author(val uid: String, val displayName: String)
