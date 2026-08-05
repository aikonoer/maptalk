package app.maptalk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.maptalk.core.DeepLinkBus
import app.maptalk.core.ThreadLink
import app.maptalk.push.MapTalkMessagingService
import app.maptalk.ui.MapTalkApp
import app.maptalk.ui.theme.MapTalkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        enableEdgeToEdge()
        setContent {
            MapTalkTheme {
                MapTalkApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent == null) return
        val fromExtra = intent.getStringExtra(MapTalkMessagingService.EXTRA_THREAD_ID)
        val fromUri = ThreadLink.threadId(intent.data)
        DeepLinkBus.offer(fromExtra ?: fromUri)
    }
}
