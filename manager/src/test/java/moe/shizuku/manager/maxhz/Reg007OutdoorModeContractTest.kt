package moe.shizuku.manager.maxhz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Reg007OutdoorModeContractTest {

    private val controller = File("src/main/java/moe/shizuku/manager/maxhz/MaxHzController.kt").readText()
    private val activity = File("src/main/java/moe/shizuku/manager/maxhz/MaxHzActivity.kt").readText()

    @Test
    fun implementationWritesTheSamsungSystemSettingWithoutFakingManualBrightness() {
        val body = functionBody(controller, "fun setOutdoorMode", "fun applyBatteryProtection")
        assertTrue(body.contains("settings put system display_outdoor_mode ${'$'}expected"))
        assertTrue(body.contains("settings get system display_outdoor_mode"))
        assertFalse(body.contains("screen_brightness"))
        assertFalse(body.contains("screen_brightness_mode"))
    }

    @Test
    fun switchMapsDirectlyToOneAndZero() {
        val body = functionBody(controller, "fun setOutdoorMode", "fun applyBatteryProtection")
        assertTrue(body.contains("val expected = if (enabled) 1 else 0"))
        assertFalse(body.contains("backup_setting"))
        assertFalse(body.contains("rollback"))
        assertFalse(body.contains("dumpsys"))
    }

    @Test
    fun uiStateIsReadThroughShizukuInsteadOfTheUnprivilegedContentResolver() {
        val reader = functionBody(controller, "fun readOutdoorModeEnabled", "fun applyBatteryProtection")
        assertTrue(reader.contains("MaxHzShell.execRaw"))
        assertTrue(reader.contains("settings get system display_outdoor_mode"))

        val state = functionBody(activity, "private fun outdoorModeActive", "private fun passThroughRuntimeState")
        assertTrue(state.contains("MaxHzController.readOutdoorModeEnabled()"))
        assertFalse(state.contains("Settings.System.getInt"))
    }

    @Test
    fun uiUsesAStandaloneSwitchWithoutDetailPageOrConfirmation() {
        val refreshPage = functionBody(activity, "Page.REFRESH ->", "Page.ADVANCED ->")
        // 户外模式现在是「显示与视觉」组里的独立 EntryItem 开关（不再是独立卡片），
        // 也没有详情页。
        assertTrue(refreshPage.contains("EntryItem(\"户外高亮模式\""))
        assertFalse(refreshPage.contains("pushDetail(\"户外模式\""))

        // 切换通过 setOutdoorMode（写三星 display_outdoor_mode），无确认弹窗。
        val toggle = functionBody(activity, "EntryItem(\"户外高亮模式\"", "Page.ADVANCED ->")
        assertTrue(toggle.contains("MaxHzController.setOutdoorMode(on)"))
        assertFalse(toggle.contains("actionConfirmation"))
        assertFalse(activity.contains("private fun outdoorModeConfirmation"))
    }

    private fun functionBody(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        check(startIndex >= 0) { "Missing start marker: $start" }
        val endIndex = source.indexOf(end, startIndex + start.length)
        check(endIndex > startIndex) { "Missing end marker: $end" }
        return source.substring(startIndex, endIndex)
    }
}
