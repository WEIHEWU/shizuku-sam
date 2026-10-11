package moe.shizuku.manager.maxhz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Reg006SameTargetSwitchContractTest {

    private val source = File(
        "src/main/java/moe/shizuku/manager/maxhz/MaxHzAccessibilityService.kt"
    ).readText()

    @Test
    fun pendingSameTargetDedupIsPackageAndGenerationScoped() {
        assertTrue(
            "REG-006: a pending target must have a package owner",
            source.contains("pendingGameAutoPackage")
        )
        assertTrue(
            "REG-006: old cleanup must compare its generation before clearing a newer same-target request",
            source.contains("pendingGameAutoGeneration == generation")
        )
    }

    @Test
    fun pendingBToASameTargetSchedulesAReplacement() {
        assertEquals(
            GameAutoEntryDecision.SCHEDULE,
            decide("A", "entry:extreme", pendingState("B", "entry:extreme"))
        )
    }

    @Test
    fun pendingBToADifferentTargetSchedulesAReplacement() {
        assertEquals(
            GameAutoEntryDecision.SCHEDULE,
            decide("A", "entry:high", pendingState("B", "entry:extreme"))
        )
    }

    @Test
    fun committedBToASameTargetTransfersOwnerWithoutShellEntry() {
        assertEquals(
            GameAutoEntryDecision.TRANSFER_COMMITTED_OWNER,
            decide(
                "A",
                "entry:extreme",
                GameAutoEntryState("B", "entry:extreme", null, null, null, false)
            )
        )
        val scheduling = functionBody("handleGameAutoPerformance", "gameAutoEntryState")
        val transfer = scheduling.indexOf("TRANSFER_COMMITTED_OWNER")
        val returnAfterTransfer = scheduling.indexOf("return@synchronized", transfer)
        val shellEntry = scheduling.indexOf("applyGameAutoEntry", transfer)
        assertTrue(transfer >= 0 && returnAfterTransfer > transfer)
        assertTrue(shellEntry < 0 || returnAfterTransfer < shellEntry)
        assertTrue(scheduling.substring(transfer, returnAfterTransfer).contains("transferCommittedGameAutoOwner"))
        val ownerTransfer = functionBody("transferCommittedGameAutoOwner", "compensateFailedGameAutoOperation")
        assertTrue(ownerTransfer.contains("setGameAutoActiveSession"))
    }

    @Test
    fun samePackageSamePendingIdentityIsTheOnlyPendingDedup() {
        assertEquals(
            GameAutoEntryDecision.DEDUP_PENDING,
            decide("A", "entry:extreme", pendingState("A", "entry:extreme"))
        )
        assertEquals(
            GameAutoEntryDecision.SCHEDULE,
            decide("A", "entry:extreme", pendingState("B", "entry:extreme"))
        )
        assertEquals(
            GameAutoEntryDecision.SCHEDULE,
            decide("A", "entry:extreme", pendingState("A", "entry:extreme", active = false))
        )
    }

    @Test
    fun noCommittedOwnerCannotDedupFromTargetCacheAlone() {
        assertEquals(
            GameAutoEntryDecision.SCHEDULE,
            decide(
                "A",
                "entry:extreme",
                GameAutoEntryState(null, "entry:extreme", null, null, null, false)
            )
        )
    }

    @Test
    fun quickBToAToBLeavesTheFinalPackageSchedulable() {
        var state = pendingState("B", "entry:extreme")
        assertEquals(GameAutoEntryDecision.SCHEDULE, decide("A", "entry:extreme", state))
        state = pendingState("A", "entry:extreme")
        assertEquals(GameAutoEntryDecision.SCHEDULE, decide("B", "entry:extreme", state))
    }

    @Test
    fun foregroundChangedAbortPerformsOneBoundedFreshReconcile() {
        val scheduling = functionBody("handleGameAutoPerformance", "gameAutoEntryState")
        assertTrue(scheduling.contains("ENTRY_ABORT_FOREGROUND_CHANGED"))
        assertTrue(scheduling.contains("reconcileGameAutoForegroundAfterEntryAbort"))
        val reconcile = functionBody(
            "reconcileGameAutoForegroundAfterEntryAbort",
            "reconcilePendingGameAutoExit"
        )
        assertTrue(reconcile.contains("MaxHzForegroundApp.currentWindowPackage()"))
        assertTrue(reconcile.contains("actualPackage !in games"))
        assertTrue(reconcile.contains("DEDUP_PENDING"))
        assertFalse("Reconcile must not contain a retry loop", reconcile.contains("while ("))
        assertFalse("Reconcile must not use a fixed sleep", reconcile.contains("delay("))
    }

    @Test
    fun nonListedAndTransientForegroundCannotBeRescheduled() {
        val reconcile = functionBody(
            "reconcileGameAutoForegroundAfterEntryAbort",
            "reconcilePendingGameAutoExit"
        )
        val transient = reconcile.indexOf("isTransientGameAutoPackage(actualPackage)")
        val listed = reconcile.indexOf("actualPackage !in games")
        val schedule = reconcile.indexOf("handleGameAutoPerformance(actualPackage)")
        assertTrue(transient >= 0 && listed > transient && schedule > listed)
    }

    @Test
    fun reg004bAndReg004cProtectionsRemainPresent() {
        assertTrue(source.contains("shouldIgnoreTransientGameEvent"))
        assertTrue(source.contains("preserve_pending_exit"))
        assertTrue(source.contains("gameAutoGeneration != generation") || source.contains("gameAutoGeneration == generation"))
        assertTrue(source.contains("while (gameAutoApplying)"))
        assertTrue(source.contains("releaseGameAutoWakeLock"))
        assertTrue(source.contains("result.code == 0 && stillDesired"))
    }

    private fun pendingState(packageName: String, target: String, active: Boolean = true) =
        GameAutoEntryState(null, null, packageName, target, 7L, active)

    private fun decide(packageName: String, target: String, state: GameAutoEntryState) =
        decideGameAutoEntry(packageName, target, state)

    private fun functionBody(startName: String, nextName: String): String {
        val start = source.indexOf("private fun $startName")
        val end = source.indexOf("private fun $nextName", start + 1)
        assertTrue("Missing function $startName", start >= 0)
        assertTrue("Missing boundary $nextName after $startName", end > start)
        return source.substring(start, end)
    }
}
