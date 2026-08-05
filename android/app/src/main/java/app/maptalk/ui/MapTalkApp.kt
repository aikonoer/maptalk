package app.maptalk.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.data.Session
import app.maptalk.push.PushRegistrar
import app.maptalk.ui.map.MapScreen
import app.maptalk.ui.onboarding.DisplayNameScreen
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes

@Composable
fun MapTalkApp() {
    val context = LocalContext.current
    val container = context.appContainer
    val sessionViewModel: SessionViewModel = viewModel(factory = SessionViewModel.factory(container))
    val session by sessionViewModel.session.collectAsStateWithLifecycle()
    val signInError by sessionViewModel.signInError.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (!container.isLocalDemo) {
            PushRegistrar.registerToken(context, container.pushRepository)
        }
    }

    when (val current = session) {
        null, Session.SignedOut -> StartupScreen(message = signInError)

        is Session.NeedsDisplayName -> DisplayNameScreen(
            onSubmit = sessionViewModel::saveDisplayName,
            isSaving = sessionViewModel.isSavingName.collectAsStateWithLifecycle().value,
        )

        is Session.Ready -> {
            LaunchedEffect(current.author.uid) {
                if (container.isLocalDemo) return@LaunchedEffect
                if (Build.VERSION.SDK_INT >= 33 &&
                    !PushRegistrar.hasNotificationPermission(context)
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    PushRegistrar.registerToken(context, container.pushRepository)
                }
            }

            MapScreen(author = current.author)
        }
    }
}

/** Shown for the moment it takes to get an anonymous account, or if that fails. */
@Composable
private fun StartupScreen(message: String?) {
    Surface(modifier = Modifier.fillMaxSize(), color = MapTalkColors.Base) {
        Column(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppMark()
            Text(
                text = "MapTalk",
                style = MaterialTheme.typography.titleLarge,
                color = MapTalkColors.Text,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = message ?: "Finding conversations near you\u2026",
                style = MaterialTheme.typography.bodyMedium,
                color = if (message == null) MapTalkColors.Subtle else MapTalkColors.Danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The logo, such as it is: the app's own speech bubble with a map pin inside it. */
@Composable
fun AppMark(size: Int = 64) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = MapTalkShapes.bubble(radius = (size * 0.32).dp),
        color = MapTalkColors.Accent.copy(alpha = 0.16f),
        contentColor = MapTalkColors.Accent,
        border = BorderStroke(1.dp, MapTalkColors.Accent.copy(alpha = 0.35f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_pin),
                contentDescription = null,
                modifier = Modifier.size((size * 0.42).dp),
            )
        }
    }
}
