package moe.shizuku.manager.maxhz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「极致性能模式」开关与它的状态文字必须读同一个状态位。
 *
 * 这份契约是从两次真实故障里提炼出来的：
 *
 * 1. **假聚合**：状态文字曾经是把 5 个子 pref 与运算拼出来的，而开关位自己是一个
 *    pref。两边不是同一个真相，于是开关显示「已开」、文字显示「关闭」同时出现；
 *    单独改动任意一个子开关，总开关还会莫名翻转。
 * 2. **语义反转**：温控只有一个 `m_thermal_enabled` 位，「放宽温控」（极致性能）和
 *    「保护温控」（系统温控保护）都往里写 true。开了极致性能后「系统温控保护」
 *    显示为开启，可系统实际在放宽温控 —— 这是会真发热的错误。
 *
 * 所以这里锁死三件事：状态读单一 pref、温控分方向记、两条入口（主页面开关与
 * 二级页卡片）写同一组状态位。
 */
class Reg003SuperPerformanceStateTest {

    private val activitySource by lazy { sourceFile("MaxHzActivity.kt").readText() }
    private val prefsSource by lazy { sourceFile("MaxHzPrefs.kt").readText() }

    private val stateBody by lazy {
        activitySource.substringAfter("private fun superPerformanceState(): String")
            .substringBefore("private fun currentInteractionRisk")
    }

    /** 唯一写入口 markSuperPerformanceState 的函数体 */
    private val markSuperPerformanceBody by lazy {
        prefsSource.substringAfter("fun markSuperPerformanceState(")
            .substringBefore("fun gameAutoSessionActive")
    }

    /** 主页面「极致性能模式」那一整条 EntryItem 定义 */
    private val toggleBody by lazy {
        activitySource.substringAfter("EntryItem(\"极致性能模式\"")
            .substringBefore("pushDetail(\"极致性能模式\")")
    }

    /** 二级页「一键满血性能模式」卡片 */
    private val sectionBody by lazy {
        activitySource.substringAfter("private fun superPerformanceSection(): View")
            .substringBefore("private fun repairToolsSection")
    }

    @Test
    fun stateTextReadsTheSameSinglePreferenceAsTheToggle() {
        assertTrue(
            "State text must read the super-performance preference",
            stateBody.contains("MaxHzPrefs.superPerformanceEnabled(this)")
        )
        assertTrue(
            "Toggle must read the super-performance preference",
            toggleBody.contains("isOn = { MaxHzPrefs.superPerformanceEnabled(")
        )
    }

    @Test
    fun stateTextMustNotAggregateSubPreferences() {
        // 五个子项里只要有一个没跟上就会报「关闭」，用户看到自己刚打开的开关
        // 写着「关闭」，只能再点一次，越点越乱。
        listOf(
            "mMainMode(this)",
            "mGpuMode(this)",
            "mRefresh120Enabled(this)",
            "mVulkanEnabled(this)"
        ).forEach { marker ->
            assertFalse(
                "State text must not AND-aggregate $marker",
                stateBody.contains(marker)
            )
        }
    }

    @Test
    fun stateTextMustNotLaunchOrCommitAnything() {
        assertFalse(
            "The UI mapper must not launch or commit an asynchronous query",
            stateBody.contains("launch") || stateBody.contains("setM")
        )
    }

    @Test
    fun bothEntryPointsCommitTheSameSuperPerformancePreference() {
        // 两条入口（主页面开关、二级页卡片）现在都走 markSuperPerformanceState 这个
        // 唯一写入口，而不是各自散写 —— 散写是「二级页开了满血、主页面开关还是关闭」
        // 那类错配的根因。
        assertTrue(toggleBody.contains("MaxHzPrefs.markSuperPerformanceState(this@MaxHzActivity, on)"))
        assertTrue(
            "Section enable must commit through the single write entry",
            sectionBody.contains("MaxHzPrefs.markSuperPerformanceState(this@MaxHzActivity, true, thermalRelaxed = true)")
        )
        assertTrue(
            "Section disable must commit through the single write entry",
            sectionBody.contains("MaxHzPrefs.markSuperPerformanceState(this@MaxHzActivity, false)")
        )
    }

    @Test
    fun thermalRelaxationUsesItsOwnPreferenceNeverTheDeprecatedSharedBit() {
        // 温控方向在 markSuperPerformanceState 内部写 relaxed 位，
        // 绝不能碰旧的 m_thermal_enabled 共享位（会把「放宽」误记成「保护」）。
        assertTrue(
            "Super performance relaxes thermal limits, so it must record the relaxed bit",
            markSuperPerformanceBody.contains("setMThermalRelaxed(")
        )
        assertFalse(
            "Deprecated shared thermal bit reverses the meaning of the protection switch",
            markSuperPerformanceBody.contains("setMThermalEnabled(")
        )
    }

    @Test
    fun protectionAndRelaxationAreMutuallyExclusive() {
        // 开极致性能（放宽）时必须把 protect 清掉，否则两个开关同时显示开启，
        // 而系统只能处在其中一种状态。
        assertTrue(
            markSuperPerformanceBody.contains("if (on && thermalRelaxed) setMThermalProtect(context, false)")
        )
        // 关闭时必须把 relaxed 一起复位，避免「已开启 + 温控已恢复」这种自相矛盾的显示。
        assertTrue(markSuperPerformanceBody.contains("if (!on) setMThermalRelaxed(context, false)"))
    }

    @Test
    fun pageReentryReevaluatesTheSamePersistedState() {
        assertTrue(activitySource.contains("{ superPerformanceState() }"))
    }

    @Test
    fun protectedRegressionsRemainPresent() {
        assertTrue(File(root, "manager/src/test/java/moe/shizuku/manager/maxhz/Reg001RestoreContractTest.kt").isFile)
        assertTrue(File(root, "manager/src/test/java/moe/shizuku/manager/maxhz/Reg002DailyPowerSaveContractTest.kt").isFile)
        assertTrue(File(root, "manager/src/test/java/moe/shizuku/manager/maxhz/Reg004GameAutoContractTest.kt").isFile)
    }

    private val root: File by lazy { locateRepoRoot() }

    private fun sourceFile(name: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val direct = File(directory, "src/main/java/moe/shizuku/manager/maxhz/$name")
            if (direct.isFile) return direct
            val fromRoot = File(directory, "manager/src/main/java/moe/shizuku/manager/maxhz/$name")
            if (fromRoot.isFile) return fromRoot
            directory = directory.parentFile
        }
        error("Unable to locate MaxHz source file: $name")
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
