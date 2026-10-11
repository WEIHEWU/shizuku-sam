package moe.shizuku.manager.adb

import android.content.Context
import moe.shizuku.manager.maxhz.MaxHzAccessibilityService

/**
 * 桥接与兼容垫片：统一重定向至整合后的 Shizuku 无障碍服务 (MaxHzAccessibilityService)。
 */
object AdbPairingAccessibilityService {

    val isRunning: Boolean
        get() = MaxHzAccessibilityService.isServiceAlive

    fun isEnabled(context: Context): Boolean {
        return MaxHzAccessibilityService.isEnabled(context)
    }
}
