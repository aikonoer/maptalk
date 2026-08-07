package app.maptalk.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maptalk.ui.AppMark
import app.maptalk.ui.theme.MapTalkColors

/**
 * First-impression gate before display name: brand + curiosity, then Google or guest.
 * Anonymous Firebase already exists underneath; Google is optional “save this account.”
 */
@Composable
fun WelcomeAuthScreen(
    allowsGoogle: Boolean,
    isBusy: Boolean,
    errorMessage: String?,
    onContinueAsGuest: () -> Unit,
    onContinueWithGoogle: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "welcomePulse")
    val markScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "markScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MapTalkColors.Base),
    ) {
        Atmosphere()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Box(modifier = Modifier.scale(markScale)) {
                    AppMark(size = 76)
                }
                Text(
                    text = "MapTalk",
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 46.sp),
                    color = MapTalkColors.Text,
                )
                Text(
                    text = "Chats pinned to places",
                    style = MaterialTheme.typography.titleMedium,
                    color = MapTalkColors.Subtle,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "What’s happening on this corner right now?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MapTalkColors.Faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 36.dp),
            ) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MapTalkColors.Danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (allowsGoogle) {
                    Button(
                        onClick = onContinueWithGoogle,
                        enabled = !isBusy,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            disabledContainerColor = Color.White,
                            disabledContentColor = Color.Black.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(18.dp)
                                    .padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black,
                            )
                        }
                        Text(
                            text = if (isBusy) "Connecting…" else "Continue with Google",
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }

                TextButton(
                    onClick = onContinueAsGuest,
                    enabled = !isBusy,
                    shape = CircleShape,
                    colors = ButtonDefaults.textButtonColors(contentColor = MapTalkColors.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MapTalkColors.Raised, CircleShape)
                        .border(1.dp, MapTalkColors.Hairline, CircleShape),
                ) {
                    Text(
                        text = if (allowsGoogle) "Explore without an account" else "Start exploring",
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }

                Text(
                    text = if (allowsGoogle) {
                        "Save your place on the map — or peek first. You can link Google later."
                    } else {
                        "Local demo — Google Sign-In shows up on a Live build."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MapTalkColors.Faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Atmosphere() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    MapTalkColors.Accent.copy(alpha = 0.24f),
                    MapTalkColors.Accent.copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.5f, size.height * 0.26f),
                radius = size.minDimension * 0.75f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF38BDF8).copy(alpha = 0.12f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.12f, size.height * 0.78f),
                radius = size.minDimension * 0.55f,
            ),
        )
        val step = 28.dp.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(
                color = MapTalkColors.Hairline.copy(alpha = 0.18f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 0.5f,
            )
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = MapTalkColors.Hairline.copy(alpha = 0.18f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 0.5f,
            )
            y += step
        }
    }
}
