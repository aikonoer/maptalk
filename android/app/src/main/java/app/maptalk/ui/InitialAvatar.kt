package app.maptalk.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maptalk.appContainer
import app.maptalk.ui.theme.avatarTint
import app.maptalk.ui.theme.initialsOf
import coil.compose.AsyncImage

/**
 * A name reduced to its initials on a coloured disc, so a stranger's replies are easy to tell
 * apart in a public thread. An optional [photoURL] replaces the initials — an HTTPS URL live, or
 * a local-demo media path. Mirrors `InitialAvatar` in `ios/MapTalk/Core/Theme.swift`.
 */
@Composable
fun InitialAvatar(
    name: String,
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    photoURL: String? = null,
) {
    val context = LocalContext.current
    val tint = avatarTint(seed)
    val model = remember(photoURL) {
        when {
            photoURL.isNullOrBlank() -> null
            photoURL.startsWith("http") -> photoURL
            else -> context.appContainer.resolveLocalMedia(photoURL)
        }
    }

    Surface(
        modifier = modifier
            .size(size)
            .border(1.dp, tint.copy(alpha = 0.35f), CircleShape),
        shape = CircleShape,
        color = tint.copy(alpha = 0.18f),
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape),
                )
            } else {
                Text(
                    text = initialsOf(name),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (size.value * 0.4f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}
