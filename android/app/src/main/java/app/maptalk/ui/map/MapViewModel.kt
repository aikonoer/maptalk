package app.maptalk.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.maptalk.AppContainer
import app.maptalk.data.SafetyRepository
import app.maptalk.data.ThreadRepository
import app.maptalk.data.model.Author
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoCluster
import app.maptalk.geo.GeoPoint
import app.maptalk.geo.Viewport
import app.maptalk.geo.ViewportQuery
import app.maptalk.geo.clusterByGeohash
import app.maptalk.location.LocationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the camera can see: where it is pointed and how far out it is zoomed. */
data class CameraSnapshot(val center: GeoPoint, val radiusKm: Double)

data class MapUiState(
    val bubbles: List<GeoCluster<ChatThread>> = emptyList(),
    val isGlobalView: Boolean = false,
    val isLoading: Boolean = true,
)

class MapViewModel(
    private val threadRepository: ThreadRepository,
    private val safetyRepository: SafetyRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val camera = MutableStateFlow<CameraSnapshot?>(null)

    private val _startLocation = MutableStateFlow<GeoPoint?>(null)

    /** Where to point the camera on first load, once we know it. */
    val startLocation: StateFlow<GeoPoint?> = _startLocation.asStateFlow()

    val errors = merge(threadRepository.errors, safetyRepository.errors)

    private val blockedUids: StateFlow<Set<String>> = safetyRepository.blockedPeople()
        .map { people -> people.map { it.uid }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
            ) { threads, blocked ->
                val visible = threads.filter { it.authorId !in blocked }
                MapUiState(
                    bubbles = clusterByGeohash(
                        items = visible,
                        prefixLength = Viewport.clusterPrefixLength(snapshot.radiusKm),
                        geohashOf = ChatThread::geohash,
                        positionOf = ChatThread::position,
                        keyOf = ChatThread::id,
                    ),
                    isGlobalView = query is ViewportQuery.GlobalRecent,
                    isLoading = false,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    fun onCameraSettled(center: GeoPoint, radiusKm: Double) {
        camera.value = CameraSnapshot(center, radiusKm)
    }

    fun locateMe() {
        viewModelScope.launch {
            locationProvider.currentLocation()?.let { _startLocation.value = it }
        }
    }

    fun createThread(
        title: String,
        kind: ThreadKind,
        position: GeoPoint,
        author: Author,
    ): String = threadRepository.createThread(title, kind, position, author)

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

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MapViewModel(
                        threadRepository = container.threadRepository,
                        safetyRepository = container.safetyRepository,
                        locationProvider = container.locationProvider,
                    )
                }
            }
    }
}
