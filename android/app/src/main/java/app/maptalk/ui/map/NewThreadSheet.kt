package app.maptalk.ui.map

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.maptalk.R
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoPoint
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.tint

private const val MAX_TITLE_LENGTH = 80

/**
 * Starts a conversation at the point the map is centred on. The title is the whole thread
 * for now; the first reply comes right after in the chat screen.
 */
@Composable
fun NewThreadSheet(
    position: GeoPoint,
    onCreate: (String, ThreadKind) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ThreadKind.GENERAL) }
    val trimmed = title.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
    ) {
        Text(
            text = "Start a chat here",
            style = MaterialTheme.typography.titleLarge,
            color = MapTalkColors.Text,
        )

        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pin),
                contentDescription = null,
                tint = MapTalkColors.Subtle,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = "%.4f, %.4f".format(position.lat, position.lng),
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Subtle,
            )
            Text(
                text = "\u00b7 anyone looking here can join in",
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Faint,
            )
        }

        OutlinedTextField(
            value = title,
            onValueChange = { if (it.length <= MAX_TITLE_LENGTH) title = it },
            placeholder = { Text("What is going on?", color = MapTalkColors.Faint) },
            singleLine = true,
            shape = RoundedCornerShape(MapTalkShapes.Field),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MapTalkColors.Raised,
                unfocusedContainerColor = MapTalkColors.Raised,
                focusedBorderColor = MapTalkColors.Accent,
                unfocusedBorderColor = MapTalkColors.Hairline,
                cursorColor = MapTalkColors.Accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
        )

        // Only worth the space once the limit is close.
        Text(
            text = "${trimmed.length}/$MAX_TITLE_LENGTH",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
            modifier = Modifier
                .padding(top = 6.dp)
                .alpha(if (trimmed.length > MAX_TITLE_LENGTH - 20) 1f else 0f),
        )

        Text(
            text = "What kind of thing is it?",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Subtle,
            modifier = Modifier.padding(top = 14.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThreadKind.entries.forEach { option ->
                KindChip(
                    kind = option,
                    isSelected = option == kind,
                    onClick = { kind = option },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Button(
            onClick = { onCreate(trimmed, kind) },
            enabled = trimmed.isNotEmpty(),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MapTalkColors.Accent,
                contentColor = Color.White,
                disabledContainerColor = MapTalkColors.Raised,
                disabledContentColor = MapTalkColors.Faint,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
        ) {
            Text(
                text = "Pin it and open the chat",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun KindChip(
    kind: ThreadKind,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (isSelected) kind.tint.copy(alpha = 0.16f) else MapTalkColors.Raised,
        label = "kindChipContainer",
    )
    val border by animateColorAsState(
        if (isSelected) kind.tint.copy(alpha = 0.5f) else Color.Transparent,
        label = "kindChipBorder",
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(MapTalkShapes.Field),
        color = container,
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = kind.glyph, style = MaterialTheme.typography.labelLarge)
            Text(
                text = kind.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) kind.tint else MapTalkColors.Subtle,
                maxLines = 1,
            )
        }
    }
}