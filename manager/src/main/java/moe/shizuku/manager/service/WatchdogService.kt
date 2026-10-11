package moe.shizuku.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import rikka.shizuku.Shizuku

class WatchdogService : Service() {

    private var lastRestartAttempt = 0L

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (ShizukuSettings.getWatchdog()) {
            val now = System.currentTimeMillis()
            // Throttle rapid repeated restarts to at least 20s interval
            if (now - lastRestartAttempt < 20_000L) {
                return@OnBinderDeadListener
            }
            lastRestartAttempt = now

            if (Shizuku.pingBinder()) {
                return@OnBinderDeadListener
            }

            ShizukuReceiverStarter.start(this, forceStart = true)
            notifyRestart()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        startForeground(NOTIFICATION_ID_RUNNING, runningNotification())
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(binderDeadListener)
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_watchdog), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun runningNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_system_icon)
        .setContentTitle(getString(R.string.watchdog_running))
        .setContentText(getString(R.string.watchdog_running_text))
        .setContentIntent(launchPendingIntent())
        .setOngoing(true)
        .build()

    private fun notifyRestart() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID_CRASH,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(getString(R.string.watchdog_shizuku_stopped_title))
                .setContentText(getString(R.string.watchdog_shizuku_stopped_text))
                .setContentIntent(launchPendingIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun launchPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID_RUNNING = 1451
        private const val NOTIFICATION_ID_CRASH = 1452

        @Volatile
        private var running = false

        @JvmStatic
        fun isRunning(): Boolean = running

        @JvmStatic
        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchdogService::class.java))
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }
    }
}
