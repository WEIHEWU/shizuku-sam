package moe.shizuku.manager.maxhz

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.adb.AdbPairingDetector

class MaxHzAccessibilityService : AccessibilityService() {

    private data class GameAutoStep(
        val name: String,
        val execute: () -> MaxHzShell.Result
    )

    private data class GameAutoStepRecord(
        val name: String,
        val startedAtMs: Long,
        val elapsedMs: Long,
        val result: MaxHzShell.Result
    )

    private data class GameAutoOperation(
        val result: MaxHzShell.Result,
        val records: List<GameAutoStepRecord>,
        val firstFailedStep: String?
    )

    private data class RefreshMode(
        val key: String,
        val delayMs: Long,
        val apply: (Context) -> Unit
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gameAutoStateLock = Any()
    private var pendingJob: Job? = null
    private var gameAutoJob: Job? = null
    private var transientGameAutoReconcileJob: Job? = null
    @Volatile
    private var pendingPackage: String? = null
    @Volatile
    private var pendingGameAutoTarget: String? = null
    @Volatile
    private var pendingGameAutoPackage: String? = null
    @Volatile
    private var pendingGameAutoGeneration: Long? = null
    @Volatile
    private var lastSeenPackage: String? = null
    private var lastAppliedMode: String? = null
    private var lastAppliedGameAutoTarget: String? = null
    private var activeGameAutoPackage: String? = null
    private var restoredGameAutoSession = false
    @Volatile
    private var gameAutoApplying = false
    @Volatile
    private var gameAutoApplyingGeneration: Long? = null
    @Volatile
    private var gameAutoGeneration = 0L
    private var rules: Map<String, MaxHzPrefs.AppRefreshRule> = emptyMap()
    private var rulesLoadedAt = 0L
    private var lastBackgroundFailureAt = 0L
    private val gamePackageCache = mutableMapOf<String, Boolean>()
    private val gameAutoTrace = ArrayDeque<String>()
    private var inputMethodCacheAt = 0L
    private var cachedDefaultInputMethodPackage: String? = null
    private var cachedEnabledInputMethodPackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceAlive = true
        cachedEnabledState = true
        lastCheckTime = SystemClock.uptimeMillis()
        activeGameAutoPackage = MaxHzPrefs.gameAutoActivePackage(this)
        if (activeGameAutoPackage != null) {
            lastAppliedGameAutoTarget = "$GAME_AUTO_TARGET_ENTRY:${MaxHzPrefs.gameAutoActiveProfile(this)}"
            restoredGameAutoSession = true
        }
        gameAutoDiagnostic(
            "service_connected instance=${System.identityHashCode(this)} active=$activeGameAutoPackage"
        )
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = EVENT_NOTIFICATION_TIMEOUT_MS
            flags = (flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) and
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val foregroundPackage = event?.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return

        // 1. 无线调试自动配对嗅探 (统一整合为 Shizuku 核心无障碍)
        if (foregroundPackage.contains("settings", ignoreCase = true) ||
            foregroundPackage.contains("developer", ignoreCase = true) ||
            foregroundPackage.contains("security", ignoreCase = true) ||
            foregroundPackage.contains("systemui", ignoreCase = true)) {
            AdbPairingDetector.onAccessibilityEvent(event, this)
        }

        // 1.1 开发者选项「更多设置」/ 应用兼容性策略 自动直达滑动
        if (CompatSettingsAutoScroller.isPending()) {
            if (foregroundPackage.contains("settings", ignoreCase = true) ||
                foregroundPackage.contains("developer", ignoreCase = true)) {
                CompatSettingsAutoScroller.onAccessibilityEvent(event, this)
            }
        }

        // 2. 刷新率调度性能守卫：只处理窗口切换，忽略内部频繁的内容变化
        if (
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        gameAutoDiagnostic(
            "event type=${event.eventType} package=$foregroundPackage last=$lastSeenPackage " +
                "pending=$pendingGameAutoTarget generation=$gameAutoGeneration"
        )
        if (isInputMethodPackage(foregroundPackage)) {
            gameAutoDiagnostic("event_ignored reason=input_method package=$foregroundPackage")
            return
        }
        if (shouldIgnoreTransientGameEvent(foregroundPackage)) {
            gameAutoDiagnostic(
                "event_ignored reason=transient package=$foregroundPackage action=preserve_pending_exit"
            )
            scheduleTransientGameAutoForegroundReconcile(foregroundPackage)
            return
        }
        transientGameAutoReconcileJob?.cancel()
        if (
            foregroundPackage == lastSeenPackage &&
            pendingJob?.isActive != true &&
            gameAutoJob?.isActive != true &&
            !needsGameAutoReconcile(foregroundPackage)
        ) return
        synchronized(gameAutoStateLock) {
            lastSeenPackage = foregroundPackage
            applyForForeground(foregroundPackage)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        gameAutoDiagnostic(
            "service_destroyed instance=${System.identityHashCode(this)} active=$activeGameAutoPackage " +
                "pending=$pendingGameAutoTarget generation=$gameAutoGeneration applying=$gameAutoApplying"
        )
        synchronized(gameAutoStateLock) {
            pendingPackage = null
            pendingGameAutoTarget = null
            pendingGameAutoPackage = null
            pendingGameAutoGeneration = null
            pendingJob?.cancel()
            gameAutoJob?.cancel()
            transientGameAutoReconcileJob?.cancel()
        }
        isServiceAlive = false
        isGameAutoExitPending = false
        cachedEnabledState = false
        lastCheckTime = SystemClock.uptimeMillis()
        AdbPairingDetector.reset()
        CompatSettingsAutoScroller.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isServiceAlive = false
        isGameAutoExitPending = false
        cachedEnabledState = false
        lastCheckTime = SystemClock.uptimeMillis()
        AdbPairingDetector.reset()
        CompatSettingsAutoScroller.cancel()
        return super.onUnbind(intent)
    }

    private fun applyForForeground(foregroundPackage: String) {
        if (!isScreenInteractive()) {
            pendingPackage = null
            if (pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_ENTRY:") == true) {
                gameAutoDiagnostic(
                    "screen_noninteractive action=cancel_pending_entry target=$pendingGameAutoTarget"
                )
                pendingGameAutoTarget = null
                pendingGameAutoPackage = null
                pendingGameAutoGeneration = null
                gameAutoJob?.cancel()
            } else if (pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_EXIT:") == true) {
                gameAutoDiagnostic(
                    "screen_noninteractive action=preserve_pending_exit target=$pendingGameAutoTarget"
                )
            }
            return
        }
        handleGameAutoPerformance(foregroundPackage)
        if (shouldGameAutoOwnRefresh(foregroundPackage)) {
            pendingPackage = null
            pendingJob?.cancel()
            return
        }
        val mode = desiredMode(foregroundPackage) ?: run {
            pendingPackage = null
            return
        }
        if (mode.key == lastAppliedMode) {
            pendingPackage = null
            return
        }
        pendingPackage = foregroundPackage
        pendingJob?.cancel()
        pendingJob = scope.launch {
            if (mode.delayMs > 0) delay(mode.delayMs)
            if (lastSeenPackage != foregroundPackage || pendingPackage != foregroundPackage) return@launch
            if (isGamePackageForPassthrough(foregroundPackage)) {
                safeApply { MaxHzController.restoreGameDisplayDefaults(foregroundPackage) }
                lastAppliedMode = "game_passthrough:$foregroundPackage"
                pendingPackage = null
                return@launch
            }
            safeApply { mode.apply(this@MaxHzAccessibilityService) }
            lastAppliedMode = mode.key
            pendingPackage = null
            recheckForegroundAfterDefaultIfNeeded(foregroundPackage, mode.key)
        }
    }

    private fun handleGameAutoPerformance(foregroundPackage: String) = synchronized(gameAutoStateLock) {
        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this)) {
            if (hasActiveGameAutoSession()) {
                scheduleGameAutoShutdownCleanup()
            } else if (hasAnyGameAutoState()) {
                clearGameAutoSessionState()
            }
            return
        }

        val gamePackages = MaxHzPrefs.gameAutoPerformancePackages(this)
        val isGameForeground = foregroundPackage in gamePackages
        if (gamePackages.isEmpty() && !isGameForeground) {
            if (hasActiveGameAutoSession()) {
                scheduleGameAutoShutdownCleanup()
            } else if (hasAnyGameAutoState()) {
                clearGameAutoSessionState()
            }
            return
        }
        gameAutoDiagnostic(
            "foreground_decision package=$foregroundPackage listed=$isGameForeground " +
                "active=$activeGameAutoPackage pending_package=$pendingGameAutoPackage " +
                "pending=$pendingGameAutoTarget generation=$gameAutoGeneration"
        )
        if (restoredGameAutoSession) {
            restoredGameAutoSession = false
            if (isGameForeground) lastAppliedGameAutoTarget = null
        }
        if (isGameForeground && pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_EXIT:") == true) {
            pendingGameAutoTarget = null
            pendingGameAutoPackage = null
            pendingGameAutoGeneration = null
            isGameAutoExitPending = false
            gameAutoJob?.cancel()
        }
        val entryWritesInFlight = gameAutoApplying &&
            pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_ENTRY:") == true &&
            pendingGameAutoGeneration != null &&
            pendingGameAutoGeneration == gameAutoApplyingGeneration
        val target = if (isGameForeground) {
            GAME_AUTO_TARGET_ENTRY_PENDING
        } else {
            if (
                !hasActiveGameAutoSession() &&
                !entryWritesInFlight
            ) return
            GAME_AUTO_TARGET_EXIT_PENDING
        }

        if (
            target.startsWith("$GAME_AUTO_TARGET_EXIT:") &&
            !hasActiveGameAutoSession() &&
            !entryWritesInFlight
        ) return

        if (!isGameForeground && entryWritesInFlight) {
            gameAutoDiagnostic(
                "entry_exit_handoff package=$foregroundPackage pending=$pendingGameAutoTarget " +
                    "generation=$pendingGameAutoGeneration"
            )
        }

        if (
            !isGameForeground &&
            pendingGameAutoTarget == target && gameAutoJob?.isActive == true &&
            pendingGameAutoGeneration == gameAutoGeneration
        ) {
            gameAutoDiagnostic(
                "exit_intent_preserved package=$foregroundPackage target=$target " +
                    "owner=$pendingGameAutoPackage generation=$gameAutoGeneration"
            )
            return@synchronized
        }

        if (isGameForeground) {
            val sameTargetJobActive =
                pendingGameAutoTarget == target && gameAutoJob?.isActive == true
            val decision = decideGameAutoEntry(
                foregroundPackage,
                target,
                gameAutoEntryState()
            )
            gameAutoDiagnostic(
                "DEDUP_DECISION decision=$decision DEDUP_ACTIVE_COMMITTED=${activeGameAutoPackage != null} " +
                    "DEDUP_ACTIVE_PACKAGE=$activeGameAutoPackage DEDUP_ACTIVE_TARGET=$lastAppliedGameAutoTarget " +
                    "DEDUP_REQUEST_PACKAGE=$foregroundPackage DEDUP_REQUEST_TARGET=$target " +
                    "same_target_job_active=$sameTargetJobActive"
            )
            when (decision) {
                GameAutoEntryDecision.DEDUP_COMMITTED,
                GameAutoEntryDecision.DEDUP_PENDING -> return@synchronized
                GameAutoEntryDecision.TRANSFER_COMMITTED_OWNER -> {
                    transferCommittedGameAutoOwner(foregroundPackage, target)
                    return@synchronized
                }
                GameAutoEntryDecision.SCHEDULE -> Unit
            }
        } else if (
            !entryWritesInFlight &&
            target == lastAppliedGameAutoTarget &&
            pendingGameAutoTarget != target
        ) {
            return@synchronized
        }

        val replacedPackage = pendingGameAutoPackage
        val replacedTarget = pendingGameAutoTarget
        val replacedGeneration = pendingGameAutoGeneration

        pendingGameAutoTarget = target
        pendingGameAutoPackage = foregroundPackage
        val generation = ++gameAutoGeneration
        pendingGameAutoGeneration = generation
        if (replacedTarget != null && (replacedPackage != foregroundPackage || replacedTarget != target)) {
            gameAutoDiagnostic(
                "PENDING_REPLACED old_package=$replacedPackage old_target=$replacedTarget " +
                    "old_generation=$replacedGeneration new_package=$foregroundPackage " +
                    "new_target=$target new_generation=$generation"
            )
        }
        gameAutoDiagnostic(
            "intent_created package=$foregroundPackage target=$target generation=$generation " +
                "delay_ms=${if (target.startsWith("$GAME_AUTO_TARGET_ENTRY:")) GAME_AUTO_ENTRY_DELAY_MS else MaxHzPrefs.gameAutoPerformanceExitDelaySeconds(this) * 1_000L}"
        )
        gameAutoJob?.cancel()
        gameAutoJob = scope.launch {
            val isEntry = target.startsWith("$GAME_AUTO_TARGET_ENTRY:")
            val wakeLock = acquireGameAutoWakeLock(target, generation)
            var entryAbortReason: String? = null
            try {
                if (!isEntry) isGameAutoExitPending = true
                delay(if (isEntry) {
                    GAME_AUTO_ENTRY_DELAY_MS
                } else {
                    MaxHzPrefs.gameAutoPerformanceExitDelaySeconds(this@MaxHzAccessibilityService) * 1_000L
                })
                if (pendingGameAutoTarget != target || gameAutoGeneration != generation) return@launch
                if (!ownsPendingGameAutoIntent(foregroundPackage, target, generation)) return@launch
                val currentGamePackages = MaxHzPrefs.gameAutoPerformancePackages(this@MaxHzAccessibilityService)
                if (!isEntry && reconcilePendingGameAutoExit(target, generation, currentGamePackages)) {
                    return@launch
                }
                if (isEntry && foregroundPackage !in currentGamePackages) return@launch
                if (isEntry && lastSeenPackage != foregroundPackage) {
                    entryAbortReason = ENTRY_ABORT_FOREGROUND_CHANGED
                    gameAutoDiagnostic(
                        "ENTRY_ABORT_FOREGROUND_CHANGED package=$foregroundPackage target=$target " +
                            "generation=$generation current=$lastSeenPackage"
                    )
                    return@launch
                }
                if (!isEntry && (lastSeenPackage in currentGamePackages)) return@launch
                while (gameAutoApplying) {
                    delay(GAME_AUTO_APPLY_WAIT_MS)
                    if (pendingGameAutoTarget != target || gameAutoGeneration != generation) return@launch
                    if (!ownsPendingGameAutoIntent(foregroundPackage, target, generation)) return@launch
                }
                if (pendingGameAutoTarget != target || gameAutoGeneration != generation) return@launch
                if (!ownsPendingGameAutoIntent(foregroundPackage, target, generation)) return@launch
                synchronized(gameAutoStateLock) {
                    if (!ownsPendingGameAutoIntent(foregroundPackage, target, generation)) return@launch
                    gameAutoApplying = true
                    gameAutoApplyingGeneration = generation
                    if (isEntry) {
                        // The first external write can leave performance state behind even if a
                        // later verification step fails. Persist cleanup ownership before writes
                        // start so a failed/partial entry can still schedule the selected exit.
                        activeGameAutoPackage = foregroundPackage
                        MaxHzPrefs.setGameAutoActiveSession(
                            this@MaxHzAccessibilityService,
                            foregroundPackage
                        )
                    }
                }
                gameAutoDiagnostic(
                    "operation_start package=$foregroundPackage target=$target generation=$generation last=$lastSeenPackage"
                )

                val operation = if (isEntry) {
                    applyGameAutoEntry()
                } else {
                    applyGameAutoExit()
                }
                val result = operation.result

                val stillDesired = synchronized(gameAutoStateLock) {
                    ownsPendingGameAutoIntent(foregroundPackage, target, generation) &&
                        isGameAutoTargetStillDesired(target, foregroundPackage)
                }
                recordGameAutoOperation(foregroundPackage, generation, target, operation, stillDesired)
                if (result.code == 0 && stillDesired) {
                    synchronized(gameAutoStateLock) {
                        if (!ownsPendingGameAutoIntent(foregroundPackage, target, generation)) return@synchronized
                        clearOwnedGameAutoFailure()
                        lastAppliedGameAutoTarget = target
                        if (isEntry) {
                            activeGameAutoPackage = foregroundPackage
                            markGameAutoEntryApplied()
                            MaxHzPrefs.setGameAutoActiveSession(this@MaxHzAccessibilityService, foregroundPackage)
                        } else {
                            activeGameAutoPackage = null
                            markGameAutoExitApplied()
                            MaxHzPrefs.clearGameAutoActiveSession(this@MaxHzAccessibilityService)
                        }
                    }
                } else {
                    if (result.code == 0) lastAppliedGameAutoTarget = null
                    if (result.code != 0) {
                        val compensation = compensateFailedGameAutoOperation(isEntry)
                        if (isEntry && compensation.code == 0) {
                            synchronized(gameAutoStateLock) {
                                if (activeGameAutoPackage == foregroundPackage) {
                                    activeGameAutoPackage = null
                                    MaxHzPrefs.clearGameAutoActiveSession(this@MaxHzAccessibilityService)
                                }
                            }
                        }
                        reportBackgroundFailure(
                            gameAutoFailureMessage(foregroundPackage, generation, target, operation) +
                                if (isEntry) {
                                    "\nentry_compensation code=${compensation.code} " +
                                        "stdout=${compensation.out.trim()} stderr=${compensation.err.trim()}"
                                } else {
                                    ""
                                }
                        )
                    }
                }
            } finally {
                if (!isEntry) isGameAutoExitPending = false
                releaseGameAutoWakeLock(wakeLock, target, generation)
                synchronized(gameAutoStateLock) {
                    if (gameAutoApplyingGeneration == generation) {
                        gameAutoApplying = false
                        gameAutoApplyingGeneration = null
                    }
                    if (pendingGameAutoTarget == target) {
                        if (
                            pendingGameAutoPackage == foregroundPackage &&
                            pendingGameAutoGeneration == generation
                        ) {
                            pendingGameAutoTarget = null
                            pendingGameAutoPackage = null
                            pendingGameAutoGeneration = null
                        }
                    }
                }
                if (entryAbortReason == ENTRY_ABORT_FOREGROUND_CHANGED) {
                    reconcileGameAutoForegroundAfterEntryAbort(foregroundPackage, target, generation)
                }
            }
        }
    }

