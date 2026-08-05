package app.maptalk.ui.map

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt
import app.maptalk.data.model.Message
import app.maptalk.data.model.ChatThread
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.core.DeepLinkBus
import app.maptalk.data.LocalDemoStore
import app.maptalk.data.model.Author
import app.maptalk.geo.GeoPoint
import app.maptalk.location.LocationProvider
import app.maptalk.ui.settings.SettingsScreen
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.thread.ThreadScreen
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WorldCenter = LatLng(20.0, 10.0)
private val CebuCenter = LatLng(LocalDemoStore.CEBU.lat, LocalDemoStore.CEBU.lng)
private const val WORLD_ZOOM = 2.2f
private const val NEARBY_ZOOM = 14f
private const val CLUSTER_ZOOM_STEP = 3f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MapScreen(
    author: Author,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startLocation by viewModel.startLocation.collectAsStateWithLifecycle()

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

    // The projection is what tells us how much ground is visible, and it only exists once the
    // map has been laid out, so the camera is reported from there rather than from the zoom.
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.projection }
            .collect { projection ->
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

    var showNewThreadSheet by remember { mutableStateOf(false) }
    var openThreadId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var previewThread by remember { mutableStateOf<ChatThread?>(null) }
    var previewLatest by remember { mutableStateOf<Message?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewCluster by remember { mutableStateOf<List<ChatThread>?>(null) }
    val newThreadSheetState = rememberModalBottomSheetState()
    val threadSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pendingDeepLink by DeepLinkBus.pendingThreadId.collectAsStateWithLifecycle()

    LaunchedEffect(pendingDeepLink) {
        val id = DeepLinkBus.consume() ?: return@LaunchedEffect
        openThreadId = id
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MapTalkColors.Base,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewThreadSheet = true },
                containerColor = MapTalkColors.Accent,
                contentColor = Color.White,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Start a chat here", style = MaterialTheme.typography.labelLarge) },
            )
        },
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
            ) {
                state.bubbles.forEach { bubble ->
                    key(bubble.key) {
                        ThreadBubbleMarker(
                            bubble = bubble,
                            onClick = {
                                val single = bubble.single
                                if (single != null) {
                                    // Fallback if the Compose hit overlay misses the bitmap.
                                    openThreadId = single.id
                                } else {
                                    scope.launch {
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngZoom(
                                                LatLng(bubble.position.lat, bubble.position.lng),
                                                cameraPositionState.position.zoom + CLUSTER_ZOOM_STEP,
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            LiveMapPulseOverlay(
                bubbles = state.bubbles,
                cameraPositionState = cameraPositionState,
            )

            // Invisible hit targets over bubbles — MarkerComposable is a bitmap, so
            // long-press has to live in Compose, not the Maps SDK.
            val projection = cameraPositionState.projection
            val density = LocalDensity.current
            // Recompose hit targets when the camera moves.
            @Suppress("UNUSED_VARIABLE")
            val cameraTick = cameraPositionState.position
            if (projection != null) {
                state.bubbles.forEach { bubble ->
                    val screen = projection.toScreenLocation(
                        LatLng(bubble.position.lat, bubble.position.lng),
                    )
                    val single = bubble.single
                    val hitW = with(density) { if (single != null) 176.dp.toPx() else 108.dp.toPx() }
                    val hitH = with(density) { 52.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (screen.x - hitW / 2f).roundToInt(),
                                    (screen.y - hitH).roundToInt(),
                                )
                            }
                            .size(if (single != null) 176.dp else 108.dp, 52.dp)
                            .combinedClickable(
                                onClick = {
                                    if (single != null) {
                                        openThreadId = single.id
                                    } else {
                                        scope.launch {
                                            cameraPositionState.animate(
                                                CameraUpdateFactory.newLatLngZoom(
                                                    LatLng(bubble.position.lat, bubble.position.lng),
                                                    cameraPositionState.position.zoom + CLUSTER_ZOOM_STEP,
                                                ),
                                            )
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (single != null) {
                                        previewCluster = null
                                        previewThread = single
                                        previewLatest = null
                                        previewLoading = true
                                        scope.launch {
                                            previewLatest = viewModel.peekMessages(single.id).lastOrNull()
                                            previewLoading = false
                                        }
                                    } else {
                                        previewThread = null
                                        previewLatest = null
                                        previewLoading = false
                                        previewCluster = bubble.items
                                    }
                                },
                            ),
                    )
                }
            }

            // Marks the spot a new chat would be pinned to.
            Crosshair(modifier = Modifier.align(Alignment.Center))

            StatusPill(
                isLoading = state.isLoading,
                isGlobalView = state.isGlobalView,
                nearbyCount = state.bubbles.sumOf { it.size },
                text = when {
                    state.isLoading -> "Looking around\u2026"
                    state.isGlobalView -> "Busiest chats worldwide"
                    else -> when (val count = state.bubbles.sumOf { it.size }) {
                        0 -> "No chats here yet"
                        1 -> "1 chat nearby"
                        else -> "$count chats nearby"
                    }
                },
                onOpenSettings = { showSettings = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = padding.calculateTopPadding() + 12.dp),
            )

            val nearbyEmpty = !state.isLoading && !state.isGlobalView && state.bubbles.isEmpty()
            if (nearbyEmpty) {
                EmptyNearbyCard(
                    onStartChat = { showNewThreadSheet = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = padding.calculateTopPadding() + 64.dp),
                )
            }

            LocateButton(
                onClick = {
                    if (hasLocationPermission) {
                        viewModel.locateMe()
                    } else {
                        permissionLauncher.launch(LocationProvider.PERMISSIONS)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = padding.calculateBottomPadding() + 20.dp),
            )

            if (previewThread != null || previewCluster != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable {
                            previewThread = null
                            previewLatest = null
                            previewCluster = null
                        },
                )
                val thread = previewThread
                val cluster = previewCluster
                when {
                    thread != null -> {
                        BubblePreviewCard(
                            thread = thread,
                            latest = previewLatest,
                            isLoading = previewLoading,
                            onOpen = {
                                // Clear peek before the sheet so it doesn't float during transition.
                                previewThread = null
                                previewLatest = null
                                previewCluster = null
                                openThreadId = thread.id
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    start = 14.dp,
                                    end = 14.dp,
                                    bottom = padding.calculateBottomPadding() + 16.dp,
                                ),
                        )
                    }
                    cluster != null -> {
                        ClusterPreviewCard(
                            threads = cluster,
                            onOpen = { opened ->
                                previewThread = null
                                previewLatest = null
                                previewCluster = null
                                openThreadId = opened.id
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    start = 14.dp,
                                    end = 14.dp,
                                    bottom = padding.calculateBottomPadding() + 16.dp,
                                ),
                        )
                    }
                }
            }
        }
    }

    if (showNewThreadSheet) {
        val pin = cameraPositionState.position.target
        val pinPosition = GeoPoint(pin.latitude, pin.longitude)
        ModalBottomSheet(
            onDismissRequest = { showNewThreadSheet = false },
            sheetState = newThreadSheetState,
            containerColor = MapTalkColors.Surface,
            contentColor = MapTalkColors.Text,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            NewThreadSheet(
                position = pinPosition,
                onCreate = { title, kind ->
                    showNewThreadSheet = false
                    openThreadId = viewModel.createThread(title, kind, pinPosition, author)
                },
            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f),
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = settingsSheetState,
            containerColor = MapTalkColors.Base,
            contentColor = MapTalkColors.Text,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            SettingsScreen(
                author = author,
                onBack = { showSettings = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
            )
        }
    }
}

@Composable
private fun StatusPill(
    isLoading: Boolean,
    isGlobalView: Boolean,
    nearbyCount: Int,
    text: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier,
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

        Surface(
            onClick = onOpenSettings,
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MapTalkColors.Surface.copy(alpha = 0.92f),
            contentColor = MapTalkColors.Text,
            border = BorderStroke(1.dp, MapTalkColors.Hairline),
            shadowElevation = 8.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "Settings",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyNearbyCard(onStartChat: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 300.dp),
        shape = RoundedCornerShape(18.dp),
        color = MapTalkColors.Surface.copy(alpha = 0.94f),
        contentColor = MapTalkColors.Text,
        border = BorderStroke(1.dp, MapTalkColors.Hairline),
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Nothing pinned near here",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Be the first — drop a chat at the crosshair.",
                style = MaterialTheme.typography.bodyMedium,
                color = MapTalkColors.Subtle,
            )
            Button(
                onClick = onStartChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MapTalkColors.Accent,
                    contentColor = Color.White,
                ),
            ) {
                Text("Start a chat here")
            }
        }
    }
}

@Composable
private fun LocateButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(46.dp),
        shape = CircleShape,
        color = MapTalkColors.Surface.copy(alpha = 0.92f),
        contentColor = MapTalkColors.Text,
        border = BorderStroke(1.dp, MapTalkColors.Hairline),
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_my_location),
                contentDescription = "Centre on my location",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Crosshair(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = MapTalkColors.Accent.copy(alpha = 0.15f),
            border = BorderStroke(2.dp, MapTalkColors.Accent),
            content = {},
        )
        Surface(
            modifier = Modifier.size(5.dp),
            shape = CircleShape,
            color = MapTalkColors.Accent,
            content = {},
        )
    }
}
