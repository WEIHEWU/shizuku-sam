package moe.shizuku.manager.maxhz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import java.util.concurrent.atomic.AtomicBoolean

class AotCompileService : Service() {

    data class TaskState(
        val isRunning: Boolean = false,
        val title: String = "",
        val compileMode: String = "speed-profile",
        val currentStep: Int = 0,
        val total: Int = 0,
        val currentPkg: String = "",
        val currentLabel: String = "",
        val currentElapsedSec: Long = 0L,
        val totalElapsedSec: Long = 0L,
        val successCount: Int = 0,
        val succeededPkgs: List<String> = emptyList(),
        val succeededSummary: List<String> = emptyList(),
        val failedSummary: List<String> = emptyList(),
        val isCompleted: Boolean = false,
        val isCancelled: Boolean = false
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var compileJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isCancelled = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            cancelTask()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "应用预编译"
            val mode = intent.getStringExtra(EXTRA_MODE) ?: "speed-profile"
            val pkgs = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
            val labels = intent.getStringArrayListExtra(EXTRA_LABELS) ?: arrayListOf()

            val appList = mutableListOf<Pair<String, String>>()
            for (i in 0 until minOf(pkgs.size, labels.size)) {
                appList.add(pkgs[i] to labels[i])
            }

            if (appList.isNotEmpty()) {
                startCompilation(title, mode, appList)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelTask()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Shizuku:AotCompileWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(3600_000L) // 最多持有 1 小时，防止永久唤醒
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock", e)
        } finally {
            wakeLock = null
        }
    }

    private fun startCompilation(
        title: String,
        compileMode: String,
        appList: List<Pair<String, String>>
    ) {
        compileJob?.cancel()
        isCancelled.set(false)
        acquireWakeLock()

        val total = appList.size
        _stateFlow.value = TaskState(
            isRunning = true,
            title = title,
            compileMode = compileMode,
            currentStep = 0,
            total = total,
            currentPkg = "",
            currentLabel = "准备开始优化...",
            currentElapsedSec = 0L,
            totalElapsedSec = 0L,
            successCount = 0
        )

        // 立即提升为前台服务并显示第一张常驻通知
        startForegroundCompat(
            NOTIFICATION_ID_RUNNING,
            buildProgressNotification(
                title = title,
                currentStep = 0,
                total = total,
                currentLabel = "正在初始化编译环境...",
                elapsedSec = 0L
            )
        )

        compileJob = serviceScope.launch {
            val isReset = compileMode == "--reset"
            val batchStartMs = SystemClock.elapsedRealtime()
            var successCount = 0
            val succeededPkgs = mutableListOf<String>()
            val succeededSummary = mutableListOf<String>()
            val failedSummary = mutableListOf<String>()

            // 预热环境：先清理轻量缓存，确保 dexopt 空间充足
            MaxHzShell.execRaw("pm trim-caches 10000000M >/dev/null 2>&1 || true", timeoutMs = 5000)

            for (i in 0 until total) {
                if (isCancelled.get()) break

                val (pkg, label) = appList[i]
                val currentStep = i + 1
                val appStartMs = SystemClock.elapsedRealtime()

                _stateFlow.value = _stateFlow.value.copy(
                    currentStep = currentStep,
                    currentPkg = pkg,
                    currentLabel = label,
                    currentElapsedSec = 0L,
                    totalElapsedSec = (SystemClock.elapsedRealtime() - batchStartMs) / 1000L
                )

                updateForegroundNotification(
                    title = title,
                    currentStep = currentStep,
                    total = total,
                    currentLabel = label,
                    elapsedSec = 0L
                )

                // 启动秒数计时器协程：每秒刷新状态栏通知与 StateFlow 进度
                val tickerJob = launch {
                    while (isActive && !isCancelled.get()) {
                        delay(1000L)
                        val elapsedSec = (SystemClock.elapsedRealtime() - appStartMs) / 1000L
                        val totalSec = (SystemClock.elapsedRealtime() - batchStartMs) / 1000L
                        _stateFlow.value = _stateFlow.value.copy(
                            currentElapsedSec = elapsedSec,
                            totalElapsedSec = totalSec
                        )
                        updateForegroundNotification(
                            title = title,
                            currentStep = currentStep,
                            total = total,
                            currentLabel = label,
                            elapsedSec = elapsedSec
                        )
                    }
                }

                val cmd = when (compileMode) {
                    "speed" -> "cmd package compile -m speed --split \"\" -f \"$pkg\" 2>/dev/null || cmd package compile -m speed -f \"$pkg\""
                    "speed-profile" -> "cmd package compile -m speed-profile --split \"\" -f \"$pkg\" 2>/dev/null || cmd package compile -m speed-profile \"$pkg\""
                    else -> "cmd package compile --reset \"$pkg\""
                }

                val res = MaxHzShell.execRaw(
                    cmd,
                    timeoutMs = if (compileMode == "speed") 360_000L else 180_000L
                )
                tickerJob.cancel()

                val appElapsedSec = ((SystemClock.elapsedRealtime() - appStartMs) / 100L) / 10.0
                val combinedOutput = "${res.out}\n${res.err}".trim()
                val isOk = res.code == 0 &&
                    !combinedOutput.contains("Failure", ignoreCase = true) &&
                    !combinedOutput.contains("Error", ignoreCase = true)

                if (isOk) {
                    successCount++
                    succeededPkgs.add(pkg)
                    succeededSummary.add("• $label（耗时 ${appElapsedSec}s）")
                } else {
                    val reason = when {
                        res.code == -1 && compileMode == "speed" ->
                            "全量编译超时（建议使用「热点极速」模式）"
                        res.code == -1 ->
                            "编译超时（内部插件过多或特权通道未响应）"
                        combinedOutput.isNotBlank() ->
                            combinedOutput.lineSequence().firstOrNull()?.take(60) ?: "退出码 ${res.code}"
                        else ->
                            "系统跳过或未检测到独立代码（退出码 ${res.code}）"
                    }
                    failedSummary.add("• $label：$reason")
                }

                _stateFlow.value = _stateFlow.value.copy(
                    successCount = successCount,
                    succeededPkgs = succeededPkgs.toList(),
                    succeededSummary = succeededSummary.toList(),
                    failedSummary = failedSummary.toList()
                )
            }

            val totalSec = (SystemClock.elapsedRealtime() - batchStartMs) / 1000L

            if (succeededPkgs.isNotEmpty() && !isReset) {
                MaxHzPrefs.recordMDexoptCompiled(applicationContext, succeededPkgs, compileMode)
            }

            val wasCancelled = isCancelled.get()
            _stateFlow.value = _stateFlow.value.copy(
                isRunning = false,
                isCompleted = !wasCancelled,
                isCancelled = wasCancelled,
                totalElapsedSec = totalSec
            )

            // 编译完成，撤销前台常驻通知并弹出完成总结通知
            stopForegroundCompat()
            releaseWakeLock()

            if (!wasCancelled) {
                showCompletionNotification(
                    title = title,
                    isReset = isReset,
                    successCount = successCount,
                    total = total,
                    totalSec = totalSec
                )
            }

            stopSelf()
        }
    }

