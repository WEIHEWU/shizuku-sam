package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.WorkManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbStartWorker
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuReceiverStarter {

    const val WORK_NAME = "adb_start_worker"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRootStarting = AtomicBoolean(false)

    fun start(
        context: Context,
        forceStart: Boolean = false,
        allowEnableWifi: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        if (!forceStart && (UserHandleCompat.myUserId() > 0
                || ShizukuStateMachine.isRunning()
                || isRootStarting.get())) {
            onComplete?.invoke()
            return
        }

        val appContext = context.applicationContext
        val launchMode = ShizukuSettings.getLastLaunchMode()
        val isKeepBoot = ShizukuSettings.isKeepStartOnBoot()

        if (launchMode == LaunchMethod.ROOT || (launchMode == LaunchMethod.UNKNOWN && isKeepBoot)) {
            if (isRootStarting.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        rootStart()
                    } finally {
                        isRootStarting.set(false)
                        onComplete?.invoke()
                    }
                }
            } else {
                Log.d(AppConstants.TAG, "Root start is already in progress, skipping redundant start")
                onComplete?.invoke()
            }
        } else if (launchMode == LaunchMethod.ADB) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            ) {
                AdbStartWorker.enqueue(appContext, allowEnableWifi = allowEnableWifi)
            } else {
                Log.w(AppConstants.TAG, "WRITE_SECURE_SETTINGS not granted for background ADB start")
            }
            onComplete?.invoke()
        } else {
            Log.w(AppConstants.TAG, "Background start not supported (mode=$launchMode)")
            onComplete?.invoke()
        }
    }

    fun stop() {
        if (!ShizukuStateMachine.isRunning()) return
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        runCatching { Shizuku.exit() }
            .onFailure { Log.w(AppConstants.TAG, "Shizuku.exit failed", it) }
    }

    fun cancelAdbStart(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    private suspend fun rootStart() {
        var rootShell: Shell? = null
        for (attempt in 1..8) {
            val shell = Shell.getShell()
            if (shell.isRoot) {
                rootShell = shell
                break
            } else {
                Log.w(AppConstants.TAG, "Root shell not ready (attempt $attempt/8), closing cached shell and waiting...")
                Shell.getCachedShell()?.close()
                if (attempt < 8) {
                    delay(1500L)
                }
            }
        }
        if (rootShell == null || !rootShell.isRoot) {
            Log.e(AppConstants.TAG, "Failed to obtain root shell after 8 attempts")
            recoverFromFailedStart()
            return
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            val result = Shell.cmd(Starter.internalCommand).exec()
            if (!result.isSuccess) {
                Log.e(AppConstants.TAG, "Root starter exited with code ${result.code}")
                recoverFromFailedStart()
            } else {
                val ready = Starter.waitForBinder(timeoutMs = 5000L)
                if (ready) {
                    ShizukuSettings.setLastLaunchMode(LaunchMethod.ROOT)
                    ShizukuSettings.setLastSettledStateRunning(true)
                }
                ShizukuStateMachine.update()
            }
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "Failed to start Shizuku with root", e)
            recoverFromFailedStart()
        }
    }

    private fun recoverFromFailedStart() {
        if (ShizukuSettings.isLastSettledStateRunning()) {
            ShizukuStateMachine.set(ShizukuStateMachine.State.CRASHED)
        } else {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
        }
        ShizukuStateMachine.update()
    }
}
