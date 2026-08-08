package app.maptalk.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import app.maptalk.core.ActivityHeat
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors

/**
 * Peek card for a single map bubble: meta line + latest tip messages.
 * Tap or swipe up to open · swipe down to dismiss.
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
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PreviewGrabber()

            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(thread.kind.glyph, style = MaterialTheme.typography.titleMedium)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(top = 2.dp),
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
                            text = buildString {
                                append(relativeTime(thread.lastMessageAt))
                                append(" · ")
                                append(thread.kind.label)
                                append(" · ")
                                append(thread.authorName)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (heat) {
                                ActivityHeat.HOT -> MapTalkColors.Accent.copy(alpha = 0.95f)
                                ActivityHeat.WARM -> MapTalkColors.Accent.copy(alpha = 0.7f)
                                ActivityHeat.COOL -> MapTalkColors.Faint
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                Text(
                    text = "Open",
                    style = MaterialTheme.typography.labelLarge,
                    color = MapTalkColors.Accent,
                    modifier = Modifier
                        .background(MapTalkColors.Accent.copy(alpha = 0.16f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.alpha(
                                        fadeOpacity(index = index, count = latest.size),
                                    ),
                                ) {
                                    Text(
                                        text = message.authorName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MapTalkColors.Subtle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = messagePreviewLine(message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MapTalkColors.Text,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = relativeTime(message.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MapTalkColors.Faint,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "Tap or swipe up to open",
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Faint,
                modifier = Modifier.fillMaxWidth(),
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
    message.hasVideo -> "Video"
    message.hasImage && message.text.isBlank() -> "Photo"
    message.hasImage -> message.text
    message.text.isBlank() -> "Message"
    else -> message.text
}
