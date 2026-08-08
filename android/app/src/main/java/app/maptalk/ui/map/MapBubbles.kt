package app.maptalk.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.core.ActivityHeat
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.MessageKind
import app.maptalk.geo.GeoCluster
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.tint
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberUpdatedMarkerState
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A marker drawn as a chat bubble: title when alone, stacked kind glyphs when clustered.
 *
 * Tap and long-press are handled by the Compose hit targets in `MapScreen`, because a
 * [MarkerComposable] is rasterised to a bitmap and the Maps SDK only offers a plain click on it.
 * Returning true from [MarkerComposable]'s own click keeps the SDK from recentring the camera if
 * one ever slips through.
 *
 * [playAppear] grows the bubble in from the anchor corner. Tip fields and thumb readiness are
 * keyed into [MarkerComposable] so the bitmap refreshes when activity or media changes
 * (markers cannot animate or load Coil asynchronously otherwise).
 */
@Composable
@GoogleMapComposable
fun ThreadBubbleMarker(
    bubble: GeoCluster<ChatThread>,
    playAppear: Boolean = false,
) {
    val thread = bubble.single
    val context = LocalContext.current
    val tipEpoch = bubble.items.maxOfOrNull { it.lastMessageAt?.toEpochMilli() ?: 0L } ?: 0L
    val mediaPath = thread?.lastMediaPath.orEmpty()
    val mediaKindId = thread?.lastMediaKind?.id.orEmpty()
    val isVideo = thread?.lastMediaKind == MessageKind.VIDEO
    val needsThumb = thread?.hasMapMediaPreview == true

    // Load outside MarkerComposable — maps-compose one-shots the content into a bitmap,
    // so AsyncImage never finishes painting inside the marker.
    val thumbBitmap by produceState<Bitmap?>(
        initialValue = null,
        mediaPath,
        mediaKindId,
        needsThumb,
    ) {
        value = if (needsThumb && mediaPath.isNotEmpty()) {
            loadMapBubbleThumb(context, mediaPath, isVideo)
        } else {
            null
        }
    }

    val appearScale = remember { Animatable(if (playAppear) 0.4f else 1f) }
    LaunchedEffect(playAppear) {
        if (playAppear) {
            if (appearScale.value >= 0.99f) appearScale.snapTo(0.4f)
            appearScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 320f),
            )
        } else {
            appearScale.snapTo(1f)
        }
    }
    // Quantize so we redraw ~every 2% of scale — enough for a smooth grow without thrashing.
    val scaleTick = (appearScale.value * 50f).roundToInt()
    MarkerComposable(
        bubble.key,
        bubble.size,
        thread?.title.orEmpty(),
        tipEpoch,
        mediaPath,
        mediaKindId,
        thumbBitmap != null,
        scaleTick,
        state = rememberUpdatedMarkerState(
            position = LatLng(bubble.position.lat, bubble.position.lng),
        ),
        // The sharpened bottom-start corner is the point being talked about, so that corner —
        // not the middle of the bubble — sits on the coordinate.
        anchor = Offset(0f, 1f),
        onClick = { true },
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                val s = appearScale.value
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 1f)
            },
        ) {
            if (thread == null) {
                ClusterBubble(threads = bubble.items)
            } else {
                ThreadBubble(thread = thread, thumbBitmap = thumbBitmap)
            }
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
private fun ThreadBubble(thread: ChatThread, thumbBitmap: Bitmap?) {
    val heat = ActivityHeat.of(thread.lastMessageAt)
    val live = heat == ActivityHeat.HOT
    val mediaPreview = thread.hasMapMediaPreview
    val isVideo = thread.lastMediaKind == MessageKind.VIDEO
    ActivityGlowBubble(heat = heat, live = live) {
        Row(
            modifier = Modifier
                .widthIn(max = if (mediaPreview) 180.dp else 200.dp)
                .padding(start = 10.dp, end = 9.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (mediaPreview) {
                MapBubbleMediaThumb(
                    bitmap = thumbBitmap,
                    isVideo = isVideo,
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
                    text = relativeTime(thread.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (heat) {
                        ActivityHeat.HOT -> MapTalkColors.Accent.copy(alpha = 0.95f)
                        ActivityHeat.WARM -> MapTalkColors.Accent.copy(alpha = 0.7f)
                        ActivityHeat.COOL -> MapTalkColors.Faint
                    },
                )
            }
        }
    }
}

@Composable
private fun MapBubbleMediaThumb(bitmap: Bitmap?, isVideo: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MapTalkColors.Raised)
            .border(1.dp, MapTalkColors.Hairline, RoundedCornerShape(6.dp)),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
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

/** Decode a software bitmap for map markers (Coil async inside MarkerComposable never paints). */
private suspend fun loadMapBubbleThumb(
    context: Context,
    path: String,
    isVideo: Boolean,
): Bitmap? = withContext(Dispatchers.IO) {
    val localFile: File? = when {
        path.startsWith("http://") || path.startsWith("https://") -> null
        path.startsWith("/") -> File(path).takeIf { it.exists() }
        else -> context.appContainer.resolveLocalMedia(path)
    }
    if (isVideo) {
        return@withContext loadMapBubbleVideoPoster(path, localFile)
    }
    if (localFile != null) {
        return@withContext BitmapFactory.decodeFile(localFile.absolutePath)
    }
    if (!path.startsWith("http://") && !path.startsWith("https://")) return@withContext null
    val request = ImageRequest.Builder(context)
        .data(path)
        .size(96)
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request)
    (result as? SuccessResult)?.drawable?.toBitmap()
}

private fun loadMapBubbleVideoPoster(path: String, file: File?): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        when {
            path.startsWith("http://") || path.startsWith("https://") ->
                retriever.setDataSource(path, HashMap())
            file != null && file.exists() ->
                retriever.setDataSource(file.absolutePath)
            else -> return null
        }
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
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
    val heat = sorted.minOfOrNull { ActivityHeat.of(it.lastMessageAt) } ?: ActivityHeat.COOL
    val anyLive = heat == ActivityHeat.HOT

    // One tight LTR strip — wrap content so the marker bitmap doesn’t stretch
    // the pill and leave the time stranded on the trailing edge.
    ActivityGlowBubble(heat = heat, live = anyLive) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(
                    // Room for the 1.5dp ring — marker bitmaps clip overflow.
                    width = (31 + (visible.size - 1).coerceAtLeast(0) * 20).dp,
                    height = 31.dp,
                ),
            ) {
                visible.forEachIndexed { index, thread ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(start = (index * 20).dp)
                            .size(31.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .background(thread.kind.tint.copy(alpha = 0.22f), CircleShape)
                                // Near-black ring (iOS Theme.surface) so overlaps separate.
                                .border(1.5.dp, MapTalkColors.Base, CircleShape),
                        ) {
                            Text(
                                text = thread.kind.glyph,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            if (overflow > 0) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = relativeTime(sorted.firstOrNull()?.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = when (heat) {
                    ActivityHeat.HOT -> MapTalkColors.Accent.copy(alpha = 0.95f)
                    ActivityHeat.WARM -> MapTalkColors.Accent.copy(alpha = 0.7f)
                    ActivityHeat.COOL -> MapTalkColors.Faint
                },
            )
        }
    }
}

/**
 * Map bubble chip. Markers are software bitmaps — skip blur/elevation (they
 * turn into hard rectangles). Soft accent halo is a light scaled fill only.
 */
@Composable
private fun ActivityGlowBubble(
    heat: ActivityHeat,
    live: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(6.dp)
            .wrapContentSize(),
    ) {
        if (heat != ActivityHeat.COOL) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .scale(if (heat == ActivityHeat.HOT) 1.08f else 1.05f)
                    .background(
                        MapTalkColors.Accent.copy(
                            alpha = if (heat == ActivityHeat.HOT) 0.22f else 0.12f,
                        ),
                        BubbleShape,
                    ),
            )
        }
        Surface(
            shape = BubbleShape,
            color = MapTalkColors.Surface,
            contentColor = MapTalkColors.Text,
            shadowElevation = 0.dp,
            border = BorderStroke(
                if (live) 1.5.dp else 1.dp,
                if (live) MapTalkColors.Accent.copy(alpha = 0.7f) else MapTalkColors.Hairline,
            ),
            content = content,
        )
    }
}

private const val MaxClusterGlyphs = 3

private val BubbleShape = MapTalkShapes.bubble(radius = 14.dp, tailRadius = 2.dp)
