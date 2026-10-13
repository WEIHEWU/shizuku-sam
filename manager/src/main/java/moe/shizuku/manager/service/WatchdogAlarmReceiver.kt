package moe.shizuku.manager.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import moe.shizuku.manager.ShizukuSettings

/**
 * External, process-independent re-arm for the Watchdog (aligned with upstream ShizukuPlus #415, #417).
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!ShizukuSettings.getWatchdog()) {
            Log.d(TAG, "Watchdog disabled in settings, not rescheduling alarm")
            return
        }

        if (!WatchdogService.isRunning()) {
            Log.w(TAG, "WatchdogService found dead by alarm re-arm; restarting")
            // Android 15+ (API 35) blocks startForegroundService() from BroadcastReceiver while locked.
            if (Build.VERSION.SDK_INT >= 35) {
                WatchdogWorker.enqueueExpeditedStart(context.applicationContext)
            } else {
                WatchdogService.start(context)
            }
        }
        WatchdogService.syncWithCurrentState(context.applicationContext)

        scheduleNext(context.applicationContext)
    }

    companion object {
        private const val TAG = "WatchdogAlarmReceiver"
        private const val REQUEST_CODE = 9001
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 min

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogAlarmReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        @JvmStatic
        fun scheduleNext(context: Context) {
            if (!ShizukuSettings.getWatchdog()) return
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val triggerAt = System.currentTimeMillis() + INTERVAL_MS
                val pi = pendingIntent(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule watchdog re-arm alarm", e)
            }
        }

        @JvmStatic
        fun cancel(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                alarmManager.cancel(pendingIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel watchdog re-arm alarm", e)
            }
        }
    }
}
