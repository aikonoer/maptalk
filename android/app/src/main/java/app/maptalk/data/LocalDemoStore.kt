package app.maptalk.data

import android.content.Context
import app.maptalk.data.model.Author
import app.maptalk.data.model.BlockedPerson
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.data.model.MessageKind
import app.maptalk.data.model.MessageReply
import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.PreparedVideo
import app.maptalk.data.model.ReportReason
import app.maptalk.data.model.ReportTargetType
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoPoint
import app.maptalk.geo.Viewport
import app.maptalk.geo.ViewportQuery
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * On-device stand-in for Auth + Firestore. Seeds a Cebu City neighbourhood of chats so the
 * phone can be tried with no Mac, no hotspot, and no Firebase project.
 */
class LocalDemoStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val media = LocalMediaStore(context)

    val uid: String = "local-demo-user"

    private val _displayName = MutableStateFlow(prefs.getString(KEY_DISPLAY_NAME, null))
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    private val threads = linkedMapOf<String, ChatThread>()
    private val messages = linkedMapOf<String, MutableList<Message>>()
    /** uid → display name */
    private val blocked = linkedMapOf<String, String>().also { map ->
        val stored = prefs.all
            .filterKeys { it.startsWith(KEY_BLOCK_PREFIX) }
            .mapNotNull { (key, value) ->
                val uid = key.removePrefix(KEY_BLOCK_PREFIX)
                val name = value as? String ?: return@mapNotNull null
                uid to name
            }
        if (stored.isNotEmpty()) {
            map.putAll(stored)
        } else {
            // Migrate legacy uid-only set.
            (prefs.getStringSet(KEY_BLOCKS, emptySet()) ?: emptySet()).forEach { uid ->
                map[uid] = "Blocked user"
            }
        }
    }

    private val threadPulse = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(Unit) }

    private val blockPulse = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(Unit) }

    init {
        cebuSeed().forEach { pack ->
            threads[pack.thread.id] = pack.thread
            messages[pack.thread.id] = pack.messages.toMutableList()
        }
    }

    fun saveDisplayName(name: String) {
        val trimmed = name.trim()
        prefs.edit().putString(KEY_DISPLAY_NAME, trimmed).apply()
        _displayName.value = trimmed
    }

    fun blockedPeople(): Flow<List<BlockedPerson>> =
        blockPulse.map { peopleSnapshot() }.onStart { emit(peopleSnapshot()) }

    fun block(blockedUid: String, displayName: String) {
        if (blockedUid == uid || blockedUid.isEmpty()) return
        blocked[blockedUid] = displayName
        prefs.edit()
            .putString("$KEY_BLOCK_PREFIX$blockedUid", displayName)
            .remove(KEY_BLOCKS)
            .apply()
        blockPulse.tryEmit(Unit)
    }

    fun unblock(blockedUid: String) {
        blocked.remove(blockedUid)
        prefs.edit().remove("$KEY_BLOCK_PREFIX$blockedUid").apply()
        blockPulse.tryEmit(Unit)
    }

    fun report(
        type: ReportTargetType,
        targetId: String,
        threadId: String,
        targetAuthorId: String,
        reason: ReportReason,
    ) {
        // Local demo: accept and forget.
        Unit
    }

    private fun peopleSnapshot(): List<BlockedPerson> =
        blocked.map { (uid, name) -> BlockedPerson(uid, name) }
            .sortedBy { it.displayName.lowercase() }

    fun threads(query: ViewportQuery): Flow<List<ChatThread>> =
        threadPulse.map { snapshot(query) }.onStart { emit(snapshot(query)) }

    fun thread(threadId: String): Flow<ChatThread?> =
        threadPulse.map { threads[threadId] }.onStart { emit(threads[threadId]) }

    fun messages(threadId: String): Flow<List<Message>> =
        threadPulse.map { messages[threadId]?.toList().orEmpty() }
            .onStart { emit(messages[threadId]?.toList().orEmpty()) }

    fun createThread(title: String, kind: ThreadKind, position: GeoPoint, author: Author): String {
        val id = "local-${UUID.randomUUID().toString().take(8)}"
        val now = Instant.now()
        val thread = ChatThread(
            id = id,
            title = title.trim(),
            kind = kind,
            position = position,
            geohash = GeoFireUtils.getGeoHashForLocation(
                GeoLocation(position.lat, position.lng),
                Fs.GEOHASH_PRECISION,
            ),
            authorId = author.uid,
            authorName = author.displayName,
            createdAt = now,
            lastMessageAt = now,
            messageCount = 0,
        )
        threads[id] = thread
        messages[id] = mutableListOf()
        threadPulse.tryEmit(Unit)
        return id
    }

    fun mediaFile(relativePath: String): File = media.file(relativePath)

    fun postMessage(
        threadId: String,
        text: String,
        author: Author,
        image: PreparedImage? = null,
        audio: PreparedAudio? = null,
        video: PreparedVideo? = null,
        sticker: String? = null,
        reply: MessageReply? = null,
    ) {
        val existing = threads[threadId] ?: return
        val trimmed = text.trim()

        val kind: MessageKind
        var body = trimmed
        var imagePath: String? = null
        var imageWidth: Int? = null
        var imageHeight: Int? = null
        var audioPath: String? = null
        var audioDurationMs: Int? = null
        var videoPath: String? = null
        var videoDurationMs: Int? = null
        var videoWidth: Int? = null
        var videoHeight: Int? = null

        when {
            sticker != null -> {
                kind = MessageKind.STICKER
                body = sticker
            }
            image != null -> {
                kind = MessageKind.IMAGE
                imagePath = media.save(image.jpegBytes)
                imageWidth = image.width
                imageHeight = image.height
            }
            video != null -> {
                kind = MessageKind.VIDEO
                body = ""
                videoPath = media.saveVideo(video.bytes)
                videoDurationMs = video.durationMs
                videoWidth = video.width
                videoHeight = video.height
            }
            audio != null -> {
                kind = MessageKind.VOICE
                body = ""
                audioPath = media.saveAudio(audio.bytes)
                audioDurationMs = audio.durationMs
            }
            else -> {
                kind = MessageKind.TEXT
                if (trimmed.isEmpty()) return
            }
        }

        val now = Instant.now()
        val message = Message(
            id = "local-msg-${UUID.randomUUID().toString().take(8)}",
            kind = kind,
            text = body,
            authorId = author.uid,
            authorName = author.displayName,
            createdAt = now,
            imagePath = imagePath,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            audioPath = audioPath,
            audioDurationMs = audioDurationMs,
            videoPath = videoPath,
            videoDurationMs = videoDurationMs,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            reply = reply,
        )
        messages.getOrPut(threadId) { mutableListOf() }.add(message)
        threads[threadId] = existing.copy(
            lastMessageAt = now,
            messageCount = existing.messageCount + 1,
        )
        threadPulse.tryEmit(Unit)
    }

    fun toggleReaction(threadId: String, messageId: String, emoji: String, author: Author) {
        val list = messages[threadId] ?: return
        val index = list.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val old = list[index]
        val reactions = old.reactions.mapValues { it.value.toMutableList() }.toMutableMap()
        for (key in reactions.keys.toList()) {
            val filtered = reactions[key]?.filter { it != author.uid }.orEmpty().toMutableList()
            if (filtered.isEmpty()) {
                reactions.remove(key)
            } else {
                reactions[key] = filtered
            }
        }
        val uids = reactions[emoji]?.toMutableList() ?: mutableListOf()
        if (uids.contains(author.uid)) {
            uids.remove(author.uid)
        } else {
            uids.add(author.uid)
        }
        if (uids.isEmpty()) {
            reactions.remove(emoji)
        } else {
            reactions[emoji] = uids
        }
        list[index] = old.copy(reactions = reactions)
        threadPulse.tryEmit(Unit)
    }

    private fun snapshot(query: ViewportQuery): List<ChatThread> {
        val all = threads.values.toList()
        return when (query) {
            is ViewportQuery.Nearby -> {
                val radiusMetres = query.radiusKm * 1_000
                all.filter { query.center.distanceTo(it.position) <= radiusMetres }
                    .sortedByDescending { it.lastMessageAt }
            }
            ViewportQuery.GlobalRecent ->
                all.sortedByDescending { it.lastMessageAt }.take(Viewport.GLOBAL_LIMIT.toInt())
        }
    }

    private data class Pack(val thread: ChatThread, val messages: List<Message>)

    private fun cebuSeed(): List<Pack> {
        val center = CEBU
        fun at(north: Double, east: Double) = GeoPoint(
            lat = center.lat + north / 111_320.0,
            lng = center.lng + east / (111_320.0 * cos(center.lat * PI / 180.0)),
        )

        fun pack(
            id: String,
            title: String,
            kind: ThreadKind,
            position: GeoPoint,
            author: String,
            ageMinutes: Long,
            replies: List<Triple<String, String, Long>>,
        ): Pack {
            val created = Instant.now().minusSeconds(ageMinutes * 60)
            val lastAge = replies.minOfOrNull { it.third } ?: ageMinutes
            val authorId = "seed-${author.lowercase().filter { it.isLetter() }}"
            val thread = ChatThread(
                id = id,
                title = title,
                kind = kind,
                position = position,
                geohash = GeoFireUtils.getGeoHashForLocation(
                    GeoLocation(position.lat, position.lng),
                    Fs.GEOHASH_PRECISION,
                ),
                authorId = authorId,
                authorName = author,
                createdAt = created,
                lastMessageAt = Instant.now().minusSeconds(lastAge * 60),
                messageCount = replies.size.toLong(),
            )
            val msgs = replies.mapIndexed { index, (name, text, age) ->
                Message(
                    id = "$id-msg-$index",
                    text = text,
                    authorId = "seed-${name.lowercase().filter { it.isLetter() }}",
                    authorName = name,
                    createdAt = Instant.now().minusSeconds(age * 60),
                )
            }
            return Pack(thread, msgs)
        }

        return listOf(
            pack(
                "cebu-waterfront",
                "Anyone else at Waterfront for the concert?",
                ThreadKind.EVENT,
                at(120.0, 80.0),
                "Priya",
                95,
                listOf(
                    Triple("Priya", "Doors were quick, barely queued", 90),
                    Triple("Marcus", "Standing left is packed, plenty of room on the right", 74),
                    Triple("Tomas", "Main act on at 9 apparently", 41),
                    Triple("Priya", "Sound is unreal from the front", 12),
                ),
            ),
            pack(
                "cebu-itpark",
                "Closing early tonight at IT Park — family thing",
                ThreadKind.NOTICE,
                at(-260.0, 140.0),
                "Loretta (Bar Sesa)",
                180,
                listOf(
                    Triple("Loretta (Bar Sesa)", "Kitchen is off but coffee is on until then", 175),
                    Triple("Dan", "Thanks for the heads up, will come by tomorrow", 120),
                ),
            ),
            pack(
                "cebu-bridge",
                "Mactan–Mandaue bridge crawling, what happened?",
                ThreadKind.TRAFFIC,
                at(700.0, -220.0),
                "Kenji",
                52,
                listOf(
                    Triple("Kenji", "Stopped for ten minutes now", 50),
                    Triple("Ava", "Two lanes closed, looks like a breakdown not a crash", 44),
                    Triple("Sam", "Took the old bridge instead, saved me 20 min", 21),
                ),
            ),
            pack(
                "cebu-carbon",
                "Carbon Market is packed tonight, food stalls everywhere",
                ThreadKind.EVENT,
                at(-90.0, -310.0),
                "Rosa",
                240,
                listOf(
                    Triple("Rosa", "Lechon stall near the entrance is worth the queue", 230),
                    Triple("Ellie", "Live band started by Fuente", 66),
                ),
            ),
            pack(
                "cebu-cat",
                "Lost a grey cat around Lahug, very friendly",
                ThreadKind.GENERAL,
                at(310.0, 420.0),
                "Hugo",
                400,
                listOf(
                    Triple("Hugo", "Answers to Miso, no collar", 395),
                    Triple("Nadia", "Saw a grey one near JY Square an hour ago", 88),
                ),
            ),
            pack(
                "cebu-brownout",
                "Brownout in Mabolo, anyone know how long?",
                ThreadKind.NOTICE,
                at(-620.0, -80.0),
                "Bea",
                150,
                listOf(Triple("Bea", "VEC notice says back by 4", 140)),
            ),
            pack(
                "cebu-srp",
                "Pickup game at SRP courts in 20 if anyone wants in",
                ThreadKind.GENERAL,
                at(430.0, -540.0),
                "Theo",
                35,
                listOf(
                    Triple("Theo", "Got 6, need 2 more", 33),
                    Triple("Ines", "On my way from Banilad", 8),
                ),
            ),
            pack(
                "cebu-seaside",
                "Queue for SM Seaside cinema is already around the corner",
                ThreadKind.GENERAL,
                at(-410.0, 660.0),
                "Fen",
                70,
                listOf(Triple("Fen", "Maybe 40 minutes from where I am", 68)),
            ),
        )
    }

        companion object {
        val CEBU = GeoPoint(10.3157, 123.8854)
        private const val PREFS = "maptalk.localDemo"
        private const val KEY_DISPLAY_NAME = "displayName"
        private const val KEY_BLOCKS = "blocks"
        private const val KEY_BLOCK_PREFIX = "block."
    }
}
