package app.maptalk.data

import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.data.model.MessageKind
import app.maptalk.data.model.MessageReply
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoPoint
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.time.Instant

/**
 * Collection and field names, mirrored in `ios/MapTalk/Data/FirestoreSchema.swift` and
 * enforced by `firebase/firestore.rules`.
 */
object Fs {
    const val THREADS = "threads"
    const val MESSAGES = "messages"
    const val USERS = "users"

    const val TITLE = "title"
    const val KIND = "kind"
    const val LAT = "lat"
    const val LNG = "lng"
    const val GEOHASH = "geohash"
    const val AUTHOR_ID = "authorId"
    const val AUTHOR_NAME = "authorName"
    const val CREATED_AT = "createdAt"
    const val LAST_MESSAGE_AT = "lastMessageAt"
    const val MESSAGE_COUNT = "messageCount"
    const val TEXT = "text"
    const val DISPLAY_NAME = "displayName"
    const val MESSAGE_KIND = "messageKind"
    const val IMAGE_PATH = "imagePath"
    const val IMAGE_WIDTH = "imageWidth"
    const val IMAGE_HEIGHT = "imageHeight"
    const val AUDIO_PATH = "audioPath"
    const val AUDIO_DURATION_MS = "audioDurationMs"
    const val REPLY_TO_ID = "replyToId"
    const val REPLY_TO_TEXT = "replyToText"
    const val REPLY_TO_AUTHOR_NAME = "replyToAuthorName"
    const val REACTIONS = "reactions"

    /** Precision used for the stored hash; the query bounds use their own precision. */
    const val GEOHASH_PRECISION = 10
}

/**
 * Documents are mapped by hand rather than through reflection, so a schema change is a
 * compile error instead of a silent null, and R8 needs no keep rules.
 *
 * A locally created document has no server timestamp yet, so timestamps are read with
 * [DocumentSnapshot.ServerTimestampBehavior.ESTIMATE]. That gives just-sent messages a
 * usable sort key instead of null.
 */
fun DocumentSnapshot.toChatThread(): ChatThread? {
    val title = getString(Fs.TITLE) ?: return null
    val lat = getDouble(Fs.LAT) ?: return null
    val lng = getDouble(Fs.LNG) ?: return null
    return ChatThread(
        id = id,
        title = title,
        kind = ThreadKind.fromId(getString(Fs.KIND)),
        position = GeoPoint(lat, lng),
        geohash = getString(Fs.GEOHASH).orEmpty(),
        authorId = getString(Fs.AUTHOR_ID).orEmpty(),
        authorName = getString(Fs.AUTHOR_NAME).orEmpty(),
        createdAt = estimatedInstant(Fs.CREATED_AT),
        lastMessageAt = estimatedInstant(Fs.LAST_MESSAGE_AT),
        messageCount = getLong(Fs.MESSAGE_COUNT) ?: 0L,
    )
}

fun DocumentSnapshot.toMessage(): Message? {
    val kind = MessageKind.fromId(getString(Fs.MESSAGE_KIND))
    val text = getString(Fs.TEXT).orEmpty()
    val imagePath = getString(Fs.IMAGE_PATH)
    val audioPath = getString(Fs.AUDIO_PATH)
    when (kind) {
        MessageKind.TEXT -> if (text.isEmpty()) return null
        MessageKind.IMAGE -> if (imagePath == null) return null
        MessageKind.VOICE -> if (audioPath == null) return null
        MessageKind.STICKER -> if (text.isEmpty()) return null
    }

    val replyId = getString(Fs.REPLY_TO_ID)
    val replyName = getString(Fs.REPLY_TO_AUTHOR_NAME)
    val replyText = getString(Fs.REPLY_TO_TEXT)
    val reply = if (replyId != null && replyName != null && replyText != null) {
        MessageReply(id = replyId, authorName = replyName, text = replyText)
    } else {
        null
    }

    @Suppress("UNCHECKED_CAST")
    val rawReactions = get(Fs.REACTIONS) as? Map<String, Any?> ?: emptyMap()
    val reactions = rawReactions.mapNotNull { (emoji, value) ->
        val uids = when (value) {
            is List<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
        if (uids.isEmpty()) null else emoji to uids
    }.toMap()

    return Message(
        id = id,
        kind = kind,
        text = text,
        authorId = getString(Fs.AUTHOR_ID).orEmpty(),
        authorName = getString(Fs.AUTHOR_NAME).orEmpty(),
        createdAt = estimatedInstant(Fs.CREATED_AT),
        imagePath = imagePath,
        imageWidth = getLong(Fs.IMAGE_WIDTH)?.toInt(),
        imageHeight = getLong(Fs.IMAGE_HEIGHT)?.toInt(),
        audioPath = audioPath,
        audioDurationMs = getLong(Fs.AUDIO_DURATION_MS)?.toInt(),
        reply = reply,
        reactions = reactions,
    )
}

private fun DocumentSnapshot.estimatedInstant(field: String): Instant? {
    val timestamp = get(field, Timestamp::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
    return timestamp?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
}
