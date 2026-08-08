package app.maptalk.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.core.LiveNow
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.MessageKind
import app.maptalk.geo.GeoCluster
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.tint
import coil.compose.AsyncImage
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberUpdatedMarkerState
import java.time.Instant

/**
 * A marker drawn as a chat bubble: title when alone, stacked kind glyphs when clustered.
 *
 * Tap and long-press are handled by the Compose hit targets in `MapScreen`, because a
 * [MarkerComposable] is rasterised to a bitmap and the Maps SDK only offers a plain click on it.
 * Returning true from [MarkerComposable]'s own click keeps the SDK from recentring the camera if
 * one ever slips through.
 */
@Composable
@GoogleMapComposable
fun ThreadBubbleMarker(bubble: GeoCluster<ChatThread>) {
    val thread = bubble.single
    MarkerComposable(
        bubble.key,
        bubble.size,
        thread?.title.orEmpty(),
        state = rememberUpdatedMarkerState(
            position = LatLng(bubble.position.lat, bubble.position.lng),
        ),
        // The sharpened bottom-start corner is the point being talked about, so that corner —
        // not the middle of the bubble — sits on the coordinate.
        anchor = Offset(0f, 1f),
        onClick = { true },
    ) {
        if (thread == null) {
            ClusterBubble(threads = bubble.items)
        } else {
            ThreadBubble(thread = thread)
        }
    }
}

/** Pin for the new-chat compose spot — stays glued to the coordinate (no screen-overlay lag). */
@Composable
@GoogleMapComposable
fun ComposePinMarker(latitude: Double, longitude: Double) {
    MarkerComposable(
        "compose-pin",
        state = rememberUpdatedMarkerState(position = LatLng(latitude, longitude)),
        anchor = Offset(0.5f, 0.5f),
        zIndex = 2f,
        onClick = { true },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp)) {
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
}

/** Where a place search landed: a labelled pin that fades out on its own after a moment. */
@Composable
@GoogleMapComposable
fun SearchLandingMarker(title: String, latitude: Double, longitude: Double) {
    MarkerComposable(
        title,
        state = rememberUpdatedMarkerState(position = LatLng(latitude, longitude)),
        anchor = Offset(0.5f, 1f),
        onClick = { true },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = MapTalkColors.Surface,
                contentColor = MapTalkColors.Text,
                shadowElevation = 8.dp,
                modifier = Modifier.border(1.dp, MapTalkColors.Hairline, CircleShape),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 180.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(16.dp)
                    .background(MapTalkColors.Accent, CircleShape)
                    .border(2.dp, MapTalkColors.Surface, CircleShape),
            )
        }
    }
}

@Composable
private fun ThreadBubble(thread: ChatThread) {
    val live = LiveNow.isLive(thread.lastMessageAt)
    val mediaPreview = thread.hasMapMediaPreview
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
                .widthIn(max = if (mediaPreview) 180.dp else 200.dp)
                .padding(start = 10.dp, end = 9.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (live) {
                LiveDot(size = 8.dp)
            }
            if (mediaPreview) {
                MapBubbleMediaThumb(
                    path = thread.lastMediaPath!!,
                    isVideo = thread.lastMediaKind == MessageKind.VIDEO,
                )
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Text(text = thread.kind.glyph, style = MaterialTheme.typography.labelSmall)
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = if (live) "Live" else relativeTime(thread.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) MapTalkColors.Accent else MapTalkColors.Faint,
                )
            }
        }
    }
}

@Composable
private fun MapBubbleMediaThumb(path: String, isVideo: Boolean) {
    val context = LocalContext.current
    val model = remember(path) {
        when {
            path.startsWith("http") -> path
            else -> context.appContainer.resolveLocalMedia(path)
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MapTalkColors.Raised)
            .border(1.dp, MapTalkColors.Hairline, RoundedCornerShape(6.dp)),
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Icon(
                painter = painterResource(
                    if (isVideo) R.drawable.ic_video else R.drawable.ic_photo,
                ),
                contentDescription = null,
                tint = MapTalkColors.Faint,
                modifier = Modifier.size(12.dp),
            )
        }
        if (isVideo) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(14.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(8.dp),
                )
            }
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
                LiveDot(
                    size = 7.dp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private const val MaxClusterGlyphs = 3

private val BubbleShape = MapTalkShapes.bubble(radius = 14.dp, tailRadius = 2.dp)
