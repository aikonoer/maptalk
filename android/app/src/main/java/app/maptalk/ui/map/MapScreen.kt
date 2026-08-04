package app.maptalk.ui.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.data.LocalDemoStore
import app.maptalk.data.model.Author
import app.maptalk.geo.GeoPoint
import app.maptalk.location.LocationProvider
import app.maptalk.ui.theme.MapTalkColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

private val WorldCenter = LatLng(20.0, 10.0)
private val CebuCenter = LatLng(LocalDemoStore.CEBU.lat, LocalDemoStore.CEBU.lng)
private const val WORLD_ZOOM = 2.2f
private const val NEARBY_ZOOM = 14f
private const val CLUSTER_ZOOM_STEP = 3f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    author: Author,
    onOpenThread: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startLocation by viewModel.startLocation.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val startsInDemo = container.isLocalDemo
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
    val sheetState = rememberModalBottomSheetState()

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
                    // Keeps the map on the same near-black ramp as the rest of the app.
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
                                    onOpenThread(single.id)
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

            // Marks the spot a new chat would be pinned to.
            Crosshair(modifier = Modifier.align(Alignment.Center))

            StatusPill(
                isLoading = state.isLoading,
                isGlobalView = state.isGlobalView,
                text = when {
                    state.isLoading -> "Looking around\u2026"
                    state.isGlobalView -> "Busiest chats worldwide"
                    else -> when (val count = state.bubbles.sumOf { it.size }) {
                        0 -> "No chats here yet"
                        1 -> "1 chat nearby"
                        else -> "$count chats nearby"
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = padding.calculateTopPadding() + 12.dp),
            )

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
        }
    }

    if (showNewThreadSheet) {
        val pin = cameraPositionState.position.target
        val pinPosition = GeoPoint(pin.latitude, pin.longitude)
        ModalBottomSheet(
            onDismissRequest = { showNewThreadSheet = false },
            sheetState = sheetState,
            containerColor = MapTalkColors.Surface,
            contentColor = MapTalkColors.Text,
        ) {
            NewThreadSheet(
                position = pinPosition,
                onCreate = { title, kind ->
                    showNewThreadSheet = false
                    onOpenThread(viewModel.createThread(title, kind, pinPosition, author))
                },
            )
        }
    }
}

@Composable
private fun StatusPill(
    isLoading: Boolean,
    isGlobalView: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MapTalkColors.Surface.copy(alpha = 0.92f),
        contentColor = MapTalkColors.Text,
        border = BorderStroke(1.dp, MapTalkColors.Hairline),
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
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
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
