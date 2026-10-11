package moe.shizuku.manager.maxhz

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import rikka.shizuku.Shizuku
import kotlin.math.min

class MaxHzFpsMonitorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: MaxHzFpsOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    @Volatile
    private var running = false
    private var monitorThread: Thread? = null

    private var currentPackage = ""
    private var currentActivityClass: String? = null
    private var currentLayer: String? = null
    private var layerCandidates: List<String> = emptyList()
    private var layerCandidateIndex = 0
    private var layerCacheAt = 0L
    private var cachedFps = -1.0
    private var cachedAt = 0L
    private var staleMisses = 0
    private var emaFps = -1.0
    private var kernelFpsPath: String? = null
    private var kernelPathChecked = false
    private var gfxLastPackage: String? = null
    private var gfxLastFrames = 0L
    private var gfxLastAt = 0L
    // 性能优化：gfxinfo 回退路径节流时间戳
    private var lastGfxInfoAt = 0L
    // 省电修复：前台查询节流时间戳
    private var lastForegroundLookupAt = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasPrivilege = Shizuku.pingBinder() || MaxHzShell.isReady()
        if (!Settings.canDrawOverlays(this) || !hasPrivilege) {
            MaxHzPrefs.setFpsMonitorRunning(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (!showOverlay()) {
            running = false
            MaxHzPrefs.setFpsMonitorRunning(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        MaxHzPrefs.setFpsMonitorRunning(this, true)
        startLoop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        MaxHzPrefs.setFpsMonitorRunning(this, false)
        val thread = monitorThread
        monitorThread = null
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(MONITOR_THREAD_JOIN_MS) }
        }
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(): Boolean {
        if (overlayView != null) return true
        val view = MaxHzFpsOverlayView(this)
        val params = WindowManager.LayoutParams(
            dp(106),
            dp(34),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(76)
        }
        overlayView = view
        overlayParams = params
        setupDrag(view)
        val manager = windowManager ?: run {
            overlayView = null
            overlayParams = null
            return false
        }
        val added = runCatching {
            manager.addView(view, params)
            true
        }.getOrDefault(false)
        if (!added) {
            overlayView = null
            overlayParams = null
        }
        return added
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching {
                if (view.isAttachedToWindow) windowManager?.removeViewImmediate(view)
            }
        }
        overlayView = null
        overlayParams = null
    }

    private fun setupDrag(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var lastTap = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = overlayParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < DOUBLE_TAP_MS) {
                            stopSelf()
                            return true
                        }
                        lastTap = now
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        runCatching { windowManager?.updateViewLayout(v, params) }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun startLoop() {
        if (monitorThread?.isAlive == true) return
        monitorThread = Thread {
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            while (running) {
                try {
                    if (!Settings.canDrawOverlays(this@MaxHzFpsMonitorService)) {
                        running = false
                        MaxHzPrefs.setFpsMonitorRunning(this@MaxHzFpsMonitorService, false)
                        mainHandler.post { stopSelf() }
                        break
                    }

                    // 省电修复：熄屏时暂停采样。原版熄屏后仍每 700ms 跑 dumpsys，
                    // 阻止 CPU 休眠，是待机耗电和发热的主要来源之一。
                    if (powerManager?.isInteractive != true) {
                        Thread.sleep(SCREEN_OFF_IDLE_MS)
                        continue
                    }

                    // 省电修复：前台包名查询节流。原版每个采样周期都跑 1-3 条 dumpsys
                    // 查前台应用，让 system_server 持续高负载。改为每 FOREGROUND_LOOKUP_MS 查一次。
                    val now = System.currentTimeMillis()
                    if (now - lastForegroundLookupAt >= FOREGROUND_LOOKUP_MS || currentPackage.isBlank()) {
                        lastForegroundLookupAt = now
                        val target = foregroundTarget()
                        if (target != null && (
                                target.packageName != currentPackage ||
                                    target.activityClass != currentActivityClass
                                )) {
                            resetForTarget(target)
                        }
                    }

                    val raw = measureFps(currentPackage)
                    val smoothed = if (raw >= 0) {
                        emaFps = if (emaFps < 0) raw else EMA_ALPHA * raw + (1 - EMA_ALPHA) * emaFps
                        emaFps
                    } else {
                        emaFps = -1.0
                        -1.0
                    }

                    val displayFps = if (smoothed >= 0) min(smoothed, 999.9) else -1.0
                    val displayPackage = currentPackage
                    mainHandler.post {
                        overlayView?.takeIf { it.isAttachedToWindow }?.update(displayFps, displayPackage)
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            }
        }.apply {
            name = "MaxHz-FPSViewer"
            isDaemon = true
            start()
        }
    }

    private fun resetForTarget(target: MaxHzForegroundTarget) {
        currentPackage = target.packageName
        currentActivityClass = target.activityClass
        currentLayer = null
        layerCandidates = emptyList()
        layerCandidateIndex = 0
        layerCacheAt = 0L
        cachedFps = -1.0
        cachedAt = 0L
        staleMisses = 0
        emaFps = -1.0
        gfxLastPackage = null
        gfxLastFrames = 0L
        gfxLastAt = 0L
        lastGfxInfoAt = 0L
    }

    private fun measureFps(packageName: String): Double {
        if (!Shizuku.pingBinder() && !MaxHzShell.isRootAvailable()) {
            return -1.0
        }

        if (packageName.isBlank()) return -1.0

        val now = System.currentTimeMillis()
        if (cachedFps >= 0 && now - cachedAt < CACHE_VALID_MS) return cachedFps

        var probes = 0
        var layer = resolveLayer(packageName)
        while (layer != null && probes < MAX_LAYER_PROBES_PER_SAMPLE) {
            val fps = readSurfaceFlingerLatency(layer)
            if (fps >= 0) {
                currentLayer = layer
                cachedFps = fps
                cachedAt = System.currentTimeMillis()
                staleMisses = 0
                return fps
            }
            probes += 1
            staleMisses += 1
            if (staleMisses >= STALE_LAYER_MISSES) {
                layer = advanceLayerCandidate()
                staleMisses = 0
            } else {
                layer = null
            }
        }

        val gfxFps = readGfxInfo(packageName)
        if (gfxFps >= 0) {
            cachedFps = gfxFps
            cachedAt = System.currentTimeMillis()
            return gfxFps
        }
        readKernelFps().takeIf { it >= 0 }?.let { return it }
        return if (cachedFps >= 0 && now - cachedAt <= STALE_CACHE_MS) cachedFps else -1.0
    }

    private fun readKernelFps(): Double {
        if (!kernelPathChecked) {
            kernelPathChecked = true
            kernelFpsPath = detectKernelFpsPath()
        }
        val path = kernelFpsPath?.takeIf { it.isNotBlank() } ?: return -1.0
        val line = exec("cat ${shellArg(path)}", 800)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
        if (line.isBlank()) return -1.0
        val value = if (line.startsWith("fps:")) {
            line.removePrefix("fps:").trim().split(Regex("\\s+")).firstOrNull()
        } else {
            line.split(Regex("\\s+")).firstOrNull()
        }
        return value?.toDoubleOrNull()?.takeIf { it >= 0 } ?: -1.0
    }

    private fun detectKernelFpsPath(): String {
        val paths = listOf(
            "/sys/devices/virtual/graphics/fb0/measured_fps",
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/drm/sde-crtc-1/measured_fps"
        )
        return paths.firstOrNull { path ->
            exec("[ -r ${shellArg(path)} ] && cat ${shellArg(path)}", 800)
                .lineSequence()
                .firstOrNull()
                ?.isNotBlank() == true
        }.orEmpty()
    }

    private fun readSurfaceFlingerLatency(layer: String): Double {
        val output = exec("dumpsys SurfaceFlinger --latency \"${shellQuoteBody(layer)}\"", 1500)
        val timestamps = MaxHzFpsSurfaceParser.presentedFrameTimestamps(output)
        return MaxHzFpsSurfaceParser.calculateFps(timestamps, System.nanoTime())
    }

    private fun readGfxInfo(packageName: String): Double {
        val now = System.currentTimeMillis()
        // 性能优化：gfxinfo framestats 是单次最重的 dumpsys，仅在 SurfaceFlinger 取不到时回退。
        // 距上次执行不足 GFXINFO_MIN_INTERVAL_MS 时跳过，交由内核 FPS / 缓存兜底，避免每秒都跑。
        if (now - lastGfxInfoAt < GFXINFO_MIN_INTERVAL_MS) return -1.0
        lastGfxInfoAt = now
        val totalFrames = TOTAL_FRAMES_PATTERN.find(
            exec("dumpsys gfxinfo ${shellArg(packageName)} framestats", 1500)
        )?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return -1.0

        if (gfxLastPackage != packageName) {
            gfxLastPackage = packageName
            gfxLastFrames = totalFrames
            gfxLastAt = now
            return -1.0
        }

        val deltaFrames = totalFrames - gfxLastFrames
        val deltaMs = now - gfxLastAt
        if (deltaMs < 250) return cachedFps
        gfxLastFrames = totalFrames
        gfxLastAt = now
        return if (deltaFrames > 0 && deltaMs > 0) deltaFrames * 1000.0 / deltaMs else -1.0
    }

    private fun foregroundTarget(): MaxHzForegroundTarget? {
        // 性能优化：原版串行跑 3 条 dumpsys（activity activities / window / window windows）。
        // mCurrentFocus / mFocusedApp / mFocusedWindow 在单次 `dumpsys window` 中即可取得，
        // 合并为 1 条；解析仍由 COMPONENT_PATTERN 匹配 pkg/activity，前台判定不受影响。
        val output = exec(
            "dumpsys window 2>/dev/null | grep -m 4 -E 'mCurrentFocus|mFocusedApp|mFocusedWindow'",
            1400
        )
        return MaxHzFpsSurfaceParser.foregroundTarget(output)
    }

    private fun resolveLayer(packageName: String): String? {
        val now = System.currentTimeMillis()
        if (layerCandidates.isNotEmpty() && now - layerCacheAt < LAYER_CACHE_MS) {
            return layerCandidates.getOrNull(layerCandidateIndex)
        }
        layerCandidates = MaxHzFpsSurfaceParser.rankedLayers(
            exec("dumpsys SurfaceFlinger --list", 1500),
            packageName,
            currentActivityClass
        )
        layerCandidateIndex = 0
        currentLayer = layerCandidates.firstOrNull()
        layerCacheAt = System.currentTimeMillis()
        return currentLayer
    }

    private fun advanceLayerCandidate(): String? {
        layerCandidateIndex += 1
        if (layerCandidateIndex < layerCandidates.size) {
            currentLayer = layerCandidates[layerCandidateIndex]
            return currentLayer
        }
        currentLayer = null
        layerCandidates = emptyList()
        layerCandidateIndex = 0
        layerCacheAt = 0L
        return null
    }

    private fun exec(command: String, timeoutMs: Long): String {
        if (!Shizuku.pingBinder()) return ""
        return runCatching { MaxHzShell.execRaw(command, timeoutMs).out }.getOrDefault("")
    }

    private fun shellArg(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }

    private fun shellQuoteBody(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        // 省电修复：采样间隔从 700ms 放宽到 1000ms，肉眼几乎无差别，
        // 但 dumpsys 调用次数减少约 30%。
        private const val POLL_INTERVAL_MS = 1_000L
        // 省电修复：熄屏时挂起采样，每 3s 醒一次只检查亮屏状态（不跑任何 dumpsys）
        private const val SCREEN_OFF_IDLE_MS = 3_000L
        // 省电修复：前台包名查询间隔（独立于 FPS 采样）
        // 性能优化：前台包名变化远慢于 2s，放宽到 3s 进一步降低 dumpsys 频率
        private const val FOREGROUND_LOOKUP_MS = 3_000L
        private const val MONITOR_THREAD_JOIN_MS = 500L
        private const val DOUBLE_TAP_MS = 300L
        private const val LAYER_CACHE_MS = 12_000L
        // 性能优化：原值 650 < POLL_INTERVAL_MS(1000)，导致缓存跨轮询永远失效、
        // 每个采样周期都跑完整 dumpsys 管线（死缓存）。放大到 >= 轮询周期后，
        // 缓存可跨轮询命中，shell/dumpsys 调用量约减半，悬浮窗仍是 1s 刷新。
        private const val CACHE_VALID_MS = 2_000L
        // 性能优化：gfxinfo framestats 是最重的 dumpsys，仅在 SurfaceFlinger 取不到时才回退。
        // 为其单独加节流，避免回退路径每秒都跑一次。
        private const val GFXINFO_MIN_INTERVAL_MS = 1_500L
        private const val STALE_CACHE_MS = 2_500L
        private const val STALE_LAYER_MISSES = 2
        private const val MAX_LAYER_PROBES_PER_SAMPLE = 3
        private const val EMA_ALPHA = 0.3
        private val TOTAL_FRAMES_PATTERN = Regex("Total frames rendered:\\s*(\\d+)")
    }
}
