package moe.shizuku.manager.maxhz

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.nio.charset.StandardCharsets

class ShellArgMaxInstrumentation : Instrumentation() {

    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle) {
        this.arguments = arguments
        start()
    }

    override fun onStart() {
        val mode = arguments.getString("mode") ?: "small"
        val startedAt = System.nanoTime()
        val result = try {
            waitForShizuku()
            runMode(mode)
        } catch (t: Throwable) {
            MaxHzShell.Result(99, "", t.stackTraceToString())
        }
        val summary = Bundle().apply {
            putString("mode", mode)
            putString("code", result.code.toString())
            putString("stdout", result.out)
            putString("stderr", result.err)
            putString("elapsed_ms", ((System.nanoTime() - startedAt) / 1_000_000L).toString())
        }
        finish(0, summary)
    }

    private fun waitForShizuku() {
        repeat(50) {
            if (MaxHzShell.isReady()) return
            Thread.sleep(100)
        }
        error("Shizuku binder was not ready after 5 seconds")
    }

    private fun runMode(mode: String): MaxHzShell.Result {
        return when (mode) {
            "small" -> verifySmallScript()
            "failure" -> verifyFailureScript()
            "timeout" -> verifyTimeoutScript()
            "large" -> verifyLargeScript()
            "leak" -> verifyRepeatedRuns()
            "refresh" -> MaxHzController.applyAdaptiveMax(120, 10)
            "daily" -> MaxHzController.applyDailySaver(80, false)
            "daily_restore" -> MaxHzController.restoreDailySaver()
            "power_truth" -> readPowerTruth()
            "daily_ui_state" -> readDailyUiState()
            "total_restore_ui" -> restoreAllThroughActivity()
            "m_power_save" -> MaxHzController.applyMPowerSave()
            "total_restore" -> MaxHzController.restoreAllPublicDefaults(emptyList())
            "super_apply" -> MaxHzController.applySuperPerformance()
            "super_restore" -> MaxHzController.restoreSuperPerformance()
            "game_auto_entry" -> combine(
                MaxHzController.restoreCompatibleSaverAdaptive(),
                MaxHzController.restoreQuickDoze(),
                MaxHzController.applyMPerformance(),
                MaxHzController.applyMRefresh120()
            )
            "game_auto_restore_saver" -> MaxHzController.restoreCompatibleSaverAdaptive()
            "game_auto_restore_doze" -> MaxHzController.restoreQuickDoze()
            "game_auto_m_performance" -> MaxHzController.applyMPerformance()
            "game_auto_refresh_120" -> MaxHzController.applyMRefresh120()
            "game_auto_gpu" -> MaxHzController.applyMGpuPerformance()
            "game_auto_vulkan" -> MaxHzController.applyMVulkan()
            "game_auto_thermal" -> MaxHzController.applyMOriginalThermal()
            "game_auto_exit" -> combine(
                MaxHzController.restoreMMainMode(),
                MaxHzController.restoreMGpu(),
                MaxHzController.restoreMVulkan(),
                MaxHzController.restoreMOriginalThermal(),
                MaxHzController.restoreRefreshRateDefaults()
            )
            else -> MaxHzShell.Result(98, "", "unknown test mode: ${mode}")
        }
    }

    private fun readPowerTruth(): MaxHzShell.Result {
        val api = targetContext.getSystemService(PowerManager::class.java)?.isPowerSaveMode
        val shell = MaxHzShell.execRaw(
            """
            effective="${'$'}(dumpsys power 2>/dev/null | grep -m 1 -E '^[[:space:]]*Battery Saver is currently: (ON|OFF)' | sed -n 's/.*currently: //p')"
            low_power="${'$'}(settings get global low_power 2>/dev/null)"
            low_power_sticky="${'$'}(settings get global low_power_sticky 2>/dev/null)"
            printf 'dumpsys_effective=%s\nglobal.low_power=%s\nglobal.low_power_sticky=%s\n' "${'$'}effective" "${'$'}low_power" "${'$'}low_power_sticky"
            """.trimIndent(),
            timeoutMs = 2_000
        )
        return MaxHzShell.Result(
            shell.code,
            "PowerManager.isPowerSaveMode=$api\n${shell.out}",
            shell.err
        )
    }

    private fun readDailyUiState(): MaxHzShell.Result {
        val activity = startActivitySync(
            Intent(targetContext, MaxHzActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitForIdleSync()
        Thread.sleep(1_000)
        waitForIdleSync()
        val allText = mutableListOf<String>()
        collectText(activity.window.decorView, allText)
        val relevant = allText.filter {
            it.contains("日用省电") || it in setOf("开启", "关闭", "部分生效", "核心失败")
        }
        val mappedState = MaxHzActivity::class.java.getDeclaredMethod("dailySaverState").run {
            isAccessible = true
            invoke(activity) as String
        }
        val api = targetContext.getSystemService(PowerManager::class.java)?.isPowerSaveMode
        runOnMainSync { activity.finish() }
        return MaxHzShell.Result(
            0,
            "Activity=${activity.javaClass.name}\nPowerManager.isPowerSaveMode=$api\nManager.dailySaverState=$mappedState\nManager.visible_text=${relevant.joinToString(" | ")}",
            ""
        )
    }

    private fun restoreAllThroughActivity(): MaxHzShell.Result {
        val activity = startActivitySync(
            Intent(targetContext, MaxHzActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MaxHzActivity
        waitForIdleSync()
        val result = MaxHzActivity::class.java.getDeclaredMethod("restoreAllAndUpdateUi").run {
            isAccessible = true
            invoke(activity) as MaxHzShell.Result
        }
        Thread.sleep(1_000)
        val mappedState = MaxHzActivity::class.java.getDeclaredMethod("dailySaverState").run {
            isAccessible = true
            invoke(activity) as String
        }
        runOnMainSync { activity.finish() }
        return MaxHzShell.Result(
            result.code,
            listOf(result.out, "Manager.dailySaverState=$mappedState").filter { it.isNotBlank() }.joinToString("\n"),
            result.err
        )
    }

    private fun collectText(view: View, output: MutableList<String>) {
        if (view is TextView) {
            view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(output::add)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectText(view.getChildAt(index), output)
        }
    }

    private fun verifySmallScript(): MaxHzShell.Result {
        val result = MaxHzShell.exec("printf 'stdout-ok\\n'; printf 'stderr-ok\\n' >&2")
        return if (result.code == 0 && result.out == "stdout-ok\n" && result.err == "stderr-ok\n") {
            MaxHzShell.Result(0, "small stdout/stderr verified", "")
        } else {
            MaxHzShell.Result(98, result.out, "small script mismatch: code=${result.code}; ${result.err}")
        }
    }

    private fun verifyFailureScript(): MaxHzShell.Result {
        val result = MaxHzShell.exec("printf 'before-exit\\n'; printf 'failure-detail\\n' >&2; exit 7")
        return if (result.code == 7 && result.out == "before-exit\n" && result.err == "failure-detail\n") {
            MaxHzShell.Result(0, "non-zero exit 7 and both streams verified", "")
        } else {
            MaxHzShell.Result(98, result.out, "failure script mismatch: code=${result.code}; ${result.err}")
        }
    }

    private fun verifyTimeoutScript(): MaxHzShell.Result {
        val result = MaxHzShell.execRaw("exec sleep 5", timeoutMs = 100)
        return if (result.code == ShellScriptTransport.CODE_TIMEOUT && result.err.contains("执行超时")) {
            MaxHzShell.Result(0, "timeout code and diagnostic verified", "")
        } else {
            MaxHzShell.Result(98, result.out, "timeout mismatch: code=${result.code}; ${result.err}")
        }
    }

    private fun verifyLargeScript(): MaxHzShell.Result {
        val script = buildString {
            repeat(8_000) { index ->
                append(": # device-stdin-padding-")
                append(index)
                append('\n')
            }
            append(
                """
                head -c 200000 /dev/zero | tr '\000' O
                head -c 200000 /dev/zero | tr '\000' E >&2
                exit 9
                """.trimIndent()
            )
        }
        val scriptBytes = script.toByteArray(StandardCharsets.UTF_8).size
        val result = MaxHzShell.exec(script, timeoutSeconds = 20)
        val stdoutBytes = result.out.toByteArray(StandardCharsets.UTF_8).size
        val stderrBytes = result.err.toByteArray(StandardCharsets.UTF_8).size
        val valid = scriptBytes > 200_000 &&
            result.code == 9 &&
            stdoutBytes == 200_000 &&
            stderrBytes == 200_000 &&
            result.out.all { it == 'O' } &&
            result.err.all { it == 'E' } &&
            !result.err.contains("Argument list too long")
        return if (valid) {
            MaxHzShell.Result(
                0,
                "large script verified: script_bytes=${scriptBytes}, stdout_bytes=${stdoutBytes}, stderr_bytes=${stderrBytes}, shell_exit=9",
                ""
            )
        } else {
            MaxHzShell.Result(
                98,
                "script_bytes=${scriptBytes}, stdout_bytes=${stdoutBytes}, stderr_bytes=${stderrBytes}, stdout_tail=${result.out.takeLast(80)}",
                "large script mismatch: shell_exit=${result.code}; stderr_tail=${result.err.takeLast(240)}"
            )
        }
    }

    private fun verifyRepeatedRuns(): MaxHzShell.Result {
        val fdsBefore = File("/proc/self/fd").list()?.size ?: -1
        repeat(30) { index ->
            val result = MaxHzShell.execRaw("printf 'run-${index}\\n'", timeoutMs = 2_000)
            if (result.code != 0 || result.out != "run-${index}\n" || result.err.isNotEmpty()) {
                return MaxHzShell.Result(98, result.out, "repeat ${index} failed: code=${result.code}; ${result.err}")
            }
        }
        Thread.sleep(200)
        val fdsAfter = File("/proc/self/fd").list()?.size ?: -1
        val liveThreads = Thread.getAllStackTraces().keys.count {
            it.isAlive && it.name.startsWith("MaxHzShell-")
        }
        val fdsValid = fdsBefore < 0 || fdsAfter <= fdsBefore + 3
        return if (liveThreads == 0 && fdsValid) {
            MaxHzShell.Result(0, "repeat verified: fds_before=${fdsBefore}, fds_after=${fdsAfter}, live_transport_threads=0", "")
        } else {
            MaxHzShell.Result(98, "fds_before=${fdsBefore}, fds_after=${fdsAfter}", "live_transport_threads=${liveThreads}")
        }
    }

    private fun combine(vararg results: MaxHzShell.Result): MaxHzShell.Result {
        return MaxHzShell.Result(
            code = results.firstOrNull { it.code != 0 }?.code ?: 0,
            out = results.map { it.out }.filter { it.isNotBlank() }.joinToString("\n"),
            err = results.map { it.err }.filter { it.isNotBlank() }.joinToString("\n")
        )
    }
}
