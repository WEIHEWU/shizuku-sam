package moe.shizuku.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.maxhz.MaxHzShell
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.AccessibilityHelper
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

class WatchdogService : Service() {

    private var lastRestartMs = 0L
    private var consecutiveCrashes = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var intentionalStop = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        consecutiveCrashes = 0
        ShizukuSettings.setLastSettledStateRunning(true)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID_CRASH)
        scope.launch(Dispatchers.IO) {
            runCatching {
                MaxHzShell.exec(AdbStarter.buildSelfHardeningCommand(applicationContext))
            }
            AccessibilityHelper.ensureAccessibilityAlive(applicationContext)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (ShizukuSettings.getWatchdog() && ShizukuSettings.isLastSettledStateRunning()) {
            handleCrashDetected()
        }
    }

    private fun handleCrashDetected() {
        if (Shizuku.pingBinder()) {
            consecutiveCrashes = 0
            return
        }
        val now = System.currentTimeMillis()
        val cooldown = backoffMs(consecutiveCrashes)
        if (now - lastRestartMs > cooldown) {
            consecutiveCrashes++
            lastRestartMs = now
            notifyRestart()
            ShizukuReceiverStarter.start(applicationContext)
            Log.d(TAG, "Watchdog: restart #$consecutiveCrashes (cooldown was ${cooldown}ms)")
        } else {
            Log.d(TAG, "Watchdog: restart suppressed (cooldown active, ${now - lastRestartMs}ms / ${cooldown}ms)")
        }
    }

    private fun startForegroundSafely(): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_RUNNING,
                    runningNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID_RUNNING,
                    runningNotification()
                )
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground refused; stopping watchdog service", e)
            false
        }

    override fun onCreate() {
        super.onCreate()
        running.set(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                createChannel()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create watchdog notification channel", e)
            }
        }

        if (!startForegroundSafely()) {
            running.set(false)
            stopSelf()
            return
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (ShizukuSettings.getWatchdog() && ShizukuSettings.isLastSettledStateRunning() && !Shizuku.pingBinder()) {
            handleCrashDetected()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafely()) {
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            intentionalStop = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (ShizukuSettings.getWatchdog() && ShizukuSettings.isLastSettledStateRunning() && !Shizuku.pingBinder()) {
            handleCrashDetected()
        }
        AccessibilityHelper.ensureAccessibilityAlive(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        scope.cancel()
        running.set(false)
        if (intentionalStop) {
            ShizukuSettings.setWatchdog(applicationContext, false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.deleteNotificationChannel("watchdog") }
        val showNotification = ShizukuSettings.getShowForegroundNotification()
        val importance = if (showNotification) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_watchdog),
            importance
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun runningNotification(): android.app.Notification {
        val showNotification = ShizukuSettings.getShowForegroundNotification()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.watchdog_running))
            .setContentText(getString(R.string.watchdog_running_text))
            .setContentIntent(launchPendingIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(if (showNotification) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        }
        return builder.build()
    }

    private fun notifyRestart() {
        getSystemService(NotificationManager::class.java)?.notify(
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
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val CHANNEL_ID = "watchdog_v2"
        private const val NOTIFICATION_ID_RUNNING = 1001
        private const val NOTIFICATION_ID_CRASH = 1002
        private const val ACTION_STOP_SERVICE = "action.stop_service"
        private const val BASE_COOLDOWN_MS = 5_000L
        private const val MAX_COOLDOWN_MS = 300_000L

        private val running = AtomicBoolean(false)

        fun backoffMs(crashes: Int): Long =
            minOf(BASE_COOLDOWN_MS * (1L shl crashes.coerceAtMost(10)), MAX_COOLDOWN_MS)

        @JvmStatic
        fun isRunning(): Boolean = running.get()

        @JvmStatic
        fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start WatchdogService: ${e.message}", e)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, WatchdogService::class.java))
                WatchdogWorker.cancel(context)
                WatchdogAlarmReceiver.cancel(context)
            }
        }

        @JvmStatic
        fun syncWithCurrentState(context: Context) {
            val isAlive = try {
                Shizuku.pingBinder()
            } catch (_: Exception) {
                false
            }
            if (isAlive) {
                ShizukuSettings.setLastSettledStateRunning(true)
            } else if (ShizukuSettings.isLastSettledStateRunning() && ShizukuSettings.getWatchdog()) {
                ShizukuReceiverStarter.start(context.applicationContext)
            }
        }
    }
}
