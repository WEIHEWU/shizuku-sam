package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.topjohnwu.superuser.Shell
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
import java.io.File
import java.net.ConnectException

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

    private fun execRootCommand(cmd: Array<String>): Boolean {
        return try {
            Shell.cmd(*cmd).exec().isSuccess
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 仅在 Root 模式下持久化配置 `service.adb.tcp.port`、`persist.adb.tcp.port` 及三星 `adb.network.port` 为 `5555`。
     * 若当前已有 `uid == 2000` (ADB) 服务运行则拒绝重启 `adbd`，防止误杀正运行于 `adbd` cgroup 的 `shizuku_server`。
     */
    fun enableAdbTcp(port: Int = 5555): Boolean {
        if (port !in 1..65535) return false
        if (runCatching { Shizuku.pingBinder() && Shizuku.getUid() == 2000 }.getOrDefault(false)) {
            Log.w("AdbStarter", "enableAdbTcp skipped: refusing to restart adbd while uid 2000 shizuku_server is running")
            return false
        }
        if (!Shell.getShell().isRoot) {
            Log.w("AdbStarter", "enableAdbTcp skipped: root shell unavailable")
            return false
        }
        return runCatching {
            val currentPort = EnvironmentUtils.getAdbTcpPort()
            if (currentPort == port && AdbPortProber.isPortOpen(port, 300)) {
                execRootCommand(arrayOf("setprop", "persist.adb.tcp.port", port.toString()))
                return@runCatching true
            }

            execRootCommand(arrayOf("setprop", "service.adb.tcp.port", port.toString()))
            execRootCommand(arrayOf("setprop", "persist.adb.tcp.port", port.toString()))
            execRootCommand(arrayOf("setprop", "adb.network.port", port.toString()))

            val restartViaCtl = execRootCommand(arrayOf("setprop", "ctl.restart", "adbd"))
            if ((!restartViaCtl || EnvironmentUtils.isSamsung()) && !AdbPortProber.isPortOpen(port, 250)) {
                val stopped = execRootCommand(arrayOf("stop", "adbd"))
                if (stopped) {
                    execRootCommand(arrayOf("start", "adbd"))
                } else {
                    execRootCommand(arrayOf("pkill", "adbd"))
                }
            }
            Log.i("AdbStarter", "enableAdbTcp applied for port $port")
            true
        }.onFailure {
            Log.w("AdbStarter", "Failed to enable ADB TCP mode on port $port", it)
        }.getOrDefault(false)
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
        val explicitFallbackHost = host?.trim()?.takeIf {
            it.isNotEmpty() && !LocalNetworkGate.isLoopbackHost(it)
        }
        var activeHost = "127.0.0.1"

        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            log?.invoke("Starting with wireless adb in port $port...\n")

            withContext(Dispatchers.IO) {
                val key = runCatching {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                }.getOrElse {
                    if (it is CancellationException) throw it
                    throw AdbKeyException(it)
                }

                // Only ask an existing root (uid 0) server to exit via fast Binder IPC so
                // shell (uid 2000) starter does not hit EPERM when killing uid 0 shizuku_server.
                if (runCatching { Shizuku.pingBinder() && Shizuku.getUid() == 0 }.getOrDefault(false)) {
                    runCatching { Shizuku.exit() }
                    delay(150)
                }

                var activePort = port
                StartupCoordinator.recordConnectTarget(activeHost, activePort, "AdbStarter.startAdb")
                Log.i("AdbStarter", "Connecting to $activeHost:$activePort to launch starter")
                log?.invoke("Connecting on port $activePort...\n")
                val (client, connectedHost) = connectLoopbackThenOptionalFallback(
                    fallbackHost = explicitFallbackHost,
                    port = activePort,
                    key = key,
                    log = log
                )
                client.use { adbClient ->
                    activeHost = connectedHost
                    log?.invoke("Successfully connected on $connectedHost:$activePort\n")
                    adbClient.runCommand("shell:${Starter.internalCommand}")

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
            val explicitFallbackHost = host?.trim()?.takeIf {
                it.isNotEmpty() && !LocalNetworkGate.isLoopbackHost(it)
            }
            withContext(Dispatchers.IO) {
                Log.i("AdbStarter", "Connecting to 127.0.0.1:$port to stop TCP mode")
                val (client, _) = connectLoopbackThenOptionalFallback(explicitFallbackHost, port, key)
                client.use { adbClient ->
                    adbClient.command("usb:")
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

    private suspend fun connectLoopbackThenOptionalFallback(
        fallbackHost: String?,
        port: Int,
        key: AdbKey,
        log: ((String) -> Unit)? = null
    ): Pair<AdbClient, String> {
        val loopbackClient = AdbClient("127.0.0.1", port, key)
        try {
            connectWithRetry(loopbackClient)
            return loopbackClient to "127.0.0.1"
        } catch (e: ConnectException) {
            runCatching { loopbackClient.close() }
            if (fallbackHost.isNullOrBlank()) throw e
            Log.w("AdbStarter", "127.0.0.1:$port refused connection after retries, trying fallback host $fallbackHost:$port")
            log?.invoke("127.0.0.1:$port refused, trying fallback host $fallbackHost:$port...\n")
            val fallbackClient = AdbClient(fallbackHost, port, key)
            try {
                fallbackClient.connect(
                    onKeyOffered = { keyOfferInFlight = true },
                    onKeyOfferDone = { keyOfferInFlight = false }
                )
                return fallbackClient to fallbackHost
            } catch (fallbackErr: Throwable) {
                runCatching { fallbackClient.close() }
                throw fallbackErr
            }
        } catch (e: Throwable) {
            runCatching { loopbackClient.close() }
            throw e
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
                if (attempt == maxAttempts || e is CancellationException || e is LocalNetworkGate.LocalNetworkPermissionException) {
                    throw e
                }
            }
        }
    }
}
