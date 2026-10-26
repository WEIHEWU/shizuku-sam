package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import java.io.EOFException
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException

object AdbStarter {

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

    suspend fun startAdb(context: Context, port: Int, log: ((String) -> Unit)? = null) {
        try {
            log?.invoke("Starting with wireless adb in port $port...\n\n")

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
                if (tcpMode && activePort != tcpPort) {
                    AdbClient("127.0.0.1", activePort, key).use { client ->
                        connectWithRetry(client)
                        activePort = tcpPort
                        runCatching { client.command("tcpip:$activePort") }
                            .onFailure { if (it !is EOFException && it !is SocketException) throw it }
                    }
                }

                stopExistingServer()

                AdbClient("127.0.0.1", activePort, key).use { client ->
                    connectWithRetry(client)
                    client.command("shell:${Starter.internalCommand}") { log?.invoke(String(it)) }
                    runCatching {
                        client.command("shell:${buildSelfHardeningCommand(context)}")
                    }
                }
                ShizukuSettings.setLastPort(activePort)
                ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                ShizukuSettings.setLastSettledStateRunning(true)
                if (ShizukuSettings.getWatchdog()) {
                    runCatching { WatchdogService.start(context.applicationContext) }
                }
            }
        } finally {
            if (ShizukuSettings.getAutoDisableUsbDebugging() && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
                }
            }
        }
    }

    suspend fun stopTcp(context: Context, port: Int) {
        runCatching {
            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            }

            if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 0) {
                throw IllegalStateException("ADB is not enabled")
            }

            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            withContext(Dispatchers.IO) {
                AdbClient("127.0.0.1", port, key).use { client ->
                    connectWithRetry(client)
                    client.command("usb:")
                }
            }
        }.onFailure {
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
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

    /**
     * 在以 shell(uid 2000) 拉起新服务前，清理可能残留的旧 shizuku_server
     * （例如开机 Root 脚本以 uid 0 拉起的进程），避免 starter 因 EPERM 无法 kill 而失败。
     */
    private suspend fun stopExistingServer() {
        val wasAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (wasAlive) {
            runCatching { Shizuku.exit() }
        }
        val cached = Shell.getCachedShell()
        if (cached != null && cached.isRoot) {
            runCatching {
                withTimeoutOrNull(3000L) {
                    Shell.cmd("killall -9 shizuku_server 2>/dev/null || pkill -9 -f shizuku_server 2>/dev/null || true").exec()
                }
            }
        }
        if (wasAlive) delay(300)
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
                if (attempt == maxAttempts || e is CancellationException) throw e
            }
        }
    }
}
