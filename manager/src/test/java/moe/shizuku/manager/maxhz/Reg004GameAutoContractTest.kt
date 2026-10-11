package moe.shizuku.manager.maxhz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Reg004GameAutoContractTest {

    private val source = File(
        "src/main/java/moe/shizuku/manager/maxhz/MaxHzAccessibilityService.kt"
    ).readText()

    @Test
    fun entryUsesNamedFailFastStepsInsteadOfEagerShellCalls() {
        val body = functionBody("applyGameAutoEntry", "applyGameAutoExit")
        assertTrue(
            "Game Auto entry must use the named step runner so the first non-zero/timeout stops later writes",
            body.contains("runGameAutoSteps(")
        )
        assertFalse(
            "Eagerly constructing a list executes later Shell steps even after an earlier timeout",
            body.contains("mutableListOf(\n            MaxHzController") || body.contains("combineResults(")
        )
        assertTrue("Entry steps must be deferred lambdas", body.contains("GameAutoStep("))
        listOf("restore_saver", "restore_quick_doze", "m_performance", "refresh_120").forEach { step ->
            assertTrue("Missing Game Auto entry diagnostic step: $step", body.contains("\"$step\""))
        }
    }

    @Test
    fun entryIsSingleTierWithOnlyThermalRelaxationGated() {
        val body = functionBody("applyGameAutoEntry", "applyGameAutoExit")
        // 三档档位（均衡 / 高性能 / 极限）已废弃：进游戏只有一种目标状态 —— 极致性能。
        assertFalse("Profile tiers must be removed", body.contains("GAME_AUTO_PROFILE_HIGH"))
        assertFalse("Profile tiers must be removed", body.contains("GAME_AUTO_PROFILE_EXTREME"))
        // gpu / vulkan 现在无条件执行（属于极致性能的固定组成）。
        listOf("gpu_performance", "vulkan").forEach { step ->
            assertTrue("Missing entry step: $step", body.contains("\"$step\""))
        }
        // 唯一可配置的是「是否放宽温控」，由独立的 gameAutoThermalRelaxed 开关控制。
        assertTrue(body.contains("MaxHzPrefs.gameAutoThermalRelaxed(this)"))
        assertTrue(body.contains("\"thermal_relax\""))
        assertTrue(body.contains("if (relaxed)"))
        // 进游戏前必须先拍快照，退出时才能还原到进游戏前的状态。
        assertTrue(body.contains("MaxHzPrefs.captureGameAutoSnapshot(this)"))
    }

    @Test
    fun exitUsesTheSameNamedFailFastStepContract() {
        val body = functionBody("applyGameAutoExit", "runGameAutoSteps")
        assertTrue(body.contains("runGameAutoSteps("))
        listOf("restore_m_main", "restore_gpu", "restore_vulkan", "restore_thermal").forEach { step ->
            assertTrue("Missing Game Auto exit diagnostic step: $step", body.contains("\"$step\""))
        }
        // 每个 shell 调用都必须包在 GameAutoStep 的延迟 lambda 里，由 runGameAutoSteps
        // 在第一个失败处截停 —— 绝不能像旧代码那样在 runner 之外急切地把多个
        // restore 用 combineResults 拼起来，那会让第一个失败之后仍继续恢复后面的子系统。
        // 唯一的 combineResults 只能出现在 apply_super_performance 这一步内部，
        // 用于合并同一步的「applySuperPerformance」与「温控方向」两个结果。
        val applyStep = body.indexOf("GameAutoStep(\"apply_super_performance\")")
        assertTrue("apply_super_performance step must be deferred", applyStep >= 0)
        assertFalse(
            "Exit must not eagerly chain restore calls before the step runner",
            body.substring(0, applyStep).contains("combineResults(")
        )
    }

    @Test
    fun stepRunnerRecordsTimingExitAndBothStreams() {
        val body = functionBody("runGameAutoSteps", "gameAutoFailureMessage")
        assertTrue("Step runner must stop at the first non-zero result", body.contains("result.code != 0"))
        assertTrue("Step runner must record elapsed time", body.contains("elapsedMs"))
        assertTrue("Step runner must retain stdout", body.contains("result.out"))
        assertTrue("Step runner must retain stderr", body.contains("result.err"))
        assertTrue("Step runner must identify the first failed step", body.contains("firstFailedStep"))
    }

    @Test
    fun failureUiIncludesOperationPackageAndStepTimeline() {
        val scheduling = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        assertTrue(scheduling.contains("gameAutoFailureMessage("))
        assertTrue(scheduling.contains("foregroundPackage"))
        assertTrue(scheduling.contains("generation"))
    }

    @Test
    fun entryPersistsCleanupOwnershipBeforeWritesAndCommitsOnlyAfterVerification() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        assertTrue(body.contains("result.code == 0 && stillDesired"))
        val operation = body.indexOf("applyGameAutoEntry(")
        val cleanupJournal = body.indexOf("setGameAutoActiveSession")
        val success = body.indexOf("result.code == 0 && stillDesired")
        val finalCommit = body.lastIndexOf("setGameAutoActiveSession")
        assertTrue("Cleanup ownership must exist before the first entry write", cleanupJournal in 0..<operation)
        assertTrue("Final applied commit still requires zero result and current intent", finalCommit > success)
    }

    @Test
    fun failedPartialEntryKeepsCleanupOwnershipUnlessCompensationSucceeds() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        val compensation = body.indexOf("compensateFailedGameAutoOperation(isEntry)")
        val compensationSuccess = body.indexOf("isEntry && compensation.code == 0", compensation)
        val clearActive = body.indexOf("activeGameAutoPackage = null", compensationSuccess)
        val clearPersisted = body.indexOf("clearGameAutoActiveSession", clearActive)
        assertTrue(compensation >= 0)
        assertTrue(compensationSuccess > compensation)
        assertTrue(clearActive > compensationSuccess)
        assertTrue(clearPersisted > clearActive)
        assertTrue(body.contains("!hasActiveGameAutoSession()"))
    }

    @Test
    fun disabledEmptyAndNonMatchingPackagesCannotEnter() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        val enabledGuard = body.indexOf("if (!MaxHzPrefs.gameAutoPerformanceEnabled(this))")
        val packageRead = body.indexOf("gameAutoPerformancePackages(this)")
        val isGameRead = body.indexOf("foregroundPackage in gamePackages")
        val emptyGuard = body.indexOf("if (gamePackages.isEmpty() && !isGameForeground)")
        val entryTarget = body.indexOf("GAME_AUTO_TARGET_ENTRY")
        // 禁用必须先于读名单；读名单必须先于判定是否为游戏；空名单 + 非透传游戏
        // 必须先于任何 entry 调度被拦截。
        assertTrue(enabledGuard >= 0 && enabledGuard < packageRead)
        assertTrue(isGameRead > packageRead)
        assertTrue(emptyGuard > isGameRead)
        assertTrue(entryTarget > emptyGuard)
        assertFalse("Empty-list handling must not erase the configured package set", body.contains("setGameAutoPerformancePackages"))
    }

    @Test
    fun repeatedEventsAndOldGenerationsCannotOverlapOrCommit() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        assertTrue(body.contains("pendingGameAutoTarget == target && gameAutoJob?.isActive == true"))
        assertTrue(body.contains("gameAutoJob?.cancel()"))
        assertTrue(body.contains("gameAutoGeneration != generation"))
        assertTrue(body.contains("while (gameAutoApplying)"))
        assertTrue(body.contains("pendingGameAutoTarget != target || gameAutoGeneration != generation"))
    }

    @Test
    fun transientSystemWindowsCannotCancelAnExitCreatedByAnExplicitForeground() {
        val start = source.indexOf("override fun onAccessibilityEvent")
        val end = source.indexOf("override fun onInterrupt", start + 1)
        assertTrue("Missing onAccessibilityEvent", start >= 0)
        assertTrue("Missing onInterrupt boundary", end > start)
        val body = source.substring(start, end)
        val transientBranch = body.substring(
            body.indexOf("if (shouldIgnoreTransientGameEvent"),
            body.indexOf("if (\n            foregroundPackage == lastSeenPackage")
        )
        assertFalse(
            "SystemUI/Game Tools/input-method events must be ignored without cancelling an exit already created by launcher/non-list foreground",
            transientBranch.contains("cancelPendingGameExit()")
        )
    }

    @Test
    fun samsungLauncherIsAnExplicitNonTransientExitForeground() {
        val body = functionBody("isTransientGameAutoPackage", "shouldGameAutoOwnRefresh")
        assertTrue(body.contains("com.android.systemui"))
        assertFalse(
            "Samsung launcher is a real foreground destination and must not be filtered as transient",
            body.contains("com.sec.android.app.launcher")
        )
    }

    @Test
    fun transientOnlyExitEventReconcilesTheStableForegroundWithoutResettingAnExit() {
        val eventBody = source.substring(
            source.indexOf("override fun onAccessibilityEvent"),
            source.indexOf("override fun onInterrupt")
        )
        assertTrue(eventBody.contains("scheduleTransientGameAutoForegroundReconcile(foregroundPackage)"))

        val reconcile = functionBody(
            "scheduleTransientGameAutoForegroundReconcile",
            "prepareGameAutoSaverOff"
        )
        val preserveExistingExit = reconcile.indexOf(
            "pendingGameAutoTarget?.startsWith(\"\$GAME_AUTO_TARGET_EXIT:\") == true"
        )
        val foregroundRead = reconcile.indexOf("MaxHzForegroundApp.currentWindowPackage()")
        val scheduleExit = reconcile.indexOf("applyForForeground(actualPackage)")
        assertTrue("An existing exit deadline must be preserved", preserveExistingExit >= 0)
        assertTrue("The real foreground must be read after SystemUI settles", foregroundRead > preserveExistingExit)
        assertTrue("A confirmed non-game foreground must enter the normal exit scheduler", scheduleExit > foregroundRead)
        assertTrue(reconcile.contains("if (actualPackage in games)"))
    }

    @Test
    fun managerForegroundCanCreateExitWhenOpenedDirectlyFromGame() {
        val body = functionBody("isTransientGameAutoPackage", "shouldGameAutoOwnRefresh")
        assertFalse(
            "The Manager is a stable foreground destination; filtering its package prevents exit:daily from ever being scheduled",
            body.contains("foregroundPackage == packageName")
        )
    }

    @Test
    fun exitOwnsItsGenerationUntilARealNewIntentSupersedesIt() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        val generation = body.indexOf("val generation = ++gameAutoGeneration")
        val restore = body.indexOf("applyGameAutoExit(")
        assertTrue("Exit generation must be created before restore", generation >= 0 && restore > generation)
        assertTrue(body.substring(generation, restore).contains("gameAutoGeneration != generation"))
        assertTrue(body.contains("result.code == 0 && stillDesired"))
    }

    @Test
    fun switchingBetweenListedGamesCancelsExitWithoutRestoring() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        val gameMatch = body.indexOf("val isGameForeground = foregroundPackage in gamePackages")
        val cancelExit = body.indexOf("pendingGameAutoTarget?.startsWith(\"\$GAME_AUTO_TARGET_EXIT:\") == true")
        val targetChoice = body.indexOf("val target = if (isGameForeground)")
        assertTrue(gameMatch >= 0 && cancelExit > gameMatch && targetChoice > cancelExit)
        assertTrue(body.substring(targetChoice).contains("\"\$GAME_AUTO_TARGET_ENTRY:"))
    }

    @Test
    fun desktopAndNonListExitUseConfiguredDelayNotEntryBudget() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        assertTrue(body.contains("gameAutoPerformanceExitDelaySeconds"))
        assertTrue(body.contains("GAME_AUTO_ENTRY_DELAY_MS"))
        assertTrue(body.contains("if (!isEntry && (lastSeenPackage in currentGamePackages"))
    }

    @Test
    fun repeatedNonGameEventsPreservePendingExitDeadlineAndGeneration() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        val targetChoice = body.indexOf("val target = if (isGameForeground)")
        val preserve = body.indexOf("pendingGameAutoTarget == target && gameAutoJob?.isActive == true", targetChoice)
        val nextGeneration = body.indexOf("val generation = ++gameAutoGeneration", targetChoice)
        assertTrue("A live same-target exit must be deduplicated before a new generation is created", preserve > targetChoice)
        assertTrue("The pending exit guard must run before generation replacement", nextGeneration > preserve)
        val guard = body.substring(preserve, nextGeneration)
        assertTrue(guard.contains("pendingGameAutoGeneration == gameAutoGeneration"))
        assertTrue(guard.contains("return@synchronized"))
    }

    @Test
    fun leavingAfterEntryWritesStartSchedulesExitBeforeEntryCommit() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        val inFlight = body.indexOf("val entryWritesInFlight =")
        val targetChoice = body.indexOf("val target = if (isGameForeground)")
        val exitTarget = body.indexOf("\"\$GAME_AUTO_TARGET_EXIT:", targetChoice)
        val nextGeneration = body.indexOf("val generation = ++gameAutoGeneration", targetChoice)
        assertTrue("The state machine must distinguish a delayed entry from one already writing system state", inFlight >= 0)
        assertTrue("In-flight entry ownership must be known before choosing the non-game target", inFlight < targetChoice)
        assertTrue("Leaving during entry writes must be allowed to create an exit target", exitTarget > targetChoice)
        assertTrue("The exit must receive a new generation", nextGeneration > exitTarget)
        val scheduling = body.substring(inFlight, nextGeneration)
        assertTrue(scheduling.contains("gameAutoApplying"))
        assertTrue(scheduling.contains("pendingGameAutoTarget?.startsWith(\"\$GAME_AUTO_TARGET_ENTRY:\") == true"))
        assertTrue(scheduling.contains("!entryWritesInFlight"))
    }

    @Test
    fun inFlightEntryCannotBeDedupedByThePreviousCommittedExit() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        val staleExitDedup = body.indexOf("target == lastAppliedGameAutoTarget")
        val nextGeneration = body.indexOf("val generation = ++gameAutoGeneration", staleExitDedup)
        assertTrue("Missing committed-target dedup guard", staleExitDedup >= 0)
        assertTrue("Committed-target dedup must run before scheduling", nextGeneration > staleExitDedup)
        val guardStart = body.lastIndexOf("else if (", staleExitDedup)
        val guard = body.substring(guardStart, nextGeneration)
        assertTrue(
            "A previous exit:daily cannot suppress compensation after a new entry has started external writes",
            guard.contains("!entryWritesInFlight")
        )
    }

    @Test
    fun dailyRestoreRemainsNamedFailFastAndUsesReg002Path() {
        val body = functionBody("applyGameAutoExit", "runGameAutoSteps")
        listOf("restore_m_main", "restore_gpu", "restore_vulkan", "restore_thermal", "apply_daily_saver").forEach { step ->
            assertTrue("Missing daily exit step $step", body.contains("\"$step\""))
        }
        assertTrue(body.contains("applyGameAutoDailySaverWithRetry()"))
        assertFalse(body.contains("timeoutSeconds = 20"))
    }

    @Test
    fun dailyExitRetriesTheSameManualDailySaverActionAndAcceptsOnlyRealSuccess() {
        val body = functionBody("applyGameAutoDailySaverWithRetry", "runGameAutoSteps")
        assertTrue(body.contains("MaxHzController.applyDailySaver("))
        assertTrue(body.contains("GAME_AUTO_DAILY_SAVER_ATTEMPTS"))
        assertTrue(body.contains("GAME_AUTO_DAILY_SAVER_RETRY_DELAY_MS"))
        assertTrue(body.contains("if (result.code == 0)"))
        assertTrue(body.contains("daily_saver_attempt"))
    }

    @Test
    fun fastReentryInvalidatesExitButLateEntryCannotCommit() {
        val body = functionBody("handleGameAutoPerformance", "isGameAutoTargetStillDesired")
        assertTrue(body.contains("isGameForeground && pendingGameAutoTarget?.startsWith"))
        assertTrue(body.contains("gameAutoJob?.cancel()"))
        assertTrue(body.contains("isGameAutoTargetStillDesired(target, foregroundPackage)"))
        assertTrue(body.contains("result.code == 0 && stillDesired"))
    }

    private fun functionBody(startName: String, nextName: String): String {
        val start = source.indexOf("private fun $startName")
        val end = source.indexOf("private fun $nextName", start + 1)
        assertTrue("Missing function $startName", start >= 0)
        assertTrue("Missing boundary $nextName after $startName", end > start)
        return source.substring(start, end)
    }
}
