package app.maptalk.ui.thread

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.maptalk.ui.theme.MapTalkBottomSheet
import app.maptalk.ui.theme.MapTalkColors

/** Curated chat wallpapers — local to this device, applied behind the message list. */
enum class ChatBackground(val id: String, val label: String) {
    STANDARD("standard", "Default"),
    MIDNIGHT("midnight", "Midnight"),
    HARBOR("harbor", "Harbor"),
    EMBER("ember", "Ember"),
    DUSK("dusk", "Dusk"),
    GRAIN("grain", "Grain"),
    ;

    companion object {
        fun fromId(id: String?): ChatBackground =
            entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

object ChatBackgroundStore {
    private const val PREFS = "maptalk.prefs"
    private const val KEY = "chatBackground"

    fun current(context: Context): ChatBackground {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ChatBackground.STANDARD.id)
        return ChatBackground.fromId(id)
    }

    fun set(context: Context, value: ChatBackground) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value.id)
            .apply()
    }
}

@Composable
fun ChatBackgroundLayer(
    style: ChatBackground,
    modifier: Modifier = Modifier,
) {
    when (style) {
        ChatBackground.STANDARD -> {
            Box(modifier.background(MapTalkColors.Base))
        }
        ChatBackground.MIDNIGHT -> {
            Box(
                modifier.background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF070B14), Color(0xFF121A2E)),
                    ),
                ),
            )
        }
        ChatBackground.HARBOR -> {
            Box(
                modifier.background(
                    Brush.linearGradient(
                        listOf(Color(0xFF071210), Color(0xFF0E1F24)),
                    ),
                ),
            )
        }
        ChatBackground.EMBER -> {
            Box(
                modifier.background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF120C0A), Color(0xFF1C1410)),
                    ),
                ),
            )
        }
        ChatBackground.DUSK -> {
            Box(
                modifier.background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B0D18), Color(0xFF15182A)),
                    ),
                ),
            )
        }
        ChatBackground.GRAIN -> {
            Box(modifier.background(MapTalkColors.Base)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val step = 14.dp.toPx()
                    var y = 0f
                    var row = 0
                    while (y <= size.height) {
                        val ox = if (row % 2 == 0) 0f else step / 2f
                        var x = ox
                        while (x <= size.width) {
                            drawCircle(
                                color = MapTalkColors.Hairline.copy(alpha = 0.55f),
                                radius = 0.7.dp.toPx(),
                                center = Offset(x, y),
                            )
                            x += step
                        }
                        y += step
                        row++
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBackgroundPickerSheet(
    selected: ChatBackground,
    onSelect: (ChatBackground) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    MapTalkBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MapTalkColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Chat background",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(ChatBackground.entries.toList(), key = { it.id }) { style ->
                    ChatBackgroundSwatch(
                        style = style,
                        isSelected = style == selected,
                        onClick = { onSelect(style) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBackgroundSwatch(
    style: ChatBackground,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MapTalkColors.Accent else MapTalkColors.Hairline,
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            ChatBackgroundLayer(style = style, modifier = Modifier.fillMaxSize())
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(MapTalkColors.Surface, CircleShape)
                        .padding(4.dp),
                ) {
                    Text(
                        text = "✓",
                        color = MapTalkColors.Accent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Text(
            text = style.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MapTalkColors.Text else MapTalkColors.Subtle,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
