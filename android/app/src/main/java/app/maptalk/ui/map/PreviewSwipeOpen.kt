package app.maptalk.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.maptalk.ui.theme.MapTalkColors
import kotlin.math.roundToInt

private const val OpenFlingVelocity = -1200f

/** Vertical drag that fires [onOpen] when the user swipes the peek upward. */
@Composable
fun Modifier.swipeUpToOpen(onOpen: () -> Unit): Modifier {
    val density = LocalDensity.current
    val pullThreshold = with(density) { 64.dp.toPx() }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val state = rememberDraggableState { delta ->
        offsetY = (offsetY + delta).coerceAtMost(0f)
    }
    return this
        .offset { IntOffset(0, offsetY.roundToInt()) }
        .alpha((1f + offsetY / 280f).coerceIn(0.55f, 1f))
        .draggable(
            state = state,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity ->
                if (offsetY < -pullThreshold || velocity < OpenFlingVelocity) {
                    onOpen()
                } else {
                    offsetY = 0f
                }
            },
        )
}

@Composable
fun PreviewGrabber(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .background(MapTalkColors.Hairline, RoundedCornerShape(2.dp)),
        )
    }
}
