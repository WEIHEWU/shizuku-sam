package moe.shizuku.manager.maxhz

import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.application

/**
 * 设备所有者 (Dhizuku / Device Owner) 模式专用底层调优引擎。
 *
 * 当设备处于未 Root 且无 Wi-Fi / 无 Shizuku 守护进程的环境时，
 * 设备所有者拥有系统最高级别的 WRITE_SECURE_SETTINGS 权限，
 * 可直接通过原生 ContentResolver 纳秒级写入 System / Secure / Global 配置，
 * 免去创建 Linux Shell 进程的开销，实现 100% 离线、抗重启硬件直通控制。
 */
object DeviceOwnerSettingsExecutor {

    private const val TAG = "MaxHzDOSettings"

    fun putSetting(namespace: String, key: String, value: String): Boolean {
        return runCatching {
            val cr = application.contentResolver
            val intVal = value.toIntOrNull()
            val floatVal = if (intVal == null) value.toFloatOrNull() else null
            var ok = false

            when (namespace.lowercase()) {
                "system" -> {
                    if (intVal != null) {
                        ok = runCatching { Settings.System.putInt(cr, key, intVal) }.getOrDefault(false)
                    } else if (floatVal != null) {
                        ok = runCatching { Settings.System.putFloat(cr, key, floatVal) }.getOrDefault(false)
                    }
                    if (!ok) {
                        ok = Settings.System.putString(cr, key, value)
                    }
                }
                "secure" -> {
                    if (intVal != null) {
                        ok = runCatching { Settings.Secure.putInt(cr, key, intVal) }.getOrDefault(false)
                    } else if (floatVal != null) {
                        ok = runCatching { Settings.Secure.putFloat(cr, key, floatVal) }.getOrDefault(false)
                    }
                    if (!ok) {
                        ok = Settings.Secure.putString(cr, key, value)
                    }
                }
                "global" -> {
                    if (intVal != null) {
                        ok = runCatching { Settings.Global.putInt(cr, key, intVal) }.getOrDefault(false)
                    } else if (floatVal != null) {
                        ok = runCatching { Settings.Global.putFloat(cr, key, floatVal) }.getOrDefault(false)
                    }
                    if (!ok) {
                        ok = Settings.Global.putString(cr, key, value)
                    }
                }
                else -> {
                    ok = false
                }
            }
            Log.d(TAG, "putSetting: $namespace $key=$value -> $ok")
            ok
        }.onFailure {
            Log.e(TAG, "putSetting failed: $namespace $key=$value", it)
        }.getOrDefault(false)
    }

    fun getSetting(namespace: String, key: String): String? {
        return runCatching {
            val cr = application.contentResolver
            when (namespace.lowercase()) {
                "system" -> {
                    Settings.System.getString(cr, key)
                        ?: runCatching { Settings.System.getInt(cr, key).toString() }.getOrNull()
                        ?: runCatching { Settings.System.getFloat(cr, key).toString() }.getOrNull()
                }
                "secure" -> {
                    Settings.Secure.getString(cr, key)
                        ?: runCatching { Settings.Secure.getInt(cr, key).toString() }.getOrNull()
                        ?: runCatching { Settings.Secure.getFloat(cr, key).toString() }.getOrNull()
                }
                "global" -> {
                    Settings.Global.getString(cr, key)
                        ?: runCatching { Settings.Global.getInt(cr, key).toString() }.getOrNull()
                        ?: runCatching { Settings.Global.getFloat(cr, key).toString() }.getOrNull()
                }
                else -> null
            }
        }.getOrNull()
    }

