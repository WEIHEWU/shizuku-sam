package moe.shizuku.manager.utils

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.maxhz.MaxHzAccessibilityService
import moe.shizuku.manager.maxhz.MaxHzPrefs
import moe.shizuku.manager.maxhz.MaxHzShell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 专为三星 One UI 及主流 Android 设备优化的无障碍精准激活、持久焊死与秒级自愈引擎。
 *
 * 核心机制：
 * 1. 【原生免 Root 0ms 激活】：利用已授予的 WRITE_SECURE_SETTINGS 权限，应用内直写 Settings.Secure，无需等待 Shell；
 * 2. 【破除假死/僵尸态 (Zombie Rebind)】：检测到组件在设置中但服务已掉线时，脉冲刷新设置并触发 PackageManager 重载，强制系统重新 bindService；
 * 3. 【全生命周期锁定与自愈】：开机、解锁、亮屏、闹钟巡检与意外断连时秒级抢救，实现真正的“无障碍不掉”；
 * 4. 【三星 One UI 专属防冻豁免】：配合 AdbStarter 豁免挂起/深度休眠，杜绝 Freecess/MARS 误杀。
 */
object AccessibilityHelper {

    private const val TAG = "AccessibilityHelper"

    const val SAMSUNG_INSTALLED_SERVICES_ACTION = "com.samsung.accessibility.installed_service"
    const val AOSP_DETAILS_SETTINGS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    const val LABEL_SERVICE = "Shizuku"
    const val LABEL_INSTANT_SWITCH = "Shizuku"
    const val LABEL_PAIRING_ASSISTANT = "Shizuku"

    private val helperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isHealInFlight = AtomicBoolean(false)
    private var lastHealAttemptTime = 0L
    private const val HEAL_DEBOUNCE_MS = 2_500L

    /**
     * 检查应用自身是否已被授予系统级 WRITE_SECURE_SETTINGS 权限
     */
    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 用户是否锁定了无障碍服务保持开启与自愈
     */
    fun isAccessibilityLocked(context: Context): Boolean {
        return MaxHzPrefs.accessibilityLockEnabled(context)
    }

    /**
     * 设置无障碍锁定与自愈状态
     */
    fun setAccessibilityLocked(context: Context, locked: Boolean) {
        MaxHzPrefs.setAccessibilityLockEnabled(context, locked)
    }

    /**
     * 检查即时切换无障碍服务是否已开启（活着或设置中已启用）
     */
    fun isInstantSwitchEnabled(context: Context): Boolean {
        return MaxHzAccessibilityService.isEnabled(context)
    }

    /**
     * 检查即时切换无障碍服务是否真正存活且处于活跃通信状态
     */
    fun isInstantSwitchAlive(): Boolean {
        return MaxHzAccessibilityService.isServiceAlive
    }

    /**
     * 检查无线调试自动配对助手无障碍服务是否已开启
     */
    fun isPairingAssistantEnabled(context: Context): Boolean {
        return AdbPairingAccessibilityService.isEnabled(context)
    }

    /**
     * 触发服务重绑（破除三星 One UI 与 Android 系统的无障碍僵尸态）。
     *
     * 原理：
     * 1. 切换本应用的 PackageManager 组件状态；
     * 2. 对 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 执行「先移除 -> 间隔 150ms -> 重新加入」的脉冲写入；
     * 3. 强行激起系统 AccessibilityManagerService 内部的 manageServicesLocked()，重新 bindService 拉起无障碍。
     */
    suspend fun pulseRebind(
        context: Context,
        serviceClass: Class<out AccessibilityService>
    ): Boolean = withContext(Dispatchers.IO) {
        val componentName = ComponentName(context, serviceClass)
        val showArgs = componentName.flattenToString()

        // 1. 先通过 PackageManager 触发组件刷新事件
        runCatching {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        val hasPermission = hasWriteSecureSettings(context)
        val shellReady = runCatching { MaxHzShell.isReady() }.getOrDefault(false)

        if (!hasPermission && !shellReady) {
            return@withContext false
        }

        val cr = context.contentResolver
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val list = current.split(':').filter { it.isNotBlank() }.toMutableList()
        list.removeAll { it.equals(showArgs, ignoreCase = true) }
        val without = list.joinToString(":")
        val with = if (without.isBlank()) showArgs else "$without:$showArgs"

        if (hasPermission) {
            runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
            }
            delay(150)
            runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } else if (shellReady) {
            runCatching {
                MaxHzShell.exec("settings put secure enabled_accessibility_services '$without'")
            }
            delay(150)
            runCatching {
                MaxHzShell.exec("settings put secure enabled_accessibility_services '$with'")
                MaxHzShell.exec("settings put secure accessibility_enabled 1")
            }
        }

        delay(300)
        MaxHzAccessibilityService.invalidateCache()
        MaxHzAccessibilityService.isServiceAlive
    }

