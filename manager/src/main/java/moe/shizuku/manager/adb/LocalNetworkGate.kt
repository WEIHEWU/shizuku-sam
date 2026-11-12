package moe.shizuku.manager.adb

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.application
import moe.shizuku.manager.utils.StartupCoordinator
import java.util.Locale

/**
 * Android 16/17 本地网络（LNP）最小权限门控（对齐 Shizuku-Next / ShizukuPlus 真机验证方案）：
 * - NsdManager 本地网络服务发现（`_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp`）在 Android 17 (SDK 37+)
 *   受 `android.permission.ACCESS_LOCAL_NETWORK` 门控，在 Android 16 (SDK 36) 受 `NEARBY_WIFI_DEVICES` 门控；
 *   未授权直接发起发现/配对时，系统会拦截并弹出「选择要连接的设备」选择器。
 * - `android.permission.USE_LOOPBACK_INTERFACE` 为清单声明的安装时权限（配合 `targetSdk = 36`），
 *   常规无线调试连接（`TLS_CONNECT`）、TCP `5555` 及后台 Watchdog / 开机自启回环直连 `127.0.0.1` 不做权限拦截；
 *   门控仅收窄至 `NsdManager` 服务发现、`TLS_PAIRING` 配对及非回环 LAN 目标地址。
 * - 权限仅在用户主动触发无线调试启动或配对时一次性申请；被拒绝后引导前往应用权限设置页；
 *   后台路径未授权时跳过 `NsdManager` 发现，绝不触发系统弹窗。
 */
object LocalNetworkGate {

    private const val TAG = "LocalNetworkGate"
    private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    class LocalNetworkPermissionException(message: String) : SecurityException(message)

    fun requiredPermission(): String? = when {
        Build.VERSION.SDK_INT >= 37 -> ACCESS_LOCAL_NETWORK
        Build.VERSION.SDK_INT >= 36 -> Manifest.permission.NEARBY_WIFI_DEVICES
        else -> null
    }

