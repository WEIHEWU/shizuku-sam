package moe.shizuku.manager.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings

abstract class AuthenticatedReceiver : BroadcastReceiver() {

    final override fun onReceive(context: Context, intent: Intent) {
        val authToken = intent.getStringExtra("auth")
        val expectedToken = ShizukuSettings.getAuthToken()
        when {
            authToken.isNullOrEmpty() -> context.notifyAuthError(
                R.string.notification_auth_missing_title,
                R.string.notification_auth_missing_message
            )
            authToken != expectedToken -> context.notifyAuthError(
                R.string.notification_auth_invalid_title,
                R.string.notification_auth_invalid_message
            )
            else -> onAuthenticated(context, intent)
        }
    }

    private fun Context.notifyAuthError(title: Int, message: Int) {
        val channelId = "auth_errors"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.notification_channel_auth_errors), NotificationManager.IMPORTANCE_HIGH)
        )
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            1450,
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(getString(title))
                .setContentText(getString(message))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
    }

    abstract fun onAuthenticated(context: Context, intent: Intent)
}
