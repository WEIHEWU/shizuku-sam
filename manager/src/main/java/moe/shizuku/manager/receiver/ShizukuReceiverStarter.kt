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
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbStartWorker
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

object ShizukuReceiverStarter {

    const val WORK_NAME = "adb_start_worker"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, forceStart: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (!forceStart && (UserHandleCompat.myUserId() > 0
                || ShizukuStateMachine.isRunning()
                || ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING)) {
            onComplete?.invoke()
            return
        }

        val appContext = context.applicationContext
        val launchMode = ShizukuSettings.getLastLaunchMode()
        if (launchMode == LaunchMethod.ROOT) {
            scope.launch {
                try {
                    rootStart()
                } finally {
                    onComplete?.invoke()
                }
            }
        } else if (launchMode == LaunchMethod.ADB) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            ) {
                AdbStartWorker.enqueue(appContext)
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

    private fun rootStart() {
        if (!Shell.getShell().isRoot) {
            Shell.getCachedShell()?.close()
            return
        }
        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            val result = Shell.cmd(Starter.internalCommand).exec()
            if (!result.isSuccess) {
                Log.e(AppConstants.TAG, "Root starter exited with code ${result.code}")
                recoverFromFailedStart()
            } else {
                ShizukuStateMachine.update()
            }
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "Failed to start Shizuku with root", e)
            recoverFromFailedStart()
        }
    }

    private fun recoverFromFailedStart() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
        ShizukuStateMachine.update()
    }
}
