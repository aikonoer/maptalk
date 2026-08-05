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
    VOICE("voice"),
    STICKER("sticker"),
    ;

    companion object {
        fun fromId(id: String?): MessageKind = entries.firstOrNull { it.id == id } ?: TEXT
    }
}

/** Quick reactions available under every bubble. */
object ReactionEmoji {
    val ALL = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
}

/** Curated sticker glyphs — no network pack. */
object StickerPack {
    val ALL = listOf(
        "👋", "🔥", "💯", "✨", "🎉", "🙌",
        "😅", "🫡", "🫶", "📍", "🚦", "☕",
    )
}

/** Snapshot of the message being replied to, denormalised onto the new message. */
data class MessageReply(
    val id: String,
    val authorName: String,
    val text: String,
)

/**
 * One reply inside a thread. Text / image / voice / sticker kinds share this shape;
 * unused media fields stay null.
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
    val audioPath: String? = null,
    val audioDurationMs: Int? = null,
    val reply: MessageReply? = null,
    /** emoji → uids who reacted with it */
    val reactions: Map<String, List<String>> = emptyMap(),
) {
    val hasImage: Boolean get() = kind == MessageKind.IMAGE && imagePath != null
    val hasVoice: Boolean get() = kind == MessageKind.VOICE && audioPath != null
    val isSticker: Boolean get() = kind == MessageKind.STICKER

    fun reacted(by: String, emoji: String): Boolean =
        reactions[emoji]?.contains(by) == true
}

/** A photo already resized and JPEG-encoded on the device, ready to store. */
data class PreparedImage(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
)

/** A short voice note ready to upload. */
data class PreparedAudio(
    val bytes: ByteArray,
    val durationMs: Int,
    val contentType: String = "audio/mp4",
)

/** Who is writing, denormalised onto every thread and message. */
data class Author(val uid: String, val displayName: String)
