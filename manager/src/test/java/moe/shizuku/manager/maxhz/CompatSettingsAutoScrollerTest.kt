package moe.shizuku.manager.maxhz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompatSettingsAutoScrollerTest {

    private fun sourceFile(name: String): File {
        val root = File(System.getProperty("user.dir") ?: ".")
        val candidate = File(root, "manager/src/main/java/moe/shizuku/manager/maxhz/$name")
        if (candidate.exists()) return candidate
        return File(root, "src/main/java/moe/shizuku/manager/maxhz/$name")
    }

    private fun homeActivitySource(): String {
        val root = File(System.getProperty("user.dir") ?: ".")
        val candidate = File(root, "manager/src/main/java/moe/shizuku/manager/home/HomeActivity.kt")
        if (candidate.exists()) return candidate.readText()
        return File(root, "src/main/java/moe/shizuku/manager/home/HomeActivity.kt").readText()
    }

    @Test
    fun testScrollerPendingStateTransitions() {
        CompatSettingsAutoScroller.cancel()
        assertFalse(CompatSettingsAutoScroller.isPending())

        CompatSettingsAutoScroller.request()
        assertTrue(CompatSettingsAutoScroller.isPending())

        CompatSettingsAutoScroller.cancel()
        assertFalse(CompatSettingsAutoScroller.isPending())
    }

    @Test
    fun testHomeActivityHooksCompatScroller() {
        val code = homeActivitySource()
        assertTrue(
            "HomeActivity must call CompatSettingsAutoScroller.request()",
            code.contains("CompatSettingsAutoScroller.request()")
        )
        assertTrue(
            "HomeActivity must handle Samsung sec_more_settings key fallback",
            code.contains("val prefKey = if (isSamsung) \"sec_more_settings\" else \"disable_app_compatibility_policies\"")
        )
        assertFalse(
            "HomeActivity must NOT show annoying toast prompts when opening compat policies",
            code.contains("toast_dev_settings_opened_compat_policies_auto")
        )
    }

    @Test
    fun testAccessibilityServiceIntegratesAutoScroller() {
        val serviceCode = sourceFile("MaxHzAccessibilityService.kt").readText()
        assertTrue(
            "Accessibility service must delegate to CompatSettingsAutoScroller",
            serviceCode.contains("CompatSettingsAutoScroller.onAccessibilityEvent(event, this)")
        )
        assertTrue(
            "Accessibility service must cancel CompatSettingsAutoScroller on destroy",
            serviceCode.contains("CompatSettingsAutoScroller.cancel()")
        )
    }

    @Test
    fun testMoreSettingsKeywordsArePrecise() {
        val code = sourceFile("CompatSettingsAutoScroller.kt").readText()
        assertTrue(
            "Must target More settings in Chinese",
            code.contains("\"更多设置\"")
        )
        assertTrue(
            "Must target More settings in English",
            code.contains("\"More settings\"")
        )
        assertFalse(
            "Must NOT contain ambiguous single word '更多' which false-matches '了解更多'",
            code.contains("\"更多\",")
        )
        assertFalse(
            "Must NOT contain ambiguous single word 'More'",
            code.contains("\"More\",")
        )
        assertFalse(
            "Must NOT abort prematurely using SemRecyclerView actionList",
            code.contains("canScrollFurther")
        )
    }
}
