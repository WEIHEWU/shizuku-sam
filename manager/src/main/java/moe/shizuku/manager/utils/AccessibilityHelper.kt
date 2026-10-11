package moe.shizuku.manager.utils

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.maxhz.MaxHzAccessibilityService
import moe.shizuku.manager.maxhz.MaxHzShell

/**
 * 专为三星 One UI 及主流 Android 设备优化的无障碍精准跳转与特权赋能工具类。
 *
 * 解决问题：
 * 1. 具备 Root / Shizuku 特权时，优先通过 Secure Settings 静默秒级激活无障碍，用户无需离开 App；
 * 2. 未具备特权时，通过详尽清晰的引导弹窗指导三星 One UI 用户点击「已安装的应用程序」精准定位开关；
 * 3. 避免在主线程直接跑 su 检测阻塞 UI 导致掉帧与冻结。
 */
object AccessibilityHelper {

    const val SAMSUNG_INSTALLED_SERVICES_ACTION = "com.samsung.accessibility.installed_service"
    const val AOSP_DETAILS_SETTINGS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    const val LABEL_SERVICE = "Shizuku"
    const val LABEL_INSTANT_SWITCH = "Shizuku"
    const val LABEL_PAIRING_ASSISTANT = "Shizuku"

    /**
     * 检查即时切换无障碍服务是否已开启
     */
    fun isInstantSwitchEnabled(context: Context): Boolean {
        return MaxHzAccessibilityService.isEnabled(context)
    }

    /**
     * 检查无线调试自动配对助手无障碍服务是否已开启
     */
    fun isPairingAssistantEnabled(context: Context): Boolean {
        return AdbPairingAccessibilityService.isEnabled(context)
    }

    /**
     * 智能处理开启「即时切换服务」：
     * 1. 优先尝试特权（Root/Shizuku）静默秒级直接激活，激活后不跳出应用；
     * 2. 特权未就绪时，弹出针对三星 One UI 等设备的清晰指引弹窗，告知用户前往「已安装的应用程序」并开启。
     */
    fun handleInstantSwitchClick(activity: Activity, onActivated: () -> Unit) {
        if (activity is LifecycleOwner) {
            activity.lifecycleScope.launch {
                val success = tryPrivilegedActivation(activity, MaxHzAccessibilityService::class.java)
                if (success) {
                    Toast.makeText(
                        activity,
                        R.string.accessibility_toast_activated_by_privilege,
                        Toast.LENGTH_SHORT
                    ).show()
                    onActivated()
                } else {
                    showSamsungGuideAndOpenSettings(
                        activity,
                        MaxHzAccessibilityService::class.java,
                        LABEL_INSTANT_SWITCH
                    )
                }
            }
        } else {
            showSamsungGuideAndOpenSettings(
                activity,
                MaxHzAccessibilityService::class.java,
                LABEL_INSTANT_SWITCH
            )
        }
    }

    /**
     * 尝试利用已有的特权通道（Root / Shizuku）直接激活无障碍服务，免去用户跳转设置与寻找菜单。
     */
    suspend fun tryPrivilegedActivation(
        context: Context,
        serviceClass: Class<out AccessibilityService>
    ): Boolean = withContext(Dispatchers.IO) {
        if (!runCatching { MaxHzShell.isReady() }.getOrDefault(false)) {
            return@withContext false
        }
        val pkg = context.packageName
        val componentName = ComponentName(context, serviceClass)
        val showArgs = componentName.flattenToString()

        runCatching {
            MaxHzShell.exec("cmd appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
            MaxHzShell.exec("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
            MaxHzShell.exec("cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore")
            MaxHzShell.exec("cmd appops set $pkg RUN_IN_BACKGROUND allow")
            MaxHzShell.exec("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            MaxHzShell.exec("dumpsys deviceidle whitelist +$pkg")
            MaxHzShell.exec("am set-standby-bucket $pkg active")
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val updated = if (current.isBlank()) {
                showArgs
            } else if (current.split(':').any { it.equals(showArgs, ignoreCase = true) }) {
                current
            } else {
                "$current:$showArgs"
            }
            MaxHzShell.exec("settings put secure enabled_accessibility_services $updated")
            MaxHzShell.exec("settings put secure accessibility_enabled 1")
        }

        delay(300)
        MaxHzAccessibilityService.invalidateCache()

        if (serviceClass == MaxHzAccessibilityService::class.java) {
            MaxHzAccessibilityService.isEnabled(context)
        } else {
            AdbPairingAccessibilityService.isEnabled(context)
        }
    }

    /**
     * 直接跳转系统无障碍设置，不弹无用阻断对话框，配合底部 Toast 提示用户
     */
    fun showSamsungGuideAndOpenSettings(
        activity: Activity,
        serviceClass: Class<out AccessibilityService>,
        serviceLabel: String
    ) {
        launchAccessibilitySettings(activity, serviceClass, serviceLabel)
    }

    fun launchAccessibilitySettings(
        context: Context,
        serviceClass: Class<out AccessibilityService>,
        serviceLabel: String
    ): Boolean {
        val componentName = ComponentName(context, serviceClass)
        val showArgs = componentName.flattenToString()

        val candidateIntents = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", showArgs)
                putExtra(":settings:show_fragment_args", bundleOf(":settings:fragment_args_key" to showArgs))
                putExtra(Intent.EXTRA_COMPONENT_NAME, showArgs)
            },
            Intent(AOSP_DETAILS_SETTINGS_ACTION).apply {
                data = Uri.parse("package:${context.packageName}")
                putExtra(Intent.EXTRA_COMPONENT_NAME, showArgs)
            }
        )

        for (intent in candidateIntents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    Toast.makeText(
                        context,
                        context.getString(R.string.accessibility_toast_samsung_hint, serviceLabel),
                        Toast.LENGTH_LONG
                    ).show()
                    return true
                }
            } catch (_: Throwable) {}
        }

        return try {
            val fallback = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            Toast.makeText(
                context,
                context.getString(R.string.accessibility_toast_samsung_hint, serviceLabel),
                Toast.LENGTH_LONG
            ).show()
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * 精准直达「即时切换服务」无障碍配置
     */
    fun openInstantSwitchSettings(context: Context): Boolean {
        if (context is Activity) {
            handleInstantSwitchClick(context) {}
            return true
        }
        return launchAccessibilitySettings(context, MaxHzAccessibilityService::class.java, LABEL_INSTANT_SWITCH)
    }

    /**
     * 精准直达「无线配对助手」无障碍配置
     */
    fun openPairingAssistantSettings(context: Context): Boolean {
        if (context is Activity) {
            if (context is LifecycleOwner) {
                context.lifecycleScope.launch {
                    val success = tryPrivilegedActivation(context, MaxHzAccessibilityService::class.java)
                    if (success) {
                        Toast.makeText(
                            context,
                            R.string.accessibility_toast_activated_by_privilege,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showSamsungGuideAndOpenSettings(
                            context,
                            MaxHzAccessibilityService::class.java,
                            LABEL_SERVICE
                        )
                    }
                }
                return true
            }
        }
        return launchAccessibilitySettings(context, MaxHzAccessibilityService::class.java, LABEL_SERVICE)
    }

    /**
     * 打开系统应用详情页以解除 Android 13+ 受限设置
     */
    fun openAppDetailsForRestrictedSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, R.string.toast_restricted_settings_guide_hint, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开应用详情设置", Toast.LENGTH_SHORT).show()
        }
    }
}
