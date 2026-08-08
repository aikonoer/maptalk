package app.maptalk.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import app.maptalk.core.ActivityHeat
import app.maptalk.data.model.ChatThread
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors

/**
 * Peek card for a clustered map bubble. Tap a row, swipe up for the most recent,
 * or swipe down to dismiss.
 */
@Composable
fun ClusterPreviewCard(
    threads: List<ChatThread>,
    onOpen: (ChatThread) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = threads.sortedByDescending { it.lastMessageAt }
    val visible = sorted.take(5)
    val overflow = (sorted.size - visible.size).coerceAtLeast(0)
    val latest = sorted.firstOrNull()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .verticalSwipe(
                onOpen = { latest?.let(onOpen) },
                onDismiss = onDismiss,
            )
            .clip(RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = MapTalkColors.Surface,
        contentColor = MapTalkColors.Text,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)) {
            PreviewGrabber()

            Text(
                text = if (sorted.size == 1) "1 chat here" else "${sorted.size} chats here",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            visible.forEachIndexed { index, thread ->
                if (index > 0) {
                    HorizontalDivider(color = MapTalkColors.Hairline)
                }
                ClusterPreviewRow(
                    thread = thread,
                    onClick = { onOpen(thread) },
                )
            }

            Text(
                text = if (overflow > 0) {
                    "+$overflow more — tap bubble to zoom · swipe up for latest"
                } else {
                    "Tap a chat · swipe up for latest"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Faint,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ClusterPreviewRow(
    thread: ChatThread,
    onClick: () -> Unit,
) {
    val heat = ActivityHeat.of(thread.lastMessageAt)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(thread.kind.glyph, style = MaterialTheme.typography.bodyLarge)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${thread.kind.label} · ${thread.authorName}",
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
