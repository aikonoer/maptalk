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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

/**
 * A marker drawn as a chat bubble: the thread's title when it stands alone, a count when
 * several threads share a geohash cell at this zoom.
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
            ClusterBubble(count = bubble.size)
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

@Composable
private fun ClusterBubble(count: Int) {
    Surface(
        shape = BubbleShape,
        color = MapTalkColors.Accent,
        contentColor = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), BubbleShape),
    ) {
        Text(
            text = if (count == 1) "1 chat" else "$count chats",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private val BubbleShape = MapTalkShapes.bubble(radius = 14.dp)
