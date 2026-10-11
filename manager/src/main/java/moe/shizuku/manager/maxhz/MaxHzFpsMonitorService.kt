package moe.shizuku.manager.maxhz

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import rikka.shizuku.Shizuku
import kotlin.math.abs
import kotlin.math.min

class MaxHzFpsMonitorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null
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
    private var lastGfxInfoAt = 0L
    private var lastForegroundLookupAt = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return
            // 当系统底层切换 LTPO / 刷新率档位（如 120Hz ↔ 60Hz ↔ 24Hz）且最近一次采样已超过 350ms 时，
            // 立即在主线程同步硬件面板刷新率，消除静止画面档位切换滞后
            val now = System.currentTimeMillis()
            if (now - cachedAt >= 350L) {
                val hwHz = readDisplayRefreshRate()
                if (hwHz > 0) {
                    emaFps = hwHz
                    overlayView?.takeIf { it.isAttachedToWindow }?.update(hwHz, currentPackage)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, mainHandler)
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
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
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
                val loopStart = SystemClock.elapsedRealtime()
                try {
                    if (!Settings.canDrawOverlays(this@MaxHzFpsMonitorService)) {
                        running = false
                        MaxHzPrefs.setFpsMonitorRunning(this@MaxHzFpsMonitorService, false)
                        mainHandler.post { stopSelf() }
                        break
                    }

                    // 熄屏时暂停采样
                    if (powerManager?.isInteractive != true) {
                        Thread.sleep(SCREEN_OFF_IDLE_MS)
                        continue
                    }

                    // 1. 优先从无障碍内存状态零延迟获取当前前台包名（0ms 开销，应用切换瞬间即刻感知）
                    val now = System.currentTimeMillis()
                    val fastPkg = MaxHzForegroundApp.currentWindowPackage()
                    if (!fastPkg.isNullOrBlank() && fastPkg != currentPackage) {
                        resetForTarget(MaxHzForegroundTarget(fastPkg, null))
                    } else if (now - lastForegroundLookupAt >= FOREGROUND_LOOKUP_MS || currentPackage.isBlank()) {
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
                        // 自适应阶跃响应：帧率变化 >= 8 FPS（如 60↔120 切换、明显掉帧）时直接跳过平滑立即显示；
                        // 仅在小幅波动（< 8 FPS）时做轻度平滑（EMA_ALPHA = 0.65）
                        emaFps = when {
                            emaFps < 0 -> raw
                            abs(raw - emaFps) >= STEP_CHANGE_THRESHOLD_FPS -> raw
                            else -> EMA_ALPHA * raw + (1 - EMA_ALPHA) * emaFps
                        }
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
                    val elapsed = SystemClock.elapsedRealtime() - loopStart
                    val sleepMs = (POLL_INTERVAL_MS - elapsed).coerceAtLeast(MIN_SLEEP_MS)
                    Thread.sleep(sleepMs)
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
        if (!Shizuku.pingBinder() && !MaxHzShell.isReady()) {
            return -1.0
        }

        if (packageName.isBlank()) {
            return readDisplayRefreshRate()
        }

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
        readKernelFps().takeIf { it >= 0 }?.let {
            cachedFps = it
            cachedAt = System.currentTimeMillis()
            return it
        }
        // 当画面静止或系统桌面无独立 BLAST 图层更新时，回退至硬件屏幕实时刷新率（LTPO 实时档位）
        val hwRefreshRate = readDisplayRefreshRate()
        if (hwRefreshRate > 0) {
            return hwRefreshRate
        }
        val now = System.currentTimeMillis()
        return if (cachedFps >= 0 && now - cachedAt <= STALE_CACHE_MS) cachedFps else -1.0
    }

    private fun readDisplayRefreshRate(): Double {
        return runCatching {
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.refreshRate?.toDouble()?.takeIf { it > 1.0 && it.isFinite() } ?: -1.0
        }.getOrDefault(-1.0)
    }

    private fun readKernelFps(): Double {
        if (!kernelPathChecked) {
            kernelPathChecked = true
            kernelFpsPath = detectKernelFpsPath()
        }
        val path = kernelFpsPath?.takeIf { it.isNotBlank() } ?: return -1.0
        val line = exec("cat ${shellArg(path)}", 600)
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
            exec("[ -r ${shellArg(path)} ] && cat ${shellArg(path)}", 600)
                .lineSequence()
                .firstOrNull()
                ?.isNotBlank() == true
        }.orEmpty()
    }

    private fun readSurfaceFlingerLatency(layer: String): Double {
        val output = exec("dumpsys SurfaceFlinger --latency \"${shellQuoteBody(layer)}\"", 1000)
        val timestamps = MaxHzFpsSurfaceParser.presentedFrameTimestamps(output)
        return MaxHzFpsSurfaceParser.calculateFps(timestamps, System.nanoTime())
    }

    private fun readGfxInfo(packageName: String): Double {
        val now = System.currentTimeMillis()
        if (now - lastGfxInfoAt < GFXINFO_MIN_INTERVAL_MS) return -1.0
        lastGfxInfoAt = now
        val totalFrames = TOTAL_FRAMES_PATTERN.find(
            exec("dumpsys gfxinfo ${shellArg(packageName)} framestats", 1000)
        )?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return -1.0

        if (gfxLastPackage != packageName) {
            gfxLastPackage = packageName
            gfxLastFrames = totalFrames
            gfxLastAt = now
            return -1.0
        }

        val deltaFrames = totalFrames - gfxLastFrames
        val deltaMs = now - gfxLastAt
        if (deltaMs < 200) return cachedFps
        gfxLastFrames = totalFrames
        gfxLastAt = now
        return if (deltaFrames > 0 && deltaMs > 0) deltaFrames * 1000.0 / deltaMs else -1.0
    }

    private fun foregroundTarget(): MaxHzForegroundTarget? {
        val output = exec(
            "dumpsys window 2>/dev/null | grep -m 4 -E 'mCurrentFocus|mFocusedApp|mFocusedWindow'",
            1000
        )
        return MaxHzFpsSurfaceParser.foregroundTarget(output)
    }

    private fun resolveLayer(packageName: String): String? {
        val now = System.currentTimeMillis()
        if (layerCandidates.isNotEmpty() && now - layerCacheAt < LAYER_CACHE_MS) {
            return layerCandidates.getOrNull(layerCandidateIndex)
        }
        layerCandidates = MaxHzFpsSurfaceParser.rankedLayers(
            exec("dumpsys SurfaceFlinger --list", 1000),
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
        if (!Shizuku.pingBinder() && !MaxHzShell.isReady()) return ""
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
        // 500ms 高响应采样周期（扣除命令执行耗时，保证每秒稳定刷新 2 次）
        private const val POLL_INTERVAL_MS = 500L
        private const val MIN_SLEEP_MS = 100L
        private const val SCREEN_OFF_IDLE_MS = 2_000L
        private const val FOREGROUND_LOOKUP_MS = 1_500L
        private const val MONITOR_THREAD_JOIN_MS = 500L
        private const val DOUBLE_TAP_MS = 300L
        private const val LAYER_CACHE_MS = 5_000L
        private const val GFXINFO_MIN_INTERVAL_MS = 1_000L
        private const val STALE_CACHE_MS = 850L
        private const val STALE_LAYER_MISSES = 1
        private const val MAX_LAYER_PROBES_PER_SAMPLE = 3
        // 阶跃阈值 >= 8 FPS 时直接跳过 EMA 瞬时跳变，小幅波动使用 0.65 高权重跟手滤波
        private const val STEP_CHANGE_THRESHOLD_FPS = 8.0
        private const val EMA_ALPHA = 0.65
        private val TOTAL_FRAMES_PATTERN = Regex("Total frames rendered:\\s*(\\d+)")
    }
}