    private fun gameAutoEntryState() = GameAutoEntryState(
        activePackage = activeGameAutoPackage,
        activeTarget = lastAppliedGameAutoTarget,
        pendingPackage = pendingGameAutoPackage,
        pendingTarget = pendingGameAutoTarget,
        pendingGeneration = pendingGameAutoGeneration,
        pendingJobActive = gameAutoJob?.isActive == true
    )

    private fun ownsPendingGameAutoIntent(
        packageName: String,
        target: String,
        generation: Long
    ): Boolean = synchronized(gameAutoStateLock) {
        pendingGameAutoPackage == packageName &&
            pendingGameAutoTarget == target &&
            pendingGameAutoGeneration == generation &&
            gameAutoGeneration == generation
    }

    private fun reconcileGameAutoForegroundAfterEntryAbort(
        abortedPackage: String,
        target: String,
        generation: Long
    ) {
        gameAutoDiagnostic(
            "RECONCILE_START aborted_package=$abortedPackage target=$target generation=$generation"
        )
        val actualPackage = MaxHzForegroundApp.currentWindowPackage()
        gameAutoDiagnostic(
            "RECONCILE_FOREGROUND package=$actualPackage aborted_package=$abortedPackage " +
                "generation=$generation"
        )
        if (actualPackage.isNullOrBlank() || isTransientGameAutoPackage(actualPackage)) {
            gameAutoDiagnostic(
                "RECONCILE_SKIPPED reason=${if (actualPackage.isNullOrBlank()) "no_foreground" else "transient"} " +
                    "package=$actualPackage generation=$generation"
            )
            return
        }
        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this)) {
            gameAutoDiagnostic("RECONCILE_SKIPPED reason=disabled package=$actualPackage generation=$generation")
            return
        }
        val games = MaxHzPrefs.gameAutoPerformancePackages(this)
        if (actualPackage !in games) {
            gameAutoDiagnostic("RECONCILE_SKIPPED reason=not_listed package=$actualPackage generation=$generation")
            return
        }
        val requestTarget = GAME_AUTO_TARGET_ENTRY_PENDING
        val decision = synchronized(gameAutoStateLock) {
            lastSeenPackage = actualPackage
            decideGameAutoEntry(actualPackage, requestTarget, gameAutoEntryState())
        }
        gameAutoDiagnostic(
            "RECONCILE_DECISION package=$actualPackage target=$requestTarget decision=$decision " +
                "generation=$generation"
        )
        if (
            decision == GameAutoEntryDecision.DEDUP_PENDING ||
            decision == GameAutoEntryDecision.DEDUP_COMMITTED
        ) {
            gameAutoDiagnostic(
                "RECONCILE_SKIPPED reason=$decision package=$actualPackage target=$requestTarget"
            )
            return
        }
        gameAutoDiagnostic("RECONCILE_SCHEDULED package=$actualPackage target=$requestTarget")
        handleGameAutoPerformance(actualPackage)
    }

    private fun reconcilePendingGameAutoExit(
        target: String,
        generation: Long,
        gamePackages: Set<String>
    ): Boolean {
        val actualPackage = MaxHzForegroundApp.currentWindowPackage() ?: return false
        if (actualPackage in gamePackages) {
            synchronized(gameAutoStateLock) {
                lastSeenPackage = actualPackage
                if (pendingGameAutoTarget == target && pendingGameAutoGeneration == generation) {
                    pendingGameAutoTarget = null
                    pendingGameAutoPackage = null
                    pendingGameAutoGeneration = null
                }
            }
            gameAutoDiagnostic(
                "exit_cancelled reason=listed_foreground_recheck package=$actualPackage " +
                    "target=$target generation=$generation"
            )
            return true
        }
        return false
    }

    private fun isGameAutoTargetStillDesired(target: String, operationPackage: String): Boolean {
        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this)) return false
        val games = MaxHzPrefs.gameAutoPerformancePackages(this)
        val isGame = operationPackage in games
        return if (target.startsWith("$GAME_AUTO_TARGET_ENTRY:")) {
            isGame && lastSeenPackage == operationPackage
        } else {
            val lastSeenIsGame = lastSeenPackage in games
            !lastSeenIsGame && !isTransientGameAutoPackage(lastSeenPackage.orEmpty())
        }
    }

    private fun transferCommittedGameAutoOwner(packageName: String, target: String) {
        val previousOwner = activeGameAutoPackage
        val generation = ++gameAutoGeneration
        gameAutoJob?.cancel()
        pendingGameAutoTarget = null
        pendingGameAutoPackage = null
        pendingGameAutoGeneration = null
        activeGameAutoPackage = packageName
        MaxHzPrefs.setGameAutoActiveSession(this, packageName)
        gameAutoDiagnostic(
            "SESSION_OWNER_TRANSFER from=$previousOwner to=$packageName " +
                "target=$target generation=$generation"
        )
    }

    private fun compensateFailedGameAutoOperation(wasEntry: Boolean): MaxHzShell.Result {
        if (!wasEntry) return MaxHzShell.Result(0, "not_entry", "")
        return MaxHzController.quickSwitchGameDefault(packageName = activeGameAutoPackage)
    }

    private fun hasActiveGameAutoSession(): Boolean {
        return activeGameAutoPackage != null ||
            MaxHzPrefs.gameAutoActivePackage(this) != null ||
            isGameAutoExitPending ||
            lastAppliedGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_ENTRY:") == true
    }

    private fun hasAnyGameAutoState(): Boolean {
        return pendingGameAutoTarget != null ||
            lastAppliedGameAutoTarget != null ||
            activeGameAutoPackage != null ||
            restoredGameAutoSession ||
            isGameAutoExitPending ||
            MaxHzPrefs.gameAutoActivePackage(this) != null
    }

    private fun needsGameAutoReconcile(packageName: String): Boolean {
        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this)) return hasActiveGameAutoSession()
        val isGame = packageName in MaxHzPrefs.gameAutoPerformancePackages(this)
        return if (isGame) {
            lastAppliedGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_ENTRY:") != true ||
                activeGameAutoPackage != packageName
        } else {
            hasActiveGameAutoSession() &&
                lastAppliedGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_EXIT:") != true
        }
    }

    private fun clearGameAutoSessionState() = synchronized(gameAutoStateLock) {
        gameAutoGeneration++
        pendingGameAutoTarget = null
        pendingGameAutoPackage = null
        pendingGameAutoGeneration = null
        lastAppliedGameAutoTarget = null
        activeGameAutoPackage = null
        restoredGameAutoSession = false
        isGameAutoExitPending = false
        gameAutoJob?.cancel()
        MaxHzPrefs.clearGameAutoActiveSession(this)
    }

    private fun scheduleGameAutoShutdownCleanup() {
        val target = GAME_AUTO_TARGET_EXIT_PENDING
        if (pendingGameAutoTarget == target && gameAutoJob?.isActive == true) return
        pendingGameAutoTarget = target
        pendingGameAutoPackage = lastSeenPackage
        val generation = ++gameAutoGeneration
        pendingGameAutoGeneration = generation
        gameAutoJob?.cancel()
        gameAutoJob = scope.launch {
            val wakeLock = acquireGameAutoWakeLock(target, generation)
            try {
                while (gameAutoApplying) delay(GAME_AUTO_APPLY_WAIT_MS)
                synchronized(gameAutoStateLock) {
                    if (pendingGameAutoTarget != target || gameAutoGeneration != generation) return@launch
                    gameAutoApplying = true
                    gameAutoApplyingGeneration = generation
                }
                val operation = applyGameAutoExit()
                val result = operation.result
                recordGameAutoOperation(lastSeenPackage.orEmpty(), generation, target, operation, true)
                if (result.code == 0) {
                    clearOwnedGameAutoFailure()
                    markGameAutoExitApplied()
                    clearGameAutoSessionState()
                } else {
                    reportBackgroundFailure(
                        gameAutoFailureMessage(lastSeenPackage.orEmpty(), generation, target, operation)
                    )
                }
            } finally {
                releaseGameAutoWakeLock(wakeLock, target, generation)
                synchronized(gameAutoStateLock) {
                    if (gameAutoApplyingGeneration == generation) {
                        gameAutoApplying = false
                        gameAutoApplyingGeneration = null
                    }
                    if (pendingGameAutoTarget == target && pendingGameAutoGeneration == generation) {
                        pendingGameAutoTarget = null
                        pendingGameAutoPackage = null
                        pendingGameAutoGeneration = null
                    }
                }
            }
        }
    }

    private fun acquireGameAutoWakeLock(target: String, generation: Long): PowerManager.WakeLock? {
        val powerManager = getSystemService(PowerManager::class.java) ?: return null
        return powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:game_auto_cycle"
        ).apply {
            setReferenceCounted(false)
            acquire(GAME_AUTO_WAKE_LOCK_TIMEOUT_MS)
            gameAutoDiagnostic("wake_lock_acquired target=$target generation=$generation")
        }
    }

    private fun releaseGameAutoWakeLock(
        wakeLock: PowerManager.WakeLock?,
        target: String,
        generation: Long
    ) {
        if (wakeLock?.isHeld == true) wakeLock.release()
        gameAutoDiagnostic("wake_lock_released target=$target generation=$generation")
    }

    private fun shouldIgnoreTransientGameEvent(packageName: String): Boolean {
        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this)) return false
        val hasGameSession = activeGameAutoPackage != null ||
            MaxHzPrefs.gameAutoActivePackage(this) != null ||
            pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_ENTRY:") == true ||
            lastAppliedGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_ENTRY:") == true
        return hasGameSession && isTransientGameAutoPackage(packageName)
    }

    private fun scheduleTransientGameAutoForegroundReconcile(eventPackage: String) =
        synchronized(gameAutoStateLock) {
            // A real non-game event has already established the exit deadline. Transient
            // SystemUI/Game Tools/IME events must never replace or extend that deadline.
            if (pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_EXIT:") == true) return
            if (transientGameAutoReconcileJob?.isActive == true) return

            transientGameAutoReconcileJob = scope.launch {
                repeat(TRANSIENT_FOREGROUND_RECONCILE_ATTEMPTS) { attempt ->
                    delay(TRANSIENT_FOREGROUND_SETTLE_MS)
                    val actualPackage = MaxHzForegroundApp.currentWindowPackage()
                    if (
                        actualPackage.isNullOrBlank() ||
                        isTransientGameAutoPackage(actualPackage)
                    ) {
                        gameAutoDiagnostic(
                            "transient_reconcile_wait event_package=$eventPackage actual=$actualPackage " +
                                "attempt=${attempt + 1}"
                        )
                        return@repeat
                    }

                    var exitScheduled = false
                    var stopReconcile = false
                    synchronized(gameAutoStateLock) {
                        if (pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_EXIT:") == true) {
                            stopReconcile = true
                            return@synchronized
                        }
                        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this@MaxHzAccessibilityService)) {
                            stopReconcile = true
                            return@synchronized
                        }
                        val games = MaxHzPrefs.gameAutoPerformancePackages(this@MaxHzAccessibilityService)
                        if (actualPackage in games) {
                            lastSeenPackage = actualPackage
                            return@synchronized
                        }

                        gameAutoDiagnostic(
                            "transient_reconcile_exit event_package=$eventPackage actual=$actualPackage " +
                                "attempt=${attempt + 1}"
                        )
                        lastSeenPackage = actualPackage
                        applyForForeground(actualPackage)
                        exitScheduled = pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_EXIT:") == true
                    }
                    if (stopReconcile || exitScheduled) return@launch
                }
                gameAutoDiagnostic(
                    "transient_reconcile_exhausted event_package=$eventPackage " +
                        "attempts=$TRANSIENT_FOREGROUND_RECONCILE_ATTEMPTS"
                )
            }
        }

    private fun prepareGameAutoSaverOff(): MaxHzShell.Result {
        val result = MaxHzController.prepareGameAutoSaverOff()
        if (result.code !in setOf(-1, 4)) return result
        val powerSaveMode = getSystemService(PowerManager::class.java)?.isPowerSaveMode
        return if (powerSaveMode == false) {
            MaxHzShell.Result(
                0,
                "${result.out}\nGame Auto 已通过 PowerManager.isPowerSaveMode=false 确认系统省电关闭",
                ""
            )
        } else {
            result
        }
    }

    /**
     * 进入游戏：切到极致性能。
     *
     * 三档档位（均衡 / 高性能 / 极限）已废弃 —— 「极致」的定义就是所有东西调到
     * 最强最快，中间档位没有存在意义，而且「均衡」档照样执行 CPU 提频和强制
     * 120Hz，名字与行为不符。现在进游戏只有一种目标状态。
     *
     * 唯一可配置的是「是否放宽温控」（[MaxHzPrefs.gameAutoThermalRelaxed]，默认关）：
     * 放宽温控会让手机明显变烫，有人打游戏就是不想让手机烫手。
     *
     * **先拍快照再改状态** —— 退出游戏时按快照还原，见 [applyGameAutoExit]。
     */
    private fun applyGameAutoEntry(): GameAutoOperation {
        MaxHzPrefs.captureGameAutoSnapshot(this)
        val targetProfile = MaxHzPrefs.gameAutoTargetProfile(this)
        val gamePkg = activeGameAutoPackage
        val steps = mutableListOf<GameAutoStep>()
        when (targetProfile) {
            MaxHzPrefs.GAME_PROFILE_DEFAULT -> {
                steps += GameAutoStep("quick_default") {
                    MaxHzController.quickSwitchGameDefault(packageName = gamePkg)
                }
            }
            MaxHzPrefs.GAME_PROFILE_SAVER -> {
                steps += GameAutoStep("quick_default") {
                    MaxHzController.quickSwitchGameDefault(packageName = gamePkg)
                }
                steps += GameAutoStep("apply_daily_saver") { applyGameAutoDailySaverWithRetry() }
            }
            else -> {
                steps += GameAutoStep("quick_performance") {
                    MaxHzController.quickSwitchGamePerformance(packageName = gamePkg)
                }
                steps += GameAutoStep("verify_system") {
                    MaxHzController.verifyGameAutoPerformance(MaxHzPrefs.GAME_AUTO_PROFILE_SUPER)
                }
            }
        }
        return runGameAutoSteps(steps)
    }

    /**
     * 退出游戏：还原到进入游戏前的状态。
     */
    private fun applyGameAutoExit(): GameAutoOperation {
        val snapshot = MaxHzPrefs.gameAutoSnapshot(this)
        val gamePkg = activeGameAutoPackage
        val steps = mutableListOf<GameAutoStep>()
        when {
            snapshot == null || !snapshot.superPerformance && !snapshot.saver -> {
                steps += GameAutoStep("quick_default") {
                    MaxHzController.quickSwitchGameDefault(packageName = gamePkg)
                }
            }
            snapshot.superPerformance -> {
                steps += GameAutoStep("quick_performance") {
                    MaxHzController.quickSwitchGamePerformance(packageName = null)
                }
            }
            snapshot.saver -> {
                steps += GameAutoStep("quick_default") {
                    MaxHzController.quickSwitchGameDefault(packageName = gamePkg)
                }
                steps += GameAutoStep("apply_daily_saver") { applyGameAutoDailySaverWithRetry() }
            }
        }
        return runGameAutoSteps(steps)
    }

    private fun applyGameAutoDailySaverWithRetry(): MaxHzShell.Result {
        val attempts = mutableListOf<MaxHzShell.Result>()
        repeat(GAME_AUTO_DAILY_SAVER_ATTEMPTS) { attempt ->
            if (attempt > 0) SystemClock.sleep(GAME_AUTO_DAILY_SAVER_RETRY_DELAY_MS)
            val result = MaxHzController.applyDailySaver(
                MaxHzPrefs.quickDozeMaintenanceMinutes(this),
                MaxHzPrefs.quickDozeNoMaintenance(this)
            )
            attempts += result
            gameAutoDiagnostic(
                "daily_saver_attempt attempt=${attempt + 1} code=${result.code}"
            )
            if (result.code == 0) {
                return MaxHzShell.Result(
                    0,
                    attempts.mapIndexed { index, item ->
                        "DAILY_ATTEMPT=${index + 1} CODE=${item.code}\n${item.out}"
                    }.joinToString("\n"),
                    attempts.mapIndexedNotNull { index, item ->
                        item.err.takeIf { it.isNotBlank() }?.let {
                            "DAILY_ATTEMPT=${index + 1}\n$it"
                        }
                    }.joinToString("\n")
                )
            }
        }
        val last = attempts.last()
        return MaxHzShell.Result(
            last.code,
            attempts.mapIndexed { index, item ->
                "DAILY_ATTEMPT=${index + 1} CODE=${item.code}\n${item.out}"
            }.joinToString("\n"),
            attempts.mapIndexedNotNull { index, item ->
                item.err.takeIf { it.isNotBlank() }?.let {
                    "DAILY_ATTEMPT=${index + 1}\n$it"
                }
            }.joinToString("\n")
        )
    }

    private fun runGameAutoSteps(steps: List<GameAutoStep>): GameAutoOperation {
        val records = mutableListOf<GameAutoStepRecord>()
        var firstFailedStep: String? = null
        for (step in steps) {
            val startedAtMs = System.currentTimeMillis()
            val startedElapsed = SystemClock.elapsedRealtime()
            gameAutoDiagnostic("step_start name=${step.name} at_ms=$startedAtMs")
            val result = try {
                step.execute()
            } catch (t: Throwable) {
                MaxHzShell.Result(-2, "", t.stackTraceToString())
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedElapsed
            records += GameAutoStepRecord(step.name, startedAtMs, elapsedMs, result)
            gameAutoDiagnostic(
                "step_end name=${step.name} elapsed_ms=$elapsedMs code=${result.code} " +
                    "stdout=${result.out.escapeGameAutoLog()} stderr=${result.err.escapeGameAutoLog()}"
            )
            if (result.code != 0) {
                firstFailedStep = step.name
                break
            }
        }
        val combined = combineResults(*records.map { it.result }.toTypedArray())
        return GameAutoOperation(combined, records, firstFailedStep)
    }

    private fun gameAutoFailureMessage(
        operationPackage: String,
        generation: Long,
        target: String,
        operation: GameAutoOperation
    ): String {
        val timeline = operation.records.joinToString("\n") { record ->
            "step=${record.name} start=${record.startedAtMs} elapsed=${record.elapsedMs}ms " +
                "code=${record.result.code} stdout=${record.result.out.trim()} stderr=${record.result.err.trim()}"
        }
        return "Game Auto 执行失败\n前台包：$operationPackage\n目标：$target\n" +
            "generation：$generation\n首个失败步骤：${operation.firstFailedStep ?: "unknown"}\n$timeline"
    }

    private fun recordGameAutoOperation(
        operationPackage: String,
        generation: Long,
        target: String,
        operation: GameAutoOperation,
        stillDesired: Boolean
    ) {
        val outcome = when {
            operation.result.code != 0 -> "FAILED"
            !stillDesired -> "STALE"
            else -> "SUCCESS"
        }
        MaxHzPrefs.saveResult(
            this,
            GAME_AUTO_RUNTIME_RESULT_KEY,
            "Game Auto runtime\noutcome=$outcome package=$operationPackage target=$target " +
                "generation=$generation code=${operation.result.code}\n" +
                "coordination:\n${gameAutoTraceSnapshot()}\n" +
                operation.records.joinToString("\n") { record ->
                    "step=${record.name} start=${record.startedAtMs} elapsed=${record.elapsedMs}ms " +
                        "code=${record.result.code} stdout=${record.result.out.trim()} stderr=${record.result.err.trim()}"
                },
            collapsed = false
        )
    }

    private fun clearOwnedGameAutoFailure() {
        val current = MaxHzPrefs.resultText(this, BACKGROUND_FAILURE_RESULT_KEY).orEmpty()
        if (current.contains("Game Auto 执行失败")) {
            MaxHzPrefs.clearResult(this, BACKGROUND_FAILURE_RESULT_KEY)
        }
    }

    private fun String.escapeGameAutoLog(): String = trim().replace('\n', '|').take(600)

    private fun gameAutoDiagnostic(message: String) {
        val line = "${System.currentTimeMillis()} $message"
        synchronized(gameAutoTrace) {
            while (gameAutoTrace.size >= GAME_AUTO_TRACE_LIMIT) gameAutoTrace.removeFirst()
            gameAutoTrace.addLast(line)
        }
        println("$GAME_AUTO_LOG_TAG $line")
        if (
            message.startsWith("intent_created") ||
            message.startsWith("intent_cancelled") ||
            message.startsWith("foreground_decision") ||
            message.startsWith("service_")
        ) {
            MaxHzPrefs.saveResult(
                this,
                GAME_AUTO_RUNTIME_RESULT_KEY,
                "Game Auto coordination\n${gameAutoTraceSnapshot()}",
                collapsed = false
            )
        }
    }

    private fun gameAutoTraceSnapshot(): String = synchronized(gameAutoTrace) {
        gameAutoTrace.joinToString("\n")
    }

    /**
     * 进入游戏后的 pref 落账。
     *
     * 走 [MaxHzPrefs.markSuperPerformanceState] 这个唯一写入口 —— 以前这里只写
     * 5 个子项、漏写 super_performance_enabled，于是一级页开关与系统状态错配：
     * 系统已经是极致性能态、开关却显示「关闭」。
     */
    private fun markGameAutoEntryApplied() {
        val targetProfile = MaxHzPrefs.gameAutoTargetProfile(this)
        when (targetProfile) {
            MaxHzPrefs.GAME_PROFILE_DEFAULT -> {
                MaxHzPrefs.markSuperPerformanceState(this, false)
                MaxHzPrefs.setCompatibleSaverEnabled(this, false)
                MaxHzPrefs.setQuickDozeEnabled(this, false)
                MaxHzPrefs.setGameAutoSessionActive(this, true)
            }
            MaxHzPrefs.GAME_PROFILE_SAVER -> {
                MaxHzPrefs.markSuperPerformanceState(this, false)
                MaxHzPrefs.setCompatibleSaverEnabled(this, true)
                MaxHzPrefs.setQuickDozeEnabled(this, true)
                MaxHzPrefs.setGameAutoSessionActive(this, true)
            }
            else -> {
                MaxHzPrefs.markSuperPerformanceState(
                    this,
                    true,
                    MaxHzPrefs.gameAutoThermalRelaxed(this)
                )
                MaxHzPrefs.setCompatibleSaverEnabled(this, false)
                MaxHzPrefs.setQuickDozeEnabled(this, false)
                MaxHzPrefs.setGameAutoSessionActive(this, true)
            }
        }
    }

    /**
     * 退出游戏后的 pref 落账：按快照还原。
     *
     * 省电位**按系统真实状态写**，不看命令返回值 —— 命令整体判 0 不代表省电真的
     * 开成功了（`runGameAutoSteps` 会聚合多步结果、`applyGameAutoDailySaverWithRetry`
     * 内部还有重试）。无条件置 true 会让 pref 与系统脱节，下次去关省电时
     * `restoreDailySaver()` 校验失败直接 exit 4，弹「恢复失败」。
     */
    private fun markGameAutoExitApplied() {
        val snapshot = MaxHzPrefs.gameAutoSnapshot(this)
        when {
            snapshot == null -> MaxHzPrefs.markSuperPerformanceState(this, false)
            snapshot.superPerformance -> MaxHzPrefs.markSuperPerformanceState(
                this,
                true,
                snapshot.thermal == "relaxed"
            )
            else -> MaxHzPrefs.markSuperPerformanceState(this, false)
        }
        if (snapshot?.thermal == "protect") MaxHzPrefs.setMThermalProtect(this, true)

        val saverOn = snapshot?.saver == true &&
            getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        MaxHzPrefs.setCompatibleSaverEnabled(this, saverOn)
        MaxHzPrefs.setQuickDozeEnabled(this, saverOn)
        MaxHzPrefs.setGameAutoSessionActive(this, false)
        MaxHzPrefs.clearGameAutoSnapshot(this)
    }

    private fun combineResults(vararg results: MaxHzShell.Result): MaxHzShell.Result {
        val code = results.firstOrNull { it.code != 0 }?.code ?: 0
        val out = results.map { it.out }.filter { it.isNotBlank() }.joinToString("\n")
        val err = results.map { it.err }.filter { it.isNotBlank() }.joinToString("\n")
        return MaxHzShell.Result(code, out, err)
    }

    private suspend fun recheckForegroundAfterDefaultIfNeeded(foregroundPackage: String, modeKey: String) {
        if (modeKey != "system_default") return
        if (MaxHzPackagePolicy.isRefreshRateTargetApp(this, foregroundPackage)) return

        delay(FALLBACK_FOREGROUND_RECHECK_DELAY_MS)
        val currentPackage = MaxHzForegroundApp.currentRecentPackage(this) ?: return
        if (currentPackage == foregroundPackage || isInputMethodPackage(currentPackage)) return

        val followUpMode = desiredMode(currentPackage) ?: return
        if (followUpMode.key == "system_default") return

        lastSeenPackage = currentPackage
        pendingPackage = currentPackage
        safeApply { followUpMode.apply(this@MaxHzAccessibilityService) }
        lastAppliedMode = followUpMode.key
        pendingPackage = null
    }

    private fun desiredMode(foregroundPackage: String): RefreshMode? {
        if (MaxHzPackagePolicy.isGameApp(this, foregroundPackage)) {
            return RefreshMode("game_passthrough:$foregroundPackage", 0L) {
                MaxHzController.restoreGameDisplayDefaults(foregroundPackage)
            }
        }

        val currentRules = appRules()
        if (currentRules.isEmpty()) return dailySaverAdaptiveMode()
        val rule = currentRules[foregroundPackage]
        if (rule != null && MaxHzPackagePolicy.isRefreshRateTargetApp(this, foregroundPackage)) {
            return if (rule.adaptive) {
                RefreshMode(
                    key = "app:${rule.packageName}:adaptive:${rule.minHz}:${rule.maxHz}",
                    delayMs = entryDelayFor(rule)
                ) {
                    MaxHzRefreshSettings.applyAdaptiveMax(it, rule.maxHz, rule.minHz)
                }
            } else {
                RefreshMode(
                    key = "app:${rule.packageName}:static:${rule.maxHz}",
                    delayMs = entryDelayFor(rule)
                ) {
                    MaxHzRefreshSettings.applyStaticRefreshRate(it, rule.maxHz)
                }
            }
        }

        dailySaverAdaptiveMode()?.let { return it }

        return RefreshMode("system_default", 0L) {
            MaxHzController.restoreRefreshRateDefaults()
        }
    }

    private fun dailySaverAdaptiveMode(): RefreshMode? {
        if (!MaxHzPrefs.compatibleSaverEnabled(this)) return null
        if (MaxHzPrefs.mMainMode(this) != "off") return null
        return RefreshMode("daily_saver_adaptive", 0L) {
            MaxHzRefreshSettings.applyScreenOnAdaptive(it)
        }
    }

    private fun entryDelayFor(rule: MaxHzPrefs.AppRefreshRule): Long {
        return when {
            !rule.adaptive && rule.maxHz <= 60 -> LOW_REFRESH_ENTRY_DELAY_MS
            rule.adaptive && rule.maxHz <= 60 -> LOW_REFRESH_ENTRY_DELAY_MS
            rule.adaptive && rule.minHz < 60 && rule.maxHz <= 90 -> SHORT_ENTRY_DELAY_MS
            else -> 0L
        }
    }

    private fun appRules(): Map<String, MaxHzPrefs.AppRefreshRule> {
        val now = System.currentTimeMillis()
        if (now - rulesLoadedAt < RULE_CACHE_MS) return rules
        rules = MaxHzPrefs.appRefreshRules(this).associateBy { it.packageName }
        rulesLoadedAt = now
        return rules
    }

    private fun isScreenInteractive(): Boolean {
        return getSystemService(PowerManager::class.java)?.isInteractive == true
    }

    private fun isInputMethodPackage(foregroundPackage: String): Boolean {
        refreshInputMethodCacheIfNeeded()
        return cachedDefaultInputMethodPackage == foregroundPackage ||
            cachedEnabledInputMethodPackages.contains(foregroundPackage)
    }

    private fun isTransientGameAutoPackage(foregroundPackage: String): Boolean {
        return foregroundPackage == "android" ||
            foregroundPackage == "com.android.systemui" ||
            foregroundPackage == "com.android.permissioncontroller" ||
            foregroundPackage == "com.google.android.permissioncontroller" ||
            foregroundPackage == "com.google.android.play.games" ||
            foregroundPackage == "com.samsung.android.game.gametools" ||
            foregroundPackage == "com.samsung.android.game.gamehome" ||
            foregroundPackage == "com.samsung.android.game.gos" ||
            foregroundPackage == "com.samsung.android.app.cocktailbarservice" ||
            foregroundPackage == "com.samsung.android.honeyboard" ||
            isInputMethodPackage(foregroundPackage)
    }

    private fun shouldGameAutoOwnRefresh(foregroundPackage: String): Boolean {
        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this)) return false
        if (foregroundPackage in MaxHzPrefs.gameAutoPerformancePackages(this)) return true
        if (activeGameAutoPackage != null) return true
        if (isGameAutoExitPending) return true
        return pendingGameAutoTarget?.startsWith("$GAME_AUTO_TARGET_EXIT:") == true
    }

    private fun refreshInputMethodCacheIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - inputMethodCacheAt < INPUT_METHOD_CACHE_MS) return
        inputMethodCacheAt = now
        val inputMethodId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        cachedDefaultInputMethodPackage = inputMethodId
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
        cachedEnabledInputMethodPackages = getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.mapTo(mutableSetOf()) { it.packageName }
            ?: emptySet()
    }

    private fun isGamePackageForPassthrough(packageName: String): Boolean {
        if (MaxHzPackagePolicy.isGameApp(this, packageName)) return true
        gamePackageCache[packageName]?.let { return it }
        val isGame = MaxHzController.isSamsungGamePackage(packageName)
        if (gamePackageCache.size > MAX_GAME_CACHE_SIZE) gamePackageCache.clear()
        gamePackageCache[packageName] = isGame
        return isGame
    }

    private inline fun safeApply(block: () -> Any?) {
        try {
            val result = block()
            if (result is MaxHzShell.Result && result.code != 0) {
                reportBackgroundFailure(result.err.ifBlank { result.out }.ifBlank { "code=${result.code}" })
            }
        } catch (t: Throwable) {
            reportBackgroundFailure(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun reportBackgroundFailure(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBackgroundFailureAt < BACKGROUND_FAILURE_THROTTLE_MS) return
        lastBackgroundFailureAt = now
        MaxHzPrefs.saveResult(
            this,
            BACKGROUND_FAILURE_RESULT_KEY,
            "MaxHz 后台执行失败\n来源：无障碍即时切换\n时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))}\n错误：$message",
            collapsed = false
        )
    }

    companion object {
        private const val EVENT_NOTIFICATION_TIMEOUT_MS = 40L
        private const val RULE_CACHE_MS = 750L
        private const val INPUT_METHOD_CACHE_MS = 30_000L
        private const val SHORT_ENTRY_DELAY_MS = 80L
        private const val LOW_REFRESH_ENTRY_DELAY_MS = 140L
        private const val FALLBACK_FOREGROUND_RECHECK_DELAY_MS = 180L
        private const val BACKGROUND_FAILURE_THROTTLE_MS = 30_000L
        private const val BACKGROUND_FAILURE_RESULT_KEY = "background_error"
        private const val GAME_AUTO_RUNTIME_RESULT_KEY = "game_auto_runtime"
        private const val MAX_GAME_CACHE_SIZE = 80
        private const val GAME_AUTO_ENTRY_DELAY_MS = 100L
        private const val TRANSIENT_FOREGROUND_SETTLE_MS = 250L
        private const val TRANSIENT_FOREGROUND_RECONCILE_ATTEMPTS = 12
        private const val GAME_AUTO_DAILY_SAVER_ATTEMPTS = 3
        private const val GAME_AUTO_DAILY_SAVER_RETRY_DELAY_MS = 2_000L
        private const val GAME_AUTO_APPLY_WAIT_MS = 100L
        private const val GAME_AUTO_TARGET_ENTRY = "entry"
        private const val GAME_AUTO_TARGET_EXIT = "exit"
        /**
         * 进/退的完整 target 字符串。
         *
         * 冒号后面原来是档位（high/extreme）和退出策略（daily/default/keep），
         * 两者都已被「固定极致性能 + 快照还原」取代。保留冒号与这段载荷只是为了
         * 让全文件 20 多处 `startsWith("entry:")` / `startsWith("exit:")` 的去重
         * 与取消判断不用跟着改 —— payload 本身已无人读取。
         */
        private const val GAME_AUTO_TARGET_ENTRY_PENDING = "entry:super"
        private const val GAME_AUTO_TARGET_EXIT_PENDING = "exit:restore"
        private const val GAME_AUTO_LOG_TAG = "MaxHzGameAuto"
        private const val ENTRY_ABORT_FOREGROUND_CHANGED = "foreground_changed"
        private const val GAME_AUTO_TRACE_LIMIT = 48
        private const val GAME_AUTO_WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1_000L

        @Volatile
        var isServiceAlive = false
            internal set

        @Volatile
        var isGameAutoExitPending = false
            internal set

        @Volatile
        private var cachedEnabledState: Boolean? = null

        @Volatile
        private var lastCheckTime = 0L

        fun isEnabled(context: Context): Boolean {
            if (isServiceAlive) return true

            val now = SystemClock.uptimeMillis()
            val cached = cachedEnabledState
            if (cached != null && now - lastCheckTime < 2000L) {
                return cached
            }

            val expected = ComponentName(context, MaxHzAccessibilityService::class.java).flattenToString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val enabled = if (enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }) {
                true
            } else {
                val manager = context.getSystemService(AccessibilityManager::class.java)
                manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any {
                        it.resolveInfo.serviceInfo.packageName == context.packageName &&
                            it.resolveInfo.serviceInfo.name == MaxHzAccessibilityService::class.java.name
                    } ?: false
            }

            cachedEnabledState = enabled
            lastCheckTime = now
            return enabled
        }

        fun invalidateCache() {
            cachedEnabledState = null
            lastCheckTime = 0L
        }
    }
}
