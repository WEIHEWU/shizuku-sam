package moe.shizuku.manager.maxhz

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MaxHz 数据与配置仓库。
 *
 * 统一封装对 [MaxHzPrefs] 的访问以及针对系统调优操作的异步调度。
 */
class MaxHzRepository(private val context: Context) {

    fun isControlEnabled(): Boolean = MaxHzPrefs.controlEnabled(context)
    fun setControlEnabled(enabled: Boolean) = MaxHzPrefs.setControlEnabled(context, enabled)

    fun isCompatibleSaverEnabled(): Boolean = MaxHzPrefs.compatibleSaverEnabled(context)
    fun setCompatibleSaverEnabled(enabled: Boolean) = MaxHzPrefs.setCompatibleSaverEnabled(context, enabled)

    fun isQuickDozeEnabled(): Boolean = MaxHzPrefs.quickDozeEnabled(context)
    fun setQuickDozeEnabled(enabled: Boolean) = MaxHzPrefs.setQuickDozeEnabled(context, enabled)

    fun isNightWakeupGuardEnabled(): Boolean = MaxHzPrefs.nightWakeupGuardEnabled(context)
    fun setNightWakeupGuardEnabled(enabled: Boolean) = MaxHzPrefs.setNightWakeupGuardEnabled(context, enabled)

    fun isScanGuardEnabled(): Boolean = MaxHzPrefs.scanGuardEnabled(context)
    fun setScanGuardEnabled(enabled: Boolean) = MaxHzPrefs.setScanGuardEnabled(context, enabled)

    fun isBatteryProtectionEnabled(): Boolean = MaxHzPrefs.batteryProtectionEnabled(context)
    fun setBatteryProtectionEnabled(enabled: Boolean) = MaxHzPrefs.setBatteryProtectionEnabled(context, enabled)

    fun isPassThroughEnabled(): Boolean = MaxHzPrefs.passThroughEnabled(context)
    fun setPassThroughEnabled(enabled: Boolean) = MaxHzPrefs.setPassThroughEnabled(context, enabled)

    fun isFpsMonitorRunning(): Boolean = MaxHzPrefs.fpsMonitorRunning(context)
    fun setFpsMonitorRunning(running: Boolean) = MaxHzPrefs.setFpsMonitorRunning(context, running)

    fun getAppRefreshRules(): List<MaxHzPrefs.AppRefreshRule> = MaxHzPrefs.appRefreshRules(context)
    fun saveAppRefreshRule(rule: MaxHzPrefs.AppRefreshRule) = MaxHzPrefs.saveAppRefreshRule(context, rule)
    fun removeAppRefreshRule(packageName: String) = MaxHzPrefs.removeAppRefreshRule(context, packageName)

    fun getSupportedRefreshRates(): List<Int> = MaxHzPrefs.supportedRefreshRates(context)
    fun setSupportedRefreshRates(rates: List<Int>) = MaxHzPrefs.setSupportedRefreshRates(context, rates)

    suspend fun executeShell(script: String, timeoutSeconds: Long = 8): MaxHzShell.Result {
        return withContext(Dispatchers.IO) {
            MaxHzShell.exec(script, timeoutSeconds)
        }
    }
}
