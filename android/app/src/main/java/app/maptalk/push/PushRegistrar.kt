package app.maptalk.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.maptalk.data.PushRepository
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

object PushRegistrar {

    private const val PREFS = "maptalk_push"
    private const val KEY_DEVICE_ID = "device_id"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun registerToken(context: Context, push: PushRepository) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                push.registerDevice(
                    deviceId = deviceId(context),
                    token = token,
                    platform = "android",
                )
            }
    }
}
