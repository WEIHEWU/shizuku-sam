package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbStartWorker
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

object ShizukuReceiverStarter {

    private const val WORK_NAME = "start_shizuku_with_adb"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, forceStart: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (UserHandleCompat.myUserId() > 0) {
            onComplete?.invoke()
            return
        }
        if (!forceStart && Shizuku.pingBinder()) {
            onComplete?.invoke()
            return
        }

        scope.launch {
            try {
                // 路线 3：检测并优先尝试 Root 自启（开机阶段给 Root 守护进程适当重试窗口）
                val lastMode = ShizukuSettings.getLastLaunchMode()
                val isRootPreferred = lastMode == ShizukuSettings.LaunchMethod.ROOT
                        || ShizukuSettings.getPreferences().getBoolean(ShizukuSettings.KEEP_START_ON_BOOT, false)

                if (isRootPreferred || Shell.isAppGrantedRoot() == true) {
                    if (tryStartRoot()) {
                        Log.i(AppConstants.TAG, "Shizuku started via root successfully on boot")
                        return@launch
                    }
                }

                // 尝试 fallback 到 Root 快速探测
                if (tryStartRoot()) {
                    Log.i(AppConstants.TAG, "Shizuku started via fallback root successfully on boot")
                    return@launch
                }

                // 路线 2：纯 ADB / USB 调试模式开机自启（免 Wi-Fi 本地回环）
                startAdb(context)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun cancelAdbStart(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private suspend fun tryStartRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            for (attempt in 1..6) {
                if (Shizuku.pingBinder()) return@withContext true
                val shell = Shell.getShell()
                if (shell.isRoot) {
                    Shell.cmd("pkill -f shizuku_starter 2>/dev/null || true").exec()
                    // Root 环境下固化 5555 端口，让系统 adbd 开机常驻 TCP 监听，离线/免 Wi-Fi 亦能秒连
                    Shell.cmd("setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555").exec()
                    Shell.cmd(Starter.internalCommand).exec()
                    delay(1200)
                    if (Shizuku.pingBinder()) {
                        ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ROOT)
                        return@withContext true
                    }
                }
                delay(1500)
            }
            false
        }.getOrDefault(false)
    }

    private fun startAdb(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        AdbStartWorker.enqueue(context.applicationContext)
    }
}
