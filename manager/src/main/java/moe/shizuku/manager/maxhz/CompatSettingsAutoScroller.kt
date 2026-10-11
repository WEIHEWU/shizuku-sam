package moe.shizuku.manager.maxhz

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开发者选项「更多设置」/「Disable app compatibility policies」直达对焦引擎。
 * 在三星 One UI 等系统中，「停用应用兼容性策略」位于开发者选项底部的「更多设置」子页面。
 * 当用户从首页卡片点击直达时，本引擎接管无障碍事件，瞬间直达底部「更多设置」并聚焦，
 * 拒绝慢速翻页动画，做到直接瞬间对齐。
 */
object CompatSettingsAutoScroller {

    private const val TAG = "CompatSettingsScroller"
    private const val TIMEOUT_MS = 10_000L

    private enum class TargetType {
        DIRECT_POLICY,
        MORE_SETTINGS
    }

    private data class TargetMatch(
        val node: AccessibilityNodeInfo,
        val type: TargetType
    )

    private val DIRECT_POLICY_KEYWORDS = listOf(
        "Disable app compatibility policies",
        "停用应用兼容性策略",
        "禁用应用兼容性策略",
        "停用應用相容性策略",
        "禁用應用相容性策略",
        "应用兼容性策略",
        "應用相容性策略",
        "App compatibility changes",
        "应用兼容性变更",
        "应用兼容性"
    )

    private val MORE_SETTINGS_KEYWORDS = listOf(
        "更多设置",
        "更多設定",
        "More settings",
        "更多开发设置",
        "更多開發設定",
        "More developer settings",
        "其他设置",
        "其他設定",
        "Other settings"
    )

    @Volatile
    private var pending = false

    @Volatile
    private var requestTimestamp = 0L

    private var scrollJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun nowMs(): Long {
        return try {
            SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    private fun log(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
            // JVM unit test stub environment
        }
    }

    fun request() {
        pending = true
        requestTimestamp = nowMs()
        log("Direct jump to compat settings requested")
    }

    fun isPending(): Boolean {
        if (!pending) return false
        if (nowMs() - requestTimestamp > TIMEOUT_MS) {
            pending = false
            return false
        }
        return true
    }

    fun cancel() {
        pending = false
        scrollJob?.cancel()
        scrollJob = null
    }

    private fun isSettingsPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        return pkg.contains("settings", ignoreCase = true) || pkg.contains("developer", ignoreCase = true)
    }

    private fun getWindowRoot(event: AccessibilityEvent?, service: AccessibilityService): AccessibilityNodeInfo? {
        val source = event?.source
        if (source != null) {
            var curr: AccessibilityNodeInfo = source
            var safety = 0
            while (safety++ < 30) {
                val parent: AccessibilityNodeInfo = try { curr.parent } catch (_: Throwable) { null } ?: break
                curr = parent
            }
            if (isSettingsPackage(curr.packageName?.toString())) {
                return curr
            }
        }
        val activeRoot = try { service.rootInActiveWindow } catch (_: Throwable) { null }
        if (activeRoot != null && isSettingsPackage(activeRoot.packageName?.toString())) {
            return activeRoot
        }
        val windows = try { service.windows } catch (_: Throwable) { null }
        if (windows != null) {
            for (w in windows) {
                val root = try { w.root } catch (_: Throwable) { null } ?: continue
                if (isSettingsPackage(root.packageName?.toString())) {
                    return root
                }
            }
        }
        return null
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?, service: AccessibilityService) {
        if (!isPending()) return
        val pkg = event?.packageName?.toString() ?: return
        if (!isSettingsPackage(pkg)) return

        if (scrollJob?.isActive == true) return

        scrollJob = scope.launch {
            try {
                executeDirectJump(event, service)
            } catch (e: Throwable) {
                Log.e(TAG, "executeDirectJump error", e)
            } finally {
                scrollJob = null
            }
        }
    }

    private suspend fun executeDirectJump(event: AccessibilityEvent?, service: AccessibilityService) {
        // 等待界面初次加载 (50ms，避免冷启动白屏瞬态)
        delay(50L)

        // 1. 若目标项已在当前屏幕视野中展示，直接对齐高亮
        val initialRoot = getWindowRoot(event, service) ?: run {
            delay(50L)
            getWindowRoot(null, service)
        }
        if (initialRoot != null) {
            val existingMatch = findTarget(initialRoot)
            if (existingMatch != null) {
                focusAndShowNode(existingMatch.node)
                log("Target already visible on screen: ${existingMatch.type}")
                pending = false
                try { initialRoot.recycle() } catch (_: Throwable) {}
                return
            }
            try { initialRoot.recycle() } catch (_: Throwable) {}
        }

        // 2. 持续高速瞬移下甩循环，直达「更多设置」或到达物理列表末端
        var attempts = 0
        val maxFlingAttempts = 6
        var lastBottomText: String? = null

        while (isPending() && attempts < maxFlingAttempts) {
            dispatchInstantFling(service)
            delay(110L)

            val currentRoot = getWindowRoot(null, service)
            if (currentRoot != null) {
                val match = findTarget(currentRoot)
                if (match != null) {
                    focusAndShowNode(match.node)
                    log("Target found and focused at attempt $attempts: ${match.type}")
                    pending = false
                    try { currentRoot.recycle() } catch (_: Throwable) {}
                    return
                }

                // 物理末端检测：若多次下甩后底部节点文本完全不再变化（至少甩动 2 次以上）
                val currentBottomText = getBottomItemSignature(currentRoot)
                if (currentBottomText != null && currentBottomText == lastBottomText && attempts >= 2) {
                    log("Physically reached end of list at attempt $attempts: $currentBottomText")
                    val bottomNode = findBottomMostItem(currentRoot)
                    if (bottomNode != null) {
                        focusAndShowNode(bottomNode)
                        log("Focused bottom-most item at end of list")
                        pending = false
                        try { bottomNode.recycle() } catch (_: Throwable) {}
                        try { currentRoot.recycle() } catch (_: Throwable) {}
                        return
                    }
                }
                lastBottomText = currentBottomText

                try { currentRoot.recycle() } catch (_: Throwable) {}
            }
            attempts++
        }

        // 3. 最终兜底扫描
        val finalRoot = getWindowRoot(null, service)
        if (finalRoot != null) {
            val finalMatch = findTarget(finalRoot)
            if (finalMatch != null) {
                focusAndShowNode(finalMatch.node)
            } else {
                val bottomNode = findBottomMostItem(finalRoot)
                if (bottomNode != null) {
                    focusAndShowNode(bottomNode)
                    try { bottomNode.recycle() } catch (_: Throwable) {}
                }
            }
            try { finalRoot.recycle() } catch (_: Throwable) {}
        }
        pending = false
    }

