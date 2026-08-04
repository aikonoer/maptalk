package app.maptalk.ui.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import app.maptalk.data.AuthRepository
import app.maptalk.ui.AppMark
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.avatarTint
import app.maptalk.ui.theme.initialsOf

/**
 * First launch. There are no accounts in v1, so all we need is the name that will sit on the
 * threads and replies this person writes.
 */
@Composable
fun DisplayNameScreen(
    onSubmit: (String) -> Unit,
    isSaving: Boolean,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val canSubmit = trimmed.isNotEmpty() && !isSaving

    Surface(modifier = Modifier.fillMaxSize(), color = MapTalkColors.Base) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            AppMark(size = 56)

            Text(
                text = "What should people call you?",
                style = MaterialTheme.typography.titleLarge,
                color = MapTalkColors.Text,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = "This sits next to everything you post. Be a person, a shop, " +
                    "or a restaurant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MapTalkColors.Subtle,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier.padding(top = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Crossfade(targetState = trimmed.isEmpty(), label = "avatarPreview") { isEmpty ->
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        if (isEmpty) {
                            Icon(
                                painter = painterResource(R.drawable.ic_person),
                                contentDescription = null,
                                tint = MapTalkColors.Faint,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            NameAvatar(name = trimmed)
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        if (it.length <= AuthRepository.MAX_DISPLAY_NAME_LENGTH) name = it
                    },
                    placeholder = { Text("Display name", color = MapTalkColors.Faint) },
                    singleLine = true,
                    shape = RoundedCornerShape(MapTalkShapes.Field),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSubmit) onSubmit(trimmed) },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MapTalkColors.Raised,
                        unfocusedContainerColor = MapTalkColors.Raised,
                        focusedBorderColor = MapTalkColors.Accent,
                        unfocusedBorderColor = MapTalkColors.Hairline,
                        cursorColor = MapTalkColors.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Only worth the space once the limit is in sight.
            Text(
                text = "${trimmed.length}/${AuthRepository.MAX_DISPLAY_NAME_LENGTH}",
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Faint,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .alpha(
                        if (trimmed.length > AuthRepository.MAX_DISPLAY_NAME_LENGTH - 8) 1f else 0f,
                    ),
            )

            Button(
                onClick = { onSubmit(trimmed) },
                enabled = canSubmit,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MapTalkColors.Accent,
                    contentColor = Color.White,
                    disabledContainerColor = MapTalkColors.Raised,
                    disabledContentColor = MapTalkColors.Faint,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(16.dp),
                    )
                }
                Text(
                    text = if (isSaving) "Getting you in\u2026" else "Start talking",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

/** Previews the face that will sit on this person's messages as they type their name. */
@Composable
private fun NameAvatar(name: String) {
    val tint by animateColorAsState(avatarTint(name), label = "avatarTint")
    Surface(
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = tint.copy(alpha = 0.18f),
        contentColor = tint,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = initialsOf(name), style = MaterialTheme.typography.labelSmall)
        }
    }
}
