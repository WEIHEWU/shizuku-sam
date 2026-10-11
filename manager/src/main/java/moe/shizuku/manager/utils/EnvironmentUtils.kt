package moe.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemProperties
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPortProber
import java.io.File

object EnvironmentUtils {

    @JvmStatic
    fun isSamsung(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
                Build.BRAND.equals("samsung", ignoreCase = true)
    }

    @JvmStatic
    fun isWatch(context: Context): Boolean {
        return (context.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_WATCH)
    }

    private var rootedCache: Boolean? = null

    fun isRooted(): Boolean {
        // 缓存结果，避免每次重建首页卡片都拉起 su 进程
        rootedCache?.let { return it }
        val result = checkRootInternal()
        rootedCache = result
        return result
    }

    /**
     * 检测设备是否安装了常见 root 管理器（Magisk / KernelSU 等）。
     * 用 PackageManager 查询，不受 app SELinux 域限制（不像 stat /system/bin/su 会被拦），
     * 可作为「设备具备 root 能力」的可靠信号，用于决定是否展示一键 Root 启动按钮。
     */
    fun isRootManagerInstalled(context: Context): Boolean {
        val pm = context.packageManager
        val rootPackages = listOf(
            "com.topjohnwu.magisk",
            "com.topjohnwu.magisk.debug",
            "me.weishu.kernelsu",
            "me.weishu.kernelsu.ui",
            "com.koushikdutta.superuser",
            "eu.chainfire.supersu"
        )
        return rootPackages.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
        }
    }

    private fun checkRootInternal(): Boolean {
        // 1. 进程 PATH 中是否存在可执行的 su
        val inPath = System.getenv("PATH")?.split(File.pathSeparatorChar)
            ?.any { runCatching { File("$it/su").canExecute() }.getOrDefault(false) } == true
        if (inPath) return true
        // 2. 直接探测常见 su 路径（部分 ROM / root 方案不暴露到 app PATH，且 SELinux 常禁止 app stat）
        val commonSuPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/magisk/su",
            "/data/adb/ksu/su",
            "/data/adb/ap/su"
        )
        if (commonSuPaths.any { runCatching { File(it).canExecute() }.getOrDefault(false) }) return true
        // 3. 最可靠：实际尝试执行 su（app 已获 root 授权时返回 0，未授权/无 su 时抛异常或非零）
        //    加超时保护，避免 root 管理器弹出授权框导致 waitFor 永久阻塞主线程
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exited = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { p.destroy() }
            exited && p.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        if (port == -1) port = SystemProperties.getInt("adb.network.port", -1)
        return port
    }

    fun getAdbTlsPort(): Int {
        return SystemProperties.getInt("service.adb.tls.port", -1)
    }

    fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun isTlsSupported(context: Context): Boolean {
        return if (isTelevision(context)) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        } else {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        }
    }

    /**
     * Android 17 (API 37) redacts Settings.Global.ADB_ENABLED to 0 for third-party apps,
     * so a 0 reading no longer means USB debugging is actually disabled.
     */
    fun isAdbEnabled(context: Context? = null): Boolean {
        val ctx = context ?: moe.shizuku.manager.application
        if (android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) > 0) return true
        return Build.VERSION.SDK_INT >= 37
    }

    fun isWifiRequired(context: Context? = null): Boolean {
        if (ShizukuSettings.getTcpMode()) return false
        if (getAdbTcpPort() > 0) return false
        if (AdbPortProber.isPortOpen(5555, 100)) return false
        val lastPort = ShizukuSettings.getLastPort()
        if (lastPort in 1..65535 && AdbPortProber.isPortOpen(lastPort, 100)) {
            return false
        }
        return true
    }
}
