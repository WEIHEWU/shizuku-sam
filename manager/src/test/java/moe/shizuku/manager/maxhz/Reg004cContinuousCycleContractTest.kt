package moe.shizuku.manager.maxhz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Reg004cContinuousCycleContractTest {

    private val prefsSource = File(
        "src/main/java/moe/shizuku/manager/maxhz/MaxHzPrefs.kt"
    ).readText()
    private val activitySource = File(
        "src/main/java/moe/shizuku/manager/maxhz/MaxHzActivity.kt"
    ).readText()
    private val accessibilitySource = File(
        "src/main/java/moe/shizuku/manager/maxhz/MaxHzAccessibilityService.kt"
    ).readText()

    @Test
    fun gameAutoKeepsTheExistingForegroundRuntimeAliveAcrossBackgroundCycles() {
        val body = functionBody(prefsSource, "requiresBackgroundService", "}")
        assertTrue(
            "Game Auto runs delayed entry/restore and Shell work after the Manager UI backgrounds; " +
                "it must keep the existing MaxHz foreground runtime active",
            body.contains("gameAutoPerformanceEnabled(context)")
        )
    }

    @Test
    fun gameAutoToggleImmediatelyReconcilesTheForegroundRuntime() {
        val body = functionBody(activitySource, "gameAutoPerformanceCard", "gameAutoOptionGroup")
        val preferenceWrite = body.indexOf("setGameAutoPerformanceEnabled")
        val serviceReconcile = body.indexOf("updateMaxHzServiceState()", preferenceWrite)
        assertTrue("Missing Game Auto preference write", preferenceWrite >= 0)
        assertTrue(
            "The foreground runtime must start on enable and stop when no feature needs it",
            serviceReconcile > preferenceWrite
        )
    }

    @Test
    fun pendingGameAutoCycleKeepsCpuAwakeAndAlwaysReleasesTheWakeLock() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val body = functionBody(accessibilitySource, "handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"))
        assertTrue(body.contains("acquireGameAutoWakeLock"))
        val finallyBlock = body.substring(body.indexOf("finally"))
        assertTrue(finallyBlock.contains("releaseGameAutoWakeLock"))
    }

    @Test
    fun screenOffCannotDiscardAnAlreadyScheduledExitRestore() {
        val body = functionBody(accessibilitySource, "applyForForeground", "handleGameAutoPerformance")
        val nonInteractive = body.substring(
            body.indexOf("if (!isScreenInteractive())"),
            body.indexOf("handleGameAutoPerformance")
        )
        assertTrue(nonInteractive.contains("GAME_AUTO_TARGET_ENTRY"))
        assertTrue(nonInteractive.contains("gameAutoJob?.cancel()"))
        assertFalse(
            "Screen-off events may cancel a pending entry, but must preserve a pending exit restore",
            nonInteractive.contains("pendingGameAutoTarget = null\n            return")
        )
    }

    @Test
    fun pendingExitRechecksTheRealForegroundBeforeItCanRestore() {
        val scheduling = functionBody(
            accessibilitySource,
            "handleGameAutoPerformance",
            "isGameAutoTargetStillDesired"
        )
        val reconcile = scheduling.indexOf("reconcilePendingGameAutoExit")
        val operationStart = scheduling.indexOf("gameAutoApplying = true")
        assertTrue(
            "A repeated A -> B task switch can omit B's accessibility event; the pending exit " +
                "must perform one fresh foreground reconciliation before restore",
            reconcile >= 0
        )
        assertTrue("Foreground reconciliation must precede restore execution", reconcile < operationStart)

        val helper = functionBody(
            accessibilitySource,
            "reconcilePendingGameAutoExit",
            "isGameAutoTargetStillDesired"
        )
        assertTrue(helper.contains("MaxHzForegroundApp.currentWindowPackage()"))
        assertTrue(helper.contains("actualPackage in gamePackages"))
        assertTrue(helper.contains("pendingGameAutoTarget = null"))
    }

    @Test
    fun completedJobsAndOldGenerationsCannotBlockTheNextEntry() {
        val body = functionBody(accessibilitySource, "handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        assertTrue(body.contains("gameAutoJob?.isActive == true"))
        assertTrue(body.contains("val generation = ++gameAutoGeneration"))
        assertTrue(body.contains("gameAutoGeneration != generation"))
        assertTrue(body.contains("gameAutoApplying = false"))
        assertTrue(body.contains("if (pendingGameAutoTarget == target)"))
        assertFalse(
            "A completed Job reference must never be treated as an active-cycle blocker",
            body.contains("gameAutoJob != null) return")
        )
    }

    @Test
    fun restoreCommitClearsAppliedSessionBeforeTheNextEntry() {
        val body = functionBody(accessibilitySource, "handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        val exitCommit = body.indexOf("activeGameAutoPackage = null")
        val persistedClear = body.indexOf("clearGameAutoActiveSession", exitCommit)
        val finallyCleanup = body.indexOf("finally", persistedClear)
        assertTrue(exitCommit >= 0)
        assertTrue(persistedClear > exitCommit)
        assertTrue(finallyCleanup > persistedClear)
        assertTrue(body.substring(finallyCleanup).contains("gameAutoApplying = false"))
    }

    @Test
    fun everyShellStepCreatesItsOwnRemoteProcessAndClosesTransportStreams() {
        val shell = File("src/main/java/moe/shizuku/manager/maxhz/MaxHzShell.kt").readText()
        val transport = File("src/main/java/moe/shizuku/manager/maxhz/ShellScriptTransport.kt").readText()
        // 进程创建已从本地反射（newProcessMethod.invoke）收敛到 ShizukuShellBridge 单点；
        // 每次执行通过 startProcess 回调创建独立进程。
        assertTrue(shell.contains("ShizukuShellBridge.newShellProcess()"))
        assertTrue(shell.contains("ShellScriptTransport.execute("))
        assertTrue(transport.contains("process.outputStream.use"))
        assertTrue(transport.contains("closeProcessStreams(process)"))
        assertTrue(transport.contains("process.destroy()"))
    }

    private fun functionBody(source: String, startName: String, nextName: String): String {
        val start = source.indexOf("fun $startName")
        val end = if (nextName == "}") {
            source.indexOf("\n}", start + 1)
        } else {
            source.indexOf("fun $nextName", start + 1)
        }
        assertTrue("Missing function $startName", start >= 0)
        assertTrue("Missing boundary after $startName", end > start)
        return source.substring(start, end)
    }
}
