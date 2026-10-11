package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RequiresApi(Build.VERSION_CODES.R)
class AdbStartWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            if (Shizuku.pingBinder()) return Result.success()

            val cr = applicationContext.contentResolver

            // 给系统 1.5 秒完成启动初始化
            if (runAttemptCount == 0 && !EnvironmentUtils.isAdbEnabled()) {
                delay(1500L)
            }

            if (applicationContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                    Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                }
            }

            // Fast path: if TCP mode is on and the port is already listening, connect directly —
            // no Wireless Debugging or Wi-Fi required.
            if (ShizukuSettings.getTcpMode()) {
                val desiredPort = ShizukuSettings.getTcpPort()
                if (desiredPort in 1..65535) {
                    if (AdbPortProber.isPortOpen(desiredPort, 600)) {
                        AdbStarter.startAdb(applicationContext, desiredPort)
                        Starter.waitForBinder()
                        if (Shizuku.pingBinder()) {
                            ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                            ShizukuSettings.setLastPort(desiredPort)
                            return Result.success()
                        }
                    }
                }
            }

            val tcpPort = EnvironmentUtils.getAdbTcpPort()
            if (tcpPort > 0 && !ShizukuSettings.getTcpMode()) {
                AdbStarter.stopTcp(applicationContext, tcpPort)
            }

            val savedPort = ShizukuSettings.getLastPort()
            val isWifiOk = !EnvironmentUtils.isWifiRequired() || ShizukuSettings.isForceStartWadbEnabled()

            // force_start_wadb fast-path: probe the default ADB TCP port when no port is already
            // known from the system. Handles devices (e.g. Vivo/FunTouchOS) that block mDNS
            // multicast — if adbd is already listening via persist.adb.tcp.port from a prior
            // session, we connect directly without going through mDNS discovery.
            if (ShizukuSettings.isForceStartWadbEnabled() && tcpPort <= 0 && savedPort <= 0) {
                val probePort = ShizukuSettings.getTcpPort().takeIf { it in 1..65535 } ?: 5555
                if (AdbPortProber.isPortOpen(probePort, 400)) {
                    AdbStarter.startAdb(applicationContext, probePort)
                    Starter.waitForBinder()
                    if (Shizuku.pingBinder()) {
                        ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                        ShizukuSettings.setLastPort(probePort)
                        return Result.success()
                    }
                }
            }

            // 端口发现链路（对齐 ShizukuPlus：带锁屏感知与用户解锁自动恢复）
            val port = when {
                tcpPort > 0 && isWifiOk -> tcpPort
                savedPort > 0 && isWifiOk && runAttemptCount == 0 -> savedPort
                else -> callbackFlow {
                    var awaitingAuth = false
                    var timeoutJob: Job? = null
                    var unlockReceiver: BroadcastReceiver? = null

                    val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT, Observer { (_, p) ->
                        if (p > 0) trySend(p)
                    })

                    fun startDiscoveryWithTimeout() {
                        adbMdns.start()
                        timeoutJob?.cancel()
                        timeoutJob = launch {
                            delay(15_000L)
                            close(TimeoutException("Timed out during mDNS port discovery"))
                        }
                    }

                    fun handleAuth() {
                        val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        if (km?.isKeyguardLocked == true) {
                            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                            unlockReceiver = object : BroadcastReceiver() {
                                override fun onReceive(context: Context, intent: Intent) {
                                    if (intent.action == Intent.ACTION_USER_PRESENT) {
                                        runCatching { context.unregisterReceiver(this) }
                                        unlockReceiver = null
                                        if (applicationContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                                            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                                        }
                                        startDiscoveryWithTimeout()
                                    }
                                }
                            }
                            ContextCompat.registerReceiver(
                                applicationContext,
                                unlockReceiver,
                                filter,
                                ContextCompat.RECEIVER_NOT_EXPORTED
                            )
                        } else {
                            awaitingAuth = true
                        }
                        timeoutJob?.cancel()
                        adbMdns.stop()
                    }

                    val observer = object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            val wifiEnabled = runCatching { Settings.Global.getInt(cr, "adb_wifi_enabled", 0) }.getOrDefault(0)
                            when (wifiEnabled) {
                                0 -> if (awaitingAuth) {
                                    close(SecurityException("Network is not authorized for wireless debugging"))
                                } else handleAuth()
                                1 -> startDiscoveryWithTimeout()
                            }
                        }
                    }

                    if (applicationContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                    }
                    cr.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer)
                    startDiscoveryWithTimeout()

                    awaitClose {
                        adbMdns.stop()
                        timeoutJob?.cancel()
                        runCatching { cr.unregisterContentObserver(observer) }
                        unlockReceiver?.let { runCatching { applicationContext.unregisterReceiver(it) } }
                    }
                }.first()
            }

            AdbStarter.startAdb(applicationContext, port)
            Starter.waitForBinder()
            if (Shizuku.pingBinder()) {
                ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                ShizukuSettings.setLastPort(port)
                Log.i(AppConstants.TAG, "Shizuku started via ADB worker on port $port")
                return Result.success()
            } else {
                return Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "AdbStartWorker failed", e)
            return if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        fun enqueue(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) return
            }

            val cb = Constraints.Builder()
            if (EnvironmentUtils.isWifiRequired() && !ShizukuSettings.isForceStartWadbEnabled())
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            val constraints = cb.build()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "adb_start_worker",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
