package moe.shizuku.manager.maxhz

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display

object MaxHzRefreshSettings {

    private data class RefreshBounds(val minHz: Int, val maxHz: Int)

    fun applyScreenOnAdaptive(context: Context) {
        val bounds = refreshBounds(context)
        val directOk = writeAdaptiveDirect(context, bounds.maxHz, bounds.minHz)
        if (!directOk || MaxHzShell.isReady()) {
            MaxHzController.applyScreenOnAdaptiveFast(bounds.maxHz, bounds.minHz)
        }
    }

    fun applyAdaptiveMax(context: Context, maxHz: Int, minHz: Int = 10) {
        val safeMax = maxHz.coerceIn(1, 240)
        val safeMin = minHz.coerceIn(0, safeMax)
        val directOk = writeAdaptiveDirect(context, safeMax, safeMin)
        if (!directOk || MaxHzShell.isReady()) {
            MaxHzController.applyAdaptiveMax(safeMax, safeMin)
        }
    }

    fun applyStaticRefreshRate(context: Context, hz: Int) {
        val safeHz = hz.coerceIn(1, 240)
        val directOk = writeStaticDirect(context, safeHz)
        if (!directOk || MaxHzShell.isReady()) {
            MaxHzController.applyStaticRefreshRate(safeHz)
        }
    }

    private fun refreshBounds(context: Context): RefreshBounds {
        val cached = MaxHzPrefs.supportedRefreshRates(context)
        val rates = cached.ifEmpty {
            displayRefreshRates(context).also {
                if (it.isNotEmpty()) MaxHzPrefs.setSupportedRefreshRates(context, it)
            }
        }
        val maxHz = rates.lastOrNull()?.coerceIn(60, 240) ?: DEFAULT_MAX_HZ
        // 关键修复：display.supportedModes 在三星设备上仅上报物理显示模式（如 [60, 120]），
        // 绝不能将 minHz 取为 rates.firstOrNull() (60)，否则会将最低刷新率错误锁死为 60Hz，
        // 导致 S24 Ultra 的 M13 LTPO 无法静态跌落至 1Hz~24Hz！这里必须默认使用 0 (无下限原生自适应)。
        val minHz = DEFAULT_MIN_HZ
        return RefreshBounds(minHz, maxHz)
    }

    private fun displayRefreshRates(context: Context): List<Int> {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return emptyList()
        return display.supportedModes
            .map { it.refreshRate.toInt() }
            .filter { it in 1..240 }
            .distinct()
            .sorted()
    }

    private fun writeAdaptiveDirect(context: Context, maxHz: Int, minHz: Int): Boolean {
        return runCatching {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, "pms_settings_refresh_rate_enabled", 0)
            Settings.Global.putInt(cr, "psm_refresh_rate_tag", 0)
            deleteGlobal(context, "sem_power_mode_refresh_rate")
            deleteGlobal(context, "sem_power_mode_refresh_rate_cover")
            Settings.Secure.putInt(cr, "refresh_rate_mode", 0)
            Settings.Secure.putInt(cr, "refresh_rate_mode", 1)
            deleteSecure(context, "refresh_rate")
            Settings.Secure.putInt(cr, "user_refresh_rate", maxHz)
            Settings.System.putString(cr, "peak_refresh_rate", "$maxHz.0")
            Settings.System.putString(cr, "user_refresh_rate", maxHz.toString())
            Settings.System.putString(cr, "min_refresh_rate", if (minHz <= 0) "0.0" else "$minHz.0")
            deleteSystem(context, "custom_refresh_rate")
            deleteSystem(context, "display_min_refresh_rate")
        }.onFailure {
            Log.w(TAG, "Direct adaptive refresh write failed: min=$minHz max=$maxHz", it)
        }.isSuccess
    }

    private fun writeStaticDirect(context: Context, hz: Int): Boolean {
        return runCatching {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, "pms_settings_refresh_rate_enabled", 0)
            Settings.Global.putInt(cr, "psm_refresh_rate_tag", 0)
            deleteGlobal(context, "sem_power_mode_refresh_rate")
            deleteGlobal(context, "sem_power_mode_refresh_rate_cover")
            if (hz == 60) {
                Settings.Secure.putInt(cr, "refresh_rate_mode", 0)
                Settings.Secure.putInt(cr, "refresh_rate", 60)
                Settings.Secure.putInt(cr, "user_refresh_rate", 60)
            } else {
                // 三星 One UI 中 refresh_rate_mode=0 是 60Hz 标准上限锁，
                // 固定 120Hz/90Hz 或固定 48Hz/30Hz/24Hz 必须处于 mode=1 并令 min=peak=hz
                Settings.Secure.putInt(cr, "refresh_rate_mode", 1)
                deleteSecure(context, "refresh_rate")
                Settings.Secure.putInt(cr, "user_refresh_rate", hz)
            }
            Settings.System.putString(cr, "peak_refresh_rate", "$hz.0")
            Settings.System.putString(cr, "user_refresh_rate", hz.toString())
            Settings.System.putString(cr, "min_refresh_rate", "$hz.0")
            deleteSystem(context, "custom_refresh_rate")
            deleteSystem(context, "display_min_refresh_rate")
        }.onFailure {
            Log.w(TAG, "Direct static refresh write failed: hz=$hz", it)
        }.isSuccess
    }

    private fun deleteSecure(context: Context, key: String) {
        runCatching { Settings.Secure.putString(context.contentResolver, key, null) }
        runCatching { context.contentResolver.delete(Settings.Secure.getUriFor(key), null, null) }
    }

    private fun deleteSystem(context: Context, key: String) {
        runCatching { Settings.System.putString(context.contentResolver, key, null) }
        runCatching { context.contentResolver.delete(Settings.System.getUriFor(key), null, null) }
    }

    private fun deleteGlobal(context: Context, key: String) {
        runCatching { Settings.Global.putString(context.contentResolver, key, null) }
        runCatching { context.contentResolver.delete(Settings.Global.getUriFor(key), null, null) }
    }

    private const val DEFAULT_MIN_HZ = 0
    private const val DEFAULT_MAX_HZ = 120
    private const val TAG = "MaxHzRefreshSettings"
}
