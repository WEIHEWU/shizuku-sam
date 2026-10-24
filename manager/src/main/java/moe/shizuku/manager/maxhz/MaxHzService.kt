package moe.shizuku.manager.maxhz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.R

class MaxHzService : Service() {

    private enum class ForegroundSource {
        USAGE_STATS,
        WINDOW
    }

    private data class RefreshMode(
        val key: String,
        val apply: (Context) -> Unit
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var refreshApplyJob: Job? = null
    @Volatile
    private var pendingRefreshMode: RefreshMode? = null
    private var lastAppliedRefreshMode: String? = null
    private var lastNightWakeupGuardAt = 0L
    private var cachedAppRules: Map<String, MaxHzPrefs.AppRefreshRule> = emptyMap()
    private var cachedAppRulesAt = 0L
    private var observedForegroundPackage: String? = null
    private var observedForegroundCount = 0
    private var observedForegroundSource: ForegroundSource? = null
    private var lastWindowLookupAt = 0L
    private var lastBackgroundFailureAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                try {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            lastAppliedRefreshMode = null
                            reconcileRefresh(force = true)
                            if (MaxHzPrefs.nightWakeupGuardEnabled(context)) {
                                applyNightWakeupGuardIfDue(force = true)
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            lastAppliedRefreshMode = null
                            reconcileRefresh(force = true)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Receiver error: ${intent.action}", t)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        monitorJob = scope.launch {
            while (isActive) {
                val appRules = appRefreshRules()
                val screenInteractive = isScreenInteractive()
                if (hasRefreshControlEnabled()) {
                    reconcileRefresh(force = false, appRules)
                } else {
                    lastAppliedRefreshMode = null
                }
                if (MaxHzPrefs.nightWakeupGuardEnabled(this@MaxHzService) && !screenInteractive) {
                    applyNightWakeupGuardIfDue(force = false)
                }
                delay(nextMonitorDelay(screenInteractive))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForegroundState()
        scope.launch {
            appRefreshRules(force = true)
            reconcileServiceState(force = true)
        }
        return if (MaxHzPrefs.requiresBackgroundService(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        unregisterReceiver(screenReceiver)
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            runCatching { manager.deleteNotificationChannel("maxhz_background") }
            val showNotification = MaxHzPrefs.showForegroundNotification(this)
            val importance = if (showNotification) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "MaxHz 后台服务",
                importance
            ).apply {
                description = "保持已启用的刷新率、待机护栏和电量调度规则"
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create notification channel", t)
        }
    }

    private fun updateForegroundState() {
        if (!MaxHzPrefs.requiresBackgroundService(this)) {
            stopForegroundCompat()
            return
        }
        createNotificationChannel()
        runCatching {
            val notif = serviceNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        }.onFailure {
            Log.w(TAG, "startForeground failed", it)
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun serviceNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, moe.shizuku.manager.MainActivity::class.java).apply {
                putExtra("extra_target_page", "overview")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        }
        return builder
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle("MaxHz 后台服务运行中")
            .setContentText("用于已启用的刷新率、待机护栏和电量规则。")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun reconcileServiceState(force: Boolean) {
        val appRules = appRefreshRules(force = force)
        if (MaxHzPrefs.controlEnabled(this) || appRules.isNotEmpty()) {
            safeApply { MaxHzController.grantSupportPermissions() }
        }
        if (MaxHzPrefs.quickDozeEnabled(this)) {
            safeApply {
                MaxHzController.applyQuickDoze(
                    MaxHzPrefs.quickDozeMaintenanceMinutes(this),
                    MaxHzPrefs.quickDozeNoMaintenance(this)
                )
            }
        }
        if (MaxHzPrefs.nightWakeupGuardEnabled(this) && !isScreenInteractive()) {
            applyNightWakeupGuardIfDue(force = true)
        }
        if (MaxHzPrefs.scanGuardEnabled(this)) {
            safeApply { MaxHzController.applyScanGuard() }
        }
        reconcileRefresh(force, appRules)
    }

    private fun reconcileRefresh(force: Boolean, appRules: Map<String, MaxHzPrefs.AppRefreshRule> = appRefreshRules()) {
        if (MaxHzAccessibilityService.isEnabled(this)) {
            val gameAutoBusy = MaxHzPrefs.gameAutoPerformanceEnabled(this) &&
                (MaxHzPrefs.gameAutoSessionActive(this) ||
                    MaxHzPrefs.gameAutoActivePackage(this) != null ||
                    MaxHzAccessibilityService.isGameAutoExitPending)
            if (appRules.isNotEmpty() || gameAutoBusy) {
                pendingRefreshMode = null
                refreshApplyJob?.cancel()
                refreshApplyJob = null
                lastAppliedRefreshMode = null
                return
            }
        }
        val mode = desiredRefreshMode(appRules)
        if (mode == null) {
            lastAppliedRefreshMode = null
            return
        }
        if (!force && mode.key == lastAppliedRefreshMode) return
        lastAppliedRefreshMode = mode.key
        enqueueRefreshApply(mode)
    }

    private fun desiredRefreshMode(appRules: Map<String, MaxHzPrefs.AppRefreshRule>): RefreshMode? {
        if (!isScreenInteractive()) return null

        val foregroundPackage = MaxHzForegroundApp.currentRecentPackage(this)
        if (!foregroundPackage.isNullOrBlank() && isGameAutoPerformanceForeground(foregroundPackage)) {
            return null
        }
        if (isGameAutoSessionBusy()) {
            return null
        }
        if (!foregroundPackage.isNullOrBlank() &&
            (MaxHzPackagePolicy.isGameApp(this, foregroundPackage) || MaxHzController.isSamsungGamePackage(foregroundPackage))
        ) {
            return RefreshMode("game_passthrough:$foregroundPackage") {
                MaxHzController.restoreGameDisplayDefaults(foregroundPackage)
            }
        }

        val rule = foregroundRefreshRule(appRules)
        if (rule != null) {
            return if (rule.adaptive) {
                RefreshMode("app:${rule.packageName}:adaptive:${rule.minHz}:${rule.maxHz}") {
                    MaxHzRefreshSettings.applyAdaptiveMax(it, rule.maxHz, rule.minHz)
                }
            } else {
                RefreshMode("app:${rule.packageName}:static:${rule.maxHz}") {
                    MaxHzRefreshSettings.applyStaticRefreshRate(it, rule.maxHz)
                }
            }
        }

        if (MaxHzPrefs.controlEnabled(this) && MaxHzPrefs.compatibleSaverEnabled(this) && MaxHzPrefs.mMainMode(this) == "off") {
            return RefreshMode("compatible_saver_adaptive") {
                MaxHzController.applyCompatibleSaverAdaptive()
            }
        }

        if (MaxHzPrefs.controlEnabled(this)) {
            return RefreshMode("screen_on_adaptive") {
                MaxHzRefreshSettings.applyScreenOnAdaptive(it)
            }
        }

        return null
    }

    private fun isGameAutoPerformanceForeground(packageName: String): Boolean {
        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this)) return false
        if (MaxHzPrefs.gameAutoPerformancePackages(this).contains(packageName) ||
            MaxHzPackagePolicy.isGameApp(this, packageName)) return true
        return isGameAutoSessionBusy()
    }

    private fun isGameAutoSessionBusy(): Boolean {
        return MaxHzPrefs.gameAutoPerformanceEnabled(this) &&
            (MaxHzPrefs.gameAutoSessionActive(this) ||
                MaxHzPrefs.gameAutoActivePackage(this) != null ||
                MaxHzAccessibilityService.isGameAutoExitPending)
    }

    private fun enqueueRefreshApply(mode: RefreshMode) {
        pendingRefreshMode = mode
        if (refreshApplyJob?.isActive == true) return
        refreshApplyJob = scope.launch {
            while (isActive) {
                val next = pendingRefreshMode ?: break
                pendingRefreshMode = null
                safeApply { next.apply(this@MaxHzService) }
            }
        }
    }

    private fun foregroundRefreshRule(appRules: Map<String, MaxHzPrefs.AppRefreshRule>): MaxHzPrefs.AppRefreshRule? {
        if (appRules.isEmpty()) return null
        if (MaxHzAccessibilityService.isEnabled(this)) return null
        val packageName = foregroundPackageForAppRules()?.takeIf { it.isNotBlank() } ?: return null
        val rule = appRules[packageName] ?: return null
        if (!MaxHzPackagePolicy.isRefreshRateTargetApp(this, packageName)) return null
        return rule
    }

    private fun foregroundPackageForAppRules(): String? {
        val usagePackage = MaxHzForegroundApp.currentRecentPackage(this)
        if (!usagePackage.isNullOrBlank()) {
            if (usagePackage == observedForegroundPackage) {
                observedForegroundCount = APP_RULE_STABLE_SAMPLES
                return usagePackage
            }
            markObservedForeground(usagePackage, ForegroundSource.USAGE_STATS)
            return usagePackage
        }

        val shouldUseWindow = observedForegroundSource != ForegroundSource.USAGE_STATS ||
            System.currentTimeMillis() - lastWindowLookupAt >= WINDOW_LOOKUP_INTERVAL_MS
        if (shouldUseWindow) {
            lastWindowLookupAt = System.currentTimeMillis()
            val windowPackage = MaxHzForegroundApp.currentWindowPackage()
            if (!windowPackage.isNullOrBlank()) {
                markObservedForeground(windowPackage, ForegroundSource.WINDOW)
                return windowPackage
            }
        }
        return stableRecentForegroundPackage()
    }

    private fun markObservedForeground(packageName: String, source: ForegroundSource) {
        observedForegroundSource = source
        observedForegroundPackage = packageName
        observedForegroundCount = APP_RULE_STABLE_SAMPLES
    }

    private fun stableRecentForegroundPackage(): String? {
        val packageName = MaxHzForegroundApp.currentRecentPackage(this)
        if (packageName.isNullOrBlank()) {
            observedForegroundPackage = null
            observedForegroundCount = 0
            return null
        }
        if (packageName == observedForegroundPackage) {
            observedForegroundCount += 1
        } else {
            observedForegroundPackage = packageName
            observedForegroundCount = 1
        }
        return if (observedForegroundCount >= APP_RULE_STABLE_SAMPLES) packageName else null
    }

    private fun hasRefreshControlEnabled(): Boolean {
        return MaxHzPrefs.controlEnabled(this)
    }

    private fun appRefreshRules(force: Boolean = false): Map<String, MaxHzPrefs.AppRefreshRule> {
        if (!MaxHzAccessibilityService.isEnabled(this)) {
            cachedAppRules = emptyMap()
            cachedAppRulesAt = System.currentTimeMillis()
            return cachedAppRules
        }
        val now = System.currentTimeMillis()
        if (!force && now - cachedAppRulesAt < APP_RULE_CACHE_MS) return cachedAppRules
        cachedAppRules = MaxHzPrefs.appRefreshRules(this).associateBy { it.packageName }
        cachedAppRulesAt = now
        return cachedAppRules
    }

    private fun nextMonitorDelay(screenInteractive: Boolean): Long {
        if (MaxHzPrefs.nightWakeupGuardEnabled(this) && !screenInteractive) return IDLE_GUARD_POLL_MS
        return DEFAULT_POLL_MS
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
            "MaxHz 后台执行失败\n来源：后台服务\n时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))}\n错误：$message",
            collapsed = false
        )
    }

    private fun applyNightWakeupGuardIfDue(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNightWakeupGuardAt < NIGHT_WAKEUP_GUARD_INTERVAL_MS) return
        lastNightWakeupGuardAt = now
        safeApply { MaxHzController.applyNightWakeupGuard(MaxHzPrefs.nightWakeupGuardPackages(this)) }
    }

    private fun isScreenInteractive(): Boolean {
        return getSystemService(PowerManager::class.java)?.isInteractive == true
    }

    companion object {
        private const val NIGHT_WAKEUP_GUARD_INTERVAL_MS = 30 * 60 * 1000L
        private const val APP_RULE_STABLE_SAMPLES = 2
        private const val APP_RULE_CACHE_MS = 2_000L
        // 省电修复：dumpsys 窗口查询从 4s 放宽到 8s（dumpsys 会让 system_server 高负载）
        private const val WINDOW_LOOKUP_INTERVAL_MS = 8_000L
        // 省电修复：熄屏护栏轮询从 60s 放宽到 10 分钟。
        // 护栏本身有 30 分钟节流 + SCREEN_OFF 事件立即触发，60s 轮询纯属浪费。
        private const val IDLE_GUARD_POLL_MS = 10 * 60_000L
        private const val DEFAULT_POLL_MS = 5 * 60_000L
        private const val BACKGROUND_FAILURE_THROTTLE_MS = 30_000L
        private const val BACKGROUND_FAILURE_RESULT_KEY = "background_error"
        private const val NOTIFICATION_CHANNEL_ID = "maxhz_background_v2"
        private const val NOTIFICATION_ID = 13601
        private const val TAG = "MaxHzService"
    }
}
