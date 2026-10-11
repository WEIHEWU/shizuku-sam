package moe.shizuku.manager.maxhz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Reg009BugFixesContractTest {

    private val controllerSource by lazy { sourceFile("MaxHzController.kt").readText() }
    private val activitySource by lazy { sourceFile("MaxHzActivity.kt").readText() }
    private val prefsSource by lazy { sourceFile("MaxHzPrefs.kt").readText() }
    private val serviceSource by lazy { sourceFile("MaxHzAccessibilityService.kt").readText() }

    @Test
    fun tombstoneModeDoesNotContainGameVideoSpeed() {
        val tombstoneBody = controllerSource.substringAfter("fun applyMTombstoneMode()")
            .substringBefore("fun restoreMTombstoneMode()")
        assertFalse(
            "Tombstone mode must not set debug.game.video.speed",
            tombstoneBody.contains("debug.game.video.speed")
        )
        assertFalse(
            "Tombstone mode must not set debug.game.video.support",
            tombstoneBody.contains("debug.game.video.support")
        )

        val restoreTombstoneBody = controllerSource.substringAfter("fun restoreMTombstoneMode()")
            .substringBefore("fun applyMGameVideoSpeed()")
        assertFalse(
            "Restore tombstone must not touch debug.game.video.speed",
            restoreTombstoneBody.contains("debug.game.video.speed")
        )
    }

    @Test
    fun gameVideoSpeedHasStandaloneFunctionsAndPref() {
        assertTrue(controllerSource.contains("fun applyMGameVideoSpeed()"))
        assertTrue(controllerSource.contains("fun restoreMGameVideoSpeed()"))
        assertTrue(prefsSource.contains("KEY_M_GAME_VIDEO_SPEED_ENABLED"))
        assertTrue(prefsSource.contains("fun mGameVideoSpeedEnabled"))
        assertTrue(activitySource.contains("EntryItem(\"游戏视频加速\""))
    }

    @Test
    fun performanceModeDoesNotLockCpuOrGpuFrequency() {
        val perfBody = controllerSource.substringAfter("fun applyMPerformance(")
            .substringBefore("fun verifyGameAutoPerformance")
        assertTrue(
            "Fixed performance mode must be false to allow dynamic frequency boost",
            perfBody.contains("cmd power set-fixed-performance-mode-enabled false")
        )
        assertFalse(
            "Fixed performance mode must never be set to true",
            perfBody.contains("cmd power set-fixed-performance-mode-enabled true")
        )
        assertFalse(
            "GPU frequency must not be capped at 900MHz",
            perfBody.contains("debug.gpu.freq.max.lock")
        )
        assertFalse(
            "GPU DCVS dynamic scaling must not be disabled",
            perfBody.contains("debug.gpu.dcvs.disable")
        )
    }

    @Test
    fun gpuPerformancePermitsGpuBoostAndAvoidsExcessiveQueue() {
        val gpuPerfBody = controllerSource.substringAfter("fun applyMGpuPerformance()")
            .substringBefore("fun applyMGpuPowerSave()")
        assertTrue(
            "thermal.gpu_boost.disable must be 0 to allow boost",
            gpuPerfBody.contains("thermal.gpu_boost.disable 0")
        )
        assertTrue(
            "Max frame queue size must be 3 to prevent input latency",
            gpuPerfBody.contains("debug.hwui.max_frame_queue_size 3")
        )
        assertTrue(
            "Renderer thread count must be 4 to avoid thread contention",
            gpuPerfBody.contains("debug.hwui.renderer_thread_count 4")
        )
    }

    @Test
    fun gameAutoDecisionUsesOnlyConfiguredWhitelist() {
        val decisionBody = serviceSource.substringAfter("private fun handleGameAutoPerformance(foregroundPackage: String)")
            .substringBefore("if (gamePackages.isEmpty() && !isGameForeground)")
        assertTrue(
            "Game foreground decision must only check user configured gamePackages whitelist",
            decisionBody.contains("val isGameForeground = foregroundPackage in gamePackages")
        )
        assertFalse(
            "Game foreground decision must not automatically trigger on passthrough apps",
            decisionBody.contains("isGameForeground = foregroundPackage in gamePackages ||")
        )
    }

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
}
