package moe.shizuku.manager.maxhz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Reg001RestoreContractTest {

    private val activitySource by lazy { sourceFile("MaxHzActivity.kt").readText() }
    private val prefsSource by lazy { sourceFile("MaxHzPrefs.kt").readText() }

    @Test
    fun totalRestoreNeverClearsUserAppRefreshRulesForAnyResult() {
        val body = functionBody("restoreAllAndUpdateUi", "safeRestoreStep")

        assertFalse(
            "Total restore must preserve app_refresh_rules for success, failure, partial success, and repeated runs",
            body.contains("clearAppRefreshRules") || body.contains("KEY_APP_REFRESH_RULES")
        )
    }

    @Test
    fun explicitClearRemainsAndCommitsOnlyAfterSystemRestoreSucceeds() {
        val section = activitySource.substringAfter(".setTitle(\"清空刷新率规则\")")
            .substringBefore("text = \"当前规则\"")
        val restoreIndex = section.indexOf("MaxHzController.restoreRefreshRateDefaults()")
        val successIndex = section.indexOf("if (it.code == 0)")
        val clearIndex = section.indexOf("MaxHzPrefs.clearAppRefreshRules")

        assertTrue("The explicit clear action must remain", clearIndex >= 0)
        assertTrue("System restore must run before destructive persistence", restoreIndex >= 0 && restoreIndex < clearIndex)
        assertTrue("Destructive persistence must be guarded by a successful result", successIndex in (restoreIndex + 1)..<clearIndex)
        assertTrue("Cancellation must remain non-destructive", section.contains(".setNegativeButton(\"取消\", null)"))
    }

    @Test
    fun appRefreshRulesUseOneDurableKeyWithoutGlobalPreferenceClear() {
        assertTrue(prefsSource.contains("KEY_APP_REFRESH_RULES = \"app_refresh_rules\""))
        assertTrue(prefsSource.contains("putString(KEY_APP_REFRESH_RULES"))
        assertTrue(prefsSource.contains("sortedBy { it.packageName }"))
        assertTrue(prefsSource.contains("if (normalized.packageName.isBlank()) return"))
        assertFalse("MaxHz preferences must not have a bulk SharedPreferences clear", prefsSource.contains(".edit().clear()"))
    }

    private fun functionBody(name: String, nextName: String): String {
        return activitySource.substringAfter("private fun $name")
            .substringBefore("private fun $nextName")
    }

    private fun sourceFile(name: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val direct = File(directory, "src/main/java/moe/shizuku/manager/maxhz/$name")
            if (direct.isFile) return direct
            val fromRoot = File(directory, "manager/src/main/java/moe/shizuku/manager/maxhz/$name")
            if (fromRoot.isFile) return fromRoot
            directory = directory?.parentFile
        }
        error("Unable to locate MaxHz source file: $name")
    }
}
