package app.maptalk.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maptalk.core.LiveNow
import app.maptalk.data.model.ChatThread
import app.maptalk.geo.GeoCluster
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.tint
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberUpdatedMarkerState
import java.time.Instant

/**
 * A marker drawn as a chat bubble: title when alone, stacked kind glyphs when clustered.
 */
@Composable
@GoogleMapComposable
fun ThreadBubbleMarker(
    bubble: GeoCluster<ChatThread>,
    onClick: () -> Unit,
) {
    val thread = bubble.single
    MarkerComposable(
        bubble.key,
        bubble.size,
        thread?.title.orEmpty(),
        state = rememberUpdatedMarkerState(
            position = LatLng(bubble.position.lat, bubble.position.lng),
        ),
        anchor = Offset(0.5f, 1f),
        onClick = {
            onClick()
            true
        },
    ) {
        if (thread == null) {
            ClusterBubble(threads = bubble.items)
        } else {
            ThreadBubble(thread = thread)
        }
    }
}

@Composable
private fun ThreadBubble(thread: ChatThread) {
    val live = LiveNow.isLive(thread.lastMessageAt)
    Surface(
        shape = BubbleShape,
        color = MapTalkColors.Surface,
        contentColor = MapTalkColors.Text,
        shadowElevation = if (live) 10.dp else 6.dp,
        modifier = Modifier.border(
            if (live) 1.5.dp else 1.dp,
            if (live) MapTalkColors.Accent.copy(alpha = 0.7f) else MapTalkColors.Hairline,
            BubbleShape,
        ),
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 200.dp)
                .padding(start = 10.dp, end = 9.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (live) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MapTalkColors.Accent, CircleShape),
                )
            }
            Text(text = thread.kind.glyph, style = MaterialTheme.typography.labelSmall)
            Text(
                text = thread.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (thread.messageCount > 0) {
                Surface(shape = CircleShape, color = thread.kind.tint.copy(alpha = 0.16f)) {
                    Text(
                        text = thread.messageCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = thread.kind.tint,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = if (live) "Live" else relativeTime(thread.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = if (live) MapTalkColors.Accent else MapTalkColors.Faint,
            )
        }
    }
}

/** Cluster pin: stack of kind glyphs (hottest first), +N if more than three. */
@Composable
private fun ClusterBubble(threads: List<ChatThread>) {
    val sorted = remember(threads) {
        threads.sortedByDescending { it.lastMessageAt ?: Instant.EPOCH }
    }
    val visible = sorted.take(MaxClusterGlyphs)
    val overflow = (sorted.size - visible.size).coerceAtLeast(0)
    val anyLive = sorted.any { LiveNow.isLive(it.lastMessageAt) }

    Surface(
        shape = BubbleShape,
        color = MapTalkColors.Surface,
        contentColor = MapTalkColors.Text,
        shadowElevation = if (anyLive) 10.dp else 6.dp,
        modifier = Modifier.border(
            if (anyLive) 1.5.dp else 1.dp,
            if (anyLive) MapTalkColors.Accent.copy(alpha = 0.7f) else MapTalkColors.Hairline,
            BubbleShape,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(
                    width = (28 + (visible.size - 1).coerceAtLeast(0) * 20).dp,
                    height = 28.dp,
                ),
            ) {
                visible.forEachIndexed { index, thread ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(start = (index * 20).dp)
                            .size(28.dp)
                            .background(thread.kind.tint.copy(alpha = 0.22f), CircleShape)
                            .border(1.5.dp, MapTalkColors.Surface, CircleShape),
                    ) {
                        Text(
                            text = thread.kind.glyph,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (overflow > 0) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else if (anyLive) {
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(7.dp)
                        .background(MapTalkColors.Accent, CircleShape),
                )
            }
        }
    }
}

private const val MaxClusterGlyphs = 3

private val BubbleShape = MapTalkShapes.bubble(radius = 14.dp)