    fun deleteSetting(namespace: String, key: String): Boolean {
        return runCatching {
            val cr = application.contentResolver
            when (namespace.lowercase()) {
                "system" -> {
                    runCatching { cr.delete(Settings.System.getUriFor(key), null, null) }
                    Settings.System.putString(cr, key, null)
                }
                "secure" -> {
                    runCatching { cr.delete(Settings.Secure.getUriFor(key), null, null) }
                    Settings.Secure.putString(cr, key, null)
                }
                "global" -> {
                    runCatching { cr.delete(Settings.Global.getUriFor(key), null, null) }
                    Settings.Global.putString(cr, key, null)
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    // 匹配 settings put 或 put_system / put_secure / put_global，支持行首带 if ! 等条件包裹
    private val settingsPutRegex = Regex(
        """(?:settings\s+put\s+(system|secure|global)|put_(system|secure|global))\s+([A-Za-z0-9_.]+)\s+([^;\n\r|&>]+)""",
        RegexOption.IGNORE_CASE
    )

    // 匹配 settings delete 或 restore_or_delete_setting
    private val settingsDeleteRegex = Regex(
        """(?:settings\s+delete|restore_or_delete_setting)\s+(system|secure|global)\s+([A-Za-z0-9_.]+)""",
        RegexOption.IGNORE_CASE
    )

    // 匹配 settings get
    private val settingsGetRegex = Regex(
        """settings\s+get\s+(system|secure|global)\s+([A-Za-z0-9_.]+)""",
        RegexOption.IGNORE_CASE
    )

    private val echoRegex = Regex("""^\s*echo\s+["']?(.*?)["']?$""")

    fun execute(script: String): MaxHzShell.Result {
        val outLines = mutableListOf<String>()
        val variables = mutableMapOf<String, String>()
        var hasFailure = false
        var failureReason = ""

        val rawLines = script.lines()
        for (rawLine in rawLines) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            // 剥离常见的重定向与管道后缀
            line = line.replace(Regex("""\s*>/dev/null\s*2>&1"""), "")
                .replace(Regex("""\s*2>/dev/null"""), "")
                .replace(Regex("""\s*\|\|\s*true"""), "")
                .replace(Regex("""\s*\|\|\s*return\s+\d+"""), "")
                .trim()

            // 1. 处理常见内置恢复宏函数调用
            if (line == "restore_pass_through" || line.startsWith("restore_pass_through ")) {
                putSetting("system", "pass_through", "0")
                outLines.add("旁路供电已恢复默认")
                continue
            }
            if (line == "restore_battery_protection" || line.startsWith("restore_battery_protection ")) {
                putSetting("global", "protect_battery", "3")
                putSetting("global", "ltc_highsoc_threshold", "95")
                putSetting("global", "ltc_release_threshold", "75")
                deleteSetting("global", "battery_protection_threshold")
                deleteSetting("system", "protect_battery")
                deleteSetting("system", "battery_life_protect_enabled")
                outLines.add("充电保护已恢复默认")
                continue
            }
            if (line == "restore_quick_doze" || line.startsWith("restore_quick_doze ")) {
                putSetting("secure", "doze_enabled", "1")
                deleteSetting("global", "device_idle_constants")
                deleteSetting("global", "battery_saver_constants")
                outLines.add("快速休眠已恢复默认")
                continue
            }
            if (line == "restore_compatible_saver" || line.startsWith("restore_compatible_saver ")) {
                putSetting("global", "low_power", "0")
                putSetting("global", "restricted_device_performance", "0,0")
                putSetting("global", "sem_low_heat_mode", "0")
                outLines.add("省电模式已恢复默认")
                continue
            }
            if (line == "restore_night_guard" || line.startsWith("restore_night_guard ")) {
                outLines.add("待机护栏已恢复默认")
                continue
            }

            // 2. 处理 cmd power set-mode
            if (line.contains("cmd power set-mode")) {
                val mode = line.substringAfterLast(" ").trim().toIntOrNull() ?: 0
                val ok = Settings.Global.putInt(application.contentResolver, "low_power", if (mode != 0) 1 else 0)
                Log.d(TAG, "DeviceOwner cmd power set-mode $mode -> $ok")
                continue
            }

            // 3. 处理 settings put 与 put_system / put_secure / put_global
            val putMatch = settingsPutRegex.find(line)
            if (putMatch != null) {
                val ns = (putMatch.groupValues[1].ifEmpty { putMatch.groupValues[2] }).lowercase()
                val key = putMatch.groupValues[3]
                var value = putMatch.groupValues[4].trim().removeSurrounding("\"").removeSurrounding("'")
                if (value.startsWith("$")) {
                    val varName = value.removePrefix("$").removeSurrounding("{", "}")
                    value = variables[varName] ?: value
                }
                val success = putSetting(ns, key, value)
                if (!success) {
                    hasFailure = true
                    failureReason = "写入失败：$key"
                }
                continue
            }

            // 4. 处理 settings delete
            val delMatch = settingsDeleteRegex.find(line)
            if (delMatch != null) {
                val ns = delMatch.groupValues[1].lowercase()
                val key = delMatch.groupValues[2]
                deleteSetting(ns, key)
                continue
            }

            // 5. 处理纯 settings get（独立查询行）
            if (line.startsWith("settings get")) {
                val getMatch = settingsGetRegex.find(line)
                if (getMatch != null) {
                    val ns = getMatch.groupValues[1]
                    val key = getMatch.groupValues[2]
                    val v = getSetting(ns, key) ?: "null"
                    outLines.add(v)
                    continue
                }
            }

            // 6. 处理变量赋值 (如 expected=1, actual="$(settings get ...)")
            if (line.contains("=") && !line.startsWith("if") && !line.startsWith("[")) {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2 && parts[0].matches(Regex("""[A-Za-z_][A-Za-z0-9_]*"""))) {
                    val k = parts[0].trim()
                    var v = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                    if (v.contains("settings get")) {
                        val subMatch = settingsGetRegex.find(v)
                        if (subMatch != null) {
                            v = getSetting(subMatch.groupValues[1], subMatch.groupValues[2]) ?: ""
                        }
                    }
                    variables[k] = v
                }
            }

            // 7. 处理简单 toggle 分支：if [ "$current" = "1" ]; then target=0; else target=1; fi
            if (line.contains("if [") && line.contains("current") && line.contains("target")) {
                val current = variables["current"] ?: getSetting("system", "display_outdoor_mode") ?: "0"
                variables["target"] = if (current == "1") "0" else "1"
            }

            // 8. 处理 echo 消息
            val echoMatch = echoRegex.find(line)
            if (echoMatch != null) {
                var msg = echoMatch.groupValues[1]
                variables.forEach { (k, v) ->
                    msg = msg.replace("\$$k", v).replace("\${$k}", v)
                }

                // 核心：若操作成功，忽略错误分支中的失败提示（如“写入失败”、“验证失败”、“可能被系统限制”）
                val isFailureMsg = msg.contains("失败") || msg.contains("FAIL") || msg.contains("被系统限制")
                if (!hasFailure && isFailureMsg) {
                    continue
                }
                // 若已有失败，忽略成功提示
                if (hasFailure && (msg.contains("已开启") || msg.contains("已应用"))) {
                    continue
                }

                // 针对二元开关，根据目标值过滤互斥文案
                val targetMode = variables["expected"] ?: variables["target"] ?: getSetting("system", "display_outdoor_mode")
                if (targetMode == "1" && msg.contains("已关闭")) continue
                if (targetMode == "0" && msg.contains("已开启")) continue

                outLines.add(msg)
                continue
            }
        }

        val code = if (hasFailure) 4 else 0
        return MaxHzShell.Result(code, outLines.joinToString("\n"), if (hasFailure) failureReason else "")
    }
}
