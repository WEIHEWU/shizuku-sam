package moe.shizuku.manager.maxhz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MaxHzAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mode = intent.getStringExtra("mode")
                val result = when (mode) {
                    "restore" -> {
                        val nightGuardPackages = MaxHzPrefs.nightWakeupGuardPackages(context)
                            .plus(MaxHzPrefs.nightWakeupGuardAppliedPackages(context))
                            .distinct()
                        runCatching { context.stopService(Intent(context, MaxHzService::class.java)) }
                        MaxHzController.restoreAllPublicDefaults(nightGuardPackages)
                    }
                    "quick_doze" -> MaxHzController.applyQuickDoze()
                    "daily_saver" -> {
                        val restoreM = MaxHzController.restoreMMainMode()
                        val dailySaver = MaxHzController.applyDailySaver()
                        MaxHzShell.Result(
                            code = listOf(restoreM, dailySaver).firstOrNull { it.code != 0 }?.code ?: 0,
                            out = listOf(restoreM.out, dailySaver.out).filter { it.isNotBlank() }.joinToString("\n"),
                            err = listOf(restoreM.err, dailySaver.err).filter { it.isNotBlank() }.joinToString("\n")
                        )
                    }
                    else -> MaxHzShell.Result(-3, "", "未知自动化模式，已拒绝执行：${mode ?: "空"}")
                }
                if (mode == "daily_saver" && result.code == 0) {
                    MaxHzPrefs.setMMainMode(context, "off")
                    MaxHzPrefs.setCompatibleSaverEnabled(context, true)
                    MaxHzPrefs.setQuickDozeEnabled(context, true)
                }
                if (mode == "restore" && result.code == 0) {
                    MaxHzPrefs.setControlEnabled(context, false)
                    MaxHzPrefs.setCompatibleSaverEnabled(context, false)
                    MaxHzPrefs.setQuickDozeEnabled(context, false)
                    MaxHzPrefs.setNightWakeupGuardEnabled(context, false)
                    MaxHzPrefs.setScanGuardEnabled(context, false)
                    MaxHzPrefs.setBatteryProtectionEnabled(context, false)
                    MaxHzPrefs.setPassThroughEnabled(context, false)
                    MaxHzPrefs.clearNightWakeupGuardAppliedPackages(context)
                    MaxHzPrefs.clearMFeatureStates(context)
                }
                Log.i(TAG, "mode=${mode ?: "default"} code=${result.code} out=${result.out.take(240)} err=${result.err.take(240)}")
                MaxHzPrefs.saveResult(
                    context,
                    RESULT_KEY,
                    automationResultText(mode, result),
                    collapsed = true
                )
            } catch (t: Throwable) {
                Log.e(TAG, "automation failed", t)
                val error = MaxHzShell.Result(-2, "", t.message ?: t.javaClass.name)
                MaxHzPrefs.saveResult(
                    context,
                    RESULT_KEY,
                    automationResultText(null, error),
                    collapsed = true
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun automationResultText(mode: String?, result: MaxHzShell.Result): String {
        val label = "自动化入口 ${mode ?: "default"}"
        return buildString {
            append("最近一次操作结果\n")
            if (result.code == 0) {
                append("已完成\n")
                append("结果：自动化任务已执行\n")
                append("下一步建议：无需处理。\n")
            } else {
                append("未完成\n")
                append("原因：").append(automationFailureReason(result)).append('\n')
                append("下一步建议：请确认 Shizuku 正在运行，并已授权本应用。\n")
            }
            append("技术详情：\n")
            append("操作：").append(label).append('\n')
            append("返回代码：").append(result.code).append('\n')
            if (result.out.isNotBlank()) append("返回内容：").append(result.out.trim()).append('\n')
            if (result.err.isNotBlank()) append("原始错误：").append(result.err.trim())
        }
    }

    private fun automationFailureReason(result: MaxHzShell.Result): String {
        val raw = "${result.err}\n${result.out}"
        return when {
            raw.contains("Shizuku", ignoreCase = true) ||
                raw.contains("未授权") ||
                raw.contains("未运行") -> "Shizuku 未运行或未授权"
            raw.contains("permission", ignoreCase = true) ||
                raw.contains("denied", ignoreCase = true) ||
                raw.contains("权限") -> "权限不足"
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("超时") -> "系统暂时没有响应，已停止等待"
            raw.isBlank() -> "系统没有返回明确原因"
            else -> raw.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "系统暂时无法完成操作"
        }
    }

    private companion object {
        private const val TAG = "MaxHzAutomation"
        private const val RESULT_KEY = "automation"
    }
}
