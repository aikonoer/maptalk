package app.maptalk.ui.map

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.core.DeepLinkBus
import app.maptalk.data.LocalDemoStore
import app.maptalk.data.model.Author
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoPoint
import app.maptalk.geo.Viewport
import app.maptalk.geo.withRoomForBubbles
import app.maptalk.location.LocationProvider
import app.maptalk.ui.InitialAvatar
import app.maptalk.ui.PlaceSearch
import app.maptalk.ui.PlaceSearchHit
import app.maptalk.ui.account.AccountScreen
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.tint
import app.maptalk.ui.thread.ThreadScreen
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WorldCenter = LatLng(20.0, 10.0)
private val CebuCenter = LatLng(LocalDemoStore.CEBU.lat, LocalDemoStore.CEBU.lng)
private const val WORLD_ZOOM = 2.2f
private const val NEARBY_ZOOM = 14f
private const val SEARCH_DEBOUNCE_MS = 320L
private const val SEARCH_LANDING_MS = 1_800L
private val GLYPH_SIZE = 28.dp
/** Compose sheet peek — leaves a map band above so the pin stays in view. */
private val ComposeSheetPeek = 420.dp
/** Side/bottom inset — matches the floating chat sheet so the map peeks around the card. */
private val ComposeSheetInset = 12.dp
private val ComposeSheetCorner = 28.dp
/** Modest zoom-in when opening compose; cancel restores the prior camera. */
private const val COMPOSE_ZOOM_DELTA = 0.55f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    author: Author,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startLocation by viewModel.startLocation.collectAsStateWithLifecycle()
    val kindFilter by viewModel.kindFilter.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val findingClosest by viewModel.findingClosest.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val startsInDemo = container.opensOnCebu
    val cameraPositionState = rememberCameraPositionState {
        position = if (startsInDemo) {
            CameraPosition.fromLatLngZoom(CebuCenter, NEARBY_ZOOM)
        } else {
            CameraPosition.fromLatLngZoom(WorldCenter, WORLD_ZOOM)
        }
    }

    var hasLocationPermission by remember { mutableStateOf(container.locationProvider.hasPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants.values.any { it }
        if (hasLocationPermission && !startsInDemo) viewModel.locateMe()
    }

    LaunchedEffect(Unit) {
        if (startsInDemo) {
            viewModel.onCameraSettled(
                center = GeoPoint(LocalDemoStore.CEBU.lat, LocalDemoStore.CEBU.lng),
                radiusKm = 3.0,
            )
        }
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && !startsInDemo) viewModel.locateMe()
    }

    LaunchedEffect(startLocation) {
        if (startsInDemo) return@LaunchedEffect
        startLocation?.let { point ->
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lng), NEARBY_ZOOM),
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.widenToClosest.collect { widen ->
            val latDelta = widen.radiusKm / 111.32
            val cosLat = kotlin.math.cos(Math.toRadians(widen.center.lat)).coerceAtLeast(0.2)
            val lngDelta = widen.radiusKm / (111.32 * cosLat)
            val bounds = LatLngBounds(
                LatLng(widen.center.lat - latDelta, widen.center.lng - lngDelta),
                LatLng(widen.center.lat + latDelta, widen.center.lng + lngDelta),
            )
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 72))
        }
    }

    // How much ground is visible comes from the projection, but `projection` is a plain getter
    // over the attached map — it never changes on its own, so watching it would report the camera
    // once and then go quiet, freezing the viewport query and the cluster size. Move is the signal;
    // the projection is only read once a move says there is something new to measure.
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.position to cameraPositionState.projection }
            .collect { (_, projection) ->
                val bounds = projection?.visibleRegion?.latLngBounds ?: return@collect
                val center = GeoPoint(bounds.center.latitude, bounds.center.longitude)
                val corner = GeoPoint(bounds.northeast.latitude, bounds.northeast.longitude)
                viewModel.onCameraSettled(center, center.distanceTo(corner) / 1_000)
            }
    }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { error ->
            snackbarHostState.showSnackbar(error.message ?: "Something went wrong")
        }
    }

    val density = LocalDensity.current
    var showNewThreadSheet by remember { mutableStateOf(false) }
    var composePosition by remember { mutableStateOf<GeoPoint?>(null) }
    /** Pre-compose camera — restored when the user cancels. */
    var composeRestoreCamera by remember { mutableStateOf<CameraPosition?>(null) }
    var openThreadId by remember { mutableStateOf<String?>(null) }
    var showAccount by remember { mutableStateOf(false) }
    var previewThread by remember { mutableStateOf<ChatThread?>(null) }
    var previewLatest by remember { mutableStateOf<List<Message>>(emptyList()) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewCluster by remember { mutableStateOf<List<ChatThread>?>(null) }
    var searchLanding by remember { mutableStateOf<PlaceSearchHit?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var emptyNearbyHintDismissed by remember { mutableStateOf(false) }
    /** Thread ids already on the map — seed once so load doesn’t grow every pin. */
    var seenBubbleThreadIds by remember { mutableStateOf(setOf<String>()) }
    var bubbleIdsSeeded by remember { mutableStateOf(false) }
    /** Fresh thread ids that should play the grow-in once. */
    var appearingBubbleIds by remember { mutableStateOf(setOf<String>()) }
    val threadSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accountSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pendingDeepLink by DeepLinkBus.pendingThreadId.collectAsStateWithLifecycle()

    LaunchedEffect(pendingDeepLink) {
        val id = DeepLinkBus.consume() ?: return@LaunchedEffect
        openThreadId = id
    }

    val bubbleThreadIds = remember(state.bubbles) {
        state.bubbles.flatMap { it.items }.map { it.id }.toSet()
    }
    // First snapshot seeds silently; later arrivals get a one-shot grow-in.
    LaunchedEffect(bubbleThreadIds) {
        if (!bubbleIdsSeeded) {
            seenBubbleThreadIds = bubbleThreadIds
            bubbleIdsSeeded = true
            return@LaunchedEffect
        }
        val fresh = bubbleThreadIds - seenBubbleThreadIds
        if (fresh.isEmpty()) return@LaunchedEffect
        seenBubbleThreadIds = seenBubbleThreadIds + fresh
        appearingBubbleIds = appearingBubbleIds + fresh
        // Fire-and-forget so a newer snapshot doesn’t cancel the clear (matches iOS Task).
        scope.launch {
            delay(520)
            appearingBubbleIds = appearingBubbleIds - fresh
        }
    }

    fun dismissCompose(restoreCamera: Boolean = true) {
        if (!showNewThreadSheet && composePosition == null) return
        showNewThreadSheet = false
        val restore = if (restoreCamera) composeRestoreCamera else null
        composeRestoreCamera = null
        scope.launch {
            if (restore != null) {
                cameraPositionState.animate(
                    CameraUpdateFactory.newCameraPosition(restore),
                    durationMs = 340,
                )
            }
            delay(280)
            composePosition = null
        }
    }

    if (showNewThreadSheet) {
        BackHandler { dismissCompose(restoreCamera = true) }
    }

    fun dismissPreview() {
        previewThread = null
        previewCluster = null
        previewLatest = emptyList()
        previewLoading = false
    }

    fun listCluster(threads: List<ChatThread>) {
        previewThread = null
        previewLatest = emptyList()
        previewLoading = false
        previewCluster = threads
    }

    fun beginCompose(at: GeoPoint) {
        if (showNewThreadSheet) return
        dismissPreview()
        if (isSearching) isSearching = false
        composePosition = at
        val current = cameraPositionState.position
        composeRestoreCamera = current
        showNewThreadSheet = true
        // Light zoom-in + pan so the pin sits in the band above the panel.
        scope.launch {
            val peekPx = with(density) { ComposeSheetPeek.toPx() }
            val mapHeightPx = context.resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
            val bounds = cameraPositionState.projection?.visibleRegion?.latLngBounds
            val latDelta = if (bounds != null) {
                (bounds.northeast.latitude - bounds.southwest.latitude).coerceAtLeast(1e-6)
            } else {
                0.02
            }
            val targetZoom = (current.zoom + COMPOSE_ZOOM_DELTA).coerceAtMost(18f)
            // Span shrinks ~2^Δz when zooming in — shift against the zoomed view.
            val zoomedLatDelta = latDelta / Math.pow(2.0, COMPOSE_ZOOM_DELTA.toDouble())
            val latShift = zoomedLatDelta * (peekPx * 0.5) / mapHeightPx
            cameraPositionState.animate(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(at.lat - latShift, at.lng))
                        .zoom(targetZoom)
                        .bearing(current.bearing)
                        .tilt(current.tilt)
                        .build(),
                ),
                durationMs = 380,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MapTalkColors.Base,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    isMyLocationEnabled = hasLocationPermission,
                    isBuildingEnabled = false,
                    isIndoorEnabled = false,
                    // Same near-black ramp as Theme / MapTalkColors (see map_style_dark.json).
                    mapStyleOptions = remember(context) {
                        MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark)
                    },
                ),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                    indoorLevelPickerEnabled = false,
                ),
                contentPadding = padding,
                onMapLongClick = { latLng ->
                    if (showNewThreadSheet || isSearching) return@GoogleMap
                    if (previewThread != null || previewCluster != null) return@GoogleMap
                    beginCompose(
                        at = GeoPoint(latLng.latitude, latLng.longitude),
                    )
                },
            ) {
                state.bubbles.forEach { bubble ->
                    key(bubble.key) {
                        ThreadBubbleMarker(
                            bubble = bubble,
                            playAppear = bubble.items.any { it.id in appearingBubbleIds },
                        )
                    }
                }
                searchLanding?.let { landing ->
                    SearchLandingMarker(
                        title = landing.title,
                        latitude = landing.latitude,
                        longitude = landing.longitude,
                    )
                }
                composePosition?.let { pin ->
                    ComposePinMarker(latitude = pin.lat, longitude = pin.lng)
                }
            }

            LiveMapPulseOverlay(
                bubbles = state.bubbles,
                cameraPositionState = cameraPositionState,
            )

            // Invisible hit targets over bubbles — MarkerComposable draws to a bitmap, so tap and
            // long-press have to live in Compose rather than in the Maps SDK.
            val projection = cameraPositionState.projection
            val density = LocalDensity.current
            // Keeps the fitted group clear of the status pill and the compose button.
            val clusterFitPadding = with(density) { 32.dp.roundToPx() }
            // Recompose hit targets when the camera moves.
            @Suppress("UNUSED_VARIABLE")
            val cameraTick = cameraPositionState.position
            if (projection != null) {
                state.bubbles.forEach { bubble ->
                    key(bubble.key) {
                        val screen = projection.toScreenLocation(
                            LatLng(bubble.position.lat, bubble.position.lng),
                        )
                        val single = bubble.single
                        val hitWidth = if (single != null) 176.dp else 108.dp
                        val hitHeight = 52.dp
                        val hitH = with(density) { hitHeight.toPx() }
                        var pressed by remember { mutableStateOf(false) }

                        Box(
                            modifier = Modifier
                                // The bubble hangs up and to the right of its anchor corner.
                                .offset {
                                    IntOffset(screen.x, (screen.y - hitH).roundToInt())
                                }
                                .size(hitWidth, hitHeight)
                                .graphicsLayer {
                                    val scale = if (pressed) 0.96f else 1f
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 1f)
                                }
                                .bubbleGestures(
                                    onPressedChange = { pressed = it },
                                    onTap = {
                                        if (single != null) {
                                            openThreadId = single.id
                                            return@bubbleGestures
                                        }
                                        // Tap = move the camera. Prefer fitting member bounds;
                                        // if too tight to separate, still step in. List is
                                        // long-press only.
                                        val fit = Viewport.drillFit(
                                            items = bubble.items,
                                            geohashOf = ChatThread::geohash,
                                            positionOf = ChatThread::position,
                                        )
                                        scope.launch {
                                            if (fit != null) {
                                                val box = fit.withRoomForBubbles()
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngBounds(
                                                        LatLngBounds(
                                                            LatLng(box.southwest.lat, box.southwest.lng),
                                                            LatLng(box.northeast.lat, box.northeast.lng),
                                                        ),
                                                        clusterFitPadding,
                                                    ),
                                                )
                                            } else {
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngZoom(
                                                        LatLng(
                                                            bubble.position.lat,
                                                            bubble.position.lng,
                                                        ),
                                                        (cameraPositionState.position.zoom + 2f)
                                                            .coerceAtMost(18f),
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                    onLongPress = {
                                        if (single != null) {
                                            // Load the latest line before the card slides in, so
                                            // the Latest block rides with the peek instead of
                                            // popping in after it.
                                            previewCluster = null
                                            previewLatest = emptyList()
                                            previewLoading = true
                                            scope.launch {
                                                previewLatest =
                                                    viewModel.peekMessages(single.id)
                                                previewLoading = false
                                                previewThread = single
                                            }
                                        } else {
                                            listCluster(bubble.items)
                                        }
                                    },
                                ),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = padding.calculateTopPadding() + 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (isSearching) {
                    PlaceSearchChrome(
                        near = GeoPoint(
                            cameraPositionState.position.target.latitude,
                            cameraPositionState.position.target.longitude,
                        ),
                        onDismiss = { isSearching = false },
                        onPick = { hit ->
                            isSearching = false
                            searchLanding = hit
                            scope.launch {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(hit.latitude, hit.longitude),
                                        hit.zoom,
                                    ),
                                    durationMs = 550,
                                )
                            }
                            scope.launch {
                                delay(SEARCH_LANDING_MS)
                                if (searchLanding?.id == hit.id) searchLanding = null
                            }
                        },
                    )
                } else {
                    StatusRow(
                        isLoading = state.isLoading,
                        isGlobalView = state.isGlobalView,
                        nearbyCount = state.bubbles.sumOf { it.size },
                        text = statusText(state),
                        author = author,
                        photoURL = profile.photoURL,
                        onSearch = { isSearching = true },
                        onOpenAccount = { showAccount = true },
                    )

                    KindFilterStack(
                        kindFilter = kindFilter,
                        onToggle = viewModel::toggleKindFilter,
                        onClear = viewModel::clearKindFilter,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(start = 16.dp, top = 10.dp),
                    )
                }

                val nearbyEmpty = !emptyNearbyHintDismissed &&
                    !state.isLoading &&
                    !state.isGlobalView && state.bubbles.isEmpty() && !state.isFilterHidingAll
                val filterEmpty = !state.isLoading && state.bubbles.isEmpty() && state.isFilterHidingAll

                LaunchedEffect(state.bubbles) {
                    // Once you've seen chats (or dismissed the tip), don't nag on every empty pan.
                    if (state.bubbles.isNotEmpty()) emptyNearbyHintDismissed = true
                }

                if (nearbyEmpty) {
                    HintCard(
                        icon = R.drawable.ic_nearby,
                        title = "Quiet around here",
                        detail = "Long-press the map to start a chat, zoom out to find one, or use the button.",
                        primaryAction = if (findingClosest) "Looking…" else "Find the closest chat",
                        primaryEnabled = !findingClosest,
                        onPrimary = {
                            emptyNearbyHintDismissed = true
                            viewModel.findClosestChat()
                        },
                        secondaryAction = "Start the first chat",
                        onSecondary = {
                            emptyNearbyHintDismissed = true
                            val target = cameraPositionState.position.target
                            beginCompose(
                                at = GeoPoint(target.latitude, target.longitude),
                            )
                        },
                        onDismiss = { emptyNearbyHintDismissed = true },
                        // Stay mounted while composing so it doesn’t pop off and flash the map.
                        modifier = Modifier
                            .padding(top = 18.dp)
                            .graphicsLayer { alpha = if (showNewThreadSheet) 0f else 1f },
                    )
                } else if (filterEmpty) {
                    HintCard(
                        icon = R.drawable.ic_search,
                        title = "Nothing of that kind here",
                        detail = "Other chats are nearby — clear the filter to see them.",
                        primaryAction = "Show all chats",
                        onPrimary = viewModel::clearKindFilter,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = !showNewThreadSheet,
                enter = fadeIn(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(120)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = padding.calculateBottomPadding() + 20.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RoundMapButton(
                        icon = R.drawable.ic_my_location,
                        contentDescription = "Centre on my location",
                        onClick = {
                            if (hasLocationPermission) {
                                viewModel.locateMe()
                            } else {
                                permissionLauncher.launch(LocationProvider.PERMISSIONS)
                            }
                        },
                    )

                    Box(modifier = Modifier.weight(1f))

                    Button(
                        onClick = {
                            val target = cameraPositionState.position.target
                            beginCompose(
                                at = GeoPoint(target.latitude, target.longitude),
                            )
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MapTalkColors.Accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Start a chat here",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }

            // In-place panel — ModalBottomSheet still flashes/resizes the map under a scrim.
            // imePadding rides the panel above the keyboard; tap the map band to clear focus
            // so the sheet settles back down with the IME.
            val composeFocusManager = LocalFocusManager.current
            val composeKeyboard = LocalSoftwareKeyboardController.current
            fun dismissComposeKeyboard() {
                composeFocusManager.clearFocus(force = true)
                composeKeyboard?.hide()
            }
            AnimatedVisibility(
                visible = showNewThreadSheet,
                enter = slideInVertically(
                    animationSpec = tween(320),
                    initialOffsetY = { it },
                ) + fadeIn(animationSpec = tween(220)),
                exit = slideOutVertically(
                    animationSpec = tween(320),
                    targetOffsetY = { it },
                ) + fadeOut(animationSpec = tween(200)),
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            ) {
                val pin = cameraPositionState.position.target
                val pinPosition = composePosition ?: GeoPoint(pin.latitude, pin.longitude)
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { dismissComposeKeyboard() },
                            ),
                    )
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ComposeSheetInset)
                            .padding(bottom = ComposeSheetInset),
                    ) {
                        val sheetHeight = minOf(ComposeSheetPeek, maxHeight)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(sheetHeight)
                                .border(1.dp, MapTalkColors.Hairline, RoundedCornerShape(ComposeSheetCorner)),
                            color = MapTalkColors.Surface,
                            contentColor = MapTalkColors.Text,
                            shape = RoundedCornerShape(ComposeSheetCorner),
                            shadowElevation = 12.dp,
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 10.dp, bottom = 4.dp)
                                        .size(width = 36.dp, height = 5.dp)
                                        .align(Alignment.CenterHorizontally)
                                        .background(MapTalkColors.Faint.copy(alpha = 0.55f), CircleShape)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { dismissComposeKeyboard() },
                                        ),
                                )
                                NewThreadSheet(
                                    position = pinPosition,
                                    onCancel = { dismissCompose(restoreCamera = true) },
                                    onDismissKeyboard = { dismissComposeKeyboard() },
                                    onCreate = { title, body, kind, image ->
                                        dismissCompose(restoreCamera = false)
                                        val id = viewModel.createThread(
                                            title = title,
                                            kind = kind,
                                            position = pinPosition,
                                            author = author,
                                            openingText = body,
                                            openingImage = image,
                                        )
                                        // Stay on the map briefly so the new bubble’s grow-in is visible.
                                        seenBubbleThreadIds = seenBubbleThreadIds + id
                                        appearingBubbleIds = appearingBubbleIds + id
                                        scope.launch {
                                            delay(780)
                                            appearingBubbleIds = appearingBubbleIds - id
                                            openThreadId = id
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (previewThread != null || previewCluster != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { dismissPreview() },
                )
            }

            AnimatedVisibility(
                visible = previewThread != null || previewCluster != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val cardModifier = Modifier.padding(
                    start = 14.dp,
                    end = 14.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                )
                val thread = previewThread
                val cluster = previewCluster
                when {
                    thread != null -> BubblePreviewCard(
                        thread = thread,
                        latest = previewLatest,
                        isLoading = previewLoading,
                        onOpen = {
                            // Clear peek before the sheet so it doesn't float during transition.
                            dismissPreview()
                            openThreadId = thread.id
                        },
                        onDismiss = { dismissPreview() },
                        modifier = cardModifier,
                    )
                    cluster != null -> ClusterPreviewCard(
                        threads = cluster,
                        onOpen = { opened ->
                            dismissPreview()
                            openThreadId = opened.id
                        },
                        onDismiss = { dismissPreview() },
                        modifier = cardModifier,
                    )
                }
            }
        }
    }

    openThreadId?.let { threadId ->
        BackHandler { openThreadId = null }
        ModalBottomSheet(
            onDismissRequest = { openThreadId = null },
            sheetState = threadSheetState,
            containerColor = MapTalkColors.Base,
            contentColor = MapTalkColors.Text,
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            ThreadScreen(
                threadId = threadId,
                author = author,
                onBack = { openThreadId = null },
                onThreadDeleted = { deletedId ->
                    viewModel.removeThread(deletedId)
                    openThreadId = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f),
            )
        }
    }

    if (showAccount) {
        ModalBottomSheet(
            onDismissRequest = { showAccount = false },
            sheetState = accountSheetState,
            containerColor = MapTalkColors.Base,
            contentColor = MapTalkColors.Text,
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            AccountScreen(
                author = author,
                onBack = { showAccount = false },
                onSessionEnded = { showAccount = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
            )
        }
    }
}

private fun statusText(state: MapUiState): String = when {
    state.isLoading -> "Looking around\u2026"
    state.isFilterHidingAll ->
        if (state.isGlobalView) "No matches worldwide" else "No matches nearby"
    state.isGlobalView -> "Busiest chats worldwide"
    else -> when (val count = state.bubbles.sumOf { it.size }) {
        0 -> "No chats here yet"
        1 -> "1 chat nearby"
        else -> "$count chats nearby"
    }
}

/**
 * Tap to open, long-press to peek — without stealing the map's pinch. A second finger anywhere
 * abandons the gesture, and nothing is consumed until one of the two actually fires, so pan and
 * zoom keep working when a gesture happens to start on top of a bubble. Mirrors
 * `BubbleGestureCatcher` on iOS, which has the same job with UIKit recognizers.
 */
private fun Modifier.bubbleGestures(
    onPressedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(onTap, onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPressedChange(true)

        var longPressed = false
        var abandoned = false
        var lifted = false
        try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.size > 1) {
                        abandoned = true
                        return@withTimeout
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        lifted = true
                        return@withTimeout
                    }
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        abandoned = true
                        return@withTimeout
                    }
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            longPressed = true
        }

        onPressedChange(false)
        if (abandoned) return@awaitEachGesture

        if (longPressed) {
            onLongPress()
        } else {
            onTap()
        }

        // A long press fires with the finger still down, so swallow the rest of that gesture and
        // keep the map from panning under it. A tap is already over — waiting for more events here
        // would eat the *next* gesture instead of this one.
        while (!lifted) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

@Composable
private fun StatusRow(
    isLoading: Boolean,
    isGlobalView: Boolean,
    nearbyCount: Int,
    text: String,
    author: Author,
    photoURL: String?,
    onSearch: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val isActive = !isLoading && !isGlobalView && nearbyCount > 0
    var countFlash by remember { mutableStateOf(false) }
    var lastCount by remember { mutableStateOf(-1) }

    LaunchedEffect(nearbyCount, isGlobalView, isLoading) {
        if (isGlobalView || isLoading) {
            lastCount = nearbyCount
            return@LaunchedEffect
        }
        if (lastCount >= 0 && nearbyCount != lastCount) {
            countFlash = true
            delay(500)
            countFlash = false
        }
        lastCount = nearbyCount
    }

    val iconPulse = rememberInfiniteTransition(label = "nearbyIcon")
    val iconScale by iconPulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.14f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "nearbyScale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MapTalkColors.Surface.copy(alpha = 0.92f),
            contentColor = MapTalkColors.Text,
            border = BorderStroke(
                1.dp,
                if (countFlash) MapTalkColors.Accent.copy(alpha = 0.55f) else MapTalkColors.Hairline,
            ),
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 1.5.dp,
                        color = MapTalkColors.Subtle,
                        modifier = Modifier.size(12.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            if (isGlobalView) R.drawable.ic_globe else R.drawable.ic_nearby,
                        ),
                        contentDescription = null,
                        tint = MapTalkColors.Accent,
                        modifier = Modifier
                            .size(13.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                                alpha = if (isActive) 0.75f + 0.25f * ((iconScale - 1f) / 0.14f) else 1f
                            },
                    )
                }
                Text(text = text, style = MaterialTheme.typography.labelLarge)
            }
        }

        RoundMapButton(
            icon = R.drawable.ic_search,
            contentDescription = "Search places",
            onClick = onSearch,
            size = 36.dp,
            iconSize = 16.dp,
        )

        // Map account button — photo/initials with a quiet pad, no neon.
        Surface(
            onClick = onOpenAccount,
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MapTalkColors.Surface.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, MapTalkColors.Hairline),
            shadowElevation = 8.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                InitialAvatar(
                    name = author.displayName,
                    seed = author.uid,
                    size = 32.dp,
                    photoURL = photoURL,
                )
            }
        }
    }
}