    fun hasPermission(context: Context? = null): Boolean {
        val perm = requiredPermission() ?: return true
        val ctx = (context?.applicationContext ?: runCatching { application }.getOrNull()) ?: return false
        return runCatching {
            ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 仅在一次权限请求返回拒绝后调用，用于判断是否已被系统标记为“不再询问”（需跳转应用设置页开启）。
     */
    fun isPermanentlyDenied(activity: Activity): Boolean {
        val perm = requiredPermission() ?: return false
        if (hasPermission(activity)) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
    }

    fun diagnosticStatusText(context: Context): String {
        val perm = requiredPermission() ?: return "无需授权 (SDK ${Build.VERSION.SDK_INT} <= 35)"
        val shortPerm = perm.substringAfterLast('.')
        return if (hasPermission(context)) {
            "已授予 ($shortPerm)"
        } else {
            "未授予 ($shortPerm)"
        }
    }

    fun isLoopbackHost(host: String?): Boolean {
        val normalized = host?.trim()?.substringBefore('%')?.lowercase(Locale.ROOT).orEmpty()
        return normalized == "127.0.0.1" || normalized == "::1" || normalized == "localhost"
    }

    private fun isDiscoveryOrPairingCaller(caller: String): Boolean {
        return caller.startsWith("AdbMdns.start") ||
            caller.startsWith("AdbMdns.restart") ||
            caller.startsWith("AdbPairing") ||
            caller.contains("Pair", ignoreCase = true) ||
            caller == "AdbDialogFragment.onDialogShow"
    }

    /**
     * 收窄后的本地网络门控：
     * - 仅对 `NsdManager` 服务发现、`TLS_PAIRING` 配对、或目标为非回环 LAN 地址的连接检查 LNP 权限；
     * - 目标 `host` 为 `127.0.0.1` 或 `::1` 的回环直连与后台自愈入口一律直接放行。
     */
    fun checkOrSkip(
        caller: String,
        port: Int = -1,
        context: Context? = null,
        isBackground: Boolean = false,
        host: String = "127.0.0.1",
        requiresLocalNetwork: Boolean = false
    ): Boolean {
        val needsGate = requiresLocalNetwork || !isLoopbackHost(host) || isDiscoveryOrPairingCaller(caller)
        if (!needsGate || hasPermission(context)) return true

        val perm = requiredPermission() ?: "UNKNOWN"
        Log.w(
            TAG,
            "SKIP_CONNECT reason=LNP_PERMISSION_NOT_GRANTED perm=$perm caller=$caller host=$host port=$port background=$isBackground"
        )
        StartupCoordinator.recordLog(
            "LOCAL_NET_GATE",
            "SKIP reason=LNP_PERMISSION_NOT_GRANTED caller=$caller host=$host port=$port bg=$isBackground"
        )
        StartupCoordinator.markWaitingForPermission("$caller ($host:$port)")
        return false
    }

    fun openAppPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun showSettingsGuideDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isBlack = isDark && ThemeHelper.isBlackNightTheme(activity)
        val bgColor = when {
            isBlack -> 0xFF0A0C10.toInt()
            isDark -> 0xFF181B22.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val strokeColor = if (isDark) 0x26FFFFFF else 0x16000000
        val dm = activity.resources.displayMetrics
        val cardDrawable = GradientDrawable().apply {
            cornerRadius = 24f * dm.density
            setColor(bgColor)
            setStroke((1f * dm.density).toInt(), strokeColor)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("需要开启本地网络权限")
            .setMessage(
                "无线调试配对与端口发现需要授予「本地网络 / 附近设备」权限；未授权时系统会拦截本地发现并弹出「选择要连接的设备」。\n\n请前往「应用信息 → 权限」开启权限后再试。"
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("去系统设置开启") { _, _ ->
                openAppPermissionSettings(activity)
            }
            .create()

        dialog.show()
        dialog.window?.let { win ->
            win.setBackgroundDrawable(cardDrawable)
            val width = minOf((dm.widthPixels * 0.90f).toInt(), (500 * dm.density).toInt())
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setDimAmount(0.55f)
            win.setGravity(Gravity.CENTER)
        }
    }

    /**
     * 绑定在 ComponentActivity 生命周期上的单次权限请求器（仅限用户主动点击启动/配对时调用）。
     */
    class Requester(private val activity: ComponentActivity) {

        private var pendingGranted: (() -> Unit)? = null
        private var pendingDenied: (() -> Unit)? = null

        private val launcher: ActivityResultLauncher<String> =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                val effectivelyGranted = isGranted && hasPermission(activity)
                val grantCb = pendingGranted
                val denyCb = pendingDenied
                pendingGranted = null
                pendingDenied = null
                if (effectivelyGranted) {
                    StartupCoordinator.resumeAutoRetry()
                    StartupCoordinator.recordLog("LOCAL_NET_GATE", "Runtime permission GRANTED in ${activity.javaClass.simpleName}")
                    grantCb?.invoke()
                } else {
                    val permanentlyDenied = isPermanentlyDenied(activity)
                    StartupCoordinator.markWaitingForPermission("${activity.javaClass.simpleName}.onDenied(permanent=$permanentlyDenied)")
                    StartupCoordinator.recordLog("LOCAL_NET_GATE", "Runtime permission DENIED in ${activity.javaClass.simpleName} (permanent=$permanentlyDenied)")
                    if (denyCb != null) {
                        denyCb.invoke()
                    } else {
                        showSettingsGuideDialog(activity)
                    }
                }
            }

        fun requestIfNeeded(
            caller: String,
            onGranted: () -> Unit,
            onDenied: (() -> Unit)? = null
        ) {
            val perm = requiredPermission()
            if (perm == null || hasPermission(activity)) {
                onGranted()
                return
            }
            pendingGranted = onGranted
            pendingDenied = onDenied
            StartupCoordinator.recordLog("LOCAL_NET_GATE", "Requesting runtime permission ($perm) from user action caller=$caller")
            runCatching {
                launcher.launch(perm)
            }.onFailure { err ->
                Log.w(TAG, "Failed to launch permission request", err)
                pendingGranted = null
                pendingDenied = null
                if (onDenied != null) onDenied() else showSettingsGuideDialog(activity)
            }
        }
    }
}
