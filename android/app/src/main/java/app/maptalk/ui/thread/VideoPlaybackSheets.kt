package app.maptalk.ui.thread

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.maptalk.R
import app.maptalk.data.VideoCompressor
import app.maptalk.ui.theme.MapTalkBottomSheet
import app.maptalk.ui.theme.MapTalkColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun VideoTrimSheet(
    uri: Uri,
    durationMs: Int,
    onConfirm: (startMs: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxStart = maxOf(0f, (durationMs - VideoCompressor.MAX_DURATION_MS).toFloat())
    var startMs by remember { mutableFloatStateOf(0f) }
    val endMs = minOf(durationMs, startMs.toInt() + VideoCompressor.MAX_DURATION_MS)
    val windowSeconds = maxOf(1, (endMs - startMs.toInt() + 500) / 1_000)
    var thumbs by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    LaunchedEffect(uri) {
        thumbs = withContext(Dispatchers.IO) {
            VideoCompressor.filmstrip(context, uri)
        }
    }

    val previewPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer.release() }
    }

    fun seekWindow(start: Float) {
        startMs = start
        val end = minOf(durationMs.toLong(), start.toLong() + VideoCompressor.MAX_DURATION_MS)
        previewPlayer.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start.toLong())
                        .setEndPositionMs(end)
                        .build(),
                )
                .build(),
        )
        previewPlayer.prepare()
        previewPlayer.playWhenReady = true
        previewPlayer.repeatMode = Player.REPEAT_MODE_ONE
    }

    LaunchedEffect(Unit) {
        seekWindow(0f)
    }

    MapTalkBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MapTalkColors.Base,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Make it short",
                style = MaterialTheme.typography.titleMedium,
                color = MapTalkColors.Text,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = previewPlayer
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = "$windowSeconds sec",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            Text(
                text = "Pick your best 15 seconds",
                style = MaterialTheme.typography.titleSmall,
                color = MapTalkColors.Text,
            )
            Text(
                text = "Short & clear looks better in chat — slide to choose the fun part.",
                style = MaterialTheme.typography.bodyMedium,
                color = MapTalkColors.Subtle,
            )

            FilmstripTrimmer(
                thumbs = thumbs,
                durationMs = durationMs,
                startMs = startMs,
                maxStart = maxStart,
                onStartChange = { seekWindow(it) },
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${formatClock(startMs.toInt())} – ${formatClock(endMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MapTalkColors.Text,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Video is ${formatClock(durationMs)} long",
                    style = MaterialTheme.typography.labelMedium,
                    color = MapTalkColors.Faint,
                )
            }

            Button(
                onClick = { onConfirm(startMs.toInt()) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MapTalkColors.Accent),
                shape = RoundedCornerShape(50),
            ) {
                Text("Looks good!")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Never mind", color = MapTalkColors.Subtle)
            }
        }
    }
}

@Composable
private fun FilmstripTrimmer(
    thumbs: List<Bitmap>,
    durationMs: Int,
    startMs: Float,
    maxStart: Float,
    onStartChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MapTalkColors.Hairline, RoundedCornerShape(12.dp)),
    ) {
        val trackW = constraints.maxWidth.toFloat()
        val windowFrac = minOf(1f, VideoCompressor.MAX_DURATION_MS.toFloat() / maxOf(1, durationMs).toFloat())
        val windowW = maxOf(with(density) { 48.dp.toPx() }, trackW * windowFrac)
        val travel = maxOf(0f, trackW - windowW)
        val x = if (maxStart > 0f) (startMs / maxStart) * travel else 0f

        Row(modifier = Modifier.fillMaxSize()) {
            if (thumbs.isEmpty()) {
                repeat(8) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MapTalkColors.Raised),
                    )
                }
            } else {
                thumbs.forEach { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        // Dim outside selection
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(with(density) { x.toDp() })
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.45f)),
            )
            Spacer(modifier = Modifier.width(with(density) { windowW.toDp() }))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.45f)),
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                .width(with(density) { windowW.toDp() })
                .fillMaxHeight()
                .border(3.dp, MapTalkColors.Accent, RoundedCornerShape(12.dp)),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(maxStart, travel, windowW) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        if (maxStart <= 0f || travel <= 0f) return@detectHorizontalDragGestures
                        val nx = (change.position.x - windowW / 2f).coerceIn(0f, travel)
                        onStartChange((nx / travel) * maxStart)
                    }
                },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FullscreenVideoPlayer(
    path: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val exo = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(path))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val density = LocalDensity.current
        val dragTravel = with(density) { 240.dp.toPx() }
        val dismissThreshold = with(density) { 110.dp.toPx() }
        val claimSlop = with(density) { 20.dp.toPx() }
        val scope = rememberCoroutineScope()
        // Only ever read inside draw/layer lambdas: reading it while composing
        // would recompose the player on every frame of the drag.
        val dragOffset = remember { mutableFloatStateOf(0f) }
        fun progressFor(offset: Float) = (offset / dragTravel).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Deliberately opaque: fading it reveals the chat, so the video's
                // own poster bubble shows through the video being dragged.
                .background(Color.Black)
                .pointerInput(Unit) {
                    // PlayerView owns its transport controls, so watch the
                    // Initial pass and only take the gesture over once it is
                    // clearly a downward drag. Taps still reach the controls.
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        var claimed = false
                        var totalX = 0f
                        var totalY = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                            if (!claimed && totalY > claimSlop && totalY > abs(totalX)) {
                                claimed = true
                            }
                            if (claimed) {
                                change.consume()
                                dragOffset.floatValue = max(0f, dragOffset.floatValue + delta.y)
                            }
                        }
                        if (claimed) {
                            if (dragOffset.floatValue > dismissThreshold) {
                                onDismiss()
                            } else {
                                scope.launch {
                                    animate(
                                        initialValue = dragOffset.floatValue,
                                        targetValue = 0f,
                                        animationSpec = spring(dampingRatio = 0.86f),
                                    ) { value, _ -> dragOffset.floatValue = value }
                                }
                            }
                        }
                    }
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    val view = LayoutInflater.from(ctx)
                        .inflate(R.layout.fullscreen_player_view, null) as PlayerView
                    view.apply {
                        player = exo
                        setFullscreenButtonClickListener { onDismiss() }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val offset = dragOffset.floatValue
                        translationY = offset
                        val shrink = 1f - progressFor(offset) * 0.14f
                        scaleX = shrink
                        scaleY = shrink
                    },
            )
        }
    }
}

private fun formatClock(ms: Int): String {
    val total = maxOf(0, ms / 1_000)
    return "%d:%02d".format(total / 60, total % 60)
}
