package app.maptalk.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maptalk.core.LiveNow
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors

/** Peek card for a single map bubble: stats + latest. Tap or swipe up to open. */
@Composable
fun BubblePreviewCard(
    thread: ChatThread,
    latest: Message?,
    isLoading: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = LiveNow.isLive(thread.lastMessageAt)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .swipeUpToOpen(onOpen)
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
                    Text(
                        text = buildString {
                            if (live) append("Live · ")
                            append(thread.kind.label)
                            append(" · ")
                            append(thread.authorName)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (live) MapTalkColors.Accent else MapTalkColors.Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MapTalkColors.Raised.copy(alpha = 0.9f), RoundedCornerShape(14.dp))
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatChip(
                    value = if (thread.messageCount == 0L) "—" else thread.messageCount.toString(),
                    label = if (thread.messageCount == 1L) "message" else "messages",
                    modifier = Modifier.weight(1f),
                )
                HorizontalDivider(
                    modifier = Modifier
                        .height(28.dp)
                        .width(1.dp),
                    color = MapTalkColors.Hairline,
                )
                StatChip(
                    value = relativeTime(thread.lastMessageAt),
                    label = if (live) "live now" else "last active",
                    modifier = Modifier.weight(1f),
                )
                HorizontalDivider(
                    modifier = Modifier
                        .height(28.dp)
                        .width(1.dp),
                    color = MapTalkColors.Hairline,
                )
                StatChip(
                    value = relativeTime(thread.createdAt),
                    label = "started",
                    modifier = Modifier.weight(1f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                .height(36.dp)
                                .background(MapTalkColors.Raised, RoundedCornerShape(6.dp)),
                        )
                    }
                    latest == null -> {
                        Text(
                            text = "Nobody has said anything yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MapTalkColors.Faint,
                        )
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = latest.authorName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MapTalkColors.Subtle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = messagePreviewLine(latest),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MapTalkColors.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = relativeTime(latest.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MapTalkColors.Faint,
                            )
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

@Composable
private fun StatChip(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MapTalkColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
            maxLines = 1,
        )
    }
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
