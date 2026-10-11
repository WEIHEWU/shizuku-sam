package moe.shizuku.manager.maxhz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Reg002DailyPowerSaveContractTest {

    private val root = locateRepoRoot()
    private val controller = File(
        root,
        "manager/src/main/java/moe/shizuku/manager/maxhz/MaxHzController.kt"
    ).readText()
    private val activity = File(
        root,
        "manager/src/main/java/moe/shizuku/manager/maxhz/MaxHzActivity.kt"
    ).readText()
    private val daily = functionBody(controller, "fun applyDailySaver(", "fun restoreDailySaver(")
    private val reader = functionBody(
        controller,
        "read_daily_power_saver_effective_state() {",
        "set_daily_power_saver_and_verify() {"
    )
    private val setter = functionBody(
        controller,
        "set_daily_power_saver_and_verify() {",
        "release_refresh_control() {"
    )

    @Test
    fun effectiveTruthUsesSamsungFrameworkCurrentStateWithoutSettingFallback() {
        assertContains(reader, "Battery Saver is currently:")
        assertFalse(reader.contains("mLowPowerModeEnabled=true"))
        assertFalse(reader.contains("settings get global low_power"))
    }

    @Test
    fun powerSaverWriteUsesBoundedEffectivePollingAndFailsClosed() {
        assertContains(setter, "cmd power set-mode")
        assertContains(setter, "POWER_SAVER_VERIFY_ATTEMPTS")
        assertContains(setter, "POWER_SAVER_VERIFY_INTERVAL_SECONDS")
        assertContains(setter, "STEP=power_saver_samsung_fallback")
        assertContains(setter, "settings put global low_power")
        assertContains(setter, "return 4")
        assertFalse(setter.contains("sleep 0.20\n"))
    }

    @Test
    fun dailyEmitsStableStepDiagnosticsAndCoreFailureCannotFallThrough() {
        listOf(
            "STEP=power_saver_write",
            "STEP=power_saver_verify"
        ).forEach { assertContains(setter, it) }
        listOf(
            "STEP=conflict_cleanup",
            "STEP=refresh_adaptive_write",
            "STEP=refresh_min_verify",
            "STEP=refresh_max_verify",
            "STEP=quick_doze_verify",
            "STEP=final_aggregate_verify"
        ).forEach { assertContains(daily, it) }
        assertContains(daily, "exit 4")
    }

    @Test
    fun dailyFinalPowerVerificationUsesEffectiveTruthAfterAllLaterWrites() {
        val finalVerify = daily.lastIndexOf("STEP=final_aggregate_verify")
        val finalTruthRead = daily.lastIndexOf("read_daily_power_saver_effective_state")
        assertTrue(finalVerify > 0)
        assertTrue(finalTruthRead > finalVerify)
    }

    @Test
    fun samsungPowerSaverGateIsBackedUpAppliedBeforeFrameworkCommitAndRestored() {
        val backup = functionBody(
            controller,
            "backup_compatible_saver() {",
            "restore_compatible_saver() {"
        )
        val restore = functionBody(
            controller,
            "restore_compatible_saver() {",
            "\n    \"\"\".trimIndent()"
        )
        val gateWrite = daily.indexOf("settings put system POWER_SAVE_MODE_OPEN 1")
        val frameworkCommit = daily.indexOf("set_daily_power_saver_and_verify 1")
        assertContains(backup, "POWER_SAVE_MODE_OPEN")
        assertContains(restore, "POWER_SAVE_MODE_OPEN")
        assertTrue("One UI gate must be written before cmd power set-mode", gateWrite >= 0 && frameworkCommit > gateWrite)
        val standardWrite = setter.indexOf("cmd power set-mode")
        val samsungFallback = setter.indexOf("settings put global low_power")
        val effectiveVerify = setter.lastIndexOf("read_daily_power_saver_effective_state")
        assertTrue("Samsung fallback is allowed only after the standard command", samsungFallback > standardWrite)
        assertTrue("Samsung fallback must still be followed by framework-effective verification", effectiveVerify > samsungFallback)
        assertFalse("Daily mode must not blindly write a literal low_power value", daily.contains("settings put global low_power 1"))
    }

    @Test
    fun uiOnlyCommitsDailyEnabledAfterShellSuccessAndReadsPowerManager() {
        val section = functionBody(activity, "private fun lowPowerStartSection(): View", "private fun quickDozeSection(): View")
        val state = functionBody(activity, "private fun dailySaverState(): String", "private fun batteryProtectionState(): String")
        assertContains(section, "if (result.code != 0)")
        assertContains(state, "isSystemPowerSaveModeOn()")
        assertContains(state, "PowerManager::class.java")
        // 「核心失败」看不出是谁干的、该怎么救，用户把它当成了 App 故障。
        // 真实含义是：pref 记着「开」，但系统省电已被某个性能命令关掉了。
        // 状态文字必须说清楚这件事，让用户知道关掉那个性能开关或重开一次即可恢复。
        assertContains(state, "已被性能模式覆盖")
        assertFalse(
            "Power-save state must not report an opaque core failure",
            state.contains("核心失败")
        )
    }

    @Test
    fun protectedRegressionsRemainPresent() {
        assertTrue(File(root, "manager/src/test/java/moe/shizuku/manager/maxhz/Reg001RestoreContractTest.kt").isFile)
        assertTrue(File(root, "manager/src/test/java/moe/shizuku/manager/maxhz/Reg003SuperPerformanceStateTest.kt").isFile)
        assertTrue(File(root, "manager/src/test/java/moe/shizuku/manager/maxhz/Reg004GameAutoContractTest.kt").isFile)
    }

    private fun functionBody(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        check(startIndex >= 0) { "Missing start marker: $start" }
        val endIndex = source.indexOf(end, startIndex + start.length)
        check(endIndex > startIndex) { "Missing end marker: $end" }
        return source.substring(startIndex, endIndex)
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("Missing expected contract marker: $expected", source.contains(expected))
    }

    private fun locateRepoRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(current, "manager/src/main/java/moe/shizuku/manager/maxhz/MaxHzController.kt").isFile) {
                return current
            }
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate repository root from ${System.getProperty("user.dir")}")
    }
}
