package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.maxhz.MaxHzShell
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.StartupCoordinator
import rikka.shizuku.Shizuku
import java.io.EOFException
import java.io.File
import java.net.SocketException

object AdbStarter {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var keyOfferInFlight: Boolean = false

    fun buildSelfHardeningCommand(context: Context): String {
        val pkg = context.packageName
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libPath = File(nativeDir, "libshizuku.so").absolutePath
        return buildString {
            append("cmd appops set $pkg ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; ")
            append("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; ")
            append("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; ")
            append("dumpsys deviceidle whitelist +$pkg 2>/dev/null; ")
            append("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null; ")
            append("cmd appops set $pkg GET_USAGE_STATS allow 2>/dev/null; ")
            append("cmd appops set $pkg SCHEDULE_EXACT_ALARM allow 2>/dev/null; ")
            append("cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null; ")
            append("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null; ")
            append("cmd appops set $pkg SYSTEM_EXEMPT_FROM_SUSPENSION allow 2>/dev/null; ")
            append("cmd appops set $pkg SYSTEM_EXEMPT_FROM_HIBERNATION allow 2>/dev/null; ")
            append("am set-inactive $pkg false 2>/dev/null; ")
            append("am set-standby-bucket $pkg active 2>/dev/null; ")
            append("cp $libPath /data/local/tmp/shizuku 2>/dev/null && chmod 755 /data/local/tmp/shizuku 2>/dev/null; ")
            append("true")
        }
    }

    suspend fun startAdb(
        context: Context,
        port: Int,
        log: ((String) -> Unit)? = null
    ) = startAdb(context, "127.0.0.1", port, log)

    suspend fun startAdb(
        context: Context,
        host: String?,
        port: Int,
        log: ((String) -> Unit)? = null
    ) {
        if (port !in 1..65535) return
        var activeHost = AdbPortProber.resolveConnectHost(host)
        if (!LocalNetworkGate.checkOrSkip(
                caller = "AdbStarter.startAdb",
                port = port,
                context = context,
                host = activeHost
            )
        ) {
            val msg = "本地网络权限未授予，已跳过连接无线调试地址 $activeHost:$port"
            log?.invoke("$msg\n")
            throw LocalNetworkGate.LocalNetworkPermissionException(msg)
        }

        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            log?.invoke("Starting with wireless adb ($activeHost:$port)...\n")

            withContext(Dispatchers.IO) {
                val key = runCatching {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                }.getOrElse {
                    if (it is CancellationException) throw it
                    throw AdbKeyException(it)
                }

                var activePort = port
                val tcpMode = ShizukuSettings.getTcpMode()
                val tcpPort = ShizukuSettings.getTcpPort()
                if (tcpMode && activePort != tcpPort && tcpPort in 1..65535) {
                    StartupCoordinator.recordConnectTarget(activeHost, activePort, "AdbStarter.tcpSwitch")
                    Log.i("AdbStarter", "Connecting to $activeHost:$activePort for tcpip:$tcpPort switch")
                    log?.invoke("Connecting to $activeHost:$activePort...\n")
                    AdbClient(activeHost, activePort, key).use { client ->
                        connectWithRetry(client)
                        log?.invoke("Connected to $activeHost:$activePort, restarting in TCP mode port: $tcpPort\n")
                        activePort = tcpPort
                        activeHost = "127.0.0.1"
                        runCatching { client.command("tcpip:$activePort") }
                            .onFailure { if (it !is EOFException && it !is SocketException) throw it }
                    }
                    delay(250)
                }

                // Only ask an existing root (uid 0) server to exit via fast Binder IPC so
                // shell (uid 2000) starter does not hit EPERM when killing uid 0 shizuku_server.
                if (runCatching { Shizuku.pingBinder() && Shizuku.getUid() == 0 }.getOrDefault(false)) {
                    runCatching { Shizuku.exit() }
                    delay(150)
                }

                StartupCoordinator.recordConnectTarget(activeHost, activePort, "AdbStarter.startAdb")
                Log.i("AdbStarter", "Connecting to $activeHost:$activePort to launch starter")
                log?.invoke("Connecting to $activeHost:$activePort...\n")
                AdbClient(activeHost, activePort, key).use { client ->
                    connectWithRetry(client)
                    log?.invoke("Successfully connected to $activeHost:$activePort\n")
                    client.runCommand("shell:${Starter.internalCommand}")
                    ShizukuSettings.setLastHost(activeHost)
                    ShizukuSettings.setLastPort(activePort)
                    ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                    ShizukuSettings.setLastSettledStateRunning(true)
                    ShizukuStateMachine.update()
                }

                val appCtx = context.applicationContext
                if (ShizukuSettings.getWatchdog()) {
                    runCatching { WatchdogService.start(appCtx) }
                }
                backgroundScope.launch {
                    delay(1500)
                    runCatching { MaxHzShell.exec(buildSelfHardeningCommand(appCtx)) }
                }
            }
        } catch (e: Throwable) {
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            }
            ShizukuStateMachine.update()
            throw e
        } finally {
            if (ShizukuSettings.getAutoDisableUsbDebugging() && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
                }
            }
        }
    }

    suspend fun stopTcp(context: Context, port: Int, host: String? = "127.0.0.1") {
        if (port !in 1..65535) return
        val targetHost = AdbPortProber.resolveConnectHost(host)
        if (!LocalNetworkGate.checkOrSkip(
                caller = "AdbStarter.stopTcp",
                port = port,
                context = context,
                isBackground = true,
                host = targetHost
            )
        ) {
            return
        }
        runCatching {
            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            }

            if (!EnvironmentUtils.isAdbEnabled()) {
                throw IllegalStateException("ADB is not enabled")
            }

            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            withContext(Dispatchers.IO) {
                Log.i("AdbStarter", "Connecting to $targetHost:$port to stop TCP mode")
                AdbClient(targetHost, port, key).use { client ->
                    connectWithRetry(client)
                    client.command("usb:")
                }
            }
        }.onFailure {
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
                ShizukuStateMachine.update()
                withContext(Dispatchers.Main) {
                    val message = when (it) {
                        is AdbKeyException -> context.getString(R.string.adb_error_key_store)
                        else -> it.message
                    }
                    Toast.makeText(context, context.getString(R.string.adb_error_stop_tcp) + ". ${message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun connectWithRetry(client: AdbClient) {
        var delayTime = 500L
        val maxAttempts = 8
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    delay(delayTime)
                    delayTime = (delayTime * 1.5).toLong().coerceAtMost(3000L) // Exponential backoff up to 3s
                }
                client.connect(
                    onKeyOffered = { keyOfferInFlight = true },
                    onKeyOfferDone = { keyOfferInFlight = false }
                )
                break
            } catch (e: Exception) {
                if (e is LocalNetworkGate.LocalNetworkPermissionException || e is CancellationException || attempt == maxAttempts) {
                    throw e
                }
            }
        }
    }
}
