package app.maptalk.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.core.ActivityHeat
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.ui.InitialAvatar
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.tint
import coil.compose.AsyncImage

/**
 * Peek card for a single map bubble — matches iOS `BubblePreviewCard`:
 * header, stats strip, latest tip lines. Tap / swipe up to open · swipe down to dismiss.
 */
@Composable
fun BubblePreviewCard(
    thread: ChatThread,
    latest: List<Message>,
    isLoading: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heat = ActivityHeat.of(thread.lastMessageAt)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .verticalSwipe(onOpen = onOpen, onDismiss = onDismiss)
            .clip(RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = MapTalkColors.Surface,
        contentColor = MapTalkColors.Text,
        shadowElevation = 12.dp,
        onClick = onOpen,
    ) {
        Column(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PreviewGrabber()

            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            thread.kind.tint.copy(alpha = 0.18f),
                            MapTalkShapes.bubble(radius = 12.dp, tailRadius = 2.dp),
                        ),
                ) {
                    Text(thread.kind.glyph, style = MaterialTheme.typography.titleMedium)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (heat != ActivityHeat.COOL) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        MapTalkColors.Accent.copy(
                                            alpha = if (heat == ActivityHeat.HOT) 1f else 0.55f,
                                        ),
                                        CircleShape,
                                    ),
                            )
                        }
                        Text(
                            text = thread.kind.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = thread.kind.tint,
                            maxLines = 1,
                        )
                        Text(
                            text = "· ${thread.authorName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MapTalkColors.Faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatChip(
                    value = if (thread.messageCount == 0L) "—" else "${thread.messageCount}",
                    label = if (thread.messageCount == 1L) "message" else "messages",
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(MapTalkColors.Hairline),
                )
                StatChip(
                    value = relativeTime(thread.lastMessageAt),
                    label = when (heat) {
                        ActivityHeat.HOT -> "active now"
                        ActivityHeat.WARM -> "active recently"
                        ActivityHeat.COOL -> "last active"
                    },
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(MapTalkColors.Hairline),
                )
                StatChip(
                    value = relativeTime(thread.createdAt),
                    label = "started",
                    modifier = Modifier.weight(1f),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MapTalkColors.Raised.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = "Latest",
                    style = MaterialTheme.typography.labelSmall,
                    color = MapTalkColors.Faint,
                )
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(MapTalkColors.Raised, RoundedCornerShape(8.dp)),
                        )
                    }
                    latest.isEmpty() -> {
                        Text(
                            text = "Nobody has said anything yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MapTalkColors.Faint,
                        )
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            latest.forEachIndexed { index, message ->
                                PeekLatestRow(
                                    message = message,
                                    showMedia = index == latest.lastIndex,
                                    opacity = fadeOpacity(index = index, count = latest.size),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MapTalkColors.Text,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
            maxLines = 1,
        )
    }
}

@Composable
private fun PeekLatestRow(
    message: Message,
    showMedia: Boolean,
    opacity: Float,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.alpha(opacity),
    ) {
        InitialAvatar(
            name = message.authorName,
            seed = message.authorId,
            size = 26.dp,
            photoURL = message.authorPhotoURL,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MapTalkColors.Subtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = relativeTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MapTalkColors.Faint,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = messagePreviewLine(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MapTalkColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showMedia && (message.hasImage || message.hasVideo)) {
            PeekMediaThumb(message = message)
        }
    }
}

@Composable
private fun PeekMediaThumb(message: Message) {
    val context = LocalContext.current
    val path = when {
        message.hasImage -> message.imagePath
        message.hasVideo -> message.videoPath
        else -> null
    }
    val model = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> context.appContainer.resolveLocalMedia(path)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MapTalkColors.Raised)
            .border(1.dp, MapTalkColors.Hairline, RoundedCornerShape(10.dp)),
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp),
            )
            if (message.hasVideo) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .padding(5.dp),
                )
            }
        } else {
            Icon(
                painter = painterResource(
                    if (message.hasVideo) R.drawable.ic_video else R.drawable.ic_photo,
                ),
                contentDescription = null,
                tint = MapTalkColors.Faint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Oldest tip rows fade; the newest stays full strength. */
private fun fadeOpacity(index: Int, count: Int): Float {
    if (count <= 1) return 1f
    val t = index.toFloat() / (count - 1).toFloat()
    return 0.38f + t * 0.62f
}

fun messagePreviewLine(message: Message): String = when {
    message.isSticker -> message.text
    message.hasVoice -> "Voice note"
    message.hasVideo -> if (message.text.isBlank()) "Video" else message.text
    message.hasImage && message.text.isBlank() -> "Photo"
    message.hasImage -> message.text
    message.text.isBlank() -> "Message"
    else -> message.text
}
