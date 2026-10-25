package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.maxhz.MaxHzPrefs
import moe.shizuku.manager.maxhz.MaxHzService
import moe.shizuku.manager.service.WatchdogAlarmReceiver
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.AccessibilityHelper

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val handled = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> true
            else -> false
        }
        if (!handled) return

        Log.i(AppConstants.TAG, "BootCompleteReceiver triggered by: $action")

        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            startMaxHzServiceIfNeeded(context)
            try {
                ShizukuReceiverStarter.start(context)
            } catch (e: Exception) {
                Log.w(AppConstants.TAG, "Auto-start skipped (service not ready, e.g. direct boot)", e)
            }
        } else {
            Log.d(AppConstants.TAG, "LOCKED_BOOT_COMPLETED — deferring start to BOOT_COMPLETED")
        }

        try {
            if (ShizukuSettings.getWatchdog()) {
                WatchdogService.start(context)
                WatchdogAlarmReceiver.scheduleNext(context)
            }
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "Watchdog start skipped", e)
        }

        try {
            AccessibilityHelper.ensureAccessibilityAlive(context)
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "Accessibility auto-restore skipped", e)
        }
    }

    private fun startMaxHzServiceIfNeeded(context: Context) {
        if (MaxHzPrefs.requiresBackgroundService(context)) {
            val serviceIntent = Intent(context, MaxHzService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }.onFailure {
                Log.w(AppConstants.TAG, "Unable to start MaxHz service on boot", it)
            }
        }
    }
}