    private fun cancelTask() {
        if (isCancelled.compareAndSet(false, true)) {
            compileJob?.cancel()
            _stateFlow.value = _stateFlow.value.copy(
                isRunning = false,
                isCancelled = true
            )
            stopForegroundCompat()
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "应用预编译服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示 ART 机器码预编译在后台的实时进度与耗时"
                    setShowBadge(false)
                    setSound(null, null)
                }
            )
        }
    }

    private fun startForegroundCompat(notificationId: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to startForeground", e)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stopForeground", e)
        }
    }

    private fun buildProgressNotification(
        title: String,
        currentStep: Int,
        total: Int,
        currentLabel: String,
        elapsedSec: Long
    ): Notification {
        val percent = if (total > 0) (currentStep * 100) / total else 0
        val contentTitle = "$title [$currentStep/$total] ($percent%)"
        val contentText = if (elapsedSec > 0) {
            "正在优化：$currentLabel · 已耗时 ${elapsedSec}s"
        } else {
            "正在优化：$currentLabel"
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("extra_open_aot_progress", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, AotCompileService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSubText(if (total > 0) "$percent%" else null)
            .setProgress(total, currentStep, total <= 1 && currentStep == 0)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "取消编译", cancelPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateForegroundNotification(
        title: String,
        currentStep: Int,
        total: Int,
        currentLabel: String,
        elapsedSec: Long
    ) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            NOTIFICATION_ID_RUNNING,
            buildProgressNotification(
                title = title,
                currentStep = currentStep,
                total = total,
                currentLabel = currentLabel,
                elapsedSec = elapsedSec
            )
        )
    }

    private fun showCompletionNotification(
        title: String,
        isReset: Boolean,
        successCount: Int,
        total: Int,
        totalSec: Long
    ) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val timeStr = if (totalSec < 60) "${totalSec}秒" else "${totalSec / 60}分${totalSec % 60}秒"
        val notifTitle = if (isReset) {
            "✅ 预编译缓存已重置"
        } else if (successCount == total) {
            "🎉 ${title}已完成"
        } else {
            "⚠️ ${title}部分完成"
        }
        val notifText = "已完成 $successCount / $total 个应用 · 总耗时 $timeStr"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            3,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        nm.notify(NOTIFICATION_ID_COMPLETED, notification)
    }

    companion object {
        private const val TAG = "AotCompileService"
        const val CHANNEL_ID = "maxhz_aot_compile"
        const val NOTIFICATION_ID_RUNNING = 1455
        const val NOTIFICATION_ID_COMPLETED = 1456

        const val ACTION_START = "moe.shizuku.manager.maxhz.ACTION_START_AOT"
        const val ACTION_CANCEL = "moe.shizuku.manager.maxhz.ACTION_CANCEL_AOT"
        const val EXTRA_TITLE = "extra_aot_title"
        const val EXTRA_MODE = "extra_aot_mode"
        const val EXTRA_PACKAGES = "extra_aot_packages"
        const val EXTRA_LABELS = "extra_aot_labels"

        private val _stateFlow = MutableStateFlow(TaskState())
        val stateFlow: StateFlow<TaskState> = _stateFlow.asStateFlow()

        val isRunning: Boolean
            get() = _stateFlow.value.isRunning

        fun start(
            context: Context,
            title: String,
            compileMode: String,
            appList: List<Pair<String, String>>
        ) {
            val intent = Intent(context, AotCompileService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MODE, compileMode)
                putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(appList.map { it.first }))
                putStringArrayListExtra(EXTRA_LABELS, ArrayList(appList.map { it.second }))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, AotCompileService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
