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
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }

        val userManager = context.getSystemService(android.os.UserManager::class.java)
        if (userManager != null && !userManager.isUserUnlocked) {
            // 用户未解锁设备前，CE 加密存储不可读，直接返回等待 ACTION_BOOT_COMPLETED
            return
        }

        startMaxHzServiceIfNeeded(context)

        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return
            val pendingResult = goAsync()
            ShizukuReceiverStarter.start(context) {
                try {
                    pendingResult.finish()
                } catch (_: Throwable) {}
            }
            if (ShizukuSettings.getWatchdog()) {
                runCatching { WatchdogService.start(context) }
                    .onFailure { Log.w(AppConstants.TAG, "Unable to start Shizuku watchdog", it) }
            }
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
