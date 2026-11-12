package moe.shizuku.manager.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.LocalNetworkGate
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一的启动/自动重连协调状态机与可观测诊断中心：
 * - 全局单飞（in-flight 防重，同时仅允许一个启动任务）
 * - 明确超时、失败原因枚举、指数退避与最大自动重试次数（默认 3 次）
 * - 用户可见的「停止重试」开关（持久化到 ShizukuSettings）
 * - 结构化事件环形缓冲区与一键诊断报告导出
 */
object StartupCoordinator {

    private const val TAG = "StartupCoordinator"
    const val MAX_AUTO_RETRIES = 3
    private const val IN_FLIGHT_STALE_TIMEOUT_MS = 35_000L
    private const val MAX_LOG_LINES = 50
    private const val PREF_RETRY_STOPPED_BY_USER = "startup_auto_retry_stopped"

    enum class Phase(val label: String) {
        IDLE("待激活"),
        STARTING("启动中"),
        WAITING_FOR_PERMISSION("等待本地网络授权"),
        RUNNING("运行中"),
        FAILED("启动失败"),
        RETRY_PAUSED("已停止重试")
    }

    enum class FailureReason(val label: String) {
        NONE("无"),
        LOCAL_NETWORK_PERMISSION_MISSING("本地网络权限未授予"),
        BINDER_TIMEOUT("等待服务响应超时"),
        ADB_CONNECT_FAILED("无线调试端口连接失败"),
        ADB_PAIRING_EXPIRED("无线调试配对密钥失效"),
        ADB_WIFI_DISABLED("系统无线调试未开启"),
        ROOT_UNAVAILABLE("未获取到 Root 授权"),
        ROOT_COMMAND_FAILED("Root 启动命令退出异常"),
        MAX_RETRIES_EXCEEDED("已达最大自动重连次数"),
        UNKNOWN("启动异常")
    }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val inFlightMode: String? = null,
        val lastFailureReason: FailureReason = FailureReason.NONE,
        val lastFailureDetail: String = "",
        val lastFailureTimeMs: Long = 0L,
        val autoRetryCount: Int = 0,
        val maxAutoRetries: Int = MAX_AUTO_RETRIES,
        val nextRetryAtMs: Long = 0L,
        val retryStoppedByUser: Boolean = false,
        val lastProbeHost: String = "-",
        val lastProbePort: Int = -1,
        val lastProbeSummary: String = "尚未探测",
        val lastProbeTimeMs: Long = 0L,
        val lastConnectEndpoint: String = "尚未连接",
        val lastConnectTimeMs: Long = 0L,
        val logVersion: Int = 0
    )

    private val inFlight = AtomicBoolean(false)
    private val inFlightAcquiredAtMs = AtomicLong(0L)
    private val logBuffer = ArrayDeque<String>(MAX_LOG_LINES + 4)

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    init {
        runCatching {
            val stopped = isRetryStoppedPersisted()
            val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            _state.update {
                it.copy(
                    phase = when {
                        alive -> Phase.RUNNING
                        stopped -> Phase.RETRY_PAUSED
                        else -> Phase.IDLE
                    },
                    retryStoppedByUser = stopped
                )
            }
        }
        Shizuku.addBinderReceivedListenerSticky {
            onBinderReceived()
        }
        Shizuku.addBinderDeadListener {
            onBinderDead()
        }
    }

    private fun isRetryStoppedPersisted(): Boolean {
        return runCatching {
            ShizukuSettings.getPreferences()?.getBoolean(PREF_RETRY_STOPPED_BY_USER, false) == true
        }.getOrDefault(false)
    }

    private fun setRetryStoppedPersisted(stopped: Boolean) {
        runCatching {
            ShizukuSettings.getPreferences()?.edit()?.putBoolean(PREF_RETRY_STOPPED_BY_USER, stopped)?.apply()
        }
    }

    fun isAutoRetryStopped(): Boolean {
        val persisted = isRetryStoppedPersisted()
        if (_state.value.retryStoppedByUser != persisted) {
            _state.update { it.copy(retryStoppedByUser = persisted) }
        }
        return persisted
    }

    private fun onBinderReceived() {
        inFlight.set(false)
        inFlightAcquiredAtMs.set(0L)
        _state.update {
            it.copy(
                phase = Phase.RUNNING,
                inFlightMode = null,
                autoRetryCount = 0,
                nextRetryAtMs = 0L
            )
        }
        recordLog("BINDER", "Shizuku binder connected -> RUNNING")
    }

    private fun onBinderDead() {
        inFlight.set(false)
        inFlightAcquiredAtMs.set(0L)
        _state.update { current ->
            val nextPhase = when {
                current.retryStoppedByUser -> Phase.RETRY_PAUSED
                current.phase == Phase.RUNNING -> Phase.IDLE
                else -> current.phase
            }
            current.copy(phase = nextPhase, inFlightMode = null)
        }
        recordLog("BINDER", "Shizuku binder dead")
    }

    /**
     * 尝试获取全局唯一启动锁。
     * @param mode "Root" 或 "ADB"
     * @param userInitiated 是否由用户主动点击触发（主动操作会自动解除暂停并重置自动重试计数）
     */
    fun tryBeginStart(mode: String, userInitiated: Boolean, caller: String): Boolean {
        val now = System.currentTimeMillis()
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (alive && !userInitiated) {
            onBinderReceived()
            recordLog("SKIP_START", "reason=ALREADY_RUNNING mode=$mode caller=$caller")
            return false
        }

        if (userInitiated) {
            setRetryStoppedPersisted(false)
            _state.update {
                it.copy(
                    retryStoppedByUser = false,
                    autoRetryCount = 0,
                    nextRetryAtMs = 0L,
                    lastFailureReason = FailureReason.NONE,
                    lastFailureDetail = ""
                )
            }
        } else {
            if (isAutoRetryStopped()) {
                _state.update { it.copy(phase = Phase.RETRY_PAUSED) }
                recordLog("SKIP_START", "reason=RETRY_STOPPED_BY_USER mode=$mode caller=$caller")
                return false
            }
            val snap = _state.value
            if (snap.autoRetryCount >= MAX_AUTO_RETRIES) {
                _state.update {
                    it.copy(
                        phase = Phase.FAILED,
                        lastFailureReason = FailureReason.MAX_RETRIES_EXCEEDED,
                        lastFailureDetail = "后台自动重连已连续失败 ${snap.autoRetryCount} 次，已自动停止重试",
                        nextRetryAtMs = 0L
                    )
                }
                recordLog("SKIP_START", "reason=MAX_RETRIES_EXCEEDED(${snap.autoRetryCount}/$MAX_AUTO_RETRIES) mode=$mode caller=$caller")
                return false
            }
            if (snap.nextRetryAtMs > now) {
                val remainMs = snap.nextRetryAtMs - now
                recordLog("SKIP_START", "reason=BACKOFF_ACTIVE remainMs=$remainMs mode=$mode caller=$caller")
                return false
            }
        }

        // 检查并释放超时陈旧锁，防止异常协程永久卡住单飞门控
        if (inFlight.get() && now - inFlightAcquiredAtMs.get() > IN_FLIGHT_STALE_TIMEOUT_MS) {
            recordLog("WARN", "Releasing stale inFlight lock (> ${IN_FLIGHT_STALE_TIMEOUT_MS}ms)")
            inFlight.set(false)
        }

        if (!inFlight.compareAndSet(false, true)) {
            if (userInitiated) {
                // 用户在前台主动点击「重试」时，若前序后台任务锁未及时释放则接管
                inFlightAcquiredAtMs.set(now)
                _state.update { it.copy(phase = Phase.STARTING, inFlightMode = mode) }
                recordLog("START_TAKEOVER", "mode=$mode caller=$caller")
                return true
            }
            recordLog("SKIP_START", "reason=IN_FLIGHT(${_state.value.inFlightMode}) mode=$mode caller=$caller")
            return false
        }

        inFlightAcquiredAtMs.set(now)
        _state.update {
            it.copy(
                phase = Phase.STARTING,
                inFlightMode = mode
            )
        }
        recordLog("START_BEGIN", "mode=$mode userInitiated=$userInitiated caller=$caller")
        return true
    }

    fun onStartSucceeded(mode: String) {
        inFlight.set(false)
        inFlightAcquiredAtMs.set(0L)
        _state.update {
            it.copy(
                phase = Phase.RUNNING,
                inFlightMode = null,
                autoRetryCount = 0,
                nextRetryAtMs = 0L,
                lastFailureReason = FailureReason.NONE,
                lastFailureDetail = ""
            )
        }
        recordLog("START_OK", "mode=$mode")
    }

    fun onStartFailed(
        reason: FailureReason,
        detail: String,
        userInitiated: Boolean,
        caller: String
    ) {
        inFlight.set(false)
        inFlightAcquiredAtMs.set(0L)
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            onStartSucceeded(_state.value.inFlightMode ?: "Unknown")
            return
        }
        val now = System.currentTimeMillis()
        _state.update { current ->
            val nextRetryCount = if (userInitiated) current.autoRetryCount else (current.autoRetryCount + 1)
            val backoffMs = backoffDelayMs(nextRetryCount)
            val hasMoreAutoRetries = !userInitiated &&
                !current.retryStoppedByUser &&
                reason != FailureReason.LOCAL_NETWORK_PERMISSION_MISSING &&
                nextRetryCount < MAX_AUTO_RETRIES
            val nextPhase = when {
                current.retryStoppedByUser -> Phase.RETRY_PAUSED
                reason == FailureReason.LOCAL_NETWORK_PERMISSION_MISSING -> Phase.WAITING_FOR_PERMISSION
                else -> Phase.FAILED
            }
            current.copy(
                phase = nextPhase,
                inFlightMode = null,
                lastFailureReason = if (!userInitiated && nextRetryCount >= MAX_AUTO_RETRIES && reason != FailureReason.LOCAL_NETWORK_PERMISSION_MISSING) {
                    FailureReason.MAX_RETRIES_EXCEEDED
                } else {
                    reason
                },
                lastFailureDetail = detail,
                lastFailureTimeMs = now,
                autoRetryCount = nextRetryCount,
                nextRetryAtMs = if (hasMoreAutoRetries) now + backoffMs else 0L
            )
        }
        val snap = _state.value
        recordLog(
            "START_FAIL",
            "reason=${reason.name} caller=$caller user=$userInitiated retry=${snap.autoRetryCount}/$MAX_AUTO_RETRIES detail=${detail.replace('\n', ' ').take(120)}"
        )
    }

    fun releaseInFlightIfIdle() {
        inFlight.set(false)
        inFlightAcquiredAtMs.set(0L)
        _state.update { current ->
            if (current.phase == Phase.STARTING) {
                val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                current.copy(
                    phase = if (alive) Phase.RUNNING else Phase.IDLE,
                    inFlightMode = null
                )
            } else {
                current.copy(inFlightMode = null)
            }
        }
    }

    fun markWaitingForPermission(caller: String) {
        inFlight.set(false)
        inFlightAcquiredAtMs.set(0L)
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                phase = Phase.WAITING_FOR_PERMISSION,
                inFlightMode = null,
                lastFailureReason = FailureReason.LOCAL_NETWORK_PERMISSION_MISSING,
                lastFailureDetail = "未授予本地网络权限，已跳过连接 ($caller)",
                lastFailureTimeMs = now,
                nextRetryAtMs = 0L
            )
        }
    }

    fun stopAutoRetry(context: Context) {
        setRetryStoppedPersisted(true)
        inFlight.set(false)
        inFlightAcquiredAtMs.set(0L)
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork("adb_start_worker")
        }
        _state.update {
            it.copy(
                phase = if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) Phase.RUNNING else Phase.RETRY_PAUSED,
                inFlightMode = null,
                retryStoppedByUser = true,
                nextRetryAtMs = 0L
            )
        }
        recordLog("USER_ACTION", "stopAutoRetry invoked -> RETRY_PAUSED")
    }

    fun resumeAutoRetry() {
        setRetryStoppedPersisted(false)
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        _state.update {
            it.copy(
                phase = if (alive) Phase.RUNNING else Phase.IDLE,
                retryStoppedByUser = false,
                autoRetryCount = 0,
                nextRetryAtMs = 0L
            )
        }
        recordLog("USER_ACTION", "resumeAutoRetry invoked")
    }

    fun recordConnectTarget(host: String, port: Int, caller: String) {
        val now = System.currentTimeMillis()
        val endpoint = "$host:$port"
        _state.update {
            it.copy(
                lastConnectEndpoint = endpoint,
                lastConnectTimeMs = now
            )
        }
        recordLog("CONNECT_TARGET", "endpoint=$endpoint caller=$caller")
    }

    fun recordPortProbe(
        candidates: Collection<Int>,
        hitPort: Int,
        caller: String,
        skippedReason: String? = null,
        hitHost: String? = null,
        candidateHosts: Collection<String> = emptyList()
    ) {
        val now = System.currentTimeMillis()
        val resolvedHost = hitHost?.takeIf { it.isNotBlank() } ?: "-"
        val hostListText = candidateHosts.joinToString("/").ifBlank { "-" }
        val summary = if (skippedReason != null) {
            "已跳过 ($skippedReason)"
        } else if (hitPort > 0) {
            "命中 $resolvedHost:$hitPort（候选端口 ${candidates.joinToString("/")}）"
        } else {
            "无开放端口（主机 $hostListText，端口 ${candidates.joinToString("/").ifBlank { "无" }}）"
        }
        _state.update {
            it.copy(
                lastProbeHost = resolvedHost,
                lastProbePort = hitPort,
                lastProbeSummary = summary,
                lastProbeTimeMs = now
            )
        }
        recordLog(
            "PORT_PROBE",
            "caller=$caller hosts=${candidateHosts.joinToString(",")} ports=${candidates.joinToString(",")} result=${if (hitPort > 0) "$resolvedHost:$hitPort" else "-1"}${if (skippedReason != null) " skipped=$skippedReason" else ""}"
        )
    }

    fun backoffDelayMs(retryCount: Int): Long {
        val shift = (retryCount - 1).coerceIn(0, 5)
        return minOf(8_000L * (1L shl shift), 120_000L)
    }

    fun classifyThrowable(e: Throwable?): FailureReason {
        if (e == null) return FailureReason.UNKNOWN
        val name = e.javaClass.simpleName
        val msg = e.message.orEmpty()
        return when {
            e is LocalNetworkGate.LocalNetworkPermissionException || msg.contains("本地网络权限") ->
                FailureReason.LOCAL_NETWORK_PERMISSION_MISSING
            e is java.util.concurrent.TimeoutException || name.contains("Timeout") || msg.contains("超时") ->
                FailureReason.BINDER_TIMEOUT
            e is javax.net.ssl.SSLProtocolException || name.contains("SSL") ->
                FailureReason.ADB_PAIRING_EXPIRED
            e is java.net.ConnectException || e is java.net.SocketException ->
                FailureReason.ADB_CONNECT_FAILED
            name.contains("NotRooted") || msg.contains("Root", ignoreCase = true) ->
                FailureReason.ROOT_UNAVAILABLE
            else -> FailureReason.UNKNOWN
        }
    }

    @Synchronized
    fun recordLog(category: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts][$category] $message"
        Log.i(TAG, line)
        if (logBuffer.size >= MAX_LOG_LINES) {
            logBuffer.removeFirst()
        }
        logBuffer.addLast(line)
        _state.update { it.copy(logVersion = it.logVersion + 1) }
    }

    @Synchronized
    fun recentLogs(): List<String> = logBuffer.toList()

    fun formatTimestamp(ms: Long): String {
        if (ms <= 0L) return "暂无记录"
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    fun buildDiagnosticReport(context: Context): String {
        val snap = _state.value
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val binderUid = if (binderAlive) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        val smState = ShizukuStateMachine.get()
        val permDetail = LocalNetworkGate.diagnosticStatusText(context)
        val mode = when (ShizukuSettings.getLastLaunchMode()) {
            ShizukuSettings.LaunchMethod.ROOT -> "Root 模式"
            ShizukuSettings.LaunchMethod.ADB -> "无线调试 (ADB) 模式"
            else -> "尚未初始化"
        }
        val rootAvail = EnvironmentUtils.isRooted() || EnvironmentUtils.isRootManagerInstalled(context)
        val watchdogOn = ShizukuSettings.getWatchdog()
        val now = System.currentTimeMillis()
        val nextRetryText = when {
            snap.retryStoppedByUser -> "已手动暂停自动重试"
            snap.nextRetryAtMs > now -> "${(snap.nextRetryAtMs - now) / 1000}s 后 (${formatTimestamp(snap.nextRetryAtMs)})"
            snap.autoRetryCount >= MAX_AUTO_RETRIES -> "已达上限 ($MAX_AUTO_RETRIES 次)，等待手动触发"
            else -> "无待执行重试"
        }
        val lanIp = moe.shizuku.manager.adb.AdbPortProber.findOwnLanAddress() ?: "未连接局域网 (回退 127.0.0.1)"
        val savedHost = ShizukuSettings.getLastHost().ifBlank { "-" }
        val savedPort = ShizukuSettings.getLastPort()
        return buildString {
            appendLine("=== Shizuku Sam 系统环境与连接诊断 ===")
            appendLine("设备环境: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT} / Android ${Build.VERSION.RELEASE})")
            appendLine("本地网络权限 (LNP): $permDetail")
            appendLine("Shizuku Binder: ${if (binderAlive) "已连接 (UID=$binderUid)" else "未连接"} · 状态机=${smState.name} · 协调器=${snap.phase.label}")
            appendLine("启动模式配置: $mode · Root环境=${if (rootAvail) "检测到" else "未检测到"} · Watchdog=${if (watchdogOn) "开启" else "关闭"}")
            appendLine("无线调试地址: 实际连接=${snap.lastConnectEndpoint} (${formatTimestamp(snap.lastConnectTimeMs)}) · 本机LAN=$lanIp · 上次记录=$savedHost:$savedPort")
            appendLine("上次端口探测: ${snap.lastProbeSummary} (${formatTimestamp(snap.lastProbeTimeMs)})")
            appendLine("最后失败原因: ${snap.lastFailureReason.label}${if (snap.lastFailureDetail.isNotBlank()) " — ${snap.lastFailureDetail}" else ""} (${formatTimestamp(snap.lastFailureTimeMs)})")
            appendLine("自动重连计数: ${snap.autoRetryCount} / ${snap.maxAutoRetries} · 下次重试: $nextRetryText")
            appendLine()
            appendLine("--- 最近事件日志 (${recentLogs().size} 条) ---")
            val logs = recentLogs()
            if (logs.isEmpty()) {
                appendLine("(暂无日志)")
            } else {
                logs.forEach { appendLine(it) }
            }
        }
    }
}
