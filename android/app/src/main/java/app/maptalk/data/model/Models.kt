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
    VIDEO("video"),
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
 * One reply inside a thread. Text / image / voice / video / sticker kinds share this shape;
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
    val videoPath: String? = null,
    val videoDurationMs: Int? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val reply: MessageReply? = null,
    /** emoji → uids who reacted with it */
    val reactions: Map<String, List<String>> = emptyMap(),
) {
    val hasImage: Boolean get() = kind == MessageKind.IMAGE && imagePath != null
    val hasVoice: Boolean get() = kind == MessageKind.VOICE && audioPath != null
    val hasVideo: Boolean get() = kind == MessageKind.VIDEO && videoPath != null
    val isSticker: Boolean get() = kind == MessageKind.STICKER
    /** Optimistic local send — not yet on the server. */
    val isLocalPending: Boolean get() = id.startsWith("local:")

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

/** A short video clip ready to upload (temp file; caller deletes after send). */
data class PreparedVideo(
    val file: java.io.File,
    val durationMs: Int,
    val width: Int,
    val height: Int,
    val contentType: String = "video/mp4",
) {
    val byteLength: Long get() = file.length()
}

/** Who is writing, denormalised onto every thread and message. */
data class Author(val uid: String, val displayName: String)

/** Someone this viewer has blocked. */
data class BlockedPerson(val uid: String, val displayName: String)

enum class ReportTargetType(val id: String) {
    MESSAGE("message"),
    THREAD("thread"),
    USER("user"),
}

enum class ReportReason(val id: String, val label: String) {
    SPAM("spam", "Spam"),
    HARASSMENT("harassment", "Harassment"),
    INAPPROPRIATE("inappropriate", "Inappropriate"),
    OTHER("other", "Something else"),
    ;

    companion object {
        val ALL = entries
    }
}
