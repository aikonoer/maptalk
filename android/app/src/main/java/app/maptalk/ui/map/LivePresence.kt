package app.maptalk.ui.map

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.maptalk.ui.theme.MapTalkColors

/**
 * Breathing Live marker — activity as presence, not a count or ellipsis.
 * Mirrors `LiveDot` in `ios/MapTalk/Core/LivePresence.swift`.
 */
@Composable
fun LiveDot(
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "liveDot")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_550),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDotBreathe",
    )
    val density = LocalDensity.current
    val basePx = with(density) { size.toPx() }
    // Room for the expanding ring (≈2.35×) without clipping.
    val canvasSize = size * 2.5f
    val accent = MapTalkColors.Accent

    Canvas(
        modifier = modifier
            .size(canvasSize)
            .clearAndSetSemantics { },
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val ringScale = 1f + breathe * 1.35f
        val ringAlpha = 0.9f * (1f - breathe)
        drawCircle(
            color = accent.copy(alpha = ringAlpha),
            radius = (basePx / 2f) * ringScale,
            center = center,
            style = Stroke(width = with(density) { 1.25.dp.toPx() }),
        )
        val coreScale = 0.9f + breathe * 0.16f
        val coreAlpha = 0.7f + breathe * 0.3f
        drawCircle(
            color = accent.copy(alpha = coreAlpha),
            radius = (basePx / 2f) * coreScale,
            center = center,
        )
    }
}
