package app.maptalk.data

import app.maptalk.data.model.Author
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.data.model.MessageKind
import app.maptalk.data.model.MessageReply
import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoPoint
import app.maptalk.geo.Viewport
import app.maptalk.geo.ViewportQuery
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Reads and writes threads. Every read is a snapshot listener, so the map and the open
 * conversation update themselves.
 *
 * Local demo mode uses the same API against an in-memory store — no network required.
 */
class ThreadRepository private constructor(
    private val backend: Backend,
    private val mediaUploader: MediaUploader? = null,
) {

    private sealed interface Backend {
        data class Firestore(val db: FirebaseFirestore) : Backend
        data class Local(val store: LocalDemoStore) : Backend
    }

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    constructor(firestore: FirebaseFirestore, mediaUploader: MediaUploader) :
        this(Backend.Firestore(firestore), mediaUploader)

    constructor(local: LocalDemoStore) : this(Backend.Local(local))

    fun threads(query: ViewportQuery): Flow<List<ChatThread>> = when (val backend = backend) {
        is Backend.Firestore -> when (query) {
            is ViewportQuery.Nearby -> nearbyThreads(backend.db, query.center, query.radiusKm)
            ViewportQuery.GlobalRecent -> globalThreads(backend.db)
        }
        is Backend.Local -> backend.store.threads(query)
    }

    private fun nearbyThreads(
        firestore: FirebaseFirestore,
        center: GeoPoint,
        radiusKm: Double,
    ): Flow<List<ChatThread>> = callbackFlow {
        val radiusMetres = radiusKm * 1_000
        val bounds = GeoFireUtils.getGeoHashQueryBounds(
            GeoLocation(center.lat, center.lng),
            radiusMetres,
        )
        val pages = MutableList(bounds.size) { emptyList<ChatThread>() }

        val registrations = bounds.mapIndexed { index, bound ->
            firestore.collection(Fs.THREADS)
                .orderBy(Fs.GEOHASH)
                .startAt(bound.startHash)
                .endAt(bound.endHash)
                .limit(Viewport.PER_BOUND_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        _errors.tryEmit(error)
                        return@addSnapshotListener
                    }
                    pages[index] = snapshot?.documents?.mapNotNull { it.toChatThread() }.orEmpty()
                    trySend(pages.flatten().within(center, radiusMetres))
                }
        }

        trySend(emptyList())
        awaitClose { registrations.forEach(ListenerRegistration::remove) }
    }

    private fun globalThreads(firestore: FirebaseFirestore): Flow<List<ChatThread>> = callbackFlow {
        val registration = firestore.collection(Fs.THREADS)
            .orderBy(Fs.LAST_MESSAGE_AT, Query.Direction.DESCENDING)
            .limit(Viewport.GLOBAL_LIMIT)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    _errors.tryEmit(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toChatThread() }.orEmpty())
            }
        awaitClose { registration.remove() }
    }

    fun thread(threadId: String): Flow<ChatThread?> = when (val backend = backend) {
        is Backend.Firestore -> callbackFlow {
            val registration = backend.db.collection(Fs.THREADS).document(threadId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        _errors.tryEmit(error)
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.takeIf { it.exists() }?.toChatThread())
                }
            awaitClose { registration.remove() }
        }
        is Backend.Local -> backend.store.thread(threadId)
    }

    fun messages(threadId: String): Flow<List<Message>> = when (val backend = backend) {
        is Backend.Firestore -> callbackFlow {
            val registration = backend.db.collection(Fs.THREADS).document(threadId)
                .collection(Fs.MESSAGES)
                .orderBy(Fs.CREATED_AT, Query.Direction.DESCENDING)
                .limit(MESSAGE_PAGE)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        _errors.tryEmit(error)
                        return@addSnapshotListener
                    }
                    val messages = snapshot?.documents?.mapNotNull { it.toMessage() }.orEmpty()
                    trySend(messages.sortedBy { it.createdAt })
                }
            awaitClose { registration.remove() }
        }
        is Backend.Local -> backend.store.messages(threadId)
    }

    fun createThread(
        title: String,
        kind: ThreadKind,
        position: GeoPoint,
        author: Author,
    ): String = when (val backend = backend) {
        is Backend.Firestore -> {
            val document = backend.db.collection(Fs.THREADS).document()
            val geohash = GeoFireUtils.getGeoHashForLocation(
                GeoLocation(position.lat, position.lng),
                Fs.GEOHASH_PRECISION,
            )
            document.set(
                mapOf(
                    Fs.TITLE to title.trim(),
                    Fs.KIND to kind.id,
                    Fs.LAT to position.lat,
                    Fs.LNG to position.lng,
                    Fs.GEOHASH to geohash,
                    Fs.AUTHOR_ID to author.uid,
                    Fs.AUTHOR_NAME to author.displayName,
                    Fs.CREATED_AT to FieldValue.serverTimestamp(),
                    Fs.LAST_MESSAGE_AT to FieldValue.serverTimestamp(),
                    Fs.MESSAGE_COUNT to 0L,
                ),
            ).addOnFailureListener { _errors.tryEmit(it) }
            document.id
        }
        is Backend.Local -> backend.store.createThread(title, kind, position, author)
    }

    fun postMessage(
        threadId: String,
        text: String,
        author: Author,
        image: PreparedImage? = null,
        audio: PreparedAudio? = null,
        sticker: String? = null,
        reply: MessageReply? = null,
    ) {
        when (val backend = backend) {
            is Backend.Firestore -> {
                val trimmed = text.trim()
                if (image == null && audio == null && sticker == null && trimmed.isEmpty()) return
                val threadRef = backend.db.collection(Fs.THREADS).document(threadId)
                val messageRef = threadRef.collection(Fs.MESSAGES).document()
                val replyFields = reply?.let {
                    mapOf(
                        Fs.REPLY_TO_ID to it.id,
                        Fs.REPLY_TO_TEXT to it.text.take(200),
                        Fs.REPLY_TO_AUTHOR_NAME to it.authorName.take(64),
                    )
                }.orEmpty()

                when {
                    sticker != null -> {
                        commitFirestoreMessage(
                            db = backend.db,
                            threadRef = threadRef,
                            messageRef = messageRef,
                            fields = mapOf(
                                Fs.TEXT to sticker,
                                Fs.MESSAGE_KIND to MessageKind.STICKER.id,
                                Fs.AUTHOR_ID to author.uid,
                                Fs.AUTHOR_NAME to author.displayName,
                                Fs.CREATED_AT to FieldValue.serverTimestamp(),
                            ) + replyFields,
                        )
                    }
                    image != null -> {
                        val uploader = mediaUploader
                        if (uploader == null) {
                            _errors.tryEmit(IllegalStateException("Photo upload is not configured"))
                            return
                        }
                        scope.launch {
                            runCatching {
                                val url = uploader.upload(threadId, messageRef.id, image)
                                commitFirestoreMessage(
                                    db = backend.db,
                                    threadRef = threadRef,
                                    messageRef = messageRef,
                                    fields = mapOf(
                                        Fs.TEXT to trimmed,
                                        Fs.MESSAGE_KIND to MessageKind.IMAGE.id,
                                        Fs.IMAGE_PATH to url,
                                        Fs.IMAGE_WIDTH to image.width,
                                        Fs.IMAGE_HEIGHT to image.height,
                                        Fs.AUTHOR_ID to author.uid,
                                        Fs.AUTHOR_NAME to author.displayName,
                                        Fs.CREATED_AT to FieldValue.serverTimestamp(),
                                    ) + replyFields,
                                )
                            }.onFailure { _errors.emit(it) }
                        }
                    }
                    audio != null -> {
                        val uploader = mediaUploader
                        if (uploader == null) {
                            _errors.tryEmit(IllegalStateException("Audio upload is not configured"))
                            return
                        }
                        scope.launch {
                            runCatching {
                                val url = uploader.upload(threadId, messageRef.id, audio)
                                commitFirestoreMessage(
                                    db = backend.db,
                                    threadRef = threadRef,
                                    messageRef = messageRef,
                                    fields = mapOf(
                                        Fs.TEXT to "",
                                        Fs.MESSAGE_KIND to MessageKind.VOICE.id,
                                        Fs.AUDIO_PATH to url,
                                        Fs.AUDIO_DURATION_MS to audio.durationMs,
                                        Fs.AUTHOR_ID to author.uid,
                                        Fs.AUTHOR_NAME to author.displayName,
                                        Fs.CREATED_AT to FieldValue.serverTimestamp(),
                                    ) + replyFields,
                                )
                            }.onFailure { _errors.emit(it) }
                        }
                    }
                    else -> {
                        commitFirestoreMessage(
                            db = backend.db,
                            threadRef = threadRef,
                            messageRef = messageRef,
                            fields = mapOf(
                                Fs.TEXT to trimmed,
                                Fs.MESSAGE_KIND to MessageKind.TEXT.id,
                                Fs.AUTHOR_ID to author.uid,
                                Fs.AUTHOR_NAME to author.displayName,
                                Fs.CREATED_AT to FieldValue.serverTimestamp(),
                            ) + replyFields,
                        )
                    }
                }
            }
            is Backend.Local -> backend.store.postMessage(
                threadId = threadId,
                text = text,
                author = author,
                image = image,
                audio = audio,
                sticker = sticker,
                reply = reply,
            )
        }
    }

    /** Toggle one emoji reaction from this user. One emoji per user (switching replaces). */
    fun toggleReaction(threadId: String, messageId: String, emoji: String, author: Author) {
        when (val backend = backend) {
            is Backend.Firestore -> {
                val ref = backend.db.collection(Fs.THREADS).document(threadId)
                    .collection(Fs.MESSAGES).document(messageId)
                scope.launch {
                    runCatching {
                        backend.db.runTransaction { transaction ->
                            val snap = transaction.get(ref)
                            @Suppress("UNCHECKED_CAST")
                            val raw = snap.get(Fs.REACTIONS) as? Map<String, Any?> ?: emptyMap()
                            val reactions = raw.mapValues { (_, value) ->
                                when (value) {
                                    is List<*> -> value.filterIsInstance<String>().toMutableList()
                                    else -> mutableListOf()
                                }
                            }.toMutableMap()

                            for (key in reactions.keys.toList()) {
                                val filtered = reactions[key]?.filter { it != author.uid }.orEmpty().toMutableList()
                                if (filtered.isEmpty()) {
                                    reactions.remove(key)
                                } else {
                                    reactions[key] = filtered
                                }
                            }

                            val list = reactions[emoji]?.toMutableList() ?: mutableListOf()
                            if (list.contains(author.uid)) {
                                list.remove(author.uid)
                            } else {
                                list.add(author.uid)
                            }
                            if (list.isEmpty()) {
                                reactions.remove(emoji)
                            } else {
                                reactions[emoji] = list
                            }
                            transaction.update(ref, Fs.REACTIONS, reactions)
                            null
                        }.await()
                    }.onFailure { _errors.emit(it) }
                }
            }
            is Backend.Local -> backend.store.toggleReaction(threadId, messageId, emoji, author)
        }
    }

    private fun commitFirestoreMessage(
        db: FirebaseFirestore,
        threadRef: com.google.firebase.firestore.DocumentReference,
        messageRef: com.google.firebase.firestore.DocumentReference,
        fields: Map<String, Any>,
    ) {
        db.batch()
            .apply {
                set(messageRef, fields)
                update(
                    threadRef,
                    mapOf(
                        Fs.LAST_MESSAGE_AT to FieldValue.serverTimestamp(),
                        Fs.MESSAGE_COUNT to FieldValue.increment(1),
                    ),
                )
            }
            .commit()
            .addOnFailureListener { _errors.tryEmit(it) }
    }

    private fun List<ChatThread>.within(center: GeoPoint, radiusMetres: Double): List<ChatThread> =
        distinctBy { it.id }
            .filter { center.distanceTo(it.position) <= radiusMetres }
            .sortedByDescending { it.lastMessageAt }

    private companion object {
        const val MESSAGE_PAGE = 200L
    }
}
