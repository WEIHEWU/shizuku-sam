package moe.shizuku.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import java.util.concurrent.TimeUnit

/**
 * Persistent worker that ensures the Watchdog remains active (aligned with upstream ShizukuPlus).
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "shizuku_watchdog_heal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(
                NotificationChannel(channelId, "Watchdog Heal", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(R.string.watchdog_running))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(HEAL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            ForegroundInfo(HEAL_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        if (!ShizukuSettings.getWatchdog()) {
            Log.d(TAG, "Watchdog is disabled in settings, skipping healing.")
            return Result.success()
        }

        if (!WatchdogService.isRunning()) {
            Log.w(TAG, "WatchdogService was DEAD! Restarting...")
            WatchdogService.start(applicationContext)
        } else {
            Log.d(TAG, "WatchdogService is healthy.")
        }

        WatchdogService.syncWithCurrentState(applicationContext)

        return Result.success()
    }

    companion object {
        private const val TAG = "WatchdogWorker"
        private const val WORK_NAME = "shizuku_watchdog_healer"
        private const val HEAL_WORK_NAME = "shizuku_watchdog_heal_once"
        private const val HEAL_NOTIFICATION_ID = 1003

        @JvmStatic
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        @JvmStatic
        fun enqueueExpeditedStart(context: Context) {
            val request = OneTimeWorkRequestBuilder<WatchdogWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                HEAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        @JvmStatic
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
