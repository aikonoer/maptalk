package app.maptalk.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.maptalk.AppContainer
import app.maptalk.data.AuthRepository
import app.maptalk.data.Fs
import app.maptalk.data.SafetyRepository
import app.maptalk.data.ThreadRepository
import app.maptalk.data.UserProfile
import app.maptalk.data.model.Author
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoCluster
import app.maptalk.geo.GeoPoint
import app.maptalk.geo.Viewport
import app.maptalk.geo.ViewportQuery
import app.maptalk.geo.clusterByGeohash
import app.maptalk.location.LocationProvider
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.maptalk.data.model.Message
import app.maptalk.data.model.PreparedImage

/** What the camera can see: where it is pointed and how far out it is zoomed. */
data class CameraSnapshot(val center: GeoPoint, val radiusKm: Double)

/** Keep [center], open the view to [radiusKm] so a distant chat becomes visible. */
data class WidenMap(val center: GeoPoint, val radiusKm: Double)

data class MapUiState(
    val bubbles: List<GeoCluster<ChatThread>> = emptyList(),
    val isGlobalView: Boolean = false,
    val isLoading: Boolean = true,
    /** True when the kind filter hid every nearby chat that would otherwise show. */
    val isFilterHidingAll: Boolean = false,
)

class MapViewModel(
    private val threadRepository: ThreadRepository,
    private val safetyRepository: SafetyRepository,
    private val authRepository: AuthRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val camera = MutableStateFlow<CameraSnapshot?>(null)

    /** Empty = show every kind. Non-empty = only those kinds (client-side; no query change). */
    private val _kindFilter = MutableStateFlow<Set<ThreadKind>>(emptySet())
    val kindFilter: StateFlow<Set<ThreadKind>> = _kindFilter.asStateFlow()

    /** Name + photo for the map's account button. */
    val profile: StateFlow<UserProfile> =
        (authRepository.currentUid?.let(authRepository::profile) ?: flowOf(UserProfile()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    private val _startLocation = MutableStateFlow<GeoPoint?>(null)

    /** Where to point the camera on first load, once we know it. */
    val startLocation: StateFlow<GeoPoint?> = _startLocation.asStateFlow()

    private val _widenToClosest = MutableSharedFlow<WidenMap>(extraBufferCapacity = 1)
    /** Same centre, wider zoom so the nearest chat lands on screen. */
    val widenToClosest: SharedFlow<WidenMap> = _widenToClosest.asSharedFlow()

    private val _findingClosest = MutableStateFlow(false)
    val findingClosest: StateFlow<Boolean> = _findingClosest.asStateFlow()

    val errors = merge(threadRepository.errors, safetyRepository.errors)

    private val blockedUids: StateFlow<Set<String>> = safetyRepository.blockedPeople()
        .map { people -> people.map { it.uid }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Optimistically hidden after Delete chat until the query snapshot catches up. */
    private val _removedThreadIds = MutableStateFlow<Set<String>>(emptySet())

    /** Freshly created chats — shown before the query snapshot includes them. */
    private val _pendingThreads = MutableStateFlow<Map<String, ChatThread>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val state: StateFlow<MapUiState> = camera
        .filterNotNull()
        .debounce(CAMERA_DEBOUNCE_MS)
        .onlyMeaningfulMoves()
        .flatMapLatest { snapshot ->
            val query = Viewport.queryFor(snapshot.center, snapshot.radiusKm)
            combine(
                threadRepository.threads(query),
                blockedUids,
                _kindFilter,
                _removedThreadIds,
                _pendingThreads,
            ) { threads, blocked, filter, removed, pending ->
                val serverIds = threads.map { it.id }.toSet()
                val pendingExtra = pending.values.filter { it.id !in serverIds && it.id !in removed }
                val visible = (threads.filter { it.id !in removed && it.authorId !in blocked } + pendingExtra)
                    .distinctBy { it.id }
                val shown = if (filter.isEmpty()) visible else visible.filter { it.kind in filter }
                MapUiState(
                    bubbles = clusterByGeohash(
                        items = shown,
                        prefixLength = Viewport.clusterPrefixLength(snapshot.radiusKm),
                        geohashOf = ChatThread::geohash,
                        positionOf = ChatThread::position,
                        keyOf = ChatThread::id,
                    ),
                    isGlobalView = query is ViewportQuery.GlobalRecent,
                    isLoading = false,
                    isFilterHidingAll = filter.isNotEmpty() && shown.isEmpty() && visible.isNotEmpty(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    /** Drop a thread from the current map snapshot immediately (e.g. after Delete chat). */
    fun removeThread(id: String) {
        _removedThreadIds.value = _removedThreadIds.value + id
    }

    fun onCameraSettled(center: GeoPoint, radiusKm: Double) {
        camera.value = CameraSnapshot(center, radiusKm)
    }

    /** Selecting every kind is the same as selecting none, so it collapses back to "all". */
    fun toggleKindFilter(kind: ThreadKind) {
        val current = _kindFilter.value
        val next = when {
            current.isEmpty() -> setOf(kind)
            kind in current -> current - kind
            else -> current + kind
        }
        _kindFilter.value = if (next.size == ThreadKind.entries.size) emptySet() else next
    }

    fun clearKindFilter() {
        _kindFilter.value = emptySet()
    }

    fun locateMe() {
        viewModelScope.launch {
            locationProvider.currentLocation()?.let { _startLocation.value = it }
        }
    }

    /**
     * Zoom out around the current camera centre until the nearest chat fits on screen —
     * centre stays put; only the visible radius grows.
     */
    fun findClosestChat() {
        val snapshot = camera.value ?: return
        if (_findingClosest.value) return
        viewModelScope.launch {
            _findingClosest.value = true
            try {
                val nearest = threadRepository.nearestThread(snapshot.center, blockedUids.value)
                    ?: return@launch
                val distanceKm = snapshot.center.distanceTo(nearest.position) / 1_000
                val radiusKm = maxOf(distanceKm * REVEAL_PADDING, snapshot.radiusKm * 1.2)
                _widenToClosest.emit(WidenMap(center = snapshot.center, radiusKm = radiusKm))
            } finally {
                _findingClosest.value = false
            }
        }
    }

    /**
     * The title is the map headline; optional [openingText] and/or [openingImage] become the
     * first message, so a chat can start with more than a one-liner.
     */
    fun createThread(
        title: String,
        kind: ThreadKind,
        position: GeoPoint,
        author: Author,
        openingText: String = "",
        openingImage: PreparedImage? = null,
    ): String {
        val id = threadRepository.createThread(title, kind, position, author)
        val opening = openingText.trim()
        val now = Instant.now()
        // Pin immediately so the map bubble can grow in before the query echoes the write.
        _pendingThreads.value = _pendingThreads.value + (
            id to ChatThread(
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
                messageCount = if (openingImage != null || opening.isNotEmpty()) 1L else 0L,
            )
        )
        if (openingImage != null || opening.isNotEmpty()) {
            threadRepository.postMessage(
                threadId = id,
                text = opening,
                author = author,
                image = openingImage,
            )
        }
        return id
    }

    /** Up to three tip messages for the long-press bubble peek (oldest → newest). */
    suspend fun peekMessages(threadId: String): List<Message> =
        threadRepository.messages(threadId).first().takeLast(3)

    /**
     * Panning a map produces a continuous stream of positions. Re-subscribing Firestore for
     * every one of them would be wasteful, so a move only counts once it changes the zoom
     * noticeably, shifts the centre by a fifth of the visible radius, or crosses the line
     * between the nearby and the worldwide query.
     */
    private fun Flow<CameraSnapshot>.onlyMeaningfulMoves(): Flow<CameraSnapshot> = flow {
        var previous: CameraSnapshot? = null
        collect { snapshot ->
            val last = previous
            if (last == null || snapshot.isMeaningfullyDifferentFrom(last)) {
                previous = snapshot
                emit(snapshot)
            }
        }
    }

    private fun CameraSnapshot.isMeaningfullyDifferentFrom(other: CameraSnapshot): Boolean {
        val isGlobal = Viewport.queryFor(center, radiusKm) is ViewportQuery.GlobalRecent
        val wasGlobal = Viewport.queryFor(other.center, other.radiusKm) is ViewportQuery.GlobalRecent
        if (isGlobal != wasGlobal) return true
        val zoomRatio = radiusKm / other.radiusKm
        if (zoomRatio !in 0.8..1.25) return true
        return center.distanceTo(other.center) > other.radiusKm * 1_000 * 0.2
    }

    companion object {
        private const val CAMERA_DEBOUNCE_MS = 250L

        /** How much wider than the bare distance, so the chat is not glued to the edge. */
        private const val REVEAL_PADDING = 1.35

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MapViewModel(
                        threadRepository = container.threadRepository,
                        safetyRepository = container.safetyRepository,
                        authRepository = container.authRepository,
                        locationProvider = container.locationProvider,
                    )
                }
            }
    }
}