    /**
     * 巡检并秒级自愈无障碍服务（防掉线核心入口）。
     *
     * 任何时候当应用发现无障碍未存活且处于锁定状态时调用，安全防抖，异步执行，不卡死主线程。
     */
    fun ensureAccessibilityAlive(context: Context, force: Boolean = false) {
        if (!isAccessibilityLocked(context) && !force) return
        if (MaxHzAccessibilityService.isServiceAlive) return

        val now = SystemClock.uptimeMillis()
        if (!force && now - lastHealAttemptTime < HEAL_DEBOUNCE_MS) return
        if (!isHealInFlight.compareAndSet(false, true)) return

        lastHealAttemptTime = now
        val appContext = context.applicationContext

        helperScope.launch {
            try {
                if (MaxHzAccessibilityService.isServiceAlive) return@launch
                Log.i(TAG, "检测到无障碍服务未处于活跃状态，正在执行秒级自愈与重绑...")
                val recovered = pulseRebind(appContext, MaxHzAccessibilityService::class.java)
                if (!recovered && !MaxHzAccessibilityService.isServiceAlive) {
                    tryPrivilegedActivation(appContext, MaxHzAccessibilityService::class.java)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "无障碍秒级自愈异常", t)
            } finally {
                isHealInFlight.set(false)
            }
        }
    }

    /**
     * 当无障碍服务成功建立 Binder 连接时回调
     */
    fun onServiceConnected(context: Context) {
        Log.i(TAG, "MaxHzAccessibilityService 已成功连接并在系统层面激活！")
        setAccessibilityLocked(context, true)
    }

    /**
     * 当无障碍服务被系统解绑或进程被销毁时回调
     */
    fun onServiceUnboundOrDied(context: Context) {
        if (!isAccessibilityLocked(context)) return
        Log.w(TAG, "无障碍服务发生解绑/断连，正在安排 1 秒后自动抢救...")
        helperScope.launch {
            delay(1000)
            ensureAccessibilityAlive(context, force = true)
        }
    }

    /**
     * 智能处理开启「即时切换服务」：
     * 1. 优先尝试特权（Root/Shizuku/Secure Settings）静默秒级直接激活，激活后不跳出应用；
     * 2. 特权未就绪时，弹出针对三星 One UI 等设备的清晰指引弹窗，告知用户前往「已安装的应用程序」并开启。
     */
    fun handleInstantSwitchClick(activity: Activity, onActivated: () -> Unit) {
        setAccessibilityLocked(activity, true)
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
     * 尝试利用已有的特权通道（Secure Settings / Root / Shizuku）直接激活无障碍服务。
     */
    suspend fun tryPrivilegedActivation(
        context: Context,
        serviceClass: Class<out AccessibilityService>
    ): Boolean = withContext(Dispatchers.IO) {
        setAccessibilityLocked(context, true)
        val pkg = context.packageName
        val componentName = ComponentName(context, serviceClass)
        val showArgs = componentName.flattenToString()

        val hasPermission = hasWriteSecureSettings(context)
        val shellReady = runCatching { MaxHzShell.isReady() }.getOrDefault(false)

        if (!hasPermission && !shellReady) {
            return@withContext false
        }

        // 若具备 Shell 环境，同步执行防休眠/防冻结自加固
        if (shellReady) {
            runCatching {
                MaxHzShell.exec(AdbStarter.buildSelfHardeningCommand(context))
            }
        }

        val cr = context.contentResolver
        val current = Settings.Secure.getString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val list = current.split(':').filter { it.isNotBlank() }.toMutableList()
        val alreadyInList = list.any { it.equals(showArgs, ignoreCase = true) }

        if (alreadyInList) {
            // 已在列表中但可能处于假死态：先移除后重新写入（脉冲重绑）
            list.removeAll { it.equals(showArgs, ignoreCase = true) }
            val without = list.joinToString(":")
            val with = if (without.isBlank()) showArgs else "$without:$showArgs"

            if (hasPermission) {
                runCatching {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
                }
                delay(150)
                runCatching {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with)
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
            } else if (shellReady) {
                runCatching {
                    MaxHzShell.exec("settings put secure enabled_accessibility_services '$without'")
                }
                delay(150)
                runCatching {
                    MaxHzShell.exec("settings put secure enabled_accessibility_services '$with'")
                    MaxHzShell.exec("settings put secure accessibility_enabled 1")
                }
            }
        } else {
            // 不在列表中：直接追加写入
            list.add(showArgs)
            val updated = list.joinToString(":")
            if (hasPermission) {
                runCatching {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
            } else if (shellReady) {
                runCatching {
                    MaxHzShell.exec("settings put secure enabled_accessibility_services '$updated'")
                    MaxHzShell.exec("settings put secure accessibility_enabled 1")
                }
            }
        }

        // 刷新组件状态以触发系统扫描
        runCatching {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        delay(300)
        MaxHzAccessibilityService.invalidateCache()

        if (serviceClass == MaxHzAccessibilityService::class.java) {
            MaxHzAccessibilityService.isServiceAlive || MaxHzAccessibilityService.isEnabled(context)
        } else {
            AdbPairingAccessibilityService.isEnabled(context)
        }
    }

    /**
     * 主动彻底关闭即时切换无障碍服务
     */
    fun disableInstantSwitch(context: Context): Boolean {
        setAccessibilityLocked(context, false)
        val showArgs = ComponentName(context, MaxHzAccessibilityService::class.java).flattenToString()
        val cr = context.contentResolver
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val list = current.split(':').filter { it.isNotBlank() }.toMutableList()
        list.removeAll { it.equals(showArgs, ignoreCase = true) }
        val updated = list.joinToString(":")

        val hasPermission = hasWriteSecureSettings(context)
        val shellReady = runCatching { MaxHzShell.isReady() }.getOrDefault(false)

        if (hasPermission) {
            runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            }
        } else if (shellReady) {
            runCatching {
                MaxHzShell.exec("settings put secure enabled_accessibility_services '$updated'")
            }
        }
        MaxHzAccessibilityService.invalidateCache()
        return true
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
