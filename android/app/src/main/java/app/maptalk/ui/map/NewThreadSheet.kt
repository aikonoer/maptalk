package app.maptalk.ui.map

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.maptalk.data.model.ThreadKind
import app.maptalk.geo.GeoPoint
import app.maptalk.ui.PlaceLabelLine
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.tint

/** Headline for the map pin + chat header. Stay short so bubbles stay scannable. */
private const val MAX_TITLE_LENGTH = 100

/** Optional opening post (Reddit-style body). Becomes the first message in the chat. */
private const val MAX_BODY_LENGTH = 1_000

/**
 * Starts a conversation at the point the map is centred on.
 * Title = pin headline; optional body = first message in the thread.
 */
@Composable
fun NewThreadSheet(
    position: GeoPoint,
    onCreate: (title: String, body: String, kind: ThreadKind) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var bodyText by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ThreadKind.GENERAL) }
    val trimmed = title.trim()
    val trimmedBody = bodyText.trim()

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

        PlaceLabelLine(
            point = position,
            trailing = "anyone looking here can join in",
            modifier = Modifier.padding(top = 6.dp),
        )

        OutlinedTextField(
            value = title,
            onValueChange = { if (it.length <= MAX_TITLE_LENGTH) title = it },
            placeholder = { Text("Title — what is going on?", color = MapTalkColors.Faint) },
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

        OutlinedTextField(
            value = bodyText,
            onValueChange = { if (it.length <= MAX_BODY_LENGTH) bodyText = it },
            placeholder = { Text("Add more if you want (optional)", color = MapTalkColors.Faint) },
            shape = RoundedCornerShape(MapTalkShapes.Field),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MapTalkColors.Raised,
                unfocusedContainerColor = MapTalkColors.Raised,
                focusedBorderColor = MapTalkColors.Accent,
                unfocusedBorderColor = MapTalkColors.Hairline,
                cursorColor = MapTalkColors.Accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 140.dp)
                .padding(top = 12.dp),
        )

        Text(
            text = "${trimmedBody.length}/$MAX_BODY_LENGTH",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
            modifier = Modifier
                .padding(top = 6.dp)
                .alpha(if (trimmedBody.length > MAX_BODY_LENGTH - 80) 1f else 0f),
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
            onClick = { onCreate(trimmed, trimmedBody, kind) },
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