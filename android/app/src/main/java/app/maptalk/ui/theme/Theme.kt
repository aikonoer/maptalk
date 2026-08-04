package app.maptalk.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.maptalk.data.model.ThreadKind

/**
 * Dark is the only theme. Everything sits on one near-black ramp so the accent and the four
 * thread colours are the only things that pull the eye — which matters on a screen where the
 * map is already busy. Values are shared with the iOS side in Theme.swift.
 */
object MapTalkColors {
    val Base = Color(0xFF0B0D12)
    val Surface = Color(0xFF14171F)
    val Raised = Color(0xFF1E222D)
    val Hairline = Color(0xFF2A2F3D)

    val Text = Color(0xFFEDEFF5)
    val Subtle = Color(0xFF9AA1B5)
    val Faint = Color(0xFF6B7285)

    val Accent = Color(0xFF6366F1)
    val Danger = Color(0xFFFB7185)
}

/** Three radii, one per kind of thing: a speech bubble, a card, an input. */
object MapTalkShapes {
    val Bubble = 18.dp
    val Card = 16.dp
    val Field = 12.dp

    /**
     * A rounded rectangle with one corner pulled in, so whatever wears it reads as something
     * being said. A `null` tail gives a plain rounded rectangle, which is what the middle of a
     * run of messages from one person wants.
     */
    fun bubble(radius: androidx.compose.ui.unit.Dp = Card, tail: Tail? = Tail.BottomStart) =
        RoundedCornerShape(
            topStart = radius,
            topEnd = radius,
            bottomEnd = if (tail == Tail.BottomEnd) PINCHED else radius,
            bottomStart = if (tail == Tail.BottomStart) PINCHED else radius,
        )

    enum class Tail { BottomStart, BottomEnd }

    private val PINCHED = 4.dp
}

/** One colour per kind: the glyph carries the meaning, the colour makes a busy map scannable. */
val ThreadKind.tint: Color
    get() = when (this) {
        ThreadKind.EVENT -> Color(0xFFC084FC)
        ThreadKind.NOTICE -> Color(0xFFFBBF24)
        ThreadKind.TRAFFIC -> Color(0xFFFB7185)
        ThreadKind.GENERAL -> Color(0xFF38BDF8)
    }

/** Faces are picked from this ramp, one per author, so replies are easy to tell apart. */
private val AvatarTints = listOf(
    Color(0xFFF87171), Color(0xFFFB923C), Color(0xFFFBBF24), Color(0xFF4ADE80),
    Color(0xFF2DD4BF), Color(0xFF38BDF8), Color(0xFF818CF8), Color(0xFFF472B6),
)

/** Deliberately not [String.hashCode]: the same person keeps the same colour on both platforms. */
fun avatarTint(seed: String): Color {
    val slot = seed.encodeToByteArray()
        .fold(0) { acc, byte -> (acc * 31 + (byte.toInt() and 0xFF)) % 1_000_003 }
    return AvatarTints[slot % AvatarTints.size]
}

fun initialsOf(name: String): String {
    val letters = name.trim().split(" ").take(2).mapNotNull { it.firstOrNull() }.joinToString("")
    return letters.ifEmpty { "?" }.uppercase()
}

private val DarkColors = darkColorScheme(
    primary = MapTalkColors.Accent,
    onPrimary = Color.White,
    primaryContainer = MapTalkColors.Accent.copy(alpha = 0.16f),
    onPrimaryContainer = MapTalkColors.Text,
    background = MapTalkColors.Base,
    onBackground = MapTalkColors.Text,
    surface = MapTalkColors.Surface,
    onSurface = MapTalkColors.Text,
    surfaceVariant = MapTalkColors.Raised,
    onSurfaceVariant = MapTalkColors.Subtle,
    outline = MapTalkColors.Hairline,
    outlineVariant = MapTalkColors.Hairline,
    error = MapTalkColors.Danger,
    onError = Color.White,
    scrim = Color(0xCC000000),
)

private val MapTalkTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun MapTalkTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = DarkColors, typography = MapTalkTypography, content = content)
}