    private fun getBottomItemSignature(container: AccessibilityNodeInfo): String? {
        val bottomNode = findBottomMostItem(container) ?: return null
        val sig = bottomNode.text?.toString()?.takeIf { it.isNotBlank() }
            ?: bottomNode.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        try { bottomNode.recycle() } catch (_: Throwable) {}
        return sig
    }

    private fun focusAndShowNode(node: AccessibilityNodeInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            }
            val targetFocusNode = findFocusableOrClickableParent(node) ?: node
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && targetFocusNode !== node) {
                targetFocusNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            }
            targetFocusNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (targetFocusNode !== node) {
                node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "focusAndShowNode failed", e)
        }
    }

    private fun findFocusableOrClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var curr = node
        var count = 0
        while (count++ < 10) {
            if (curr.isFocusable || curr.isClickable) return curr
            val parent = try { curr.parent } catch (_: Throwable) { null } ?: break
            curr = parent
        }
        return null
    }

    private fun findBottomMostItem(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val childCount = container.childCount
        if (childCount == 0) return null
        for (i in childCount - 1 downTo 0) {
            val child = try { container.getChild(i) } catch (_: Throwable) { null } ?: continue
            val text = child.text?.toString()
            val desc = child.contentDescription?.toString()
            if (!text.isNullOrBlank() || !desc.isNullOrBlank() || child.isClickable) {
                return child
            }
            val nested = findBottomMostItem(child)
            if (nested != null) {
                try { child.recycle() } catch (_: Throwable) {}
                return nested
            }
            try { child.recycle() } catch (_: Throwable) {}
        }
        return null
    }

    private fun findTarget(root: AccessibilityNodeInfo): TargetMatch? {
        val directNode = findDirectPolicy(root)
        if (directNode != null) {
            return TargetMatch(directNode, TargetType.DIRECT_POLICY)
        }

        val moreSettingsNode = findMoreSettings(root)
        if (moreSettingsNode != null) {
            return TargetMatch(moreSettingsNode, TargetType.MORE_SETTINGS)
        }

        return searchTargetRecursive(root)
    }

    private fun findDirectPolicy(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (kw in DIRECT_POLICY_KEYWORDS) {
            val list = try { root.findAccessibilityNodeInfosByText(kw) } catch (_: Throwable) { null }
            if (!list.isNullOrEmpty()) {
                val first = list[0]
                for (i in 1 until list.size) {
                    try { list[i].recycle() } catch (_: Throwable) {}
                }
                return first
            }
        }
        return null
    }

    private fun findMoreSettings(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (kw in MORE_SETTINGS_KEYWORDS) {
            val list = try { root.findAccessibilityNodeInfosByText(kw) } catch (_: Throwable) { null }
            if (!list.isNullOrEmpty()) {
                val first = list[0]
                for (i in 1 until list.size) {
                    try { list[i].recycle() } catch (_: Throwable) {}
                }
                return first
            }
        }
        return null
    }

    private fun searchTargetRecursive(node: AccessibilityNodeInfo): TargetMatch? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        for (kw in DIRECT_POLICY_KEYWORDS) {
            if (text.contains(kw, ignoreCase = true) || desc.contains(kw, ignoreCase = true)) {
                return TargetMatch(node, TargetType.DIRECT_POLICY)
            }
        }

        for (kw in MORE_SETTINGS_KEYWORDS) {
            if (text.contains(kw, ignoreCase = true) || desc.contains(kw, ignoreCase = true)) {
                return TargetMatch(node, TargetType.MORE_SETTINGS)
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val match = searchTargetRecursive(child)
            if (match != null) return match
            try { child.recycle() } catch (_: Throwable) {}
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
            try { child.recycle() } catch (_: Throwable) {}
        }
        return null
    }

    private fun dispatchInstantFling(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val dm = service.resources.displayMetrics
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            val startY = h * 0.85f
            val endY = h * 0.08f
            val path = Path().apply {
                moveTo(w / 2f, startY)
                lineTo(w / 2f, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 25L))
                .build()
            service.dispatchGesture(gesture, null, null)
        } catch (e: Throwable) {
            log("dispatchInstantFling failed: ${e.message}")
        }
    }
}
