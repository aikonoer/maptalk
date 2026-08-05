package app.maptalk.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.maptalk.MainActivity
import app.maptalk.R
import app.maptalk.appContainer
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID

class MapTalkMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val container = applicationContext.appContainer
        if (container.isLocalDemo) return
        container.pushRepository.registerDevice(
            deviceId = deviceId(),
            token = token,
            platform = "android",
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return
        val threadId = message.data["threadId"]

        ensureChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!threadId.isNullOrBlank()) {
                putExtra(EXTRA_THREAD_ID, threadId)
            }
        }
        val pending = PendingIntent.getActivity(
            this,
            threadId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(
                (threadId ?: body).hashCode(),
                notification,
            )
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chat replies",
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    companion object {
        const val CHANNEL_ID = "thread_messages"
        const val EXTRA_THREAD_ID = "threadId"
        private const val PREFS = "maptalk_push"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
