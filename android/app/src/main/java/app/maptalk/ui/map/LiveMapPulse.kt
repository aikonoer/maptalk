package app.maptalk.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.maptalk.data.model.ChatThread
import app.maptalk.geo.GeoCluster
import app.maptalk.ui.theme.MapTalkColors
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * One-shot flash when a pin gets a fresh message.
 *
 * Drawn in Compose over the map (not inside [MarkerComposable]) because markers
 * are bitmaps and cannot run continuous animation. Continuous radar rings were
 * dropped — they fought the crosshair; Live is carried by the border instead.
 */
@Composable
fun LiveMapPulseOverlay(
    bubbles: List<GeoCluster<ChatThread>>,
    cameraPositionState: CameraPositionState,
) {
    val projection = cameraPositionState.projection ?: return
    @Suppress("UNUSED_VARIABLE")
    val cameraTick = cameraPositionState.position
    val density = LocalDensity.current
    val ringSize = with(density) { 110.dp.toPx() }

    val lastSeen = remember { mutableStateMapOf<String, Instant?>() }
    var flashes by remember { mutableStateOf<Map<String, Instant>>(emptyMap()) }

    LaunchedEffect(bubbles) {
        val nextFlashes = flashes.toMutableMap()
        bubbles.forEach { bubble ->
            val thread = bubble.single ?: return@forEach
            val previous = lastSeen[thread.id]
            val current = thread.lastMessageAt
            if (previous != null && current != null && current.isAfter(previous)) {
                nextFlashes[thread.id] = Instant.now()
            }
            lastSeen[thread.id] = current
        }
        val cutoff = Instant.now().minusMillis(650)
        nextFlashes.entries.removeIf { it.value.isBefore(cutoff) }
        flashes = nextFlashes
    }

    LaunchedEffect(flashes.keys.toList()) {
        if (flashes.isEmpty()) return@LaunchedEffect
        delay(700)
        val cutoff = Instant.now().minusMillis(650)
        flashes = flashes.filterValues { it.isAfter(cutoff) }
    }

    // No fillMaxSize — a full-screen Box would steal map pans.
    Box {
        bubbles.forEach { bubble ->
            val thread = bubble.single ?: return@forEach
            if (!flashes.containsKey(thread.id)) return@forEach

            val screen = projection.toScreenLocation(
                LatLng(bubble.position.lat, bubble.position.lng),
            )
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (screen.x - ringSize / 2f).roundToInt(),
                            (screen.y - ringSize / 2f).roundToInt(),
                        )
                    }
                    .size(110.dp),
            ) {
                FreshMessageFlash()
            }
        }
    }
}

@Composable
private fun FreshMessageFlash() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 520, easing = LinearEasing))
    }
    val accent = MapTalkColors.Accent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = progress.value
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * (0.2f + 0.55f * t)
        drawCircle(
            color = accent.copy(alpha = (1f - t) * 0.55f),
            radius = radius,
            center = center,
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = accent.copy(alpha = (1f - t) * 0.2f),
            radius = radius * 0.55f,
            center = center,
        )
    }
}
