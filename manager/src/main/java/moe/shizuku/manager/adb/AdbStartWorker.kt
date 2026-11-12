package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.StartupCoordinator
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RequiresApi(Build.VERSION_CODES.R)
class AdbStartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val allowEnableWifi = inputData.getBoolean(KEY_ALLOW_ENABLE_WIFI, false)
        val isBackground = !allowEnableWifi
        try {
            if (Shizuku.pingBinder()) {
                StartupCoordinator.onStartSucceeded("ADB")
                return Result.success()
            }

            if (isBackground && StartupCoordinator.isAutoRetryStopped()) {
                StartupCoordinator.recordLog("ADB_WORKER", "SKIP doWork: retryStoppedByUser=true")
                return Result.failure()
            }

            if (!LocalNetworkGate.checkOrSkip(
                    caller = "AdbStartWorker.doWork",
                    port = -1,
                    context = applicationContext,
                    isBackground = isBackground
                )
            ) {
                return Result.failure()
            }

            if (!StartupCoordinator.tryBeginStart("ADB", userInitiated = allowEnableWifi, caller = "AdbStartWorker.doWork")) {
                return Result.failure()
            }

            val cr = applicationContext.contentResolver

            if (allowEnableWifi &&
                applicationContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                }
            }

            // Fast path: if TCP mode is on and the port is already listening, connect directly —
            // no Wireless Debugging or Wi-Fi required.
            if (ShizukuSettings.getTcpMode()) {
                val desiredPort = ShizukuSettings.getTcpPort()
                if (desiredPort in 1..65535) {
                    val ep = AdbPortProber.probeOpenEndpoint(
                        port = desiredPort,
                        timeoutMs = 600,
                        caller = "AdbStartWorker.tcpMode",
                        context = applicationContext,
                        isBackground = isBackground
                    )
                    if (ep != null) {
                        AdbStarter.startAdb(applicationContext, ep.first, ep.second)
                        if (!Starter.waitForBinder()) {
                            throw TimeoutException("等待 Shizuku 服务响应超时 (${ep.first}:${ep.second})")
                        }
                        StartupCoordinator.onStartSucceeded("ADB")
                        return Result.success()
                    }
                }
            }

            val tcpPort = EnvironmentUtils.getAdbTcpPort()
            if (tcpPort > 0 && !ShizukuSettings.getTcpMode()) {
                AdbStarter.stopTcp(applicationContext, tcpPort)
            }

            val savedPort = ShizukuSettings.getLastPort()
            val isWifiOk = !EnvironmentUtils.isWifiRequired(applicationContext) || ShizukuSettings.isForceStartWadbEnabled()

            // force_start_wadb fast-path: probe the default ADB TCP port when no port is already
            // known from the system. Handles devices (e.g. Vivo/FunTouchOS) that block mDNS
            // multicast — if adbd is already listening via persist.adb.tcp.port from a prior
            // session, we connect directly without going through mDNS discovery.
            if (ShizukuSettings.isForceStartWadbEnabled() && tcpPort <= 0 && savedPort <= 0) {
                val probePort = ShizukuSettings.getTcpPort().takeIf { it in 1..65535 } ?: 5555
                val ep = AdbPortProber.probeOpenEndpoint(
                    port = probePort,
                    timeoutMs = 400,
                    caller = "AdbStartWorker.forceWadb",
                    context = applicationContext,
                    isBackground = isBackground
                )
                if (ep != null) {
                    AdbStarter.startAdb(applicationContext, ep.first, ep.second)
                    if (!Starter.waitForBinder()) {
                        throw TimeoutException("等待 Shizuku 服务响应超时 (${ep.first}:${ep.second})")
                    }
                    StartupCoordinator.onStartSucceeded("ADB")
                    return Result.success()
                }
            }

            val tcpEndpoint = if (tcpPort > 0 && isWifiOk) {
                AdbPortProber.probeOpenEndpoint(
                    port = tcpPort,
                    timeoutMs = 400,
                    caller = "AdbStartWorker.tcpPort",
                    context = applicationContext,
                    isBackground = isBackground
                )
            } else null

            val savedEndpoint = if (tcpEndpoint == null && savedPort > 0 && isWifiOk) {
                AdbPortProber.probeOpenEndpoint(
                    port = savedPort,
                    timeoutMs = 400,
                    caller = "AdbStartWorker.savedPort",
                    context = applicationContext,
                    isBackground = isBackground
                )
            } else null

            val (resolvedHost, port) = tcpEndpoint ?: savedEndpoint ?: run {
                val isWifiAdbEnabled = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
                if (!allowEnableWifi && !isWifiAdbEnabled) {
                    Log.i(AppConstants.TAG, "doWork: adb_wifi_enabled is 0 and allowEnableWifi=false; aborting background start")
                    StartupCoordinator.onStartFailed(
                        StartupCoordinator.FailureReason.ADB_WIFI_DISABLED,
                        "系统无线调试开关处于关闭状态",
                        userInitiated = false,
                        caller = "AdbStartWorker.doWork"
                    )
                    return Result.failure()
                }
                callbackFlow {
                    val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT, Observer { (_, p) ->
                        if (p > 0) {
                            trySend("127.0.0.1" to p)
                        }
                    })

                        var awaitingAuth = false
                        var timeoutJob: Job? = null
                        var unlockReceiver: BroadcastReceiver? = null

                        fun startDiscoveryWithTimeout() {
                            adbMdns.start()
                            timeoutJob?.cancel()
                            timeoutJob = launch {
                                delay(45_000L)
                                close(TimeoutException("Timed out during mDNS port discovery"))
                            }
                        }

                        fun handleAuth() {
                            val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                            if (km?.isKeyguardLocked == true && allowEnableWifi) {
                                runCatching {
                                    val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                                        nm.createNotificationChannel(
                                            NotificationChannel(
                                                CHANNEL_ID,
                                                applicationContext.getString(R.string.notification_channel_adb_pairing),
                                                NotificationManager.IMPORTANCE_LOW
                                            )
                                        )
                                    }
                                    val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                                        .setSmallIcon(R.drawable.ic_system_icon)
                                        .setContentTitle(applicationContext.getString(R.string.app_name))
                                        .setContentText("等待解锁后自动启动无线调试服务…")
                                        .setOngoing(true)
                                        .setSilent(true)
                                        .build()
                                    val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                        ForegroundInfo(
                                            NOTIFICATION_ID,
                                            notification,
                                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                                        )
                                    } else {
                                        ForegroundInfo(NOTIFICATION_ID, notification)
                                    }
                                    setForegroundAsync(foregroundInfo)
                                }
                                val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                                unlockReceiver = object : BroadcastReceiver() {
                                    override fun onReceive(context: Context, intent: Intent) {
                                        if (intent.action == Intent.ACTION_USER_PRESENT) {
                                            runCatching { context.unregisterReceiver(this) }
                                            unlockReceiver = null
                                            if (allowEnableWifi &&
                                                applicationContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                                            ) {
                                                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                                            }
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
                                when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                                    0 -> if (awaitingAuth || !allowEnableWifi) {
                                        close(SecurityException("Wireless debugging is disabled or network is not authorized"))
                                    } else {
                                        handleAuth()
                                    }
                                    1 -> startDiscoveryWithTimeout()
                                }
                            }
                        }

                        if (allowEnableWifi &&
                            applicationContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                        ) {
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

            Log.i(AppConstants.TAG, "doWork: resolved endpoint $resolvedHost:$port, starting ADB client")
            StartupCoordinator.recordLog("ADB_WORKER", "Resolved endpoint=$resolvedHost:$port")
            AdbStarter.startAdb(applicationContext, resolvedHost, port)
            if (!Starter.waitForBinder()) {
                throw TimeoutException("等待 Shizuku 服务响应超时 ($resolvedHost:$port)")
            }
            StartupCoordinator.onStartSucceeded("ADB")
            runCatching {
                (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.cancel(NOTIFICATION_ID)
            }
            Log.i(AppConstants.TAG, "doWork: Shizuku service successfully started and binder ready on $resolvedHost:$port")

            return Result.success()
        } catch (e: CancellationException) {
            StartupCoordinator.releaseInFlightIfIdle()
            throw e
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "AdbStartWorker failed on runAttempt $runAttemptCount", e)
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            }
            return if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                StartupCoordinator.onStartSucceeded("ADB")
                Result.success()
            } else {
                StartupCoordinator.onStartFailed(
                    reason = StartupCoordinator.classifyThrowable(e),
                    detail = e.localizedMessage ?: e.javaClass.simpleName,
                    userInitiated = allowEnableWifi,
                    caller = "AdbStartWorker.doWork"
                )
                Result.failure()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "AdbStartWorker"
        private const val NOTIFICATION_ID = 1448
        private const val KEY_ALLOW_ENABLE_WIFI = "allow_enable_wifi"

        fun enqueue(context: Context, allowEnableWifi: Boolean = false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) return
            }

            if (!allowEnableWifi && StartupCoordinator.isAutoRetryStopped()) {
                StartupCoordinator.recordLog("ADB_WORKER", "SKIP enqueue: retryStoppedByUser=true")
                return
            }

            if (!LocalNetworkGate.checkOrSkip(
                    caller = "AdbStartWorker.enqueue",
                    port = -1,
                    context = context,
                    isBackground = !allowEnableWifi
                )
            ) {
                return
            }

            val cb = Constraints.Builder()
            if (EnvironmentUtils.isWifiRequired(context) && !ShizukuSettings.isForceStartWadbEnabled()) {
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            }
            val constraints = cb.build()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ALLOW_ENABLE_WIFI to allowEnableWifi))
                .build()

            val policy = if (AdbStarter.keyOfferInFlight) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
            WorkManager.getInstance(context).enqueueUniqueWork(
                "adb_start_worker",
                policy,
                request
            )
        }
    }
}
