package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.ShizukuSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无线调试免输入自动配对嗅探引擎。
 * 统一作为 Shizuku 无障碍服务的一部分运行。
 */
object AdbPairingDetector {

    private const val TAG = "AdbPairingDetector"
    private const val AUTO_PAIRING_TIMEOUT_MS = 60_000L
    private const val MAX_DEPTH = 20

    @Volatile
    private var pairingPort: Int? = null

    @Volatile
    private var pairingPassword: String? = null

    @Volatile
    private var candidateWindowId = -1

    @Volatile
    private var isPairingInProgress = false

    private val timeoutScheduled = AtomicBoolean(false)
    private var detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ipPortRegex = Regex("""(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})""")
    private val passwordRegex = Regex("""\b\d{6}\b""")
    private val fiveDigitRegex = Regex("""\b\d{5}\b""")

    fun reset() {
        pairingPort = null
        pairingPassword = null
        candidateWindowId = -1
        isPairingInProgress = false
        timeoutScheduled.set(false)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?, service: AccessibilityService) {
        if (event == null || isPairingInProgress) return
        if (pairingPort != null && pairingPassword != null) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName) return

        // 关键性能守卫：只监听系统设置相关进程（无线调试所在页面）
        val isSettingsApp = pkg.contains("settings", ignoreCase = true) ||
                pkg.contains("security", ignoreCase = true) ||
                pkg.contains("developer", ignoreCase = true) ||
                pkg.contains("systemui", ignoreCase = true)
        if (!isSettingsApp) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val source = event.source ?: return
        val windowId = event.windowId

        // 窗口隔离：不同弹窗切换时重置候选，避免把外层连接端口与内层配对码混用
        if (windowId != candidateWindowId && pairingPort == null && pairingPassword == null) {
            candidateWindowId = windowId
        } else if (windowId != candidateWindowId) {
            candidateWindowId = windowId
            pairingPort = null
            pairingPassword = null
        }

        val windowChain = ArrayList<AccessibilityNodeInfo>()
        val windowRoot = run {
            var curr: AccessibilityNodeInfo = source
            while (true) {
                val parent = try { curr.parent } catch (_: Throwable) { null } ?: break
                windowChain.add(parent)
                curr = parent
            }
            curr
        }

        try {
            // 遵循用户指令：不模拟自动点击系统按钮，由用户手动点击「使用配对码配对设备」

            // 1. 扫描提取端口
            if (pairingPort == null) {
                findPortInNode(windowRoot)
            }

            // 3. 扫描提取 6 位配对码
            if (pairingPort != null && pairingPassword == null) {
                findPasswordInNode(windowRoot)
            }
        } finally {
            for (node in windowChain) {
                try { node.recycle() } catch (_: Throwable) {}
            }
        }

        val portValue = pairingPort
        val codeValue = pairingPassword

        if (portValue != null && codeValue != null && !isPairingInProgress) {
            isPairingInProgress = true
            Log.i(TAG, "Starting zero-touch pairing: 127.0.0.1:$portValue code=$codeValue")
            executePairing(portValue, codeValue, service)
        }
    }

    private fun executePairing(port: Int, code: String, service: AccessibilityService) {
        detectorScope.launch {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load ADB key", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(service, service.getString(R.string.adb_error_key_store), Toast.LENGTH_LONG).show()
                }
                isPairingInProgress = false
                return@launch
            }

            val success = AdbPairingClient("127.0.0.1", port, code, key).runCatching {
                start()
            }.getOrDefault(false)

            withContext(Dispatchers.Main) {
                if (success) {
                    Log.i(TAG, "Auto-pairing succeeded!")
                    ShizukuSettings.setLastPort(port)
                    Toast.makeText(service, service.getString(R.string.pairing_code_auto_success), Toast.LENGTH_LONG).show()

                    // 1. 自动后退关闭系统配对弹窗
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

                    // 2. 配对完成，直接拉回应用主页并立即全自动启动服务（100% 对齐 ShizukuPlus auto-start on pair）
                    val intent = Intent(service, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("EXTRA_AUTO_START_AFTER_PAIRING", true)
                    }
                    service.startActivity(intent)
                    reset()
                } else {
                    Log.w(TAG, "Auto-pairing handshake failed, resetting candidates to retry on next frame")
                    pairingPort = null
                    pairingPassword = null
                    isPairingInProgress = false
                }
            }
        }
    }

    private fun findPortInNode(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > MAX_DEPTH || pairingPort != null) return
        val text = try { node.text?.toString() ?: "" } catch (_: Throwable) { "" }

        if (text.isNotEmpty()) {
            ipPortRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                pairingPort = it
                Log.i(TAG, "Pairing port found via IP:Port -> $it")
                return
            }
            if (text.contains("Port", ignoreCase = true) || text.contains("端口", ignoreCase = true)) {
                fiveDigitRegex.find(text)?.value?.toIntOrNull()?.let {
                    pairingPort = it
                    Log.i(TAG, "Pairing port found via fallback -> $it")
                    return
                }
            }
        }

        try {
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                try {
                    findPortInNode(child, depth + 1)
                } finally {
                    try { child.recycle() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun findPasswordInNode(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > MAX_DEPTH || pairingPassword != null) return
        val text = try { node.text?.toString() ?: "" } catch (_: Throwable) { "" }

        if (text.isNotEmpty()) {
            passwordRegex.find(text)?.value?.let {
                // 排除端口误判为配对码
                if (it.toIntOrNull() != pairingPort) {
                    pairingPassword = it
                    Log.i(TAG, "Pairing password found -> $it")
                    scheduleTimeoutIfNeeded()
                    return
                }
            }
        }

        try {
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                try {
                    findPasswordInNode(child, depth + 1)
                } finally {
                    try { child.recycle() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun scheduleTimeoutIfNeeded() {
        if (timeoutScheduled.compareAndSet(false, true)) {
            detectorScope.launch(Dispatchers.Main) {
                delay(AUTO_PAIRING_TIMEOUT_MS)
                if (pairingPort == null || pairingPassword == null) {
                    Log.w(TAG, "Pairing discovery timed out, resetting")
                    reset()
                }
            }
        }
    }
}