/**
 * Kind filter: stacked glyphs like a cluster pin; tap spreads them out to toggle. The map stays
 * interactive while it is open — collapse with the trailing ×.
 */
@Composable
private fun KindFilterStack(
    kindFilter: Set<ThreadKind>,
    onToggle: (ThreadKind) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val filterActive = kindFilter.isNotEmpty()
    val kinds = ThreadKind.entries
    // Collapsed the glyphs overlap like a cluster pin; expanded they spread out far enough to tap.
    val step by animateDpAsState(
        targetValue = if (expanded) GLYPH_SIZE + 10.dp else 20.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "kindFilterStep",
    )

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MapTalkColors.Surface.copy(alpha = 0.92f),
        border = BorderStroke(
            if (filterActive) 1.5.dp else 1.dp,
            if (filterActive) MapTalkColors.Accent.copy(alpha = 0.55f) else MapTalkColors.Hairline,
        ),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(step * (kinds.size - 1) + GLYPH_SIZE)
                    .height(GLYPH_SIZE),
            ) {
                kinds.forEachIndexed { index, kind ->
                    val selected = kind in kindFilter
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(start = step * index)
                            .size(GLYPH_SIZE)
                            .graphicsLayer {
                                alpha = if (expanded && filterActive && !selected) 0.45f else 1f
                            }
                            .background(
                                kind.tint.copy(
                                    alpha = when {
                                        !filterActive -> 0.22f
                                        selected -> 0.28f
                                        else -> 0.12f
                                    },
                                ),
                                CircleShape,
                            )
                            .border(
                                1.5.dp,
                                if (filterActive && selected) {
                                    kind.tint.copy(alpha = 0.75f)
                                } else {
                                    MapTalkColors.Surface
                                },
                                CircleShape,
                            )
                            .clickable {
                                if (expanded) onToggle(kind) else expanded = true
                            },
                    ) {
                        Text(text = kind.glyph, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (expanded) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(GLYPH_SIZE)
                        .background(MapTalkColors.Raised, CircleShape)
                        .border(1.dp, MapTalkColors.Hairline, CircleShape)
                        .clickable {
                            if (filterActive) onClear()
                            expanded = false
                        },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = if (filterActive) {
                            "Clear filter and collapse"
                        } else {
                            "Collapse"
                        },
                        tint = MapTalkColors.Subtle,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/** Search chrome: type a place, tap a result, the camera flies there. Not a chat-text search. */
@Composable
private fun PlaceSearchChrome(
    near: GeoPoint,
    onDismiss: () -> Unit,
    onPick: (PlaceSearchHit) -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceSearchHit>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var didComplete by remember { mutableStateOf(false) }

    BackHandler { onDismiss() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            results = emptyList()
            isLoading = false
            didComplete = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        isLoading = true
        results = PlaceSearch.search(context, trimmed, near)
        isLoading = false
        didComplete = true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                color = MapTalkColors.Surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, MapTalkColors.Hairline),
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = MapTalkColors.Subtle,
                        modifier = Modifier.size(14.dp),
                    )
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search a place", color = MapTalkColors.Faint) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MapTalkColors.Accent,
                            focusedTextColor = MapTalkColors.Text,
                            unfocusedTextColor = MapTalkColors.Text,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    )
                    if (isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 1.5.dp,
                            color = MapTalkColors.Subtle,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MapTalkColors.Accent)
            }
        }

        if (results.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MapTalkColors.Surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, MapTalkColors.Hairline),
                shadowElevation = 10.dp,
            ) {
                Column {
                    results.forEachIndexed { index, hit ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MapTalkColors.Hairline,
                                modifier = Modifier.padding(start = 44.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    keyboard?.hide()
                                    onPick(hit)
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_pin),
                                contentDescription = null,
                                tint = MapTalkColors.Accent,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = hit.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MapTalkColors.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                hit.subtitle?.let { subtitle ->
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MapTalkColors.Faint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (didComplete && !isLoading && query.isNotBlank()) {
            Text(
                text = "No places matched",
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Faint,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

@Composable
private fun HintCard(
    icon: Int,
    title: String,
    detail: String,
    primaryAction: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondaryAction: String? = null,
    onSecondary: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.widthIn(max = 280.dp),
        shape = RoundedCornerShape(20.dp),
        color = MapTalkColors.Surface.copy(alpha = 0.92f),
        contentColor = MapTalkColors.Text,
        border = BorderStroke(1.dp, MapTalkColors.Hairline.copy(alpha = 0.85f)),
        shadowElevation = 8.dp,
    ) {
        Box {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MapTalkColors.Accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MapTalkColors.Accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MapTalkColors.Subtle,
                        textAlign = TextAlign.Center,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onPrimary,
                        enabled = primaryEnabled,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MapTalkColors.Accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(primaryAction)
                    }
                    if (secondaryAction != null && onSecondary != null) {
                        TextButton(
                            onClick = onSecondary,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MapTalkColors.Accent,
                            ),
                        ) {
                            Text(secondaryAction)
                        }
                    }
                }
            }
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Dismiss",
                        tint = MapTalkColors.Faint,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundMapButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    iconSize: Dp = 20.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MapTalkColors.Surface.copy(alpha = 0.92f),
        contentColor = MapTalkColors.Text,
        border = BorderStroke(1.dp, MapTalkColors.Hairline),
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

