package moe.shizuku.manager.maxhz

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.google.android.material.appbar.AppBarLayout
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import moe.shizuku.manager.maxhz.GlassDockNav
import moe.shizuku.manager.maxhz.MaxHzComposeRefreshBus
import moe.shizuku.manager.utils.AccessibilityHelper
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.StatusBarManager
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.widget.NestedScrollView
import androidx.core.view.WindowCompat
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.app.ThemeHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class MaxHzActivity : AppBarActivity() {

    private data class ResultState(
        val text: String,
        val collapsed: Boolean
    )

    private data class ResultTag(
        val key: String,
        val text: String
    )

    private data class CommandOutcome(
        val result: MaxHzShell.Result,
        val changes: String,
        val elapsedMs: Long
    )

    private data class DozeHistoryItem(
        val time: String,
        val state: String
    )

    private data class ConfirmationPrompt(
        val title: String,
        val message: String,
        val positiveText: String = "确定切换",
        val negativeText: String = "取消"
    )

    private enum class RiskLevel(val label: String) {
        LOW("低风险"),
        MEDIUM("中风险"),
        HIGH("高风险")
    }

    private data class FeatureSafety(
        val risk: RiskLevel,
        val note: String
    )

    protected data class ThemeColors(
        val page: Int,
        val surface: Int,
        val surfaceContainer: Int,
        val surfaceContainerHigh: Int,
        val outline: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
        val errorContainer: Int,
        val onErrorContainer: Int,
        val warningContainer: Int,
        val onWarningContainer: Int,
        val error: Int
    )

    private data class StatusStyle(
        val textColor: Int,
        val backgroundColor: Int
    )

    private data class NightFactor(
        val label: String,
        val value: Double
    )

    private data class DashboardItem(
        val title: String,
        val state: String,
        val detail: String
    )

    private data class DiagnosticItem(
        val title: String,
        val state: String,
        val detail: String,
        val suggestion: String
    )

    private data class OperationLogItem(
        val time: String,
        val event: String,
        val result: String
    )

    private data class BackgroundErrorUi(
        val exists: Boolean,
        val recovered: Boolean,
        val reason: String,
        val suggestion: String,
        val time: String,
        val raw: String
    )

    private data class SystemResidueUi(
        val checked: Boolean,
        val hasResidue: Boolean,
        val state: String,
        val summary: String,
        val raw: String
    )

    private data class ShizukuOfflineHint(
        val detail: String,
        val suggestion: String
    )

    private data class NightRestrictionItem(
        val packageName: String,
        val bucket: String,
        val restricted: Boolean,
        val runRestricted: Boolean,
        val anyRunRestricted: Boolean,
        val wakeRestricted: Boolean,
        val alarmRestricted: Boolean
    )

    private enum class ButtonRole {
        PRIMARY,
        SECONDARY,
        DANGER
    }

    protected var lastResultView: TextView? = null
    private var resultKeyCounter = 0
    private lateinit var serviceSwitch: MaterialSwitch
    protected lateinit var pageContent: LinearLayout
    protected lateinit var scrollView: ScrollView
    protected lateinit var contentContainer: FrameLayout
    /** 承载页面背景的容器，玻璃背景快照以它为采集源 */
    protected var glassRoot: View? = null
    /** 共享玻璃 dock 覆盖层（在 contentContainer 之外，translationX 动画不影响它） */
    protected lateinit var glassDockOverlay: FrameLayout
    private var glassDockView: ComposeView? = null
    protected val navButtons = mutableListOf<TextView>()
    private val resultStates = mutableMapOf<String, ResultState>()
    private val resultViews = mutableMapOf<String, TextView>()
    private val resultBadges = mutableMapOf<String, TextView>()
    private val resultContainers = mutableMapOf<String, View>()
    protected var selectedPage = Page.OVERVIEW
    private val DIAGNOSTIC_PERMISSION_TITLES = setOf("Shizuku", "无障碍服务", "悬浮窗权限", "忽略电池优化")
    private val DIAGNOSTIC_FEATURE_TITLES = setOf("FPS Monitor运行条件", "每 App 规则环境检查", "刷新率控制条件", "Night Guard执行环境", "Doze", "电池保护")
    private val DIAGNOSTIC_BACKGROUND_TITLES = setOf("后台服务状态", "后台错误", "系统残留检测")
    private var selectedMPage = MPage.MAIN
    private var selectedAppRefreshPackage = ""
    private var selectedAppRefreshLabel = ""
    private var lastKnownShizukuReady: Boolean? = null
    private var lastKnownAccessibilityEnabled: Boolean? = null
    protected var lastKnownOutdoorModeEnabled = false
    private var mainSwipeDownX = 0f
    private var mainSwipeDownY = 0f
    private var mainSwipeStartOnInteractive = false
    private var mainSwipeConsumed = false
    private var mainPageAnimating = false
    private var detailPageAnimating = false
    private val diagnosticTargetViews = mutableMapOf<String, View>()

    // Galaxy MaxHz 式分层导航：主页只放功能入口，点击进入独立设置页，返回键逐级回退
    protected data class DetailScreen(
        val title: String,
        val builder: () -> View,
        val keepScroll: Boolean = false,
        val skipScrollTop: Boolean = false,
        val targetDiagnosticKey: String? = null,
        var savedParentScrollY: Int = 0
    )
    protected val detailStack = mutableListOf<DetailScreen>()
    protected var detailModalDialog: android.app.Dialog? = null
    protected var modalRootView: View? = null
    protected var modalBackBtn: TextView? = null
    protected var modalTitleView: TextView? = null
    protected var modalOptionBPillContainer: ViewGroup? = null
    protected var modalCloseBtn: TextView? = null
    protected var modalScrollView: NestedScrollView? = null
    protected var modalContentContainer: FrameLayout? = null
    protected var navBar: View? = null
    protected var systemBottomInset = 0

    /**
     * Compose 页面（首页玻璃 dock）的底部 inset 状态源：
     * insets 回调写入后 Compose 自动重组，玻璃 dock 无需重建即可
     * 避让手势条。View 侧继续用 systemBottomInset（同步赋值）。
     */
    protected val systemBottomInsetState = mutableIntStateOf(0)

    /**
     * 玻璃 dock 的选中页签状态源（0=主页 1=屏幕 2=性能 3=省电）。
     * 切页时写入，Compose 自动重组高亮，无需重建 ComposeView。
     */
    protected val dockSelectedTabState = mutableIntStateOf(0)

    protected val density by lazy { resources.displayMetrics.density }
    protected val gap by lazy { dp(10) }
    protected val themeColors by lazy { loadThemeColors() }

    protected enum class Page {
        SHIZUKU,
        OVERVIEW,
        POWER,
        REFRESH,
        ADVANCED
    }

    private enum class AppListFilter {
        ALL,
        USER,
        SYSTEM
    }

    private enum class AotPickerTab {
        UNCOMPILED,
        COMPILED
    }

    private enum class MPage {
        MAIN,
        GRAPHICS,
        NETWORK_TOUCH,
        THERMAL_EXTRA
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.maxhz_title)

        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        scrollView = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, actionBarInset(), 0, 0)
            addView(pageContent)
        }
        contentContainer = FrameLayout(this).apply {
            addView(scrollView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        // 共享玻璃 dock 覆盖层容器：必须在 pageArea 之前初始化，
        // 否则 lateinit 未赋值会在 addView(glassDockOverlay) 处抛异常导致闪退。
        glassDockOverlay = FrameLayout(this)
        // pageArea: contentContainer (translated during swipe) + glassDockOverlay (fixed overlay at bottom)
        val pageArea = FrameLayout(this).apply {
            addView(contentContainer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(glassDockOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.gravity = android.view.Gravity.BOTTOM })
        }
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = LiquidGlassTheme.createPageBackgroundDrawable(isDarkMode(), isShizukuBlackTheme())
            addView(pageArea, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            navBar = bottomNavigation()
            addView(navBar)
        }
        setContentView(rootLayout)
        glassRoot = rootLayout
        // 接线系统栏 Insets：此前从未调用，systemBottomInset 恒为 0，
        // dock 会压到手势条；同时 insets 回调会统一执行 updateSystemBarPadding，
        // 保证首进与切换页面后 dock 内边距完全一致（高度不跳变）。
        installSystemBarInsets(rootLayout)
        // 顶部状态栏 + 标题栏统一为页面背景起始色（不再纯黑实底 + 白字），
        // 必须在初始页面渲染前完成，避免首帧仍显示旧黑条
        applyUnifiedTopBar()
        installBackNavigation()
        installGlassBackdropSync(rootLayout)
        setupGlassDockOverlay()
        if (this::class.java == MaxHzActivity::class.java) {
            setupInitialPage()
            updateMaxHzServiceState()
            syncStatusChangeLogs()
        }
    }

    protected open fun setupInitialPage() {
        val targetPageStr = intent?.getStringExtra("extra_target_page")
        val initialPage = when (targetPageStr?.lowercase()) {
            "power" -> Page.POWER
            "refresh" -> Page.REFRESH
            "advanced" -> Page.ADVANCED
            else -> Page.REFRESH
        }
        renderPage(initialPage)
    }

    protected fun hideMaxHzScrollView() {
        if (::scrollView.isInitialized) {
            scrollView.visibility = View.GONE
        }
    }

    protected fun showMaxHzScrollView() {
        if (::scrollView.isInitialized) {
            scrollView.visibility = View.VISIBLE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 方案 A：彻底移除全局 Activity 级别的触摸拦截。
        // 页面交互完全交由 Compose LazyColumn 与 ScrollView 的原生纵向物理滚动分发，
        // 页面切换 100% 由底部毛玻璃 Dock 承载，彻底杜绝一切水平误触与切页闪动。
        return super.dispatchTouchEvent(event)
    }

    private fun isMainSwipeBlocked(event: MotionEvent): Boolean {
        if (detailStack.isNotEmpty()) return true
        if (currentFocus is EditText) return true
        var target = findTouchedView(window.decorView, event.rawX.toInt(), event.rawY.toInt())
        while (target != null) {
            if (target is Button ||
                target is CompoundButton ||
                target is EditText ||
                target is SeekBar ||
                target is Spinner
            ) {
                return true
            }
            val parent = target.parent
            target = parent as? View
        }
        return false
    }

    private fun findTouchedView(view: View, rawX: Int, rawY: Int): View? {
        if (view.visibility != View.VISIBLE || !isPointInside(view, rawX, rawY)) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findTouchedView(view.getChildAt(index), rawX, rawY)?.let { return it }
            }
        }
        return view
    }

    private fun isPointInside(view: View, rawX: Int, rawY: Int): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] &&
            rawX <= location[0] + view.width &&
            rawY >= location[1] &&
            rawY <= location[1] + view.height
    }

    private fun handleMainPageSwipe(dx: Float) {
        if (mainPageAnimating) return
        val pages = listOf(Page.SHIZUKU, Page.REFRESH, Page.ADVANCED, Page.POWER)
        val currentIndex = pages.indexOf(selectedPage).coerceAtLeast(0)
        val nextIndex = if (dx < 0) currentIndex + 1 else currentIndex - 1
        if (nextIndex in pages.indices) {
            renderPageWithSwipeAnimation(pages[nextIndex], dx)
        }
    }

    private fun switchMainPageBySwipe(dx: Float) {
        val pages = listOf(Page.SHIZUKU, Page.REFRESH, Page.ADVANCED, Page.POWER)
        val currentIndex = pages.indexOf(selectedPage).coerceAtLeast(0)
        val nextIndex = if (dx < 0) currentIndex + 1 else currentIndex - 1
        if (nextIndex in pages.indices) {
            renderPage(pages[nextIndex])
        }
    }

    /**
     * 截取当前内容影像（半分辨率）。
     *
     * 只用于切页过渡时留一层「旧内容」的影子。半分辨率是因为过渡只有 200ms
     * 且在运动中，肉眼分辨不出；但位图从 16MB 降到 4MB，截取耗时从几十毫秒
     * 降到几毫秒，不会在手势结束时卡一帧。
     */
    private fun captureContentSnapshot(): Bitmap? = try {
        val target = if (::contentContainer.isInitialized) contentContainer else pageContent
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) {
            null
        } else {
            val scale = 0.6f
            val bmp = Bitmap.createBitmap(
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(scale, scale)
            target.draw(canvas)
            bmp
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 切页过渡（左右滑动）。
     *
     * ## 旧实现为什么会有白闪
     *
     * 旧逻辑是「整块内容淡出到 alpha 0 → 换页 → 再淡回来」。中间约 200ms
     * 内容区处于半透明，露出下面 `rootLayout` 的极光底 —— 浅色模式那层底
     * 亮度约 244，而带内容的页面平均才 166，于是肉眼看到的就是一整片白。
     * 实测：内容区亮度 166 → 229.5，持续约 5 帧，状态栏（在 contentContainer
     * 之外）不受影响，佐证是「整块内容变透明」而不是动画没跑。
     *
     * ## 新实现
     *
     * 1. 先把旧内容截成一张不透明的影像，盖在内容层之上、dock 之下；
     * 2. 在影像遮挡下换内容（用户看不见换的过程）；
     * 3. 让影像淡出，同时新内容带一点位移滑回原位 —— 视觉上就是交叉溶解。
     *
     * 整个过程任何时刻都至少有一层不透明像素，底层永远不会露出来，
     * 因此不会有白闪；同时保留了柔和的过渡，不是硬切。
     */
    protected open fun renderPageWithSwipeAnimation(page: Page, dx: Float) {
        // Page.SHIZUKU 会触发 finishToHome() → Activity 转场到 HomeActivity。
        // ghost 动画依赖当前 Activity 的 View 树存活，finish 后视图销毁、
        // ghost 随之消失，露出 HomeActivity 的极光底（浅色 ~230）——
        // 实测恰好就是那 2 帧「白闪」。对 SHIZUKU 直接走 finishToHome()
        // 自带的转场动画（maxhz_enter_from_left / maxhz_exit_to_right），
        // 不经过 ghost 通道。
        if (page == Page.SHIZUKU) {
            mainPageAnimating = true
            val target = if (::contentContainer.isInitialized) contentContainer else pageContent
            target.animate().cancel()
            target.translationX = 0f
            target.alpha = 1f
            renderPage(page)
            target.animate().setListener(null)
            mainPageAnimating = false
            return
        }

        val target = if (::contentContainer.isInitialized) contentContainer else pageContent
        val width = target.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val direction = if (dx < 0) -1 else 1
        mainPageAnimating = true
        target.animate().cancel()

        val host = target.parent as? ViewGroup
        val snapshot = captureContentSnapshot()
        if (host == null || snapshot == null) {
            // 拿不到宿主或截图失败：退回直接换页（宁可硬切也不要白闪）
            target.translationX = 0f
            target.alpha = 1f
            renderPage(page)
            target.animate().setListener(null)
            mainPageAnimating = false
            return
        }

        // 1) 旧内容影像：盖在 contentContainer 之上，但在 dock 覆盖层之下，
        //    这样过渡期间 dock 不会被压住而闪一下。
        val ghost = ImageView(this).apply {
            setImageBitmap(snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        host.addView(
            ghost,
            host.indexOfChild(target) + 1,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 2) 在影像遮挡下换内容，新内容先整体挪到屏幕外，准备滑入。
        //
        //    这里**不能**让 ghost 做「原地淡出」：实测新页面（Compose 首次
        //    组合）要 1~2 帧才画得出来，ghost 一淡出，露出来的就是还没画上
        //    内容的空背景（浅色模式亮度 244）—— 那正是用户看到的白闪。
        //
        //    改成整页平移：ghost 与内容的边缘在动画每一刻都严格相接
        //    （ghost 右缘 = 内容左缘），屏幕任一位置始终被不透明像素覆盖，
        //    底层永远不会露出来，同时过渡依然是柔和的滑动而不是硬切。
        val duration = 300L
        val offset = direction * width.toFloat()
        target.translationX = -offset
        target.alpha = 1f
        renderPage(page)

        // 3) 旧页整页滑出、新页整页滑入（与手势同向）
        val interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        val ghostAnim = ghost.animate()
            .translationX(offset)
            .setDuration(duration)
            .setInterpolator(interpolator)
        val contentAnim = target.animate()
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(interpolator)

        // 动画结束监听与下面的兜底延时都会调 finish，必须幂等 ——
        // 否则第二次 removeView 会抛 IllegalArgumentException 直接崩。
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                ghost.animate().cancel()
                target.animate().setListener(null)
                host.removeView(ghost)
                ghost.setImageDrawable(null)
                runCatching { snapshot.recycle() }
                target.translationX = 0f
                target.alpha = 1f
                mainPageAnimating = false
            }
        }
        contentAnim.setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = finish()
            override fun onAnimationCancel(animation: Animator) = finish()
        })
        // 等新页面真正画出来再起滑。
        //
        // 实测：Compose 页面首次组合要 1~2 帧，重页面甚至更久。若动画立刻
        // 启动，滑到终点时新页面可能还是空白（整屏亮度 230，dock 因折射到
        // 亮背景也跟着变亮）—— 实测每次翻页都有 1~2 帧这种白闪。
        // 等待期间屏幕上仍是 ghost（旧内容，静止），观感是「顿一下再滑」，
        // 远好过闪一整片白。两个 post 保证跨过至少一整帧。
        target.post {
            target.postOnAnimation {
                ghostAnim.start()
                contentAnim.start()
                // 兜底：动画被系统打断时也要收尾，避免 ghost 永久盖住页面
                ghost.postDelayed({ if (ghost.parent != null) finish() }, duration + 200L)
            }
        }
    }

    private fun finishBySwipeBack() {
        finishToHome()
    }

    @Suppress("DEPRECATION")
    protected open fun finishToHome() {
        finish()
        overridePendingTransition(R.anim.maxhz_enter_from_left, R.anim.maxhz_exit_to_right)
    }

    private fun installBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!popDetail()) {
                    finishToHome()
                }
            }
        })
    }

    private fun installSystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            systemBottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            systemBottomInsetState.intValue = systemBottomInset
            updateSystemBarPadding()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 接入玻璃背景快照。
     *
     * 玻璃必须折射「卡片背后的背景内容」才有意义，而 View 体系拿不到背后
     * 的内容，所以把页面背景离屏重绘成一张共享快照，所有卡片按各自的窗口
     * 位置去采样它。亮色、深色共用这条路径，不需要任何分支。
     */
    private fun installGlassBackdropSync(root: View) {
        LiquidGlassTheme.setDensity(resources.displayMetrics.density)
        // 把主题强调色灌进玻璃引擎：按钮、徽章、进度条共用同一个来源，
        // 系统换主题色（Material You 动态取色）后才会跟着变。
        LiquidGlassTheme.setAccent(themeColors.primary)
        root.post { refreshGlassBackdrop(root) }
        root.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // 尺寸变化（旋转、分屏、键盘）会让快照与背景错位，需要重新采集
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                refreshGlassBackdrop(v)
            }
        }
    }

    /**
     * 重新采集玻璃背景快照。
     *
     * 主题在亮色/深色/纯黑之间切换时必须调用，否则玻璃会采样到上一次主题
     * 的背景 —— 这正是「改了深色却漏掉亮色」最容易复发的地方。
     */
    protected fun refreshGlassBackdrop(root: View? = glassRoot) {
        val target = root ?: return
        LiquidGlassTheme.setDensity(resources.displayMetrics.density)
        target.post {
            LiquidGlassTheme.captureBackdrop(target, isDarkMode(), isShizukuBlackTheme())
            target.invalidate()
        }
    }

    protected fun updateSystemBarPadding() {
        // dock 避让高度（dock 自身高度 + 底部边距 ~100dp）以及系统手势条 inset。
        // 在二级详情页时 dock 已隐藏，避让高度为 0，防止底部大面积留白。
        val dockClearance = if (detailStack.isNotEmpty()) 0 else dp(100)
        val bottomPadding = systemBottomInset + dockClearance
        // clipToPadding 必须设为 false，scrollView 底部 padding 置 0，
        // 让滚动内容能完整贯穿延伸到底部、穿透毛玻璃 dock 显示（即用户要求的“可以穿透 dock”）；
        // 避让内边距移至内部 pageContent 的底部，并留足安全缓冲，保证最底部的控件与文字能完全滚出 dock 上方。
        scrollView.clipToPadding = false
        scrollView.clipChildren = false
        scrollView.setPadding(0, actionBarInset(), 0, 0)
        navBar?.setPadding(dp(10), dp(6), dp(10), dp(6) + systemBottomInset)
        if (::pageContent.isInitialized) {
            pageContent.clipToPadding = false
            pageContent.clipChildren = false
            pageContent.setPadding(dp(16), dp(12), dp(16), bottomPadding + dp(32))
        }
    }

    // Galaxy MaxHz 式分层导航：返回键先退出二级设置页，再退出 Activity
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!popDetail()) {
            finishToHome()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home && popDetail()) return true
        if (item.itemId == android.R.id.home) {
            finishToHome()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkOpenAotProgressFromIntent(intent)
    }

    private fun checkOpenAotProgressFromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("extra_open_aot_progress", false) == true) {
            intent.removeExtra("extra_open_aot_progress")
            if (AotCompileService.isRunning) {
                showAotLiveProgressDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkOpenAotProgressFromIntent(intent)
        val fpsStateChanged = syncFpsMonitorOverlayPermission()
        syncStatusChangeLogs()
        syncBackgroundErrorLog()
        // 设置页改过夜间模式后回到这里，快照必须重采：MaxHzActivity 的基类
        // 不一定走 AppCompatDelegate 的 recreate 链路（见 isDarkMode 注释），
        // 不主动刷新的话玻璃会继续采样上一个主题的背景。
        refreshGlassBackdrop()
        glassRoot?.background = LiquidGlassTheme.createPageBackgroundDrawable(isDarkMode(), isShizukuBlackTheme())
        // 顶栏（状态栏 + 标题栏）也要跟着刷新。
        //
        // applyUnifiedTopBar 原本只在 onCreate 跑一次，但设置页换壁纸/换主题后
        // 只 bump 了 Compose 重组（MaxHzComposeRefreshBus.bump()），顶栏不会重跑
        // —— 于是在设置里加上/移除壁纸返回后，内容区已经是新壁纸、顶栏还停在
        // 旧的极光起始色，割裂成两块。这里在回前台时补一次。
        if (findViewById<View>(R.id.toolbar_container) != null) {
            applyUnifiedTopBar()
        }
        ensureMaxHzContentVisible(forceDetailRefresh = fpsStateChanged)
        keepControlUiResponsive()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 快照是全屏 Bitmap，随 Activity 一起释放
        LiquidGlassTheme.releaseBackdrop()
        glassRoot = null
    }

    private fun syncFpsMonitorOverlayPermission(): Boolean {
        if (!MaxHzPrefs.fpsMonitorRunning(this) || Settings.canDrawOverlays(this)) return false
        MaxHzPrefs.setFpsMonitorRunning(this, false)
        stopService(Intent(this, MaxHzFpsMonitorService::class.java))
        appendOperationLog("FPS Monitor已停止", "悬浮窗权限已撤销")
        return true
    }

    private fun ensureMaxHzContentVisible(forceDetailRefresh: Boolean = false) {
        if (!::pageContent.isInitialized) return
        if (detailStack.isNotEmpty() && (forceDetailRefresh || pageContent.childCount == 0 || isContentEffectivelyBlank())) {
            renderDetail()
        } else if (detailStack.isEmpty() && (pageContent.childCount == 0 || isContentEffectivelyBlank())) {
            renderPage(selectedPage)
        }
    }

    private fun isContentEffectivelyBlank(): Boolean {
        if (!::pageContent.isInitialized) return true
        if (pageContent.childCount == 0) return true
        for (index in 0 until pageContent.childCount) {
            val child = pageContent.getChildAt(index)
            if (child.visibility == View.VISIBLE && child.width != 0 && child.height != 0) return false
            if (child.visibility == View.VISIBLE && child.layoutParams?.height != 0) return false
        }
        return true
    }

    private fun keepControlUiResponsive() {
        if (!MaxHzPrefs.controlEnabled(this) && !MaxHzPrefs.hasAppRefreshRules(this)) return
        if (!MaxHzShell.isReady()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { MaxHzController.ensureControlUiRefreshRate() }
            }
        }
    }

    protected open fun renderPage(page: Page, restoreScrollY: Int? = null) {
        val switchingPage = page != selectedPage
        if (switchingPage || detailStack.isNotEmpty()) {
            collapseAllResults()
            lastResultView = null
        }
        selectedPage = page
        if (restoreScrollY == null) {
            detailStack.clear()
            detailModalDialog?.dismiss()
        }
        supportActionBar?.title = getString(R.string.maxhz_title)
        // 底部导航统一由 Compose 玻璃 dock 承载（首页 + 三个 MaxHz tab 共用
        // GlassDockNav），旧 View 层实底 navBar 在所有页面隐藏。
        navBar?.visibility = View.GONE
        if (detailStack.isEmpty()) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            if (::glassDockOverlay.isInitialized) {
                glassDockOverlay.visibility = View.VISIBLE
            }
        }
        updateSystemBarPadding()
        pageContent.removeAllViews()
        maxHzGroups.clear()
        maxHzExtraViews.clear()
        maxHzActionCards.clear()
        when (page) {
            Page.SHIZUKU -> {
                finishToHome()
            }
            Page.OVERVIEW -> {
                renderPage(Page.REFRESH)
            }
            Page.REFRESH -> {
                addGroup(
                    "分应用刷新率规则",
                    listOf(
                        EntryItem("全屏编辑应用规则", "为单个应用单独设置 60Hz / 90Hz / 120Hz 刷新率", { appRefreshState() }) {
                            pushDetail("分应用自定义刷新率") { appRefreshRateSection() }
                        },
                        EntryItem("无障碍即时切换服务", "检测前台应用实时切换刷新率，只读包名，安全省电", { appRefreshInstantState() }) {
                            AccessibilityHelper.openInstantSwitchSettings(this)
                        }
                    )
                )

                // 户外模式现在是「显示与视觉」组里的独立 EntryItem 开关（不再是独立卡片），
                // 也没有详情页。
                addGroup(
                    "显示与视觉",
                    listOf(
                        EntryItem("户外高亮模式", "强光下提升屏幕亮度与对比度（需三星或支持设备）", { outdoorModeState() }, toggle = ToggleAction(
                            isOn = { outdoorModeActive() },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        MaxHzController.setOutdoorMode(on)
                                    }
                                    if (r.code == 0) {
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    onDone(r.code == 0)
                                }
                            }
                        )),
                        EntryItem("实时 FPS 监视器", "悬浮窗显示前台 App 实际渲染 FPS，不写系统设置", { fpsMonitorState() }) {
                            pushDetail("实时 FPS 监视器") { refreshRateSection() }
                        },
                        EntryItem("添加户外模式快捷磁贴", "向控制中心快捷磁贴添加“户外高亮模式”（Android 13+）", { "点击添加" }) {
                            requestOutdoorModeTile()
                        }
                    )
                )
                maxHzExtraViews.add { appRefreshRateSection() }
            }
            Page.ADVANCED -> {
                renderPage(Page.POWER)
            }
            Page.POWER -> {
                addGroup(
                    "系统响应",
                    listOf(
                        EntryItem("增强处理响应", "启用三星官方增强处理模式，提升 CPU 调度响应与操作跟手度", { enabledState(MaxHzPrefs.mCpuResponseEnabled(this@MaxHzActivity)) }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.mCpuResponseEnabled(this@MaxHzActivity) },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.applyMCpuResponse() else MaxHzController.restoreMCpuResponse()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.setMCpuResponseEnabled(this@MaxHzActivity, on)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    onDone(r.code == 0)
                                }
                            }
                        )),
                    )
                )

                addGroup(
                    "游戏模式与调度",
                    listOf(
                        EntryItem(
                            "游戏模式切换",
                            "支持系统默认、省电自适应高刷或性能模式，退出后还原",
                            { gameAutoPerformanceState() },
                            toggle = ToggleAction(
                                isOn = { MaxHzPrefs.gameAutoPerformanceEnabled(this@MaxHzActivity) },
                                onToggle = { on, onDone ->
                                    if (on && !MaxHzAccessibilityService.isEnabled(this@MaxHzActivity)) {
                                        Toast.makeText(
                                            this@MaxHzActivity,
                                            "需要先启用无障碍即时切换服务才能识别前台游戏，正在为您直达开启...",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity)
                                        onDone(false)
                                        return@ToggleAction
                                    }
                                    MaxHzPrefs.setGameAutoPerformanceEnabled(this@MaxHzActivity, on)
                                    if (!on) {
                                        MaxHzPrefs.setGameAutoSessionActive(this@MaxHzActivity, false)
                                        MaxHzPrefs.clearGameAutoSnapshot(this@MaxHzActivity)
                                    }
                                    updateMaxHzServiceState()
                                    Toast.makeText(
                                        this@MaxHzActivity,
                                        if (on) "游戏模式已开启" else "游戏模式已关闭",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDone(true)
                                }
                            )
                        ) {
                            pushDetail("游戏模式切换") { gameAutoPerformanceCard() }
                        },
                    )
                )

                addGroup(
                    "系统优化与加速",
                    listOf(
                        EntryItem("应用启动加速", "预编译已安装应用，大幅提升冷启动速度与内存整理", { enabledState(MaxHzPrefs.mDexoptEnabled(this@MaxHzActivity)) }) {
                            pushDetail("应用启动加速") {
                                LinearLayout(this@MaxHzActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    mOriginalMemoryCompile(this)
                                }
                            }
                        },
                        EntryItem("强制所有应用分屏/小窗", "解除限制，让所有应用均支持分屏与悬浮窗", {
                            if (MaxHzPrefs.resultText(this@MaxHzActivity, "force_resizable") == "1") "已开启" else "默认"
                        }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.resultText(this@MaxHzActivity, "force_resizable") == "1" },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.keepForceResizableActivities() else MaxHzController.restoreForceResizableActivities()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.saveResult(this@MaxHzActivity, "force_resizable", if (on) "1" else "0", true)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    onDone(r.code == 0)
                                }
                            }
                        )),
                    )
                )

                addGroup(
                    "省电与调度",
                    listOf(
                        EntryItem("省电模式自适应高刷", "突破系统省电 60Hz 限制，开启省电模式依然保持 120Hz 自适应流畅", { dailySaverState() }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.compatibleSaverEnabled(this@MaxHzActivity) },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.applyDailySaver(
                                            MaxHzPrefs.quickDozeMaintenanceMinutes(this@MaxHzActivity),
                                            MaxHzPrefs.quickDozeNoMaintenance(this@MaxHzActivity)
                                        ) else MaxHzController.restoreDailySaver()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.setCompatibleSaverEnabled(this@MaxHzActivity, on)
                                        MaxHzPrefs.setQuickDozeEnabled(this@MaxHzActivity, on)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        if (!on) {
                                            MaxHzPrefs.setCompatibleSaverEnabled(this@MaxHzActivity, false)
                                            MaxHzPrefs.setQuickDozeEnabled(this@MaxHzActivity, false)
                                            updateMaxHzServiceState()
                                        }
                                        Toast.makeText(
                                            this@MaxHzActivity,
                                            if (on) "开启失败: $err" else "恢复失败（开关已重置，可重试）: $err",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    onDone(r.code == 0)
                                }
                            }
                        )) {
                            pushDetail("日用省电模式") { lowPowerStartSection() }
                        },
                    )
                )

                addGroup(
                    "供电与充电",
                    listOf(
                        EntryItem("电池信息与健康度", "实时读取电量、温度、充放电电流、循环数与三星原厂健康度", { "点击查看" }) {
                            showBatteryInfoDialog()
                        },

                        EntryItem("旁路供电 (充电直供)", "充电器直接为主板供电，边玩边充不发热", { passThroughState() }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.passThroughEnabled(this@MaxHzActivity) },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.applyPassThroughMode(MaxHzPrefs.batteryLimit(this@MaxHzActivity)) else MaxHzController.restorePassThroughMode()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.setPassThroughEnabled(this@MaxHzActivity, on)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    onDone(r.code == 0)
                                }
                            }
                        )) {
                            showBatteryProtectionDialog()
                        },

                        EntryItem("充电上限保护", "自定义 80%/85% 充电上限，保护电池循环寿命", { "${MaxHzPrefs.batteryLimit(this@MaxHzActivity)}% / ${MaxHzPrefs.batteryRecharge(this@MaxHzActivity)}%" }) {
                            showBatteryProtectionDialog()
                        },
                    )
                )

                addGroup(
                    "后台与休眠",
                    listOf(
                        EntryItem("后台进程休眠（墓碑）", "冻结后台闲置应用，杜绝后台偷跑电量", { enabledState(MaxHzPrefs.mTombstoneEnabled(this@MaxHzActivity)) }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.mTombstoneEnabled(this@MaxHzActivity) },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.applyMTombstoneMode() else MaxHzController.restoreMTombstoneMode()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.setMTombstoneEnabled(this@MaxHzActivity, on)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    onDone(r.code == 0)
                                }
                            }
                        )),

                        EntryItem("限制后台自启", "限制三方应用后台自启、隐式唤醒与无故复活（适配三星与主流机型）", { enabledState(MaxHzPrefs.mAutostartLimitEnabled(this@MaxHzActivity)) }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.mAutostartLimitEnabled(this@MaxHzActivity) },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.applyMAutostartLimit() else MaxHzController.restoreMAutostartLimit()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.setMAutostartLimitEnabled(this@MaxHzActivity, on)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    onDone(r.code == 0)
                                }
                            }
                        )),

                        EntryItem("限制后台定位", "限制后台应用频繁请求定位，降低待机耗电", { enabledState(MaxHzPrefs.mLocationThrottleEnabled(this@MaxHzActivity)) }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.mLocationThrottleEnabled(this@MaxHzActivity) },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.applyMLocationThrottle() else MaxHzController.restoreMLocationThrottle()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.setMLocationThrottleEnabled(this@MaxHzActivity, on)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    onDone(r.code == 0)
                                }
                            }
                        )),
                    )
                )

                addGroup(
                    "深度休眠",
                    listOf(
                        EntryItem("熄屏深度休眠", "熄屏后立即进入深度睡眠，大幅降低夜间掉电", {
                            if (MaxHzPrefs.quickDozeEnabled(this@MaxHzActivity)) "已开启" else "已关闭"
                        }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.quickDozeEnabled(this@MaxHzActivity) },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.applyQuickDoze(
                                            MaxHzPrefs.quickDozeMaintenanceMinutes(this@MaxHzActivity),
                                            MaxHzPrefs.quickDozeNoMaintenance(this@MaxHzActivity)
                                        ) else MaxHzController.restoreQuickDoze()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.setQuickDozeEnabled(this@MaxHzActivity, on)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    // 把成败回传给渲染层：失败时它会把开关弹回原状态
                                    // 并提示，而不是一直停在「已开」的假象上。
                                    onDone(r.code == 0)
                                }
                            }
                        )) {
                            pushDetail("熄屏深度休眠") { quickDozeSection() }
                        },

                        EntryItem("限制扫描与同步", "限制后台 Wi-Fi/蓝牙扫描与无用同步，节省电量", { enabledState(MaxHzPrefs.scanGuardEnabled(this@MaxHzActivity)) }, toggle = ToggleAction(
                            isOn = { MaxHzPrefs.scanGuardEnabled(this@MaxHzActivity) },
                            onToggle = { on, onDone ->
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        if (on) MaxHzController.disableAutoSync() else MaxHzController.enableAutoSync()
                                    }
                                    if (r.code == 0) {
                                        MaxHzPrefs.setScanGuardEnabled(this@MaxHzActivity, on)
                                        updateMaxHzServiceState()
                                    } else {
                                        val err = r.err.ifBlank { r.out }.ifBlank { "执行异常 (${r.code})" }
                                        Toast.makeText(this@MaxHzActivity, if (on) "开启失败: $err" else "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    }
                                    // 把成败回传给渲染层：失败时它会把开关弹回原状态
                                    // 并提示，而不是一直停在「已开」的假象上。
                                    onDone(r.code == 0)
                                }
                            }
                        )),

                        EntryItem("待机异常唤醒排查", "排查并拦截后台频繁唤醒 CPU 的异常应用", { nightWakeupGuardState() }) {
                            pushDetail("待机异常唤醒排查") { nightWakeupGuardSection() }
                        },
                        EntryItem("后台保护名单", "管理系统 Doze 白名单，控制不受后台限制的应用", { "查看名单" }) {
                            pushDetail("后台保护名单") { dozeWhitelistSection() }
                        },
                    )
                )

                addGroup(
                    "系统维护",
                    listOf(
                        EntryItem("恢复系统默认", "一键清除所有自定义优化，恢复系统默认状态", { "就绪" }) {
                            pushDetail("恢复系统默认") { repairToolsSection() }
                        },
                        EntryItem("策略与状态总览", "查看当前各模块运行状态、生效策略与冲突检测", { "查看状态" }) {
                            pushDetail("策略与状态总览") { classicOverviewSection() }
                        },
                        EntryItem("操作日志与历史记录", "查看各项优化与调优指令的执行记录及审计日志", { "查看记录" }) {
                            pushDetail("操作日志与审计") { operationLogSection() }
                        },
                        EntryItem("全量系统诊断报告", "生成并导出系统硬件与运行环境全量诊断信息", { "生成报告" }) {
                            pushDetail("系统诊断与报告导出") { systemDiagnosticsSection() }
                        },
                    )
                )

                addActionCard(
                    title = "游戏模式快捷磁贴",
                    subtitle = "向下拉通知栏添加游戏模式快捷磁贴，一键锁高刷与释放性能",
                    buttonText = "添加到控制中心"
                ) {
                    requestGameModeTile()
                }
            }
        }
        if (page == Page.REFRESH || page == Page.ADVANCED || page == Page.POWER) {
            dockSelectedTabState.intValue = dockIndexFor(page)
            renderMaxHzComposePage()
        }
        if (restoreScrollY != null) {
            scrollView.post { scrollView.scrollTo(0, restoreScrollY) }
        } else {
            scrollView.post { scrollView.scrollTo(0, 0) }
        }
        updateNavSelection()
    }

    /** 页面 → 玻璃 dock 页签 index（0 特权服务 / 1 性能硬件 / 2 应用规则） */
    protected open fun dockIndexFor(page: Page): Int = when (page) {
        Page.SHIZUKU -> 0
        Page.POWER -> 1
        Page.REFRESH -> 2
        else -> 0
    }

    /**
     * 玻璃 dock 页签点击：0 特权服务 / 1 性能硬件 / 2 应用规则
     */
    protected open fun handleDockTabClick(index: Int) {
        val target = when (index) {
            0 -> Page.SHIZUKU
            1 -> Page.POWER
            2 -> Page.REFRESH
            else -> Page.SHIZUKU
        }
        if (target == selectedPage) return
        renderPage(target)
    }

    // ============ 阶段 B：MaxHz 三个 tab 的 Compose 渲染 ============
    private val maxHzGroups = mutableStateListOf<MaxHzGroup>()
    private val maxHzExtraViews = mutableStateListOf<() -> View>()
    /** 原生 Compose 特殊卡（不走 View 通道的 MaterialCardView） */
    private val maxHzActionCards = mutableStateListOf<MaxHzActionCard>()
    private var maxHzComposeView: ComposeView? = null

    private fun addGroup(
        label: String,
        items: List<EntryItem>,
        collapsedByDefault: Boolean = false
    ) {
        maxHzGroups.add(MaxHzGroup(label, items, collapsedByDefault))
    }

    private fun addActionCard(
        title: String,
        subtitle: String,
        buttonText: String,
        onClick: () -> Unit
    ) {
        maxHzActionCards.add(MaxHzActionCard(title, subtitle, buttonText, onClick))
    }

    private fun addExtraView(block: () -> View) {
        maxHzExtraViews.add(block)
    }

    /**
     * 创建共享玻璃 dock 覆盖层，添加到 glassDockOverlay（在 contentContainer 之外）。
     * 翻页动画只对 contentContainer 做 translationX，dock 始终固定在底部。
     */
    private fun setupGlassDockOverlay() {
        if (::glassDockOverlay.isInitialized.not()) return
        val dock = ComposeView(this).apply {
            setContent {
                // MaterialTheme 注入 LocalIndication(ripple)，否则按钮按下无任何反馈
                GlassAppTheme(isDark = isDarkMode()) {
                    val density = LocalDensity.current
                    val bottomInsetDp = with(density) { systemBottomInsetState.intValue.toFloat().toDp() }
                    Box(
                        Modifier.fillMaxWidth().wrapContentHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        GlassDockNav(
                            isDark = isDarkMode(),
                            selectedIndex = dockSelectedTabState.intValue,
                            bottomInsetDp = bottomInsetDp,
                            onTabClick = { index -> handleDockTabClick(index) }
                        )
                    }
                }
            }
        }
        glassDockView = dock
        // glassDockOverlay 现在撑满 pageArea（MATCH_PARENT），因此必须给 dock 自身
        // 设 gravity=BOTTOM：FrameLayout 子 View 默认靠 TOP|START，不设 gravity 会让
        // dock 跑到屏幕顶部（实测 ComposeView 落在 [0,0][1440,285]），底部反而空出。
        // 高度保持 WRAP_CONTENT，dock 只占自身 ~64dp + 底部 inset，不吃掉内容区高度。
        glassDockOverlay.addView(dock, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = android.view.Gravity.BOTTOM })
    }

    /** 通知共享 dock 刷新（tab 选中状态变更后调用） */
    protected fun refreshGlassDock() {
        glassDockView?.invalidate()
    }

    private fun renderMaxHzComposePage() {
        hideMaxHzScrollView()
        maxHzComposeView?.let {
            // 同页内（MaxHz 三 tab 互切）复用同一 ComposeView，不重淡入，避免闪烁
            showWithFadeIfHidden(it)
            return
        }
        val cv = ComposeView(this).apply {
            setContent {
                GlassAppTheme(isDark = isDarkMode()) {
                MaxHzPageScreen(
                    isDark = isDarkMode(),
                    primaryColor = androidx.compose.ui.graphics.Color(themeColors.primary),
                    topInsetPx = actionBarInset(),
                    bottomInsetPx = systemBottomInsetState.intValue,
                    fullHeightPx = (glassRoot?.height ?: 0).toFloat().takeIf { it > 0f }
                        ?: resources.displayMetrics.heightPixels * 0.93f,
                    refreshTick = MaxHzComposeRefreshBus.tick.value,
                    selectedTabIndex = dockSelectedTabState.intValue,
                    flatBackground = isFlatBackground(),
                    wallpaperUri = ShizukuSettings.getWallpaperUri()?.let { Uri.parse(it) },
                    wallpaperFit = ShizukuSettings.getWallpaperFit(),
                    isBlackNightTheme = isShizukuBlackTheme(),
                    onTabClick = { index -> handleDockTabClick(index) },
                    groups = maxHzGroups.toList(),
                    actionCards = maxHzActionCards.toList(),
                    extraViewFactories = maxHzExtraViews.toList()
                )
                }
            }
        }
        contentContainer.addView(
            cv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        maxHzComposeView = cv
        // 首次创建：淡入进入，消除瞬切的「闪一下」
        fadeInPage(cv)
    }

    /**
     * 隐藏 MaxHz 的 Compose 页。
     *
     * 回到首页时必须调用：compose 页是后来 addView 的，z-order 在首页之上，
     * 不隐藏会出现「首页内容 + MaxHz 内容 + 两个玻璃 dock」三层叠加。
     */
    protected fun hideMaxHzCompose() {
        maxHzComposeView?.let { fadeOutPage(it) }
    }

    // ============ Galaxy MaxHz 式分层导航核心（One UI 9 统一模态架构） ============

    protected open fun pushDetail(
        title: String,
        keepScroll: Boolean = false,
        skipScrollTop: Boolean = false,
        targetDiagnosticKey: String? = null,
        builder: () -> View
    ) {
        val currentScrollY = modalScrollView?.scrollY ?: 0
        val screen = DetailScreen(title, builder, keepScroll, skipScrollTop, targetDiagnosticKey, savedParentScrollY = currentScrollY)
        detailStack.add(screen)

        if (detailModalDialog == null || !detailModalDialog!!.isShowing) {
            showDetailModalDialog(screen)
        } else {
            transitionModalToScreen(screen, isPush = true)
        }
    }

    protected open fun popDetail(): Boolean {
        if (detailStack.isEmpty()) {
            if (detailModalDialog?.isShowing == true) {
                detailModalDialog?.dismiss()
                return true
            }
            return false
        }
        if (detailStack.size > 1) {
            detailStack.removeAt(detailStack.lastIndex)
            val previousScreen = detailStack.last()
            transitionModalToScreen(previousScreen, isPush = false)
            return true
        }
        detailStack.clear()
        detailModalDialog?.dismiss()
        return true
    }

    protected fun dismissDetailModal() {
        detailStack.clear()
        detailModalDialog?.dismiss()
    }

    protected open fun onDetailModalDismissed() {
        if (selectedPage != Page.SHIZUKU && ::scrollView.isInitialized) {
            val y = scrollView.scrollY
            renderPage(selectedPage, restoreScrollY = y)
        }
    }

    protected fun modalHeaderButtonBackground(isDark: Boolean, radiusDp: Int): Drawable {
        val normal = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(if (isDark) 0x14FFFFFF.toInt() else 0x0A000000.toInt())
        }
        return RippleDrawable(
            ColorStateList.valueOf(rippleColor()),
            normal,
            GradientDrawable().apply {
                cornerRadius = dp(radiusDp).toFloat()
                setColor(Color.WHITE)
            }
        )
    }

    protected fun unifiedDialogCardBackground(isDark: Boolean): GradientDrawable {
        return moe.shizuku.manager.utils.DialogThemeHelper.createDialogBackground(this)
    }

    protected fun applyUnifiedDialogWindowStyle(dialog: android.app.Dialog, customWidth: Int? = null, transparentBg: Boolean = false) {
        moe.shizuku.manager.utils.DialogThemeHelper.applyUnifiedDialogWindowStyle(dialog, customWidth)
        if (transparentBg) {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    protected fun MaterialAlertDialogBuilder.showUnified(customWidth: Int? = null): androidx.appcompat.app.AlertDialog {
        val dialog = this.create()
        dialog.show()
        applyUnifiedDialogWindowStyle(dialog, customWidth)
        return dialog
    }

    protected fun showDetailModalDialog(screen: DetailScreen) {
        val isDark = isDarkMode()
        val dm = resources.displayMetrics
        val dialogWidth = minOf((dm.widthPixels * 0.92f).toInt(), dp(520))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            background = unifiedDialogCardBackground(isDark)
            clipToOutline = true
            elevation = dp(16).toFloat()
        }
        modalRootView = root

        // 顶部常驻标题与操作栏（已去除拖拽把手小杠，对齐原生居中弹窗）
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(6))
        }

        // 返回上一级按钮（‹）- 三级页面自动显现
        val backBtn = TextView(this).apply {
            text = "‹"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(themeColors.primary)
            visibility = if (detailStack.size > 1) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(4)
            }
            background = modalHeaderButtonBackground(isDark, 18)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                popDetail()
            }
        }
        modalBackBtn = backBtn
        headerBar.addView(backBtn)

        // 页面标题
        val titleView = TextView(this).apply {
            text = screen.title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColors.onSurface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (detailStack.size > 1) dp(2) else dp(6)
            }
        }
        modalTitleView = titleView
        headerBar.addView(titleView)

        // Option B 说明胶囊容器
        val pillContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        }
        modalOptionBPillContainer = pillContainer
        headerBar.addView(pillContainer)

        // 右上角关闭按钮（✕）
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(themeColors.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            background = modalHeaderButtonBackground(isDark, 17)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                dismissDetailModal()
            }
        }
        modalCloseBtn = closeBtn
        headerBar.addView(closeBtn)

        root.addView(headerBar)

        // 极细分隔线
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(if (isDark) 0x1FFFFFFF.toInt() else 0x12000000.toInt())
        }
        root.addView(divider)

        // 滚动视口 + 内容承载容器（动态自适应高度，最大不超过屏幕 82%，杜绝大面积空白）
        // 滚动视口 + 内容承载容器（动态自适应高度，按内容紧凑自适应，最高不超过屏幕 80%，杜绝大面积留白）
        val maxH = (resources.displayMetrics.heightPixels * 0.80f).toInt()
        val scrollView = object : NestedScrollView(this@MaxHzActivity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
                val wSpec = View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY)

                val child = if (childCount > 0) getChildAt(0) else null
                val childWSpec = View.MeasureSpec.makeMeasureSpec(
                    maxOf(0, availableWidth - paddingLeft - paddingRight),
                    View.MeasureSpec.EXACTLY
                )
                val childHSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                child?.measure(childWSpec, childHSpec)

                val naturalHeight = (child?.measuredHeight ?: 0) + paddingTop + paddingBottom
                val desiredHeight = minOf(naturalHeight, maxH)

                val exactHSpec = View.MeasureSpec.makeMeasureSpec(desiredHeight, View.MeasureSpec.EXACTLY)
                super.onMeasure(wSpec, exactHSpec)
                setMeasuredDimension(resolveSize(availableWidth, widthMeasureSpec), desiredHeight)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(dp(14), dp(4), dp(14), dp(8))
            clipToPadding = false
        }
        modalScrollView = scrollView

        val contentContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        modalContentContainer = contentContainer
        scrollView.addView(contentContainer)
        root.addView(scrollView)

        // 初始化内容
        collapseAllResults()
        lastResultView = null
        detailSubtitles.clear()
        detailTogglePills.clear()
        detailSubtitlesExpanded = false

        val screenView = runCatching { screen.builder() }
            .getOrElse { error ->
                errorCard("页面加载异常", error.message ?: error.javaClass.simpleName)
            }
        screenView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        contentContainer.addView(screenView)

        if (detailSubtitles.isNotEmpty()) {
            pillContainer.visibility = View.VISIBLE
            pillContainer.addView(createOptionBTogglePill())
        } else {
            pillContainer.visibility = View.GONE
        }

        val dialog = android.app.Dialog(this, R.style.ThemeOverlay_App_MaterialAlertDialog).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                popDetail()
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            detailModalDialog = null
            modalRootView = null
            modalBackBtn = null
            modalTitleView = null
            modalOptionBPillContainer = null
            modalCloseBtn = null
            modalScrollView = null
            modalContentContainer = null
            detailStack.clear()
            detailSubtitles.clear()
            detailTogglePills.clear()
            onDetailModalDismissed()
        }

        applyUnifiedDialogWindowStyle(dialog, dialogWidth, transparentBg = true)
        dialog.show()
        applyUnifiedDialogWindowStyle(dialog, dialogWidth, transparentBg = true)

        detailModalDialog = dialog

        screen.targetDiagnosticKey?.let { key ->
            scrollView.post { scrollToDiagnosticTarget(key) }
        }
    }

    private fun transitionModalToScreen(screen: DetailScreen, isPush: Boolean) {
        val container = modalContentContainer ?: return
        val pillContainer = modalOptionBPillContainer ?: return
        val scrollView = modalScrollView ?: return
        val titleView = modalTitleView ?: return
        val backBtn = modalBackBtn ?: return

        titleView.text = screen.title
        backBtn.visibility = if (detailStack.size > 1) View.VISIBLE else View.GONE
        (titleView.layoutParams as? LinearLayout.LayoutParams)?.marginStart = if (detailStack.size > 1) dp(2) else dp(6)

        collapseAllResults()
        lastResultView = null
        detailSubtitles.clear()
        detailTogglePills.clear()
        detailSubtitlesExpanded = false

        val newView = runCatching { screen.builder() }
            .getOrElse { error ->
                errorCard("页面加载异常", error.message ?: error.javaClass.simpleName)
            }

        pillContainer.removeAllViews()
        if (detailSubtitles.isNotEmpty()) {
            pillContainer.visibility = View.VISIBLE
            pillContainer.addView(createOptionBTogglePill())
        } else {
            pillContainer.visibility = View.GONE
        }

        val oldView = if (container.childCount > 0) container.getChildAt(0) else null
        newView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (oldView == null) {
            container.addView(newView)
            if (isPush) {
                if (!screen.keepScroll && !screen.skipScrollTop) scrollView.scrollTo(0, 0)
            }
            return
        }

        val width = container.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val exitDir = if (isPush) -1 else 1
        val enterDir = if (isPush) 1 else -1

        newView.alpha = 0f
        newView.translationX = enterDir * width * 0.12f
        container.addView(newView)

        val duration = 180L
        val interpolator = android.view.animation.DecelerateInterpolator()

        oldView.animate()
            .translationX(exitDir * width * 0.12f)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction {
                container.removeView(oldView)
            }
            .start()

        newView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction {
                screen.targetDiagnosticKey?.let { key ->
                    scrollView.post { scrollToDiagnosticTarget(key) }
                }
            }
            .start()

        if (isPush) {
            if (!screen.keepScroll && !screen.skipScrollTop) {
                scrollView.post { scrollView.scrollTo(0, 0) }
            }
        } else {
            scrollView.post { scrollView.scrollTo(0, screen.savedParentScrollY) }
        }
    }

    protected val detailSubtitles = mutableListOf<View>()
    protected val detailTogglePills = mutableListOf<TextView>()
    protected var detailSubtitlesExpanded = false

    protected fun bindOptionBSubtitle(touchTarget: View, vararg subtitleViews: View) {
        subtitleViews.forEach { sub ->
            if (!detailSubtitles.contains(sub)) {
                detailSubtitles.add(sub)
            }
            sub.visibility = if (detailSubtitlesExpanded) View.VISIBLE else View.GONE
            sub.isClickable = true
            sub.setOnClickListener {
                it.visibility = View.GONE
            }
        }
        touchTarget.isLongClickable = true
        touchTarget.setOnLongClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val anyVisible = subtitleViews.any { it.visibility == View.VISIBLE }
            val next = if (anyVisible) View.GONE else View.VISIBLE
            subtitleViews.forEach { it.visibility = next }
            true
        }
    }

    protected fun styleOptionBTogglePill(pill: TextView, expanded: Boolean) {
        val isDark = isDarkMode()
        pill.text = if (expanded) "隐藏说明" else "显示说明"
        pill.textSize = 11.5f
        pill.includeFontPadding = false
        pill.setTypeface(pill.typeface, android.graphics.Typeface.BOLD)
        pill.setTextColor(
            if (expanded) {
                if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
            } else {
                themeColors.onSurfaceVariant
            }
        )
        pill.setPadding(dp(10), dp(4), dp(10), dp(4))
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(
                if (expanded) {
                    if (isDark) 0xFF162946.toInt() else 0xFFEBF3FF.toInt()
                } else {
                    if (isDark) 0xFF21242C.toInt() else 0xFFF0F2F6.toInt()
                }
            )
        }
    }

    protected fun createOptionBTogglePill(groupSubtitlesProvider: (() -> List<View>)? = null): TextView {
        return TextView(this).apply {
            isClickable = true
            isFocusable = true
            if (groupSubtitlesProvider == null) {
                styleOptionBTogglePill(this, detailSubtitlesExpanded)
                detailTogglePills.add(this)
                setOnClickListener { v ->
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    detailSubtitlesExpanded = !detailSubtitlesExpanded
                    val vis = if (detailSubtitlesExpanded) View.VISIBLE else View.GONE
                    detailSubtitles.forEach { it.visibility = vis }
                    detailTogglePills.forEach { styleOptionBTogglePill(it, detailSubtitlesExpanded) }
                    modalRootView?.requestLayout()
                }
            } else {
                var groupExpanded = false
                styleOptionBTogglePill(this, groupExpanded)
                setOnClickListener { v ->
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    val targets = groupSubtitlesProvider()
                    val anyVisible = targets.any { it.visibility == View.VISIBLE }
                    groupExpanded = !anyVisible
                    val vis = if (groupExpanded) View.VISIBLE else View.GONE
                    targets.forEach { it.visibility = vis }
                    styleOptionBTogglePill(this, groupExpanded)
                    modalRootView?.requestLayout()
                }
            }
        }
    }

    protected fun createDetailOptionBHeader(title: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(10))
            addView(TextView(this@MaxHzActivity).apply {
                text = title
                textSize = 13f
                setTextColor(themeColors.primary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(createOptionBTogglePill())
        }
    }

    protected fun renderDetail(restoreScrollY: Int? = null) {
        val screen = detailStack.lastOrNull() ?: return
        if (detailModalDialog == null || !detailModalDialog!!.isShowing) {
            showDetailModalDialog(screen)
            return
        }
        val container = modalContentContainer ?: return
        val pillContainer = modalOptionBPillContainer ?: return
        val scrollView = modalScrollView ?: return
        val titleView = modalTitleView ?: return
        val backBtn = modalBackBtn ?: return

        titleView.text = screen.title
        backBtn.visibility = if (detailStack.size > 1) View.VISIBLE else View.GONE
        (titleView.layoutParams as? LinearLayout.LayoutParams)?.marginStart = if (detailStack.size > 1) dp(2) else dp(6)

        collapseAllResults()
        lastResultView = null
        detailSubtitles.clear()
        detailTogglePills.clear()
        detailSubtitlesExpanded = false

        val view = runCatching { screen.builder() }
            .getOrElse { error ->
                errorCard("页面加载异常", error.message ?: error.javaClass.simpleName)
            }

        pillContainer.removeAllViews()
        if (detailSubtitles.isNotEmpty()) {
            pillContainer.visibility = View.VISIBLE
            pillContainer.addView(createOptionBTogglePill())
        } else {
            pillContainer.visibility = View.GONE
        }

        container.removeAllViews()
        container.addView(view)

        val diagnosticKey = screen.targetDiagnosticKey
        if (diagnosticKey != null) {
            scrollView.post { scrollToDiagnosticTarget(diagnosticKey) }
        } else if (restoreScrollY != null) {
            scrollView.post { scrollView.scrollTo(0, restoreScrollY) }
        } else if (!screen.keepScroll && !screen.skipScrollTop) {
            scrollView.post { scrollView.scrollTo(0, 0) }
        }
    }

    protected fun entryGroupCard(groupLabel: String, items: List<EntryItem>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val groupSubs = mutableListOf<View>()
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(14), dp(3))
                addView(TextView(this@MaxHzActivity).apply {
                    text = groupLabel
                    textSize = 13f
                    setTextColor(themeColors.primary)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(createOptionBTogglePill { groupSubs })
            })
            addView(MaterialCardView(this@MaxHzActivity).apply {
                applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                    items.forEach { item ->
                        addView(entryRow(item, groupSubs))
                    }
                })
            })
        }
    }

    private fun entryRow(item: EntryItem, groupSubs: MutableList<View>? = null): View {
        val isDark = isDarkMode()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val icon = featureIconContainer(item.title)
            addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                rightMargin = dp(14)
            })
            lateinit var subTextView: TextView
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MaxHzActivity).apply {
                    text = featureTitle(item.title)
                    textSize = 16f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                val stateText = item.stateProvider?.invoke()
                val sub = if (stateText.isNullOrBlank()) item.subtitle else stateText
                subTextView = TextView(this@MaxHzActivity).apply {
                    text = sub
                    textSize = 13f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(subTextView)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            groupSubs?.add(subTextView)
            bindOptionBSubtitle(this, subTextView)
            val row = this
            if (item.toggle != null) {
                var programmaticChange = false
                lateinit var switch: MaterialSwitch
                switch = MaterialSwitch(this@MaxHzActivity).apply {
                    isChecked = item.toggle.isOn()
                    LiquidGlassTheme.styleIosSwitch(this, isDark)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (programmaticChange) return@setOnCheckedChangeListener
                        playClickFeedback(row, pressed = true)
                        item.toggle.onToggle(isChecked) { _ ->
                            runOnUiThread {
                                programmaticChange = true
                                switch.isChecked = item.toggle.isOn()
                                programmaticChange = false
                                val newState = item.stateProvider?.invoke()
                                subTextView.text = if (newState.isNullOrBlank()) item.subtitle else newState
                            }
                        }
                    }
                }
                addView(switch, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) })
                setOnClickListener {
                    playClickFeedback(this, pressed = true)
                    item.onClick()
                }
            } else {
                addView(TextView(this@MaxHzActivity).apply {
                    text = "›"
                    textSize = 20f
                    includeFontPadding = false
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (isDark) 0xFF707A8F.toInt() else 0xFF94A3B8.toInt())
                    setPadding(dp(8), 0, 0, 0)
                })
                setOnClickListener {
                    playClickFeedback(this, pressed = true)
                    item.onClick()
                }
            }
        }
    }

    // 日用省电模式独立设置页
    private fun bottomNavigation(): View {
        val isDark = isDarkMode()
        val dockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 与 updateSystemBarPadding() 的基准值完全一致：
            // 首次进入（Shizuku 主页不走 super.renderPage）与切换页面后
            // 的内边距不再出现 16/2/10 与 10/6/6 的差异，dock 高度恒定。
            setPadding(dp(10), dp(6), dp(10), dp(6))
            // 液态玻璃 dock：与卡片共用同一条背景快照折射管线，折射强度更高
            // （20dp 高度 / 14dp 位移）、玻璃更实，浮在滚动内容之上。
            // 注：原先这里有一条 1dp 实心分隔线，与 dock 卡片的描边/高光/阴影
            // 视觉冲突且颜色硬编码，已移除。
            val dockBar = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                val dockRadius = dp(28).toFloat()
                background = LiquidGlassTheme.createDockDrawable(
                    cornerRadiusPx = dockRadius,
                    isDark = isDark,
                    host = this
                )
                // dock 是 LinearLayout，只靠背景裁剪的话 elevation 阴影仍是方角，
                // 这里补一个圆角 Outline 让阴影跟着圆角走。
                LiquidGlassTheme.applyDockMaterial(this, dockRadius, isDark)
                val tabs = listOf(
                    Page.SHIZUKU to "Shizuku",
                    Page.REFRESH to "屏幕显示",
                    Page.ADVANCED to "性能加速",
                    Page.POWER to "电池省电"
                )
                tabs.forEachIndexed { index, (page, label) ->
                    val item = navButton(page, label)
                    navButtons.add(item)
                    addView(item, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        if (index > 0) leftMargin = dp(4)
                    })
                }
            }
            addView(dockBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return dockContainer
    }

    private fun navButton(page: Page, label: String): TextView {
        return MaterialButton(this).apply {
            tag = page
            text = label
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            includeFontPadding = false
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            minHeight = dp(40)
            minimumHeight = dp(40)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(20)
            setPadding(dp(2), dp(6), dp(2), dp(6))
            maxLines = 1
            backgroundTintList = null
            strokeWidth = 0
            setOnClickListener {
                playClickFeedback(this)
                renderPage(page)
            }
        }
    }

    protected open fun updateNavSelection() {
        val isDark = isDarkMode()
        navButtons.forEach { button ->
            val selected = button.tag == selectedPage
            // 选中项原先是「深蓝底 + 纯白字」。底改成中性玻璃后白色字就变成了
            // 白压白 —— 实测对比度仅 20（未选中项为 158），完全看不清。
            // 这里跟着换成最深的正文色，靠「深浅 + 字重 + 底玻璃更实」三层区分
            // 选中态，不引入任何强调色。
            val selectedTextColor = themeColors.onSurface
            val unselectedTextColor = if (isDark) 0xFFCBD5E1.toInt() else 0xFF475569.toInt()
            button.setTextColor(if (selected) selectedTextColor else unselectedTextColor)
            button.setTypeface(button.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (button is MaterialButton) {
                button.backgroundTintList = null
                // 底部导航不该被强调色染色 —— 原先选中态走 isPrimary，
                // 结果整条底栏变成一块蓝。改成中性强调：只把玻璃做实一档。
                button.background = if (selected) {
                    LiquidGlassTheme.createButtonDrawable(
                        cornerRadiusPx = dp(20).toFloat(),
                        isDark = isDark,
                        isPrimary = false,
                        isEmphasis = true
                    )
                } else {
                    LiquidGlassTheme.createButtonDrawable(
                        cornerRadiusPx = dp(20).toFloat(),
                        isDark = isDark,
                        isPrimary = false
                    )
                }
                button.strokeWidth = 0
            }
        }
    }

    private fun clearBackgroundError() {
        MaxHzPrefs.clearResult(this, BACKGROUND_ERROR_RESULT_KEY)
        resultStates.remove(BACKGROUND_ERROR_RESULT_KEY)
        resultViews.remove(BACKGROUND_ERROR_RESULT_KEY)
        resultBadges.remove(BACKGROUND_ERROR_RESULT_KEY)
        refreshVisibleStatusContent()
        Toast.makeText(this, "后台错误已清除", Toast.LENGTH_SHORT).show()
    }

    private fun refreshVisibleStatusContent() {
        if (detailStack.isNotEmpty()) {
            renderDetail()
        } else {
            renderPage(selectedPage)
        }
    }

    private fun refreshVisibleStatusContentPreservingResult() {
        val activeResult = lastResultView
        val activeKey = (activeResult?.tag as? ResultTag)?.key
        refreshVisibleStatusContent()
        if (activeKey != null) {
            resultViews[activeKey]?.let { lastResultView = it }
        }
    }

    private fun dashboardStatusRow(item: DashboardItem): View {
        val stateTextColor = dashboardStatusTextColor(item)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            installClickFeedback(this)
            setPadding(dp(4), dp(10), dp(0), dp(10))
            featureIconContainer(item.title)?.let { icon ->
                addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    rightMargin = dp(16)
                })
            }
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MaxHzActivity).apply {
                    text = item.title
                    textSize = 16f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(TextView(this@MaxHzActivity).apply {
                    text = item.state
                    textSize = 14f
                    setTextColor(stateTextColor)
                    includeFontPadding = false
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MaxHzActivity).apply {
                text = "›"
                textSize = 24f
                includeFontPadding = false
                setTextColor(themeColors.onSurfaceVariant)
                // 原 alpha=0.55 会把箭头冲淡到约 2.7:1（亮色）/ 远低于 AA；
                // 去掉透明度后 onSurfaceVariant 本身约 8:1，两种模式都清晰。
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, 0, 0)
            })
            setOnClickListener {
                playClickFeedback(this, pressed = true)
                navigateFromDashboard(item.title)
            }
        }
    }

    private fun dashboardItems(): List<DashboardItem> {
        val shizukuReady = MaxHzShell.isReady()
        val accessibilityEnabled = MaxHzAccessibilityService.isEnabled(this)
        val appRuleCount = MaxHzPrefs.appRefreshRules(this).size
        val fpsRunning = MaxHzPrefs.fpsMonitorRunning(this)
        val nightApplied = MaxHzPrefs.nightWakeupGuardEnabled(this)
        val quickDoze = MaxHzPrefs.quickDozeEnabled(this)
        val batteryEnabled = MaxHzPrefs.batteryProtectionEnabled(this)
        val backgroundRequired = MaxHzPrefs.requiresBackgroundService(this)
        val backgroundRunning = isMaxHzServiceRunning()
        val backgroundError = backgroundErrorUi()
        return listOf(
            DashboardItem("Shizuku", if (shizukuReady) "已连接" else "未运行", ""),
            DashboardItem(
                "无障碍",
                when {
                    accessibilityEnabled -> "已开启"
                    appRuleCount > 0 -> "需要开启"
                    else -> "未启用"
                },
                ""
            ),
            DashboardItem("FPS", if (fpsRunning) "运行中" else "未运行", ""),
            DashboardItem("Night Guard", if (nightApplied) "已应用" else "未应用", ""),
            DashboardItem("Doze", if (quickDoze) "已启用" else "已关闭", ""),
            DashboardItem("电池保护", if (batteryEnabled) "已启用（${MaxHzPrefs.batteryLimit(this)}%）" else "未启用", ""),
            DashboardItem(
                "后台服务",
                if (backgroundRunning) "运行中" else "未运行",
                if (backgroundRequired) "当前配置需要后台服务" else "当前无需后台服务"
            ),
            DashboardItem("后台错误", when {
                !backgroundError.exists -> "无"
                backgroundError.recovered -> "历史错误"
                else -> "需要处理"
            }, "")
        )
    }

    private fun dashboardNeedsAttention(item: DashboardItem): Boolean {
        val state = item.state
        if (item.title == "后台服务" && state == "未运行" && item.detail.contains("无需")) return false
        return state.contains("未连接") ||
            state.contains("未运行") ||
            state.contains("最近错误") ||
            state.contains("需要处理") ||
            state.contains("可能残留") ||
            state.contains("需要开启") ||
            state.contains("需要无障碍") ||
            state.contains("候选") && !state.startsWith("0 ")
    }

    private fun dashboardStatusTile(item: DashboardItem): View {
        val stateTextColor = dashboardStatusTextColor(item)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(themeColors.surfaceContainer, dp(CARD_RADIUS_DP).toFloat())
            installClickFeedback(this)
            setOnClickListener {
                playClickFeedback(this, pressed = true)
                navigateFromDashboard(item.title)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(TextView(this@MaxHzActivity).apply {
                text = item.title
                textSize = 14f
                setTextColor(themeColors.onSurfaceVariant)
                includeFontPadding = false
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(TextView(this@MaxHzActivity).apply {
                text = item.state
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(stateTextColor)
                includeFontPadding = false
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun dashboardStatusTextColor(item: DashboardItem): Int {
        return when {
            item.state.contains("错误") || item.state.contains("需要处理") -> themeColors.error
            item.state.contains("已连接") || item.state.contains("已开启") || item.state.contains("已应用") || item.state.contains("运行中") -> themeColors.primary
            else -> themeColors.onSurfaceVariant
        }
    }

    private fun navigateFromDashboard(title: String) {
        when (title) {
            "Shizuku" -> pushDetail("系统诊断", skipScrollTop = true, targetDiagnosticKey = "Shizuku") { systemDiagnosticsSection("Shizuku") }
            "无障碍" -> {
                if (!MaxHzAccessibilityService.isEnabled(this)) {
                    AccessibilityHelper.openInstantSwitchSettings(this)
                } else {
                    pushDetail("分别设置单个应用程序") { appRefreshRateSection() }
                }
            }
            "FPS" -> pushDetail("实时 FPS 监视器") { refreshRateSection() }
            "Night Guard" -> pushDetail("待机异常唤醒排查") { nightWakeupGuardSection() }
            "Doze" -> pushDetail("快速的 Doze 模式") { quickDozeSection() }
            "电池保护" -> showBatteryProtectionDialog()
            "后台服务" -> pushDetail("系统诊断", skipScrollTop = true, targetDiagnosticKey = "后台服务状态") { systemDiagnosticsSection("后台服务状态") }
            "后台错误" -> pushDetail("系统诊断", skipScrollTop = true, targetDiagnosticKey = "后台错误") { systemDiagnosticsSection("后台错误") }
            else -> pushDetail("系统诊断") { systemDiagnosticsSection() }
        }
    }

    private fun shizukuOfflineHint(): ShizukuOfflineHint {
        return when (ShizukuSettings.getLastLaunchMode()) {
            ShizukuSettings.LaunchMethod.ROOT -> ShizukuOfflineHint(
                "设备已重启或服务已停止。请检查 Root 授权状态。",
                "请检查 Root 授权状态。"
            )
            ShizukuSettings.LaunchMethod.ADB -> {
                if (hasWirelessAdbKey()) {
                    ShizukuOfflineHint(
                        "设备重启后通常只需重新启动 Shizuku。一般无需重新配对。",
                        "通常只需重新启动 Shizuku。"
                    )
                } else {
                    ShizukuOfflineHint(
                        "设备重启后需要重新通过电脑启动 Shizuku。",
                        "需要通过电脑重新启动。"
                    )
                }
            }
            else -> ShizukuOfflineHint(
                "请启动 Shizuku 后重试。",
                "请启动 Shizuku 后重试。"
            )
        }
    }

    private fun hasWirelessAdbKey(): Boolean {
        return ShizukuSettings.getPreferences().contains("adbkey")
    }

    private fun appRefreshEnvironmentState(ruleCount: Int, accessibilityEnabled: Boolean): String {
        return when {
            ruleCount <= 0 -> "未配置"
            accessibilityEnabled -> "正常"
            else -> "需要无障碍"
        }
    }

    private fun isMaxHzServiceRunning(): Boolean {
        val activityManager = getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == MaxHzService::class.java.name }
    }

    protected fun systemDiagnosticsSection(focusTitle: String? = null): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            diagnosticTargetViews.clear()
            val items = diagnosticItems()
            val visibleItems = items.filter {
                it.title in DIAGNOSTIC_PERMISSION_TITLES ||
                    it.title in DIAGNOSTIC_FEATURE_TITLES ||
                    it.title in DIAGNOSTIC_BACKGROUND_TITLES
            }
            val problemCount = visibleItems.count { diagnosticSeverity(it) == "failure" }
            val warningCount = visibleItems.count { diagnosticSeverity(it) == "warning" }
            addView(card {
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MaxHzActivity).apply {
                        text = "系统诊断"
                        textSize = 18f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(themeColors.onSurface)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(button("复制诊断结果") {
                        copyTextToClipboard("MaxHz 诊断报告", buildDiagnosticReport(visibleItems))
                    })
                })
                addView(TextView(this@MaxHzActivity).apply {
                    text = if (problemCount == 0 && warningCount == 0) {
                        "全部正常"
                    } else {
                        "发现 ${problemCount} 个问题 / ${warningCount} 个提醒"
                    }
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(this@MaxHzActivity).apply {
                    text = "只检查当前状态，不自动修复、不授权、不执行系统命令。"
                    textSize = 12f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(0, dp(3), 0, 0)
                })
            })
            diagnosticGroupView("权限状态", visibleItems.filter { it.title in DIAGNOSTIC_PERMISSION_TITLES }, focusTitle)
                ?.let { addSpacedView(it, gap) }
            diagnosticGroupView("功能状态", visibleItems.filter { it.title in DIAGNOSTIC_FEATURE_TITLES }, focusTitle)
                ?.let { addSpacedView(it, gap) }
            diagnosticGroupView("后台状态", visibleItems.filter { it.title in DIAGNOSTIC_BACKGROUND_TITLES }, focusTitle)
                ?.let { addSpacedView(it, gap) }
        }
    }

    protected fun operationLogSection(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val logs = readOperationLogs()
            val successCount = logs.count { operationLogCategory(it) == "成功" }
            val warningCount = logs.count { operationLogCategory(it) == "警告" }
            val failureCount = logs.count { operationLogCategory(it) == "失败" }
            var selectedFilter = "全部"
            val chipContainer = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val logContainer = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            fun renderLogs() {
                chipContainer.removeAllViews()
                listOf("全部", "成功", "警告", "失败").forEachIndexed { index, label ->
                    chipContainer.addView(filterChip(label, selectedFilter == label) {
                        selectedFilter = label
                        renderLogs()
                    }, LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (index > 0) leftMargin = dp(6)
                    })
                }
                logContainer.removeAllViews()
                val visibleLogs = logs.filter { selectedFilter == "全部" || operationLogCategory(it) == selectedFilter }
                if (visibleLogs.isEmpty()) {
                    logContainer.addView(emptyText("暂无操作记录\n后续操作会显示在这里。"))
                } else {
                    visibleLogs.forEachIndexed { index, item ->
                        if (index > 0) logContainer.addView(divider())
                        logContainer.addView(operationLogRow(item))
                    }
                }
            }
            addView(card {
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MaxHzActivity).apply {
                        text = "最近操作日志"
                        textSize = 18f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(themeColors.onSurface)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(button("清空日志") {
                        clearOperationLogs()
                        renderDetail()
                    })
                    addView(button("导出最近日志") {
                        copyTextToClipboard("MaxHz 最近日志", buildOperationLogExport(logs))
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(8) })
                })
                addView(TextView(this@MaxHzActivity).apply {
                    text = "最近 50 条 · 成功 ${successCount} 条 · 警告 ${warningCount} 条 · 失败 ${failureCount} 条"
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(this@MaxHzActivity).apply {
                    text = "本地查看，不上传、不联网。"
                    textSize = 12f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(0, dp(2), 0, 0)
                })
            })
            addSpacedView(chipContainer, dp(10))
            addSpacedView(groupCard {
                addView(logContainer)
            }, gap)
            renderLogs()
        }
    }

    private fun diagnosticItems(): List<DiagnosticItem> {
        val shizukuReady = MaxHzShell.isReady()
        val accessibilityEnabled = MaxHzAccessibilityService.isEnabled(this)
        val overlayAllowed = Settings.canDrawOverlays(this)
        val ignoringBattery = getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true
        val writeSettings = Settings.System.canWrite(this)
        val nightEnabled = MaxHzPrefs.nightWakeupGuardEnabled(this)
        val nightPackages = MaxHzPrefs.nightWakeupGuardPackages(this).size
        val fpsRunning = MaxHzPrefs.fpsMonitorRunning(this)
        val appRuleCount = MaxHzPrefs.appRefreshRules(this).size
        val backgroundRequired = MaxHzPrefs.requiresBackgroundService(this)
        val backgroundRunning = isMaxHzServiceRunning()
        val shizukuOfflineHint = shizukuOfflineHint()
        val backgroundError = backgroundErrorUi()
        val residue = systemResidueUi()
        return listOf(
            DiagnosticItem(
                "Shizuku",
                if (shizukuReady) "正常" else "异常",
                if (shizukuReady) "已连接，权限通道可用。" else "Shizuku 未运行。${shizukuOfflineHint.detail}",
                if (shizukuReady) "无需处理。" else shizukuOfflineHint.suggestion
            ),
            DiagnosticItem(
                "无障碍服务",
                if (accessibilityEnabled) "正常" else if (appRuleCount > 0) "异常" else "未启用",
                if (accessibilityEnabled) "即时切换已开启。" else "无障碍即时切换未开启。",
                if (accessibilityEnabled) "无需处理。" else "请开启 MaxHz 无障碍服务。"
            ),
            DiagnosticItem(
                "悬浮窗权限",
                if (overlayAllowed) "正常" else if (fpsRunning) "异常" else "未启用",
                if (overlayAllowed) "允许显示 FPS 悬浮窗。" else "当前未允许显示在其他应用上层。",
                if (overlayAllowed) "无需处理。" else "请开启“显示在其他应用上层”。"
            ),
            DiagnosticItem(
                "忽略电池优化",
                if (ignoringBattery) "正常" else "建议观察",
                if (ignoringBattery) "系统不会强制优化本应用后台。" else "系统可能限制本应用后台服务存活。",
                if (ignoringBattery) "无需处理。" else "如果后台控制不稳定，可在系统电池设置中允许本应用后台运行。"
            ),
            DiagnosticItem(
                "Doze相关权限",
                if (shizukuReady || writeSettings) "正常" else "异常",
                "WRITE_SETTINGS：${if (writeSettings) "已允许" else "未允许"}；Shizuku：${if (shizukuReady) "已连接" else "未连接"}。",
                if (shizukuReady || writeSettings) "当前条件可用于页面状态判断。" else "启动 Shizuku 或手动授权后再执行 Doze 相关功能。"
            ),
            DiagnosticItem(
                "Doze",
                if (MaxHzPrefs.quickDozeEnabled(this)) "正常" else "未启用",
                "Quick Doze：${if (MaxHzPrefs.quickDozeEnabled(this)) "已启用" else "已关闭"}。",
                if (MaxHzPrefs.quickDozeEnabled(this)) "无需处理。" else "未启用时使用系统默认 Doze。"
            ),
            DiagnosticItem(
                "电池保护",
                if (MaxHzPrefs.batteryProtectionEnabled(this)) "正常" else "未启用",
                if (MaxHzPrefs.batteryProtectionEnabled(this)) {
                    "电池保护已启用，上限 ${MaxHzPrefs.batteryLimit(this)}%。"
                } else {
                    "电池保护未启用。"
                },
                if (MaxHzPrefs.batteryProtectionEnabled(this)) "无需处理。" else "未启用时由系统电池策略接管。"
            ),
            DiagnosticItem(
                "Night Guard执行环境",
                when {
                    nightEnabled && !shizukuReady -> "异常"
                    nightEnabled || nightPackages > 0 -> "正常"
                    else -> "未启用"
                },
                "名单 ${nightPackages} 个；状态：${if (nightEnabled) "已应用" else "未应用"}。",
                if (!shizukuReady && (nightEnabled || nightPackages > 0)) "Shizuku 未连接时无法应用或恢复护栏。" else "扫描不会自动限制后台，应用护栏前可继续检查名单。"
            ),
            DiagnosticItem(
                "FPS Monitor运行条件",
                when {
                    fpsRunning && (!overlayAllowed || !shizukuReady) -> "异常"
                    fpsRunning -> "正常"
                    else -> "未启用"
                },
                "悬浮窗：${if (overlayAllowed) "已允许" else "未允许"}；Shizuku：${if (shizukuReady) "已连接" else "未连接"}。",
                if (fpsRunning && (!overlayAllowed || !shizukuReady)) "关闭后重新授权，再手动开启 FPS Monitor。" else "未开启时不会采样 FPS。"
            ),
            DiagnosticItem(
                "每 App 规则环境检查",
                when {
                    appRuleCount <= 0 -> "未启用"
                    accessibilityEnabled -> "正常"
                    else -> "建议观察"
                },
                "每 App 规则 ${appRuleCount} 个；无障碍：${if (accessibilityEnabled) "已开启" else "未开启"}。",
                when {
                    appRuleCount <= 0 -> "未配置每 App 规则时无需处理。"
                    accessibilityEnabled -> "规则会通过无障碍事件自动切换。"
                    else -> "每 App 规则需要无障碍服务。当前无障碍未开启，规则不会自动切换。"
                }
            ),
            DiagnosticItem(
                "刷新率控制条件",
                when {
                    appRuleCount > 0 && !accessibilityEnabled -> "异常"
                    appRuleCount > 0 -> "正常"
                    MaxHzPrefs.controlEnabled(this@MaxHzActivity) -> "正常"
                    else -> "未启用"
                },
                "每 App 规则 ${appRuleCount} 个；后台控制：${if (MaxHzPrefs.controlEnabled(this@MaxHzActivity)) "已开启" else "已关闭"}。",
                if (appRuleCount > 0 && !accessibilityEnabled) "建议开启无障碍即时切换，避免进入/退出 App 延迟。" else "当前不需要自动修复。"
            ),
            DiagnosticItem(
                "后台服务状态",
                when {
                    backgroundRequired && !backgroundRunning -> "异常"
                    backgroundRunning -> "正常"
                    else -> "未启用"
                },
                "${if (backgroundRunning) "运行中" else "未运行"}\n${if (backgroundRequired) "当前配置需要后台服务" else "当前无需后台服务"}",
                when {
                    backgroundRequired && !backgroundRunning ->
                        "当前配置需要后台服务，但服务未运行。建议刷新状态，或检查 Shizuku / 系统后台限制。"
                    backgroundRequired ->
                        "后台服务由当前配置自动管理，无需手动启动或停止。"
                    else ->
                        "当前无需后台服务。"
                }
            ),
            DiagnosticItem(
                "系统残留检测",
                when {
                    residue.hasResidue -> "建议观察"
                    residue.checked -> "正常"
                    else -> "未确认"
                },
                residue.summary + if (residue.raw.isNotBlank()) "\n高级详情：${residue.raw}" else "",
                when {
                    residue.hasResidue -> "建议进入“总恢复”执行恢复系统默认接管。Night Guard 卸载前名单丢失时，需要手动检查曾被限制的 App。"
                    residue.checked -> "未发现明显残留，无需处理。"
                    else -> "如曾卸载重装，建议点击首页“检测残留”或在总恢复页执行检测。"
                }
            ),
            DiagnosticItem(
                "后台错误",
                when {
                    !backgroundError.exists -> "正常"
                    backgroundError.recovered -> "历史错误（当前已恢复）"
                    else -> "异常"
                },
                when {
                    !backgroundError.exists -> "当前没有后台错误。"
                    backgroundError.recovered ->
                        "上次失败原因：${backgroundError.reason}\n发生时间：${backgroundError.time}\n高级详情：${backgroundError.raw}"
                    else ->
                        "当前原因：${backgroundError.reason}\n高级详情：${backgroundError.raw}"
                },
                when {
                    !backgroundError.exists -> "无需处理。"
                    backgroundError.recovered -> backgroundError.suggestion
                    else -> "当前原因：${backgroundError.reason}；建议：${backgroundError.suggestion}"
                }
            )
        )
    }

    private fun diagnosticGroupView(title: String, items: List<DiagnosticItem>, focusTitle: String? = null): View? {
        if (items.isEmpty()) return null
        return groupCard {
            addView(TextView(this@MaxHzActivity).apply {
                text = title
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColors.onSurface)
                setPadding(0, dp(10), 0, dp(4))
            })
            items.forEachIndexed { index, item ->
                if (index > 0) addView(divider())
                val focused = item.title == focusTitle
                val row = expandableDiagnosticRow(item, expandedInitially = focused).apply {
                    tag = "diagnostic:${item.title}"
                }
                diagnosticTargetViews[item.title] = row
                addView(row)
            }
        }
    }

    private fun scrollToDiagnosticTarget(key: String) {
        val scroller = modalScrollView ?: if (::scrollView.isInitialized) scrollView else null
        if (key == "Shizuku") {
            scroller?.scrollTo(0, 0)
            return
        }
        val target = diagnosticTargetViews[key] ?: return
        scrollToDiagnosticTarget(target)
    }

    private fun scrollToDiagnosticTarget(target: View) {
        val scroller = modalScrollView ?: if (::scrollView.isInitialized) scrollView else null
        if (scroller == null) return
        val scrollLocation = IntArray(2)
        val targetLocation = IntArray(2)
        scroller.getLocationInWindow(scrollLocation)
        target.getLocationInWindow(targetLocation)
        val targetY = (scroller.scrollY + targetLocation[1] - scrollLocation[1] - dp(24)).coerceAtLeast(0)
        scroller.scrollTo(0, targetY)
    }

    private fun diagnosticSeverity(item: DiagnosticItem): String {
        return when (item.state) {
            "异常" -> "failure"
            "历史错误（当前已恢复）" -> "warning"
            "建议观察", "未启用" -> "warning"
            "正常" -> "normal"
            else -> "disabled"
        }
    }

    private fun diagnosticStyle(item: DiagnosticItem): StatusStyle {
        return when (diagnosticSeverity(item)) {
            "failure" -> statusStyleFor("异常")
            "warning" -> statusStyleFor("建议观察")
            "normal" -> statusStyleFor("正常")
            else -> statusStyleFor("不适用")
        }
    }

    private fun diagnosticBadge(item: DiagnosticItem): TextView {
        return TextView(this).apply {
            text = diagnosticDisplayState(item)
            textSize = 11f
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = dp(26)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            maxLines = 1
            setTextColor(themeColors.onSurfaceVariant)
            background = rounded(Color.TRANSPARENT, dp(13).toFloat())
        }
    }

    private fun diagnosticDisplayTitle(item: DiagnosticItem): String {
        return when (item.title) {
            "无障碍服务" -> "无障碍"
            "悬浮窗权限" -> "悬浮窗"
            "忽略电池优化" -> "电池优化"
            "FPS Monitor运行条件" -> "FPS"
            "刷新率控制条件" -> "刷新率"
            "Night Guard执行环境" -> "Night Guard"
            "后台服务状态" -> "后台服务"
            else -> item.title
        }
    }

    private fun diagnosticDisplayState(item: DiagnosticItem): String {
        if (item.state == "历史错误（当前已恢复）") return item.state
        return when (diagnosticSeverity(item)) {
            "failure" -> "需要处理"
            "warning" -> if (item.state == "未启用") "未启用" else "建议处理"
            "normal" -> "正常"
            else -> "不适用"
        }
    }

    private fun operationLogCategory(item: OperationLogItem): String {
        val text = "${item.event} ${item.result}"
        return when {
            text.contains("失败") ||
                text.contains("异常") ||
                text.contains("错误") ||
                text.contains("未通过") ||
                text.contains("未连接") ||
                text.contains("权限缺失") -> "失败"

            text.contains("警告") ||
                text.contains("提醒") ||
                text.contains("建议") ||
                text.contains("未启用") ||
                text.contains("未确认") ||
                text.contains("等待") -> "警告"

            text.contains("成功") ||
                text.contains("已应用") ||
                text.contains("已恢复") ||
                text.contains("已开启") ||
                text.contains("已关闭") ||
                text.contains("已启动") ||
                text.contains("已停止") ||
                text.contains("已清除") ||
                text.contains("正常") -> "成功"

            else -> "信息"
        }
    }

    private fun operationLogStyle(category: String): StatusStyle {
        return when (category) {
            "成功" -> statusStyleFor("正常")
            "警告" -> statusStyleFor("建议观察")
            "失败" -> statusStyleFor("异常")
            else -> statusStyleFor("不适用")
        }
    }

    private fun operationLogEventTitle(item: OperationLogItem): String {
        return item.event
            .removeSuffix("成功")
            .removeSuffix("失败")
            .trim()
            .ifBlank { item.event }
    }

    private fun operationLogStatusText(item: OperationLogItem): String {
        val category = operationLogCategory(item)
        return when (category) {
            "失败" -> if (item.result.startsWith("失败")) item.result else "失败"
            "警告" -> if (item.result.startsWith("警告")) item.result else "警告"
            "成功" -> item.result.ifBlank { "已记录" }
            else -> "信息"
        }
    }

    private fun operationLogBrief(item: OperationLogItem): String {
        val result = item.result.trim().ifBlank { "已记录" }
        return when (operationLogCategory(item)) {
            "失败" -> if (result.startsWith("失败")) result else "失败：$result"
            "警告" -> if (result.startsWith("警告")) result else "提醒：$result"
            else -> result
        }
    }

    private fun userFriendlyBackgroundError(raw: String): String {
        val text = raw.lowercase(Locale.US)
        return when {
            raw.isBlank() -> "当前没有后台错误"
            text.contains("shizuku") && (text.contains("未运行") || text.contains("未授权") || text.contains("denied") || text.contains("permission")) ->
                "Shizuku 未连接或未授权"
            text.contains("deadobject") || text.contains("binder") ->
                "Shizuku 连接已断开"
            text.contains("无障碍") || text.contains("accessibility") ->
                "无障碍未开启"
            text.contains("悬浮窗") || text.contains("overlay") || text.contains("system_alert_window") ->
                "悬浮窗权限缺失"
            text.contains("appops") || text.contains("securityexception") || text.contains("permission denied") ->
                "权限不足"
            text.contains("timeout") || text.contains("超时") ->
                "系统响应超时"
            else -> "后台执行失败，请查看高级详情"
        }
    }

    private fun backgroundErrorUi(): BackgroundErrorUi {
        val raw = MaxHzPrefs.resultText(this, BACKGROUND_ERROR_RESULT_KEY).orEmpty().trim()
            .lineSequence()
            .map { replacePackageNamesForUser(it) }
            .joinToString("\n")
        if (raw.isBlank()) {
            return BackgroundErrorUi(
                exists = false,
                recovered = false,
                reason = "当前没有后台错误",
                suggestion = "无需处理。",
                time = "未知",
                raw = ""
            )
        }
        val reason = userFriendlyBackgroundError(raw)
        val recovered = backgroundErrorRecovered(reason)
        val time = readOperationLogs().firstOrNull { it.event == "后台执行失败" }?.time ?: "未知"
        return BackgroundErrorUi(
            exists = true,
            recovered = recovered,
            reason = reason,
            suggestion = if (recovered) {
                "当前环境已恢复。如不再需要，可点击“清除后台错误”。"
            } else {
                backgroundErrorSuggestion(reason)
            },
            time = time,
            raw = raw
        )
    }

    private fun systemResidueUi(): SystemResidueUi {
        val raw = MaxHzPrefs.resultText(this, SYSTEM_RESIDUE_RESULT_KEY).orEmpty().trim()
            .lineSequence()
            .map { replacePackageNamesForUser(it) }
            .filterNot { isIgnoredBatteryProtectionResidueLine(it) }
            .joinToString("\n")
        if (raw.isBlank()) {
            return SystemResidueUi(
                checked = false,
                hasResidue = false,
                state = "未检测",
                summary = "尚未检测系统残留。卸载重装后建议检测一次。",
                raw = ""
            )
        }
        val hasResidue = raw.contains("发现可能系统残留") || raw.contains("可能残留：")
        val clean = raw.contains("未发现明显系统残留")
        return SystemResidueUi(
            checked = true,
            hasResidue = hasResidue,
            state = when {
                hasResidue -> "可能残留"
                clean -> "未发现"
                else -> "已检测"
            },
            summary = when {
                hasResidue -> "检测到可能由卸载前旧功能留下的系统设置。"
                clean -> "未发现明显系统残留。"
                else -> "已有检测结果，请查看高级详情。"
            },
            raw = raw
        )
    }

    private fun backgroundErrorRecovered(reason: String): Boolean {
        return when (reason) {
            "无障碍未开启" -> MaxHzAccessibilityService.isEnabled(this)
            "Shizuku 未连接或未授权",
            "Shizuku 连接已断开" -> MaxHzShell.isReady()
            "悬浮窗权限缺失" -> Settings.canDrawOverlays(this)
            "权限不足",
            "系统响应超时" -> MaxHzShell.isReady()
            else -> false
        }
    }

    private fun backgroundErrorSuggestion(reason: String): String {
        return when (reason) {
            "无障碍未开启" -> "请开启 MaxHz 无障碍服务后重试。"
            "Shizuku 未连接或未授权",
            "Shizuku 连接已断开" -> "请启动 Shizuku 并授予本应用权限。"
            "悬浮窗权限缺失" -> "请开启“显示在其他应用上层”。"
            "权限不足" -> "请检查 Shizuku / ADB 权限后重试。"
            "系统响应超时" -> "请稍后重试，或检查系统后台限制。"
            else -> "请查看高级详情，必要时复制诊断信息反馈。"
        }
    }

    private fun buildDiagnosticReport(items: List<DiagnosticItem>): String {
        val versionName = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")
        return buildString {
            appendLine("MaxHz 诊断报告")
            appendLine("版本：$versionName")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Shizuku：${if (MaxHzShell.isReady()) "已连接" else "未连接"}")
            appendLine("无障碍：${if (MaxHzAccessibilityService.isEnabled(this@MaxHzActivity)) "已开启" else "未开启"}")
            appendLine("悬浮窗：${if (Settings.canDrawOverlays(this@MaxHzActivity)) "已授权" else "未授权"}")
            appendLine("FPS：${fpsMonitorState()}")
            appendLine("Night Guard：${nightWakeupGuardState()}")
            appendLine("每 App 规则：${appRefreshEnvironmentState(MaxHzPrefs.appRefreshRules(this@MaxHzActivity).size, MaxHzAccessibilityService.isEnabled(this@MaxHzActivity))}")
            appendLine("最近错误：${userFriendlyBackgroundError(MaxHzPrefs.resultText(this@MaxHzActivity, BACKGROUND_ERROR_RESULT_KEY).orEmpty())}")
            appendLine()
            appendLine("诊断项：")
            items.forEach {
                appendLine("- ${diagnosticDisplayTitle(it)}：${diagnosticDisplayState(it)}；建议：${it.suggestion}")
            }
        }
    }

    private fun buildOperationLogExport(logs: List<OperationLogItem>): String {
        return buildString {
            appendLine("MaxHz 最近操作日志")
            appendLine("最近 ${logs.size.coerceAtMost(OPERATION_LOG_LIMIT)} 条")
            logs.take(OPERATION_LOG_LIMIT).forEach {
                appendLine("${it.time}  ${operationLogEventTitle(it)}  ${operationLogBrief(it)}")
            }
            if (logs.isEmpty()) appendLine("暂无操作记录")
        }
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun expandableDiagnosticRow(item: DiagnosticItem, expandedInitially: Boolean = false): View {
        var expanded = expandedInitially
        lateinit var titleView: TextView
        lateinit var stateView: TextView
        lateinit var suggestionView: TextView
        lateinit var detailView: TextView
        fun update() {
            titleView.text = "${diagnosticDisplayTitle(item)} ${if (expanded) "▾" else "▸"}"
            stateView.text = diagnosticDisplayState(item)
            suggestionView.text = item.suggestion
            detailView.visibility = if (expanded) View.VISIBLE else View.GONE
            detailView.text = "高级详情：${item.detail}"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            installClickFeedback(this)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                expanded = !expanded
                update()
                playClickFeedback(this)
            }
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                titleView = TextView(this@MaxHzActivity).apply {
                    textSize = 15.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(themeColors.onSurface)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(diagnosticBadge(item), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) })
            })
            stateView = TextView(this@MaxHzActivity).apply {
                textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColors.onSurfaceVariant)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            addView(stateView)
            suggestionView = TextView(this@MaxHzActivity).apply {
                textSize = 12f
                setTextColor(themeColors.onSurfaceVariant)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            addView(suggestionView)
            detailView = TextView(this@MaxHzActivity).apply {
                textSize = 12f
                setTextColor(themeColors.onSurfaceVariant)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(8), 0, 0)
            }
            addView(detailView)
            update()
        }
    }

    private fun operationLogRow(item: OperationLogItem): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(11), 0, dp(11))
            addView(TextView(this@MaxHzActivity).apply {
                text = item.time
                textSize = 12.5f
                setTextColor(themeColors.onSurfaceVariant)
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(50), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MaxHzActivity).apply {
                    text = operationLogEventTitle(item)
                    textSize = 14.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(themeColors.onSurface)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(this@MaxHzActivity).apply {
                    text = operationLogStatusText(item)
                    textSize = 12.3f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(themeColors.onSurfaceVariant)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                })
                addView(TextView(this@MaxHzActivity).apply {
                    text = operationLogBrief(item)
                    textSize = 12f
                    setTextColor(themeColors.onSurfaceVariant)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun lowPowerStartSection(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionCard(
                "日用省电模式",
                "系统省电 + 自适应高刷 + Quick Doze，适合日用。",
                "应用",
                "日用省电模式",
                "开启系统省电、自适应高刷和 Quick Doze，只清理明显冲突项，不关闭后台数据。",
	                {
                    val result = MaxHzController.applyDailySaver(
                        MaxHzPrefs.quickDozeMaintenanceMinutes(this@MaxHzActivity),
                        MaxHzPrefs.quickDozeNoMaintenance(this@MaxHzActivity)
                    )
                    if (result.code != 0) return@actionCard result
                    if (!isSystemPowerSaveModeOn()) {
                        return@actionCard MaxHzShell.Result(
                            4,
                            listOf(
                                result.out,
                                "STEP=ui_power_saver_verify\nSOURCE=PowerManager.isPowerSaveMode\nEXPECTED=true\nACTUAL=false\nRESULT=FAIL"
                            ).filter { it.isNotBlank() }.joinToString("\n"),
                            result.err
                        )
                    }
                    MaxHzPrefs.setMMainMode(this@MaxHzActivity, "off")
                    MaxHzPrefs.setMRefresh120Enabled(this@MaxHzActivity, false)
                    MaxHzPrefs.setCompatibleSaverEnabled(this@MaxHzActivity, true)
                    MaxHzPrefs.setQuickDozeEnabled(this@MaxHzActivity, true)
                    updateMaxHzServiceState()
                    result
                },
                "恢复",
	                "恢复兼容省电",
	                "恢复省电、刷新率和 Quick Doze 参数，让系统重新接管。",
	                {
	                    MaxHzController.restoreDailySaver().also {
	                        if (it.code == 0) {
	                            MaxHzPrefs.setCompatibleSaverEnabled(this@MaxHzActivity, false)
	                            MaxHzPrefs.setQuickDozeEnabled(this@MaxHzActivity, false)
	                            updateMaxHzServiceState()
	                        }
	                    }
                },
                { dailySaverState() },
                actionConfirmation = { dailySaverConflictConfirmation() }
            ))
        }
    }

    protected fun openQuickDozeSection(): View = quickDozeSection()

    private fun quickDozeSection(): View {
        // Galaxy MaxHz 式独立设置页：主开关 + 无需维护 + 维护间隔 + 管理电池使用
        val resultKey = "quick_doze"
        val resultView = resultText(resultKey)
        lateinit var masterSwitch: MaterialSwitch
        lateinit var masterStateText: TextView
        lateinit var intervalValueView: TextView
        lateinit var noMaintenanceSwitch: MaterialSwitch
        var intervalSeekBar: SeekBar? = null
        var suppressMasterSwitch = false

        fun applyQuickDozeResult(): MaxHzShell.Result {
            return MaxHzController.applyQuickDoze(
                MaxHzPrefs.quickDozeMaintenanceMinutes(this),
                MaxHzPrefs.quickDozeNoMaintenance(this)
            ).also {
                if (it.code == 0) {
                    MaxHzPrefs.setQuickDozeEnabled(this, true)
                    updateMaxHzServiceState()
                }
            }
        }

        fun updateIntervalText() {
            intervalValueView.text = if (MaxHzPrefs.quickDozeNoMaintenance(this)) {
                "已停用（无需维护）"
            } else {
                "${MaxHzPrefs.quickDozeMaintenanceMinutes(this)} min."
            }
            intervalSeekBar?.let { seekBar ->
                seekBar.isEnabled = !MaxHzPrefs.quickDozeNoMaintenance(this)
                seekBar.progress = (MaxHzPrefs.quickDozeMaintenanceMinutes(this) / 10).coerceIn(1, 12) - 1
            }
        }

        fun reapplyIfEnabled(onResult: ((MaxHzShell.Result) -> Unit)? = null) {
            if (!MaxHzPrefs.quickDozeEnabled(this)) return
            runCommand(
                resultView,
                "更新 Doze 参数",
                "按新的维护设置重新写入 Doze 参数。",
                onResult = onResult
            ) { applyQuickDozeResult() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // 主开关卡片（仿 Galaxy MaxHz 顶部"关/开"大开关）
            val topDescView = TextView(this@MaxHzActivity).apply {
                text = "熄屏时快速进入 doze 模式，以改善待机耗电。\n设备解锁时 doze 模式自动停用。"
                textSize = 12.5f
                setTextColor(themeColors.onSurfaceVariant)
                setLineSpacing(0f, 1.25f)
                includeFontPadding = false
                visibility = if (detailSubtitlesExpanded) View.VISIBLE else View.GONE
            }
            addView(MaterialCardView(this@MaxHzActivity).apply {
                applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        masterStateText = TextView(this@MaxHzActivity).apply {
                            text = if (MaxHzPrefs.quickDozeEnabled(this@MaxHzActivity)) "开" else "关"
                            textSize = 16f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(themeColors.onSurface)
                        }
                        addView(masterStateText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        masterSwitch = MaterialSwitch(this@MaxHzActivity).apply {
                            isChecked = MaxHzPrefs.quickDozeEnabled(this@MaxHzActivity)
                            LiquidGlassTheme.styleIosSwitch(this, isDarkMode())
                            setOnCheckedChangeListener { _, isChecked ->
                                if (suppressMasterSwitch) return@setOnCheckedChangeListener
                                masterStateText.text = if (isChecked) "开" else "关"
                                if (isChecked) {
                                    runCommand(
                                        resultView,
                                        "开启快速待机",
                                        "启用系统 Doze 并写入更快进入待机的参数；不修改 App Doze 白名单。",
                                        onResult = { result ->
                                            if (result.code != 0) {
                                                suppressMasterSwitch = true
                                                masterSwitch.isChecked = false
                                                suppressMasterSwitch = false
                                                masterStateText.text = "关"
                                            }
                                        }
                                    ) { applyQuickDozeResult() }
                                } else {
                                    runCommand(
                                        resultView,
                                        "恢复快速待机",
                                        "清理本功能写入的 Doze 参数，让系统默认待机策略接管。",
                                        onResult = { result ->
                                            if (result.code != 0) {
                                                suppressMasterSwitch = true
                                                masterSwitch.isChecked = true
                                                suppressMasterSwitch = false
                                                masterStateText.text = "开"
                                            }
                                        }
                                    ) {
                                        MaxHzController.restoreQuickDoze().also {
                                            if (it.code == 0) {
                                                MaxHzPrefs.setQuickDozeEnabled(this@MaxHzActivity, false)
                                                updateMaxHzServiceState()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        addView(masterSwitch)
                    })
                    addSpacedView(topDescView, dp(4))
                })
                bindOptionBSubtitle(this, topDescView)
            })

            // 维护设置卡片：无需维护开关 + 初始维护窗口间隔
            addSpacedView(MaterialCardView(this@MaxHzActivity).apply {
                applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(6), dp(16), dp(10))
                    // 无需维护
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                        val noMaintSub = TextView(this@MaxHzActivity).apply {
                            text = "节省最多的电池"
                            textSize = 12.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(LinearLayout(this@MaxHzActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(TextView(this@MaxHzActivity).apply {
                                text = "无需维护"
                                textSize = 15.5f
                                setTextColor(themeColors.onSurface)
                            })
                            addView(noMaintSub)
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        var suppressNoMaintenance = false
                        noMaintenanceSwitch = MaterialSwitch(this@MaxHzActivity).apply {
                            isChecked = MaxHzPrefs.quickDozeNoMaintenance(this@MaxHzActivity)
                            LiquidGlassTheme.styleIosSwitch(this, isDarkMode())
                            setOnCheckedChangeListener { _, isChecked ->
                                if (suppressNoMaintenance) return@setOnCheckedChangeListener
                                val previous = MaxHzPrefs.quickDozeNoMaintenance(this@MaxHzActivity)
                                MaxHzPrefs.setQuickDozeNoMaintenance(this@MaxHzActivity, isChecked)
                                updateIntervalText()
                                reapplyIfEnabled { result ->
                                    if (result.code != 0) {
                                        MaxHzPrefs.setQuickDozeNoMaintenance(this@MaxHzActivity, previous)
                                        suppressNoMaintenance = true
                                        noMaintenanceSwitch.isChecked = previous
                                        suppressNoMaintenance = false
                                        updateIntervalText()
                                    }
                                }
                            }
                        }
                        addView(noMaintenanceSwitch)
                        bindOptionBSubtitle(this, noMaintSub)
                    })
                    // 分隔线
                    addView(View(this@MaxHzActivity).apply {
                        setBackgroundColor(themeColors.outline)
                        alpha = 0.4f
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                    // 初始维护窗口间隔：仿 Galaxy MaxHz 页面内滑动条
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(10), 0, dp(4))
                        addView(LinearLayout(this@MaxHzActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(TextView(this@MaxHzActivity).apply {
                                text = "初始维护窗口间隔"
                                textSize = 15.5f
                                setTextColor(themeColors.onSurface)
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            intervalValueView = TextView(this@MaxHzActivity).apply {
                                textSize = 14f
                                setTextColor(themeColors.primary)
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                            }
                            addView(intervalValueView)
                        })
                        val intervalSub = TextView(this@MaxHzActivity).apply {
                            text = "间隔越长，电池消耗越少"
                            textSize = 12.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(intervalSub)
                        intervalSeekBar = SeekBar(this@MaxHzActivity).apply {
                            max = 11
                            progress = (MaxHzPrefs.quickDozeMaintenanceMinutes(this@MaxHzActivity) / 10).coerceIn(1, 12) - 1
                            setPadding(0, dp(4), 0, 0)
                            var previousMinutes = MaxHzPrefs.quickDozeMaintenanceMinutes(this@MaxHzActivity)
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                    if (!fromUser) return
                                    val minutes = (progress + 1) * 10
                                    MaxHzPrefs.setQuickDozeMaintenanceMinutes(this@MaxHzActivity, minutes)
                                    updateIntervalText()
                                }

                                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                                    previousMinutes = MaxHzPrefs.quickDozeMaintenanceMinutes(this@MaxHzActivity)
                                }

                                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                                    if (MaxHzPrefs.quickDozeNoMaintenance(this@MaxHzActivity)) {
                                        Toast.makeText(this@MaxHzActivity, "已开启“无需维护”，无需设置间隔。", Toast.LENGTH_SHORT).show()
                                        return
                                    }
                                    reapplyIfEnabled { result ->
                                        if (result.code != 0) {
                                            MaxHzPrefs.setQuickDozeMaintenanceMinutes(this@MaxHzActivity, previousMinutes)
                                            updateIntervalText()
                                        }
                                    }
                                }
                            })
                        }
                        addView(intervalSeekBar, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ))
                        addView(LinearLayout(this@MaxHzActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(TextView(this@MaxHzActivity).apply {
                                text = "10 分钟"
                                textSize = 11.5f
                                setTextColor(themeColors.onSurfaceVariant)
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            addView(TextView(this@MaxHzActivity).apply {
                                text = "120 分钟"
                                textSize = 11.5f
                                gravity = Gravity.END
                                setTextColor(themeColors.onSurfaceVariant)
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        })
                        installClickFeedback(this)
                        setOnClickListener {
                            playClickFeedback(this)
                            if (MaxHzPrefs.quickDozeNoMaintenance(this@MaxHzActivity)) {
                                Toast.makeText(this@MaxHzActivity, "已开启“无需维护”，无需设置间隔。", Toast.LENGTH_SHORT).show()
                            }
                        }
                        bindOptionBSubtitle(this, intervalSub)
                    })
                })
            }, gap)

            // 管理电池使用卡片（后台保护名单 + 最近的待机记录 + 系统电池优化入口）
            val batteryMgmtSubtitles = mutableListOf<View>()
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(6))
                addView(TextView(this@MaxHzActivity).apply {
                    text = "管理电池使用"
                    textSize = 12.5f
                    setTextColor(themeColors.primary)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(createOptionBTogglePill { batteryMgmtSubtitles })
            })
            addView(MaterialCardView(this@MaxHzActivity).apply {
                applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(4), dp(16), dp(4))
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        installClickFeedback(this)
                        setPadding(0, dp(8), 0, dp(8))
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "检查当前待机状态"
                            textSize = 15.5f
                            setTextColor(themeColors.onSurface)
                        })
                        val sub = TextView(this@MaxHzActivity).apply {
                            text = "确认参数已配置，或手机当前是否已经进入待机省电。"
                            textSize = 12.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(sub)
                        batteryMgmtSubtitles.add(sub)
                        bindOptionBSubtitle(this, sub)
                        setOnClickListener {
                            playClickFeedback(this)
                            runCommand(
                                resultView,
                                "检查当前待机状态",
                                "只读取系统当前待机状态，不修改任何设置。",
                                refreshServiceAfter = false
                            ) { MaxHzController.readQuickDozeStatus() }
                        }
                    })
                    addView(View(this@MaxHzActivity).apply {
                        setBackgroundColor(themeColors.outline)
                        alpha = 0.4f
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        installClickFeedback(this)
                        setPadding(0, dp(8), 0, dp(8))
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "待机异常唤醒排查"
                            textSize = 15.5f
                            setTextColor(themeColors.onSurface)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        })
                        val sub = TextView(this@MaxHzActivity).apply {
                            text = "多维诊断后台频繁唤醒与异常持锁应用，一键压制。"
                            textSize = 12.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(sub)
                        batteryMgmtSubtitles.add(sub)
                        bindOptionBSubtitle(this, sub)
                        setOnClickListener {
                            playClickFeedback(this)
                            pushDetail("待机异常唤醒排查") { nightWakeupGuardSection() }
                        }
                    })
                    addView(View(this@MaxHzActivity).apply {
                        setBackgroundColor(themeColors.outline)
                        alpha = 0.4f
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        installClickFeedback(this)
                        setPadding(0, dp(8), 0, dp(8))
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "后台保护名单"
                            textSize = 15.5f
                            setTextColor(themeColors.onSurface)
                        })
                        val sub = TextView(this@MaxHzActivity).apply {
                            text = "控制哪些应用不被系统严格限制后台。"
                            textSize = 12.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(sub)
                        batteryMgmtSubtitles.add(sub)
                        bindOptionBSubtitle(this, sub)
                        setOnClickListener {
                            playClickFeedback(this)
                            pushDetail("后台保护名单") { dozeWhitelistSection() }
                        }
                    })
                    addView(View(this@MaxHzActivity).apply {
                        setBackgroundColor(themeColors.outline)
                        alpha = 0.4f
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        installClickFeedback(this)
                        setPadding(0, dp(8), 0, dp(8))
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "最近待机记录"
                            textSize = 15.5f
                            setTextColor(themeColors.onSurface)
                        })
                        val sub = TextView(this@MaxHzActivity).apply {
                            text = "查看手机最近是否进入待机省电状态。"
                            textSize = 12.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(sub)
                        batteryMgmtSubtitles.add(sub)
                        bindOptionBSubtitle(this, sub)
                        setOnClickListener {
                            playClickFeedback(this)
                            pushDetail("最近待机记录") { dozeHistorySection() }
                        }
                    })
                    addView(View(this@MaxHzActivity).apply {
                        setBackgroundColor(themeColors.outline)
                        alpha = 0.4f
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        installClickFeedback(this)
                        setPadding(0, dp(8), 0, dp(8))
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "管理电池使用量"
                            textSize = 15.5f
                            setTextColor(themeColors.onSurface)
                        })
                        val sub = TextView(this@MaxHzActivity).apply {
                            text = "打开系统电池优化页面，查看或调整应用电池用量。"
                            textSize = 12.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(sub)
                        batteryMgmtSubtitles.add(sub)
                        bindOptionBSubtitle(this, sub)
                        setOnClickListener {
                            playClickFeedback(this)
                            openBatteryUsageSettings()
                        }
                    })
                })
            })

            addSpacedView(resultView, dp(12))
            updateIntervalText()
        }
    }

    protected fun dozeWhitelistSection(): View {
        val resultKey = "doze_whitelist"
        val resultView = resultText(resultKey)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        var currentWhitelist = emptySet<String>()
        var appFilter = AppListFilter.ALL
        var searchQuery = ""
        lateinit var filterContainer: LinearLayout
        var redrawFilters: () -> Unit = {}

        fun render(whitelist: Set<String>) {
            val userRestricted = MaxHzPrefs.dozeRestrictedPackages(this@MaxHzActivity)
            val effectiveWhitelist = whitelist - userRestricted
            currentWhitelist = effectiveWhitelist
            listContainer.removeAllViews()
            listContainer.addView(emptyText("正在加载应用列表..."))
            lifecycleScope.launch {
                val apps = withContext(Dispatchers.IO) {
                    loadDozeWhitelistTargetApps(effectiveWhitelist)
                }.filter { app ->
                    when (appFilter) {
                        AppListFilter.USER -> !app.systemApp
                        AppListFilter.SYSTEM -> app.systemApp
                        AppListFilter.ALL -> true
                    }
                }.filter { app ->
                    if (searchQuery.isBlank()) true
                    else {
                        val query = searchQuery.lowercase(Locale.getDefault())
                        app.label.lowercase(Locale.getDefault()).contains(query) ||
                            app.packageName.lowercase(Locale.getDefault()).contains(query)
                    }
                }
                if (currentWhitelist != effectiveWhitelist) return@launch
                listContainer.removeAllViews()
                if (apps.isEmpty()) {
                    listContainer.addView(emptyText("没有找到匹配的应用。"))
                    return@launch
                }
                apps.forEachIndexed { index, app ->
                    if (index > 0) listContainer.addView(divider())
                    val isChecked = app.packageName in currentWhitelist
                    val initialStatus = when {
                        isChecked -> "开启保护"
                        app.systemApp && app.packageName in userRestricted -> "已限制后台 (AppOps)"
                        else -> "关闭"
                    }
                    listContainer.addView(switchListRow(
                        title = app.label,
                        summary = "${appKindShort(app.trustClass)} · $initialStatus",
                        checked = isChecked,
                        onChanged = { checked, switch, summaryView ->
                            switch.isEnabled = false
                            lifecycleScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    MaxHzController.setDozeWhitelistPackage(app.packageName, checked)
                                }
                                switch.isEnabled = true
                                if (result.code == 0) {
                                    MaxHzPrefs.setDozePackageRestricted(this@MaxHzActivity, app.packageName, !checked)
                                    currentWhitelist = if (checked) currentWhitelist + app.packageName else currentWhitelist - app.packageName
                                    val statusDesc = if (checked) "开启保护" else if (app.systemApp) "已限制后台 (AppOps)" else "关闭"
                                    summaryView.text = "${appKindShort(app.trustClass)} · $statusDesc"
                                    showCommandResult(
                                        resultView,
                                        "更新后台保护名单",
                                        if (app.systemApp && !checked) "已对该系统组件撤销后台运行权限并移入受限待机桶。" else "修改该应用是否保留在后台保护名单中。",
                                        result
                                    )
                                } else {
                                    switch.isChecked = !checked
                                    val statusDesc = if (!checked) "开启保护" else if (app.systemApp) "已限制后台 (AppOps)" else "关闭"
                                    summaryView.text = "${appKindShort(app.trustClass)} · $statusDesc"
                                    showCommandResult(
                                        resultView,
                                        "更新后台保护名单",
                                        "修改该应用是否保留在后台保护名单中。",
                                        result
                                    )
                                }
                            }
                        }
                    ))
                }
            }
        }

        fun applyFilter(filter: AppListFilter) {
            appFilter = filter
            redrawFilters()
            render(currentWhitelist)
        }

        fun selectFilter(filter: AppListFilter) {
            if (filter == AppListFilter.SYSTEM &&
                !releasePrefs().getBoolean(KEY_SYSTEM_APP_FILTER_CONFIRMED, false)
            ) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("显示系统应用？")
                    .setMessage("系统应用、预装组件和核心服务建议谨慎处理。普通用户一般只需要处理用户安装的 App。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续查看") { _, _ ->
                        releasePrefs().edit().putBoolean(KEY_SYSTEM_APP_FILTER_CONFIRMED, true).apply()
                        applyFilter(filter)
                    }
                    .showUnified()
            } else {
                applyFilter(filter)
            }
        }

        fun updateFilterButtons() {
            filterContainer.removeAllViews()
            listOf(
                AppListFilter.ALL to "全部",
                AppListFilter.USER to "用户应用",
                AppListFilter.SYSTEM to "系统应用"
            ).forEachIndexed { index, (filter, label) ->
                filterContainer.addView(filterChip(label, appFilter == filter) {
                    selectFilter(filter)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
        }
        redrawFilters = { updateFilterButtons() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val topTipView = TextView(this@MaxHzActivity).apply {
                text = "关闭后，系统更容易限制该应用后台活动。通讯、电话、推送、健康类谨慎关闭。"
                textSize = 13f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(dp(16), dp(2), dp(16), dp(12))
                setLineSpacing(0f, 1.25f)
            }
            addView(topTipView)
            bindOptionBSubtitle(topTipView, topTipView)
            addView(EditText(this@MaxHzActivity).apply {
                hint = "搜索应用"
                textSize = 14f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                setTextColor(themeColors.onSurface)
                setHintTextColor(themeColors.onSurfaceVariant)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = rounded(themeColors.surfaceContainerHigh, dp(22).toFloat())
                minHeight = dp(44)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchQuery = s?.toString().orEmpty()
                        render(currentWhitelist)
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(6) })
            filterContainer = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            addView(filterContainer)
            updateFilterButtons()
            addSpacedView(horizontalActions(
                button("刷新列表") {
                runCommand(
                        resultView,
                        "读取后台保护名单",
                        "读取当前后台保护名单，只更新列表显示，不修改系统设置。",
                        refreshServiceAfter = false,
                        onResult = { result ->
                            if (result.code == 0) render(parsePackageLines(result.out).toSet())
                        }
                    ) {
                        MaxHzController.readDozeWhitelistPackages()
                    }
                },
                button("管理电池使用") {
                    openBatteryUsageSettings()
                }
            ), dp(2))
            addSpacedView(groupCard {
                addView(listContainer)
            }, dp(6))
            addSpacedView(resultView, dp(8))
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { MaxHzController.readDozeWhitelistPackages() }
                if (result.code == 0) render(parsePackageLines(result.out).toSet()) else {
                    val message = "无法读取白名单状态\n原因：${commandFailureReason(result)}"
                    listContainer.addView(emptyText(message))
                    showCommandResult(
                        resultView,
                        "读取后台保护名单",
                        "读取当前后台保护名单，只更新列表显示，不修改系统设置。",
                        result
                    )
                }
            }
        }
    }

    private fun dozeHistorySection(): View {
        val resultKey = "doze_history"
        val resultView = resultText(resultKey)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun render(text: String) {
            listContainer.removeAllViews()
            val items = parseDozeHistory(text)
            if (items.isEmpty()) {
                listContainer.addView(emptyText("目前还没有 doze 日志。请在手机熄屏、未充电并静置一段时间后再查看。"))
                return
            }
            items.take(80).forEachIndexed { index, item ->
                if (index > 0) listContainer.addView(divider())
                listContainer.addView(twoLineListRow(item.time, item.state))
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val topTipView = TextView(this@MaxHzActivity).apply {
                text = "这里显示手机最近是否进入待机省电。普通状态表示仍在正常运行；浅度或深度待机表示系统正在省电。"
                textSize = 13f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(dp(16), dp(2), dp(16), dp(12))
                setLineSpacing(0f, 1.25f)
            }
            addView(topTipView)
            bindOptionBSubtitle(topTipView, topTipView)
            addSpacedView(horizontalActions(
                button("刷新日志") {
                    runCommand(
                        resultView,
                        "读取最近 doze 日志",
                        "读取手机最近的待机省电状态，只展示状态变化，不修改任何设置。",
                        refreshServiceAfter = false,
                        onResult = { result ->
                            if (result.code == 0) render(result.out)
                        }
                    ) {
                        MaxHzController.readDozeHistory()
                    }
                }
            ), dp(2))
            addSpacedView(groupCard {
                addView(listContainer)
            }, dp(12))
            addSpacedView(resultView, dp(8))
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { MaxHzController.readDozeHistory() }
                if (result.code == 0) render(result.out) else {
                    val message = "读取状态异常：${formatCommandMessage(result.err.ifBlank { result.out })}"
                    listContainer.addView(emptyText(message))
                    showCommandResult(
                        resultView,
                        "读取最近 doze 日志",
                        "读取手机最近的待机省电状态，只展示状态变化，不修改任何设置。",
                        result
                    )
                }
            }
        }
    }

    protected fun nightWakeupGuardSection(): View {
        val resultKey = "night_wakeup_guard"
        val stateView = statusBadge(nightWakeupGuardState())
        val resultView = resultText(resultKey)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        var suggestionsExpanded = false
        var scanAnalysisReport = ""
        val suggestionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        lateinit var suggestionHeader: TextView
        lateinit var suggestionCountView: TextView
        var packageHeaderTitle: TextView? = null
        var onPackageListChanged: (() -> Unit)? = null
        var guardSwitch: MaterialSwitch? = null
        var suppressSwitchListener = false
        var updateSuggestionsFn: (() -> Unit)? = null
        var suggestionFoldHeaderView: View? = null

        val summaryTextView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(themeColors.onSurfaceVariant)
            includeFontPadding = false
        }

        fun suggestionCount(): Int = nightWakeupGuardSuggestionList().size

        fun updateSummary() {
            val guardCount = MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity).size
            val sCount = suggestionCount()
            val isApplied = MaxHzPrefs.nightWakeupGuardEnabled(this@MaxHzActivity)
            summaryTextView.text = when {
                guardCount == 0 && !isApplied -> "当前状态：名单为空 · 护栏未启用（请先扫描或添加应用）"
                guardCount == 0 && isApplied -> "当前状态：护栏已就绪 · 名单为空（请在下方添加需要压制的应用）"
                isApplied -> "当前策略：已加入 ${guardCount} 款 · 发现 ${sCount} 个异常候选 · 护栏生效中"
                else -> "当前策略：已加入 ${guardCount} 款 · 发现 ${sCount} 个异常候选 · 未启用"
            }
            packageHeaderTitle?.text = "护栏名单 ($guardCount)"
        }

        fun updateSuggestionHeader() {
            val count = suggestionCount()
            if (count == 0) {
                suggestionFoldHeaderView?.visibility = View.GONE
                suggestionContainer.visibility = View.VISIBLE
            } else {
                suggestionFoldHeaderView?.visibility = View.VISIBLE
                suggestionHeader.text = if (suggestionsExpanded) "扫描建议 ($count) ▾" else "扫描建议 ($count) ▸"
                suggestionCountView.text = if (suggestionsExpanded) {
                    "共发现 $count 个异常候选应用，可一键批量或按项选择加入护栏"
                } else {
                    "已发现 $count 个异常候选，点击展开查看详情与建议"
                }
                suggestionContainer.visibility = if (suggestionsExpanded) View.VISIBLE else View.GONE
            }
        }

        fun updateSuggestions() {
            updateSuggestionsFn?.invoke()
        }

        fun updateList() {
            renderNightWakeupGuardPackages(listContainer) {
                onPackageListChanged?.invoke()
            }
            val state = nightWakeupGuardState()
            stateView.text = state
            updateStatusText(stateView, state)
            updateSummary()
            suppressSwitchListener = true
            guardSwitch?.isChecked = MaxHzPrefs.nightWakeupGuardEnabled(this@MaxHzActivity)
            suppressSwitchListener = false
        }

        fun selectSuggestedPackages() {
            val guarded = MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity).toSet()
            val suggestions = nightWakeupGuardSuggestionList()
            val candidates = suggestions
                .filterIndexed { index, suggestion ->
                    suggestion.packageName !in guarded &&
                        nightSuggestionRiskLabel(suggestion, index, suggestions) == "建议处理" &&
                        !MaxHzPackagePolicy.isSystemRelated(this@MaxHzActivity, suggestion.packageName)
                }
                .distinctBy { it.packageName }
            if (candidates.isEmpty()) {
                val reason = when {
                    suggestions.isEmpty() -> "当前还没有扫描建议。请先点击“开始扫描”。"
                    suggestions.all { it.packageName in guarded } -> "扫描建议已经全部在护栏名单中。"
                    else -> "系统统计可能还不够，或候选多为系统、通讯、推送类应用，需要手动判断。建议正常使用一段时间后再扫描。"
                }
                statusTextLater(resultView, "没有可自动选择的建议项。\n$reason")
                return
            }

            var selectedCount = 0
            var position = 0
            fun processNext() {
                if (position >= candidates.size) {
                    updateList()
                    updateSuggestions()
                    statusTextLater(
                        resultView,
                        "已选择 ${selectedCount} 个建议项。\n如需开始限制，请在上方开启“启用待机异常护栏”。"
                    )
                    return
                }
                val suggestion = candidates[position++]
                addNightWakeupGuardPackageWithWhitelistCheck(
                    packageName = suggestion.packageName,
                    appName = appLabelForPackage(suggestion.packageName),
                    resultView = resultView,
                    onAdded = {
                        selectedCount += 1
                        updateList()
                        updateSuggestions()
                        listContainer.post { processNext() }
                    },
                    onCancelled = {
                        listContainer.post { processNext() }
                    }
                )
            }
            processNext()
        }

        var suggestionRiskFilter = "全部"
        updateSuggestionsFn = {
            renderNightWakeupGuardSuggestions(
                container = suggestionContainer,
                resultView = resultView,
                scanAnalysisReport = scanAnalysisReport,
                currentFilter = suggestionRiskFilter,
                onFilterChanged = { newFilter ->
                    suggestionRiskFilter = newFilter
                    updateSuggestions()
                },
                onSelectSuggested = { selectSuggestedPackages() }
            ) { packageName, enabled, toggle ->
                if (enabled) {
                    addNightWakeupGuardPackageWithWhitelistCheck(
                        packageName = packageName,
                        appName = appLabelForPackage(packageName),
                        resultView = resultView,
                        onAdded = {
                            updateList()
                            updateSuggestions()
                        },
                        onCancelled = {
                            toggle.isChecked = false
                        }
                    )
                } else {
                    MaxHzPrefs.removeNightWakeupGuardPackage(this@MaxHzActivity, packageName)
                    if (MaxHzPrefs.nightWakeupGuardEnabled(this@MaxHzActivity)) {
                        MaxHzController.restoreNightWakeupGuard(listOf(packageName))
                    }
                    updateList()
                    updateSuggestions()
                    statusTextLater(
                        resultView,
                        "${appLabelForPackage(packageName)}：已移出待机护栏。"
                    )
                }
            }
            updateSuggestionHeader()
            updateSummary()
        }
        onPackageListChanged = {
            updateList()
            updateSuggestions()
        }

        fun applyNightWakeupGuardFromUi() {
            val packages = MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity)
            if (packages.isEmpty()) {
                Toast.makeText(this@MaxHzActivity, "护栏名单为空，请先在下方添加应用或从扫描建议中选择", Toast.LENGTH_SHORT).show()
                suppressSwitchListener = true
                guardSwitch?.isChecked = false
                suppressSwitchListener = false
                return
            }
            runCommand(
                resultView,
                "待机异常唤醒排查",
                "只对你手动加入名单的 App 生效；名单为空时不压制任何应用。",
                onResult = {
                    updateList()
                    updateSuggestions()
                },
                block = {
                    MaxHzController.applyNightWakeupGuard(packages).also {
                        if (it.code == 0 && packages.isNotEmpty()) {
                            MaxHzPrefs.addNightWakeupGuardAppliedPackages(this@MaxHzActivity, packages)
                            MaxHzPrefs.setNightWakeupGuardEnabled(this@MaxHzActivity, true)
                            startMaxHzService()
                        }
                    }
                }
            )
        }

        fun restoreNightWakeupGuardFromUi() {
            runCommand(
                resultView,
                "恢复待机护栏",
                "恢复当前名单和此前实际应用过护栏的 App，避免删除名单后留下后台、唤醒锁、精确闹钟、Doze 白名单或待机桶残留。",
                onResult = {
                    updateList()
                    updateSuggestions()
                },
                block = {
                    val packages = MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity)
                        .plus(MaxHzPrefs.nightWakeupGuardAppliedPackages(this@MaxHzActivity))
                        .distinct()
                    MaxHzController.restoreNightWakeupGuard(packages).also {
                        if (it.code == 0) {
                            MaxHzPrefs.clearNightWakeupGuardAppliedPackages(this@MaxHzActivity)
                            MaxHzPrefs.setNightWakeupGuardEnabled(this@MaxHzActivity, false)
                            updateMaxHzServiceState()
                        }
                    }
                }
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // 卡片 1：待机异常唤醒排查总控（规范 Hero Card，与游戏模式 1:1 对齐）
            addView(card {
                val heroSub = TextView(this@MaxHzActivity).apply {
                    text = "智能排查待机异常唤醒，抑制后台唤醒锁与过度闹钟"
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(3), 0, 0)
                }
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val icon = featureIconContainer("待机异常唤醒排查")
                    addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        rightMargin = dp(14)
                    })
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "待机异常唤醒排查"
                            textSize = 16.5f
                            setTextColor(themeColors.onSurface)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            includeFontPadding = false
                        })
                        addView(heroSub)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(headerBadges(stateView, resultBadge(resultKey)))
                })

                val switchView = switchRow(
                    "启用待机异常护栏",
                    MaxHzPrefs.nightWakeupGuardEnabled(this@MaxHzActivity)
                ) { enabled ->
                    if (suppressSwitchListener) return@switchRow
                    if (enabled) {
                        val packages = MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity)
                        if (packages.isEmpty()) {
                            val suggestions = nightWakeupGuardSuggestionList()
                            val unadded = suggestions.filter { it.packageName !in packages }
                            if (unadded.isNotEmpty()) {
                                MaterialAlertDialogBuilder(this@MaxHzActivity)
                                    .setTitle("护栏名单目前为空")
                                    .setMessage("待机护栏需要指定要压制异常唤醒的应用。\n\n当前已扫描出 ${unadded.size} 个异常唤醒候选应用，是否直接一键添加建议项，或手动挑选应用？")
                                    .setNeutralButton("暂不开启") { _, _ ->
                                        suppressSwitchListener = true
                                        guardSwitch?.isChecked = false
                                        suppressSwitchListener = false
                                    }
                                    .setNegativeButton("手动挑选") { _, _ ->
                                        showNightWakeupGuardAppPicker { app ->
                                            addNightWakeupGuardPackageWithWhitelistCheck(
                                                packageName = app.packageName,
                                                appName = app.label,
                                                resultView = resultView,
                                                onAdded = {
                                                    updateList()
                                                    updateSuggestions()
                                                    applyNightWakeupGuardFromUi()
                                                }
                                            )
                                        }
                                    }
                                    .setPositiveButton("一键添加建议项") { _, _ ->
                                        selectSuggestedPackages()
                                        listContainer.postDelayed({
                                            if (MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity).isNotEmpty()) {
                                                applyNightWakeupGuardFromUi()
                                            }
                                        }, 350)
                                    }
                                    .setOnCancelListener {
                                        suppressSwitchListener = true
                                        guardSwitch?.isChecked = false
                                        suppressSwitchListener = false
                                    }
                                    .showUnified()
                            } else {
                                MaterialAlertDialogBuilder(this@MaxHzActivity)
                                    .setTitle("护栏名单目前为空")
                                    .setMessage("待机护栏需要指定需要压制异常唤醒的 App。\n\n您可以先点击“开始扫描”自动诊断，或立即手动挑选应用加入护栏名单。")
                                    .setNeutralButton("暂不开启") { _, _ ->
                                        suppressSwitchListener = true
                                        guardSwitch?.isChecked = false
                                        suppressSwitchListener = false
                                    }
                                    .setNegativeButton("开始扫描") { _, _ ->
                                        runCommand(
                                            resultView,
                                            "智能待机扫描",
                                            "读取耗电、闹钟和待机桶排行，只生成建议，不自动压制。",
                                            refreshServiceAfter = false,
                                            onResult = { result ->
                                                if (result.code == 0) {
                                                    scanAnalysisReport = formatCommandMessage(result.out)
                                                    MaxHzPrefs.setNightWakeupGuardSuggestionDetails(
                                                        this@MaxHzActivity,
                                                        parseNightWakeupSuggestionDetails(result.out)
                                                    )
                                                    suggestionsExpanded = true
                                                    updateSuggestions()
                                                }
                                            }
                                        ) {
                                            MaxHzController.scanNightWakeupSuggestions()
                                        }
                                    }
                                    .setPositiveButton("选择应用") { _, _ ->
                                        showNightWakeupGuardAppPicker { app ->
                                            addNightWakeupGuardPackageWithWhitelistCheck(
                                                packageName = app.packageName,
                                                appName = app.label,
                                                resultView = resultView,
                                                onAdded = {
                                                    updateList()
                                                    updateSuggestions()
                                                    applyNightWakeupGuardFromUi()
                                                }
                                            )
                                        }
                                    }
                                    .setOnCancelListener {
                                        suppressSwitchListener = true
                                        guardSwitch?.isChecked = false
                                        suppressSwitchListener = false
                                    }
                                    .showUnified()
                            }
                            return@switchRow
                        }
                        if (releasePrefs().getBoolean(KEY_NIGHT_GUARD_RISK_CONFIRMED, false)) {
                            applyNightWakeupGuardFromUi()
                        } else {
                            MaterialAlertDialogBuilder(this@MaxHzActivity)
                                .setTitle("启用待机护栏前确认")
                                .setMessage(
                                    "启用护栏后将抑制名单内应用的后台唤醒锁与过度闹钟，可能影响其后台即时推送与同步。\n\n" +
                                        "建议先从非即时通讯类 App 开始测试。"
                                )
                                .setNegativeButton("取消") { _, _ ->
                                    suppressSwitchListener = true
                                    guardSwitch?.isChecked = false
                                    suppressSwitchListener = false
                                }
                                .setPositiveButton("我已了解并启用") { _, _ ->
                                    releasePrefs().edit().putBoolean(KEY_NIGHT_GUARD_RISK_CONFIRMED, true).apply()
                                    applyNightWakeupGuardFromUi()
                                }
                                .setOnCancelListener {
                                    suppressSwitchListener = true
                                    guardSwitch?.isChecked = false
                                    suppressSwitchListener = false
                                }
                                .showUnified()
                        }
                    } else {
                        restoreNightWakeupGuardFromUi()
                    }
                }
                guardSwitch = switchView
                addSpacedView(switchView, dp(12))

                addSpacedView(summaryTextView, dp(10))
                bindOptionBSubtitle(this, heroSub, summaryTextView)
            })

            // 卡片 2：异常唤醒扫描与分析
            addSpacedView(card {
                addView(TextView(this@MaxHzActivity).apply {
                    text = "异常唤醒扫描与分析"
                    textSize = 15f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                })
                val scanSub = TextView(this@MaxHzActivity).apply {
                    text = "读取前台耗电与唤醒锁，排查不必要的后台闹钟与高频唤醒"
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(3), 0, 0)
                }
                addView(scanSub)
                bindOptionBSubtitle(this, scanSub)

                addSpacedView(horizontalActions(
                    button("开始扫描") {
                        runCommand(
                            resultView,
                            "智能待机扫描",
                            "读取耗电、闹钟和待机桶排行，只生成建议，不自动压制。",
                            refreshServiceAfter = false,
                            onResult = { result ->
                                if (result.code == 0) {
                                    scanAnalysisReport = formatCommandMessage(result.out)
                                    MaxHzPrefs.setNightWakeupGuardSuggestionDetails(
                                        this@MaxHzActivity,
                                        parseNightWakeupSuggestionDetails(result.out)
                                    )
                                    suggestionsExpanded = true
                                    suggestionContainer.visibility = View.VISIBLE
                                    updateSuggestions()
                                }
                            }
                        ) {
                            MaxHzController.scanNightWakeupSuggestions()
                        }
                    },
                    button("后台静默扫描") {
                        startNightWakeupBackgroundScan(
                            onProgress = {
                                statusTextLater(resultView, "正在后台静默深度扫描，通知栏已显示扫描进度，完成后将主动提醒您！")
                            },
                            onCompleted = { count ->
                                suggestionsExpanded = true
                                suggestionContainer.visibility = View.VISIBLE
                                updateSuggestions()
                                updateSummary()
                                statusTextLater(resultView, "后台静默扫描完成！已发现 $count 个建议项。")
                            }
                        )
                    }
                ), dp(10))

                val suggestionFoldHeader = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val isDark = isDarkMode()
                    background = outlinedRounded(
                        if (isDark) 0xFF252A34.toInt() else 0xFFEFF3F8.toInt(),
                        if (isDark) 0x1FFFFFFF.toInt() else 0x0C000000.toInt(),
                        dp(10).toFloat()
                    )
                    installClickFeedback(this, foregroundRipple = false)
                    suggestionHeader = TextView(this@MaxHzActivity).apply {
                        textSize = 13.5f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(themeColors.onSurface)
                    }
                    suggestionCountView = TextView(this@MaxHzActivity).apply {
                        textSize = 12f
                        setTextColor(themeColors.onSurfaceVariant)
                        setPadding(0, dp(2), 0, 0)
                    }
                    addView(suggestionHeader)
                    addView(suggestionCountView)
                    bindOptionBSubtitle(this, suggestionCountView)
                    setOnClickListener {
                        playClickFeedback(this)
                        suggestionsExpanded = !suggestionsExpanded
                        suggestionContainer.visibility = if (suggestionsExpanded) View.VISIBLE else View.GONE
                        updateSuggestionHeader()
                    }
                }
                suggestionFoldHeaderView = suggestionFoldHeader
                addSpacedView(suggestionFoldHeader, dp(10))
                addSpacedView(suggestionContainer, dp(8))
            }, gap)

            // 卡片 3：护栏名单管理（与游戏名单管理卡片结构 1:1 对齐）
            addSpacedView(card {
                val guardPackages = MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity)
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    packageHeaderTitle = TextView(this@MaxHzActivity).apply {
                        text = "护栏名单 (${guardPackages.size})"
                        textSize = 15f
                        setTextColor(themeColors.onSurface)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        includeFontPadding = false
                    }
                    addView(packageHeaderTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(createOptionBTogglePill())
                })

                val actionButtons = mutableListOf<View>()
                actionButtons.add(button("添加应用") {
                    showNightWakeupGuardAppPicker { app ->
                        addNightWakeupGuardPackageWithWhitelistCheck(
                            packageName = app.packageName,
                            appName = app.label,
                            resultView = resultView,
                            onAdded = {
                                updateList()
                                updateSuggestions()
                            }
                        )
                    }
                })
                actionButtons.add(button("查看生效限制") {
                    showNightWakeupCurrentRestrictionsDialog(resultView) {
                        updateList()
                        updateSuggestions()
                    }
                })
                actionButtons.add(button("后台保护白名单") {
                    pushDetail("后台保护名单") { dozeWhitelistSection() }
                })
                if (guardPackages.isNotEmpty()) {
                    actionButtons.add(button("清空名单") {
                        MaterialAlertDialogBuilder(this@MaxHzActivity)
                            .setTitle("清空护栏名单")
                            .setMessage("确定要清空护栏名单吗？\n\n若护栏处于生效状态，将自动恢复所有已限制的应用。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("清空并还原") { _, _ ->
                                val allPkgs = MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity)
                                    .plus(MaxHzPrefs.nightWakeupGuardAppliedPackages(this@MaxHzActivity))
                                    .distinct()
                                MaxHzController.restoreNightWakeupGuard(allPkgs)
                                MaxHzPrefs.clearNightWakeupGuardPackages(this@MaxHzActivity)
                                MaxHzPrefs.clearNightWakeupGuardAppliedPackages(this@MaxHzActivity)
                                MaxHzPrefs.setNightWakeupGuardEnabled(this@MaxHzActivity, false)
                                updateMaxHzServiceState()
                                updateList()
                                updateSuggestions()
                                Toast.makeText(this@MaxHzActivity, "护栏名单已清空并恢复", Toast.LENGTH_SHORT).show()
                            }
                            .showUnified()
                    })
                }
                addSpacedView(horizontalActions(*actionButtons.toTypedArray()), dp(6))

                addSpacedView(listContainer, dp(8))
            }, gap)

            // 卡片 4：运行机制与保护机制（与游戏模式卡片 4 结构对齐）
            addSpacedView(card {
                val mechSub = TextView(this@MaxHzActivity).apply {
                    text = "• 精准针对：仅对您手动加入护栏名单的 App 生效，名单为空时不压制任何应用。\n• 完整还原：关闭开关或删除应用时，自动完整还原唤醒锁与待机状态，不留残留。\n• 安全白名单：重要通讯、电话及系统核心服务内置智能白名单保护，防止漏接重要消息。"
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setLineSpacing(0f, 1.35f)
                    includeFontPadding = false
                }
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MaxHzActivity).apply {
                        text = "运行机制与保护机制"
                        textSize = 15f
                        setTextColor(themeColors.onSurface)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(createOptionBTogglePill { listOf(mechSub) })
                })
                addSpacedView(mechSub, dp(8))
                addSpacedView(resultView, dp(6))
                bindOptionBSubtitle(this, mechSub)
            }, gap)



            updateSummary()
            updateSuggestions()
            updateList()
        }
    }

    protected fun startNightWakeupBackgroundScan(
        onProgress: (() -> Unit)? = null,
        onCompleted: ((Int) -> Unit)? = null
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channelId = "maxhz_night_scan"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val channel = NotificationChannel(
                channelId,
                "待机异常唤醒扫描",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "后台智能待机扫描与诊断通知"
            }
            nm.createNotificationChannel(channel)
        }

        val notifyId = 20091
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_night_guard", true)
        }
        val pendingIntent = openIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val progressNotification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle("待机异常唤醒正在后台深度扫描...")
            .setContentText("正在多维分析后台唤醒锁、定时闹钟与异常持锁应用")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm?.notify(notifyId, progressNotification)
        Toast.makeText(this, "已在后台启动待机深度扫描，完成后将在通知栏提醒！", Toast.LENGTH_SHORT).show()
        onProgress?.invoke()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = MaxHzController.scanNightWakeupSuggestions()
            withContext(Dispatchers.Main) {
                if (result.code == 0) {
                    val details = parseNightWakeupSuggestionDetails(result.out)
                    MaxHzPrefs.setNightWakeupGuardSuggestionDetails(this@MaxHzActivity, details)

                    val finishNotification = NotificationCompat.Builder(this@MaxHzActivity, channelId)
                        .setSmallIcon(R.drawable.ic_system_icon)
                        .setContentTitle("待机异常唤醒扫描完成")
                        .setContentText("已发现 ${details.size} 个异常唤醒候选应用，点击查看分析报告")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()

                    nm?.notify(notifyId, finishNotification)
                    onCompleted?.invoke(details.size)
                } else {
                    val failNotification = NotificationCompat.Builder(this@MaxHzActivity, channelId)
                        .setSmallIcon(R.drawable.ic_system_icon)
                        .setContentTitle("待机异常唤醒扫描未完成")
                        .setContentText("扫描未能成功完成，请检查特权环境")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .build()
                    nm?.notify(notifyId, failNotification)
                }
            }
        }
    }

    private fun refreshRateSection(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MaxHzActivity).apply {
                text = "FPS Monitor 开启期间会持续采样并刷新悬浮窗，长时间开启可能增加发热和耗电。不需要看帧率时建议关闭。"
                textSize = 12.5f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(dp(4), dp(2), dp(4), dp(12))
                setLineSpacing(0f, 1.25f)
            })
            addView(actionCard(
                "实时 FPS 监视器",
                "悬浮窗显示前台 App 实际渲染 FPS，不写系统设置。",
                "开启",
                "实时 FPS 监视器",
                "读取 measured_fps、SurfaceFlinger 和 gfxinfo 帧统计，尽量显示真实 FPS 档位。",
                { startFpsMonitorResult() },
	                "关闭",
	                "关闭 FPS 监视器",
	                "停止 FPS 悬浮窗服务，不修改刷新率、省电、温控或任何系统策略。",
	                { stopFpsMonitorResult() },
	                { fpsMonitorState() }
	            ))
	        }
	    }

    // 旧版「状态栏快捷开关」是这里的 View 卡（featureCard + MaterialCardView），
    // 已改为 Compose 的 MaxHzActionCard 渲染 —— 见 collectMaxHzPageContent 里的
    // addActionCard(...)，材质与页面上其他玻璃卡完全一致。

    private fun requestOutdoorModeTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "请下拉状态栏，进入编辑并手动添加“户外模式”。", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(StatusBarManager::class.java)
        manager.requestAddTileService(
            ComponentName(this, OutdoorModeTileService::class.java),
            "户外模式",
            Icon.createWithResource(this, R.drawable.ic_maxhz_sun_24),
            mainExecutor
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "户外模式已添加到状态栏。"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "户外模式已经在状态栏中。"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "没有添加；可在状态栏编辑页面手动添加。"
                else -> "系统未能添加磁贴；请在状态栏编辑页面手动添加。"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestGameModeTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "请下拉状态栏，进入编辑并手动添加“游戏模式”。", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(StatusBarManager::class.java)
        manager.requestAddTileService(
            ComponentName(this, GameModeTileService::class.java),
            "游戏模式",
            Icon.createWithResource(this, R.drawable.ic_maxhz_speed_24),
            mainExecutor
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "游戏模式已添加到状态栏。"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "游戏模式已经在状态栏中。"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "没有添加；可在状态栏编辑页面手动添加。"
                else -> "系统未能添加磁贴；请在状态栏编辑页面手动添加。"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    protected fun batteryProtectionSection(): View {
        val batteryLimitInput = numberInput(MaxHzPrefs.batteryLimit(this))
        val batteryRechargeInput = numberInput(MaxHzPrefs.batteryRecharge(this))
        val batteryState = statusBadge(batteryProtectionState())
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addSpacedView(featureCard(
                "自定义电池保护阈值",
                "自定义充电截止电量与重新开始充电的阈值，延长电池循环寿命。",
                headerBadges(batteryState)
            ) {
                addSpacedView(batteryProtectionInputGroup(
                    "充电上限%" to batteryLimitInput,
                    "恢复充电%" to batteryRechargeInput
                ), dp(BUTTON_SPACING_DP))
                addSpacedView(horizontalActions(
                    button("应用保护设置") {
                        val limit = batteryLimitInput.safeInt(80).coerceIn(50, 100)
                        val recharge = batteryRechargeInput.safeInt(75).coerceIn(40, limit)
                        val wasEnabled = MaxHzPrefs.batteryProtectionEnabled(this@MaxHzActivity)
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                MaxHzController.applyBatteryProtection(limit, recharge)
                            }
                            if (result.code == 0) {
                                MaxHzPrefs.setBatteryProtectionConfig(this@MaxHzActivity, limit, recharge)
                                MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, true)
                                startMaxHzService()
                                updateStatusText(batteryState, batteryProtectionState())
                                Toast.makeText(this@MaxHzActivity, "已应用电池保护设置 (上限: ${limit}%, 回充: ${recharge}%)", Toast.LENGTH_SHORT).show()
                            } else {
                                MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, wasEnabled)
                                Toast.makeText(this@MaxHzActivity, "设置失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    button("恢复默认设置") {
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                MaxHzController.restoreBatteryProtection()
                            }
                            if (result.code == 0) {
                                MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, false)
                                updateMaxHzServiceState()
                                updateStatusText(batteryState, batteryProtectionState())
                                Toast.makeText(this@MaxHzActivity, "已恢复系统默认电池保护", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MaxHzActivity, "恢复失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ), dp(BUTTON_SPACING_DP))
                addSpacedView(button("查看硬件电池实时状态") {
                    showBatteryInfoDialog()
                }, dp(BUTTON_SPACING_DP))
            }, gap)
        }
    }

    private fun batteryProtectionLastAppliedText(): String {
        return if (MaxHzPrefs.batteryProtectionEnabled(this)) {
            buildString {
                append("最近一次应用设置")
                append('\n').append("充电上限：").append(MaxHzPrefs.batteryLimit(this@MaxHzActivity)).append('%')
                append('\n').append("恢复充电：").append(MaxHzPrefs.batteryRecharge(this@MaxHzActivity)).append('%')
                append('\n').append("来源：本 App 最近一次成功应用记录")
                append('\n').append("说明：这不是 Samsung 系统实时读取值。")
            }
        } else {
            "最近一次应用设置\n暂无应用记录\n设置参数后点击“应用设置”，成功后会显示在这里。"
        }
    }

    protected fun showBatteryProtectionDialog(onChanged: (() -> Unit)? = null) {
        val batteryLimitInput = numberInput(MaxHzPrefs.batteryLimit(this))
        val batteryRechargeInput = numberInput(MaxHzPrefs.batteryRecharge(this))
        val statusView = batteryProtectionSystemStatusView(
            "当前状态：${batteryProtectionState()}\n" +
                "充电上限：${MaxHzPrefs.batteryLimit(this)}%  ·  恢复充电：${MaxHzPrefs.batteryRecharge(this)}%"
        )

        fun applyPreset(limit: Int, recharge: Int) {
            batteryLimitInput.setText(limit.toString())
            batteryRechargeInput.setText(recharge.toString())
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
            addView(TextView(this@MaxHzActivity).apply {
                text = "自定义充电截止电量与恢复充电阈值，达到上限后自动停止充电以延长电池循环寿命。"
                textSize = 12.5f
                setTextColor(themeColors.onSurfaceVariant)
                setLineSpacing(0f, 1.25f)
                setPadding(0, 0, 0, dp(10))
            })
            addSpacedView(statusView, dp(10))
            addSpacedView(
                horizontalActions(
                    button("80% / 75% 日常") { applyPreset(80, 75) },
                    button("85% / 80% 均衡") { applyPreset(85, 80) },
                    button("90% / 85% 出行") { applyPreset(90, 85) }
                ),
                dp(10)
            )
            addSpacedView(
                batteryProtectionInputGroup(
                    "充电上限%" to batteryLimitInput,
                    "恢复充电%" to batteryRechargeInput
                ),
                dp(6)
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("充电上限保护")
            .setView(ScrollView(this).apply {
                addView(content)
            })
            .setPositiveButton("应用并开启", null)
            .setNeutralButton("恢复默认", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        val isDark = isDarkMode()
        dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
        applyUnifiedDialogWindowStyle(dialog)

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val limit = batteryLimitInput.safeInt(80).coerceIn(50, 100)
            val recharge = batteryRechargeInput.safeInt(75).coerceIn(40, (limit - 5).coerceAtLeast(40))
            val wasEnabled = MaxHzPrefs.batteryProtectionEnabled(this@MaxHzActivity)
            statusView.text = "正在写入电池保护配置 (${limit}% / ${recharge}%)..."
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    MaxHzController.applyBatteryProtection(limit, recharge)
                }
                if (result.code == 0) {
                    MaxHzPrefs.setBatteryProtectionConfig(this@MaxHzActivity, limit, recharge)
                    MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, true)
                    startMaxHzService()
                    onChanged?.invoke()
                    Toast.makeText(
                        this@MaxHzActivity,
                        "已开启充电上限保护（${limit}% 停充 / ${recharge}% 复充）",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (dialog.isShowing) dialog.dismiss()
                } else {
                    MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, wasEnabled)
                    statusView.text = "设置失败：${commandFailureReason(result)}"
                    Toast.makeText(
                        this@MaxHzActivity,
                        "设置失败：${commandFailureReason(result)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            statusView.text = "正在恢复系统默认充电设置..."
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    MaxHzController.restoreBatteryProtection()
                }
                if (result.code == 0) {
                    MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, false)
                    updateMaxHzServiceState()
                    onChanged?.invoke()
                    Toast.makeText(this@MaxHzActivity, "已恢复系统默认电池保护", Toast.LENGTH_SHORT).show()
                    if (dialog.isShowing) dialog.dismiss()
                } else {
                    statusView.text = "恢复失败：${commandFailureReason(result)}"
                    Toast.makeText(
                        this@MaxHzActivity,
                        "恢复失败：${commandFailureReason(result)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    protected fun applyBatteryProtectionFromUi(
        limit: Int,
        recharge: Int,
        onChanged: (() -> Unit)? = null,
        onResult: (Boolean, String) -> Unit
    ) {
        val safeLimit = limit.coerceIn(50, 100)
        val safeRecharge = recharge.coerceIn(40, (safeLimit - 5).coerceAtLeast(40))
        val wasEnabled = MaxHzPrefs.batteryProtectionEnabled(this)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MaxHzController.applyBatteryProtection(safeLimit, safeRecharge)
            }
            if (result.code == 0) {
                MaxHzPrefs.setBatteryProtectionConfig(this@MaxHzActivity, safeLimit, safeRecharge)
                MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, true)
                startMaxHzService()
                onChanged?.invoke()
                val msg = "已开启充电上限保护（${safeLimit}% 停充 / ${safeRecharge}% 复充）"
                Toast.makeText(this@MaxHzActivity, msg, Toast.LENGTH_SHORT).show()
                onResult(true, msg)
            } else {
                MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, wasEnabled)
                val msg = "设置失败：${commandFailureReason(result)}"
                Toast.makeText(this@MaxHzActivity, msg, Toast.LENGTH_SHORT).show()
                onResult(false, msg)
            }
        }
    }

    protected fun restoreBatteryProtectionFromUi(
        onChanged: (() -> Unit)? = null,
        onResult: (Boolean, String) -> Unit
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MaxHzController.restoreBatteryProtection()
            }
            if (result.code == 0) {
                MaxHzPrefs.setBatteryProtectionEnabled(this@MaxHzActivity, false)
                updateMaxHzServiceState()
                onChanged?.invoke()
                val msg = "已恢复系统默认电池保护"
                Toast.makeText(this@MaxHzActivity, msg, Toast.LENGTH_SHORT).show()
                onResult(true, msg)
            } else {
                val msg = "恢复失败：${commandFailureReason(result)}"
                Toast.makeText(this@MaxHzActivity, msg, Toast.LENGTH_SHORT).show()
                onResult(false, msg)
            }
        }
    }

    protected fun fetchBatteryInfoFromUi(onResult: (String) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    MaxHzController.readBatteryProtectionStatus()
                } catch (t: Throwable) {
                    MaxHzShell.Result(-2, "", t.message ?: t.javaClass.simpleName)
                }
            }
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val text = if (result.code == 0) {
                "更新时间：$time\n${batteryProtectionSystemStatusText(result.out)}"
            } else {
                "更新时间：$time\n无法读取电池信息\n请检查 Shizuku 或 Root 权限状态后重试。"
            }
            onResult(text)
        }
    }

    protected fun showBatteryInfoDialog() {
        val infoView = batteryProtectionSystemStatusView("电池信息\n正在读取...\n点击下方“刷新数据”可重新获取。")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(TextView(this@MaxHzActivity).apply {
                text = "显示当前电量、温度、充电状态、电流、电压、循环数、三星健康度等运行信息；支持无线调试与 Root 实时直读。"
                textSize = 12.5f
                setTextColor(themeColors.onSurfaceVariant)
                setLineSpacing(0f, 1.25f)
                setPadding(0, 0, 0, dp(12))
            })
            addView(infoView)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("电池运行信息与健康度")
            .setView(ScrollView(this).apply {
                addView(content)
            })
            .setPositiveButton("关闭", null)
            .setNeutralButton("刷新数据", null)
            .create()

        fun loadData() {
            infoView.text = "电池信息\n正在读取最新状态..."
            fetchBatteryInfoFromUi { text ->
                if (dialog.isShowing) {
                    infoView.text = "电池信息\n$text"
                }
            }
        }

        dialog.show()
        val isDark = isDarkMode()
        dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
        applyUnifiedDialogWindowStyle(dialog)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            loadData()
        }
        loadData()
    }

    protected fun appRefreshRateSection(): View {
        val maxHzInput = numberInput(120)
        val minHzInput = numberInput(10)
        var supportedRates = listOf(60, 120)
        val adaptiveBox = CheckBox(this).apply {
            text = "自适应刷新率"
            textSize = 14f
            isChecked = true
        }
        val supportedText = TextView(this).apply {
            textSize = 12f
            setTextColor(themeColors.onSurfaceVariant)
            text = "支持档位：未检测，默认按 60/120Hz 处理"
            setPadding(0, 0, 0, dp(8))
        }
        val rulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val appRuleWarning = TextView(this).apply {
            text = "每 App 规则需要无障碍服务。\n当前无障碍未开启，规则不会自动切换。\n点击此处立即前往开启无障碍服务"
            textSize = 12.5f
            setTextColor(themeColors.onWarningContainer)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(0f, 1.2f)
            background = rounded(themeColors.warningContainer, dp(14).toFloat())
            setOnClickListener {
                AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity)
            }
            visibility = View.GONE
        }
        val rulesState = statusBadge(appRefreshState())
        val instantState = statusBadge(appRefreshInstantState())
        val selectedAppText = stateText(selectedAppRefreshText())
        var resultView: TextView? = null

        fun updateRulesList() {
            renderAppRefreshRules(rulesContainer) {
                selectedAppText.text = selectedAppRefreshText()
                updateRulesList()
            }
            rulesState.text = appRefreshState()
            instantState.text = appRefreshInstantState()
            appRuleWarning.visibility = if (
                MaxHzPrefs.appRefreshRules(this@MaxHzActivity).isNotEmpty() &&
                !MaxHzAccessibilityService.isEnabled(this@MaxHzActivity)
            ) View.VISIBLE else View.GONE
            updateMaxHzServiceState()
        }

        fun applyRates(rates: List<Int>) {
            supportedRates = rates
            supportedText.text = "支持档位：${supportedRates.joinToString(" / ")} Hz"
            maxHzInput.setText((supportedRates.maxOrNull() ?: 120).toString())
            minHzInput.setText((supportedRates.minOrNull() ?: 10).toString())
        }

        fun applyDetectedRates(result: MaxHzShell.Result): MaxHzShell.Result {
            if (result.code != 0) return result
            val parsed = parseRefreshRates(result.out)
            if (parsed.isEmpty()) {
                return MaxHzShell.Result(-1, result.out, "未能解析刷新率档位，请保留手动输入")
            }
            MaxHzPrefs.setSupportedRefreshRates(this@MaxHzActivity, parsed)
            runOnUiThread {
                applyRates(parsed)
            }
            return MaxHzShell.Result(0, "支持档位：${parsed.joinToString(" / ")} Hz", "")
        }

        fun fillRefreshPreset(maxHz: Int, minHz: Int, adaptive: Boolean, label: String) {
            maxHzInput.setText(maxHz.toString())
            minHzInput.setText(minHz.coerceAtMost(maxHz).toString())
            adaptiveBox.isChecked = adaptive
            resultView?.let {
                statusTextLater(it, "已填入 $label：${if (adaptive) "自适应 ${minHz.coerceAtMost(maxHz)}-$maxHz Hz" else "静态 ${maxHz}Hz"}。确认应用后点“保存”才会写入规则。")
            }
        }

        fun nearestAtMost(target: Int): Int {
            return supportedRates.filter { it <= target }.maxOrNull() ?: supportedRates.minOrNull() ?: target
        }

        fun highest(): Int = supportedRates.maxOrNull() ?: 120
        fun lowest(): Int = supportedRates.minOrNull() ?: 10

        val cachedRates = MaxHzPrefs.supportedRefreshRates(this)
        if (cachedRates.isNotEmpty()) {
            applyRates(cachedRates)
        } else if (MaxHzPrefs.supportedRefreshRatesDetected(this)) {
            supportedText.text = "支持档位：已检测但未解析到档位，默认按 60/120Hz 处理"
        } else {
            supportedText.text = "支持档位：首次进入自动检测一次，之后使用缓存"
        }

        supportedText.post {
            if (MaxHzPrefs.supportedRefreshRatesDetected(this)) return@post
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        MaxHzController.readSupportedRefreshRates()
                    } catch (t: Throwable) {
                        MaxHzShell.Result(-2, "", t.message ?: t.javaClass.simpleName)
                    }
                }
                MaxHzPrefs.setSupportedRefreshRatesDetected(this@MaxHzActivity, true)
                applyDetectedRates(result)
            }
        }

        val resultKey = "app_refresh"
        resultView = resultText(resultKey)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // 卡片 1：每 App 刷新率总控
            addView(card {
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val icon = featureIconContainer("分应用自定义刷新率")
                    addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        rightMargin = dp(14)
                    })
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "每 App 独立刷新率"
                            textSize = 16.5f
                            setTextColor(themeColors.onSurface)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            includeFontPadding = false
                        })
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "前台应用自动匹配专属刷新率，切出即刻恢复"
                            textSize = 12.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            includeFontPadding = false
                            setPadding(0, dp(3), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(headerBadges(rulesState, instantState, resultBadge(resultKey)))
                })

                addSpacedView(TextView(this@MaxHzActivity).apply {
                    text = "档位建议：视频建议 60Hz 避免抖动；电子书 24/30Hz 极致省电；游戏建议自适应最高Hz。需要开启无障碍即时切换服务以精准感知前台应用。"
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setLineSpacing(0f, 1.25f)
                    includeFontPadding = false
                }, dp(10))

                addSpacedView(appRuleWarning, dp(8))

                addSpacedView(horizontalActions(
                    button("开启即时切换") {
                        instantState.text = appRefreshInstantState()
                        AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity)
                        statusTextLater(resultView ?: return@button, "正在为您直达无障碍设置，请开启「Shizuku / MaxHz 即时切换服务」。")
                    },
                    button("刷新状态") {
                        instantState.text = appRefreshInstantState()
                        statusTextLater(resultView ?: return@button, "即时切换：${appRefreshInstantState()}。")
                    }
                ), dp(10))
            })

            // 卡片 2：规则参数配置与预设
            addSpacedView(card {
                addView(TextView(this@MaxHzActivity).apply {
                    text = "配置规则参数"
                    textSize = 15f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                })
                addSpacedView(supportedText, dp(6))

                addSpacedView(TextView(this@MaxHzActivity).apply {
                    text = "快速填充推荐预设："
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                }, dp(8))
                addSpacedView(horizontalActions(
                    button("日用") { fillRefreshPreset(highest(), lowest(), true, "日用推荐") },
                    button("视频") { fillRefreshPreset(nearestAtMost(60), lowest(), true, "视频推荐") },
                    button("阅读") {
                        val readHz = if (supportedRates.any { it <= 30 }) nearestAtMost(30) else nearestAtMost(60)
                        fillRefreshPreset(readHz, lowest().coerceAtMost(readHz), false, "阅读推荐")
                    },
                    button("游戏") { fillRefreshPreset(highest(), nearestAtMost(60).coerceAtMost(highest()), true, "游戏推荐") }
                ), dp(6))

                addSpacedView(settingsInputGroup(
                    "最高Hz" to maxHzInput,
                    "最低Hz" to minHzInput
                ), dp(10))
                addView(adaptiveBox)

                addSpacedView(selectedAppText, dp(10))
                addSpacedView(horizontalActions(
                    button("选择应用") {
                        if (!MaxHzShell.isReady()) {
                            Toast.makeText(this@MaxHzActivity, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
                            return@button
                        }
                        if (!MaxHzAccessibilityService.isEnabled(this@MaxHzActivity)) {
                            Toast.makeText(this@MaxHzActivity, "无障碍服务尚未开启，请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                            AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity)
                            return@button
                        }
                        showUserAppPicker { packageName, label ->
                            selectedAppRefreshPackage = packageName
                            selectedAppRefreshLabel = label
                            selectedAppText.text = selectedAppRefreshText()
                            statusTextLater(resultView ?: return@showUserAppPicker, "已选择 ${label}。")
                            updateRulesList()
                        }
                    },
                    button("保存") {
                        if (!MaxHzShell.isReady()) {
                            Toast.makeText(this@MaxHzActivity, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
                            return@button
                        }
                        if (!MaxHzAccessibilityService.isEnabled(this@MaxHzActivity)) {
                            Toast.makeText(this@MaxHzActivity, "无障碍服务尚未开启，请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                            AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity)
                            return@button
                        }
                        runCommand(resultView ?: return@button, "保存每 App 刷新率", "保存应用规则；自适应模式写入最高/最低刷新率，静态模式固定到最高刷新率。") {
                            val packageName = selectedAppRefreshPackage.trim()
                            if (!MaxHzAccessibilityService.isEnabled(this@MaxHzActivity)) {
                                runOnUiThread { AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity) }
                                MaxHzShell.Result(-1, "", "请先开启无障碍即时切换，正在为您直达无障碍设置...")
                            } else if (!isPackageNameValid(packageName)) {
                                MaxHzShell.Result(-1, "", "请先点“选择应用”选择一个用户安装的应用")
                            } else if (!MaxHzPackagePolicy.isRefreshRateTargetApp(this@MaxHzActivity, packageName)) {
                                MaxHzShell.Result(-1, "", "只支持用户可见应用。系统组件、后台服务和核心框架不会保存刷新率规则。")
                            } else {
                                val maxHz = maxHzInput.safeInt(120).coerceIn(1, 240)
                                val minHz = minHzInput.safeInt(10).coerceIn(1, maxHz)
                                val rule = MaxHzPrefs.AppRefreshRule(packageName, adaptiveBox.isChecked, maxHz, minHz)
                                MaxHzPrefs.saveAppRefreshRule(this@MaxHzActivity, rule)
                                val permissionResult = MaxHzController.grantSupportPermissions()
                                runOnUiThread {
                                    startMaxHzService()
                                    instantState.text = appRefreshInstantState()
                                }
                                val currentPackage = MaxHzForegroundApp.currentPackage(this@MaxHzActivity)
                                val saveResult = if (currentPackage == packageName) {
                                    if (rule.adaptive) {
                                        MaxHzController.applyAdaptiveMax(rule.maxHz, rule.minHz)
                                    } else {
                                        MaxHzController.applyStaticRefreshRate(rule.maxHz)
                                    }
                                } else {
                                    MaxHzShell.Result(0, "规则已保存：${selectedAppRefreshLabel.ifBlank { "所选应用" }}\n切换到该应用时自动生效", "")
                                }
                                combineResults(permissionResult, saveResult).also {
                                    runOnUiThread {
                                        selectedAppText.text = selectedAppRefreshText()
                                        updateRulesList()
                                    }
                                }
                            }
                        }.also {
                            rulesContainer.post {
                                updateRulesList()
                            }
                        }
                    }
                ), dp(10))

                addSpacedView(horizontalActions(
                    button("删除选定规则") {
                        val target = resultView ?: return@button
                        val packageName = selectedAppRefreshPackage.trim()
                        if (!isPackageNameValid(packageName)) {
                            statusTextLater(target, "请先点“选择应用”选择要删除规则的应用。")
                            return@button
                        }
                        val appName = selectedAppRefreshLabel.ifBlank { appLabelForPackage(packageName) }
                        MaterialAlertDialogBuilder(this@MaxHzActivity)
                            .setTitle("删除刷新率规则")
                            .setMessage("$appName\n\n删除此 App 的刷新率规则？\n\n删除后不会影响系统刷新率设置，只是停止自动切换。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                runCommand(target, "恢复当前 App 刷新率", "移除当前应用规则，并立刻恢复系统默认自适应刷新率。") {
                                    MaxHzPrefs.removeAppRefreshRule(this@MaxHzActivity, packageName)
                                    runOnUiThread {
                                        if (selectedAppRefreshPackage == packageName) {
                                            selectedAppRefreshPackage = ""
                                            selectedAppRefreshLabel = ""
                                            selectedAppText.text = "已选择：未选择应用"
                                        }
                                        updateRulesList()
                                        updateMaxHzServiceState()
                                        instantState.text = appRefreshInstantState()
                                    }
                                    MaxHzController.applyScreenOnAdaptive()
                                }
                            }
                            .showUnified()
                    },
                    button("清空所有规则") {
                        val target = resultView ?: return@button
                        MaterialAlertDialogBuilder(this@MaxHzActivity)
                            .setTitle("清空刷新率规则")
                            .setMessage("清空所有每 App 刷新率规则？\n\n清空后不会删除应用，只会停止所有按 App 自动切换刷新率。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("清空") { _, _ ->
                                runCommand(target, "恢复所有 App 刷新率", "清空所有每 App 刷新率规则，并恢复系统默认自适应刷新率。") {
                                    MaxHzController.restoreRefreshRateDefaults().also {
                                        if (it.code == 0) {
                                            MaxHzPrefs.clearAppRefreshRules(this@MaxHzActivity)
                                            runOnUiThread {
                                                selectedAppRefreshPackage = ""
                                                selectedAppRefreshLabel = ""
                                                selectedAppText.text = "已选择：未选择应用"
                                                updateRulesList()
                                                updateMaxHzServiceState()
                                                instantState.text = appRefreshInstantState()
                                            }
                                        }
                                    }
                                }
                            }
                            .showUnified()
                    }
                ), dp(8))
            }, gap)

            // 卡片 3：当前已配置的应用规则列表
            addSpacedView(card {
                addView(TextView(this@MaxHzActivity).apply {
                    text = "已配置应用规则列表"
                    textSize = 15f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                })
                addSpacedView(rulesContainer, dp(8))
                addSpacedView(resultView ?: resultText(resultKey), dp(8))
            }, gap)

            updateRulesList()
        }
    }

    // mOriginalSection（原"系统深度调优专区"）已移除：其条目与"性能加速"Tab 完全重复，
    // 独有功能"温控守护与安全防护"已作为正式条目加入性能加速 Tab。相关跳转入口亦已删除。

    // 触控采样画板与测试工具已彻底移除

    protected fun mOriginalMemoryCompile(parent: LinearLayout) {
        parent.addView(groupTitle("应用启动加速与内存整理", "通过 ART AOT 机器码预编译与内存碎片整理，大幅提升冷启动速度与运行流畅度。"))
        val aotBadge = statusBadge(enabledState(MaxHzPrefs.mDexoptEnabled(this@MaxHzActivity)))
        parent.addSpacedView(featureCard(
            "ART 机器码预编译加速",
            "【底层原理】App 安装包默认以 Dex 字节码运行，冷启动与滑动时需实时翻译解释，容易产生掉帧或启动迟滞；ART 预编译提前将其转换为 CPU 可直接执行的原生机器码（.odex），让执行效率翻倍。\n" +
                "• 热点极速（speed-profile，推荐）：仅编译日常高频调用的 10%~20% 核心启动与滑动路径。单应用仅需 1~3 秒、几乎不占额外空间，即可获得约 90% 流畅度提升。\n" +
                "• 深度全量（speed）：将应用内 100% 所有代码和 SDK 强制完整编译。单应用约需 15~60 秒、占用 100~400MB 空间，适合在「自选应用编译」中为 3~5 个主力重度 App（如微信、大作游戏）单独开启。\n" +
                "【功能说明】\n" +
                "• 自选应用编译：自主勾选目标应用，可自由切换热点极速、深度全量或重置缓存。\n" +
                "• 优化系统与桌面：对系统桌面 Launcher、SystemUI 及核心系统组件执行热点极速优化，桌面滑动与手势即刻丝滑。\n" +
                "• 全部应用极速优化：对手机所有已安装应用批量执行热点极速优化，告别应用冷启动卡顿。\n" +
                "支持转入后台运行：执行过程中点击「转到后台」可在状态栏与通知栏实时查看变动进度，持有 CPU 锁防杀保活。",
            headerBadges(aotBadge)
        ) {
            val resumeBanner = TextView(this@MaxHzActivity).apply {
                textSize = 12.5f
                setTextColor(themeColors.primary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (isDarkMode()) 0x2610B981 else 0x1F10B981)
                }
                visibility = if (AotCompileService.isRunning) View.VISIBLE else View.GONE
                setOnClickListener {
                    showAotLiveProgressDialog {
                        updateStatusText(aotBadge, enabledState(true))
                    }
                }
            }
            addView(resumeBanner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })

            lifecycleScope.launch {
                AotCompileService.stateFlow.collect { st ->
                    if (st.isRunning) {
                        val pct = if (st.total > 0) (st.currentStep * 100) / st.total else 0
                        resumeBanner.text = "后台正在优化中：[${st.currentStep}/${st.total}] ($pct%) · 点击查看实时进度"
                        resumeBanner.visibility = View.VISIBLE
                    } else {
                        resumeBanner.visibility = View.GONE
                    }
                }
            }

            addSpacedView(horizontalActions(
                button("自选应用编译") {
                    if (AotCompileService.isRunning) {
                        Toast.makeText(this@MaxHzActivity, "当前已有优化任务在后台运行中，已为您打开进度", Toast.LENGTH_SHORT).show()
                        showAotLiveProgressDialog {
                            updateStatusText(aotBadge, enabledState(true))
                        }
                    } else {
                        showAotSpecificAppsPickerDialog {
                            updateStatusText(aotBadge, enabledState(true))
                        }
                    }
                },
                button("优化系统与桌面") {
                    if (AotCompileService.isRunning) {
                        Toast.makeText(this@MaxHzActivity, "当前已有优化任务在后台运行中，已为您打开进度", Toast.LENGTH_SHORT).show()
                        showAotLiveProgressDialog {
                            updateStatusText(aotBadge, enabledState(true))
                        }
                    } else {
                        runAotCompilationWithProgress("优化系统与桌面", isFull = false) {
                            updateStatusText(aotBadge, enabledState(true))
                        }
                    }
                },
                button("全部应用极速优化") {
                    if (AotCompileService.isRunning) {
                        Toast.makeText(this@MaxHzActivity, "当前已有优化任务在后台运行中，已为您打开进度", Toast.LENGTH_SHORT).show()
                        showAotLiveProgressDialog {
                            updateStatusText(aotBadge, enabledState(true))
                        }
                    } else {
                        runAotCompilationWithProgress("全部应用极速优化", isFull = true) {
                            updateStatusText(aotBadge, enabledState(true))
                        }
                    }
                },
                button("清除编译缓存") {
                    if (AotCompileService.isRunning) {
                        Toast.makeText(this@MaxHzActivity, "后台正在执行优化，请先等待完成或在进度弹窗中取消", Toast.LENGTH_SHORT).show()
                        return@button
                    }
                    val progressDialog = showActionProgressDialog("清除编译缓存", "正在恢复初始状态，请稍候...")
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            MaxHzController.restoreMDexopt()
                        }
                        if (progressDialog.isShowing) runCatching { progressDialog.dismiss() }
                        if (result.code == 0) {
                            MaxHzPrefs.setMDexoptEnabled(this@MaxHzActivity, false)
                            updateStatusText(aotBadge, enabledState(false))
                            Toast.makeText(this@MaxHzActivity, "清除编译缓存已生效", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MaxHzActivity, "操作失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ), dp(6))
        }, dp(8))

        parent.addSpacedView(singleActionCard(
            "应用缓存与空载进程清理",
            "调用 Android PackageManager 清理应用临时缓存，并回收后台空载缓存进程释放可用内存。",
            "立即清理释放",
            "释放应用缓存与空载后台进程",
            "执行 pm trim-caches 与 am kill-all，立即释放系统缓存与后台空载进程。",
            { MaxHzController.applyMQuickTrim() },
            refreshServiceAfter = false
        ), gap)
    }

    private fun showAotSpecificAppsPickerDialog(onCompleteState: () -> Unit) {
        if (!runCatching { MaxHzShell.isReady() }.getOrDefault(false)) {
            Toast.makeText(this, "特权通道未就绪，请先启动 Shizuku 或授予 Root 权限", Toast.LENGTH_SHORT).show()
            return
        }

        val loadingDialog = MaterialAlertDialogBuilder(this)
            .setTitle("自选应用 ART 预编译")
            .setView(emptyText("正在加载已安装应用列表，请稍候..."))
            .setNegativeButton("取消", null)
            .showUnified()

        lifecycleScope.launch {
            val allApps = withContext(Dispatchers.IO) {
                val installed = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getInstalledApplications(0)
                    }
                } catch (_: Throwable) {
                    emptyList<ApplicationInfo>()
                }
                installed
                    .asSequence()
                    .filter {
                        it.enabled &&
                            !it.packageName.isNullOrBlank() &&
                            it.packageName != packageName &&
                            (it.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
                    }
                    .map { info ->
                        val label = safeLoadLabel(info)
                        val isSys = isSystemApp(info)
                        val trustClass = MaxHzPackagePolicy.trustClass(this@MaxHzActivity, info)
                        UserAppChoice(
                            label = label,
                            packageName = info.packageName,
                            systemApp = isSys,
                            trustClass = trustClass
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedWith(compareBy<UserAppChoice> { it.systemApp }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                    .toList()
            }

            if (!loadingDialog.isShowing) return@launch
            runCatching { loadingDialog.dismiss() }

            if (allApps.isEmpty()) {
                Toast.makeText(this@MaxHzActivity, "未读取到可优化的应用列表", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val isDark = isDarkMode()
            val compiledMap = MaxHzPrefs.mDexoptCompiledMap(this@MaxHzActivity).toMutableMap()
            val iconCache = mutableMapOf<String, Drawable?>()
            val selectedPackages = linkedSetOf<String>()
            var currentTab = AotPickerTab.UNCOMPILED
            var selectedFilter = if (allApps.any { !it.systemApp }) AppListFilter.USER else AppListFilter.ALL
            var compileMode = "speed-profile" // 默认推荐热点极速编译（1~3秒完成，兼顾速度与体积）
            var searchQuery = ""
            val visibleApps = mutableListOf<UserAppChoice>()

            lateinit var tabContainer: LinearLayout
            lateinit var modeContainer: LinearLayout
            lateinit var modeDescView: TextView
            lateinit var filterContainer: LinearLayout
            lateinit var selectionCountView: TextView
            lateinit var emptyHintView: TextView
            lateinit var listView: ListView
            lateinit var listAdapter: ArrayAdapter<UserAppChoice>
            var pickerDialog: androidx.appcompat.app.AlertDialog? = null

            fun filteredApps(): List<UserAppChoice> {
                return allApps.filter { app ->
                    val isCompiled = compiledMap.containsKey(app.packageName)
                    val matchesTab = when (currentTab) {
                        AotPickerTab.UNCOMPILED -> !isCompiled
                        AotPickerTab.COMPILED -> isCompiled
                    }
                    if (!matchesTab) return@filter false

                    val matchesFilter = when (selectedFilter) {
                        AppListFilter.ALL -> true
                        AppListFilter.USER -> !app.systemApp
                        AppListFilter.SYSTEM -> app.systemApp
                    }
                    if (!matchesFilter) return@filter false
                    if (searchQuery.isBlank()) return@filter true
                    val q = searchQuery.lowercase(Locale.getDefault())
                    app.label.lowercase(Locale.getDefault()).contains(q) ||
                        app.packageName.lowercase(Locale.getDefault()).contains(q)
                }
            }

            fun updateSelectionSummary() {
                val tabTotal = filteredApps().size
                val tabLabel = if (currentTab == AotPickerTab.UNCOMPILED) "未编译" else "已编译"
                selectionCountView.text = if (selectedPackages.isNotEmpty()) {
                    "已勾选 ${selectedPackages.size} 个 · $tabLabel $tabTotal 个"
                } else {
                    "${tabLabel}共 $tabTotal 个应用"
                }
            }

            fun updateDialogButtons() {
                val btn = pickerDialog?.getButton(android.content.DialogInterface.BUTTON_POSITIVE) ?: return
                btn.text = when {
                    compileMode == "reset" -> "清除缓存"
                    currentTab == AotPickerTab.COMPILED -> "重新编译"
                    else -> "开始编译"
                }
            }

            fun refreshList() {
                visibleApps.clear()
                visibleApps.addAll(filteredApps())
                listAdapter.notifyDataSetChanged()
                updateSelectionSummary()

                if (visibleApps.isEmpty()) {
                    emptyHintView.visibility = View.VISIBLE
                    listView.visibility = View.GONE
                    emptyHintView.text = when {
                        searchQuery.isNotBlank() -> "未找到匹配的应用"
                        currentTab == AotPickerTab.COMPILED -> "暂无已编译应用\n请先在「未编译」中勾选应用进行加速优化"
                        else -> "所有应用均已完成编译加速"
                    }
                } else {
                    emptyHintView.visibility = View.GONE
                    listView.visibility = View.VISIBLE
                }
            }

            fun updateFilterChips() {
                filterContainer.removeAllViews()
                val tabApps = allApps.filter { app ->
                    if (currentTab == AotPickerTab.UNCOMPILED) !compiledMap.containsKey(app.packageName)
                    else compiledMap.containsKey(app.packageName)
                }
                val userCount = tabApps.count { !it.systemApp }
                val sysCount = tabApps.count { it.systemApp }
                listOf(
                    AppListFilter.USER to "用户 ($userCount)",
                    AppListFilter.ALL to "全部 (${tabApps.size})",
                    AppListFilter.SYSTEM to "系统 ($sysCount)"
                ).forEachIndexed { idx, (filter, label) ->
                    filterContainer.addView(
                        filterChip(label, selectedFilter == filter) {
                            selectedFilter = filter
                            updateFilterChips()
                            refreshList()
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) leftMargin = dp(6)
                        }
                    )
                }
            }

            fun updateModeChips() {
                modeContainer.removeAllViews()
                val modes = if (currentTab == AotPickerTab.UNCOMPILED) {
                    listOf(
                        "speed-profile" to "热点极速",
                        "speed" to "全量编译"
                    )
                } else {
                    listOf(
                        "speed-profile" to "热点极速",
                        "speed" to "全量编译",
                        "reset" to "重置缓存"
                    )
                }
                modes.forEachIndexed { idx, (modeKey, label) ->
                    modeContainer.addView(
                        filterChip(label, compileMode == modeKey) {
                            compileMode = modeKey
                            updateModeChips()
                            updateDialogButtons()
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) leftMargin = dp(6)
                        }
                    )
                }
                modeDescView.text = when (compileMode) {
                    "speed-profile" -> "热点极速（默认推荐 · speed-profile）：仅编译日常高频调用的 10%~20% 核心代码。耗时仅 1~3 秒、占 10~30MB 空间，即可获得约 90% 流畅度提升，兼顾速度与空间。"
                    "speed" -> "全量编译（深度拉满 · speed）：将应用内 100% 所有类与组件全部编译为原生机器码。大型应用耗时 15~60 秒、占 100~400MB 空间，适合重度主力应用或游戏。"
                    else -> "重置缓存（--reset）：清除所选应用生成的本地机器码（.odex）编译缓存，释放存储空间并恢复系统初始运行状态。"
                }
            }

            fun updateTabs() {
                tabContainer.removeAllViews()
                val uncompiledCount = allApps.count { !compiledMap.containsKey(it.packageName) }
                val compiledCount = allApps.count { compiledMap.containsKey(it.packageName) }

                val tabs = listOf(
                    AotPickerTab.UNCOMPILED to "未编译 ($uncompiledCount)",
                    AotPickerTab.COMPILED to "已编译 ($compiledCount)"
                )

                tabs.forEachIndexed { idx, (tab, title) ->
                    val isSelected = currentTab == tab
                    val tabView = TextView(this@MaxHzActivity).apply {
                        text = title
                        gravity = Gravity.CENTER
                        textSize = 13.5f
                        setTypeface(typeface, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else themeColors.onSurfaceVariant)
                        includeFontPadding = false
                        minHeight = dp(38)
                        background = GradientDrawable().apply {
                            cornerRadius = dp(11).toFloat()
                            if (isSelected) {
                                setColor(if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt())
                            } else {
                                setColor(android.graphics.Color.TRANSPARENT)
                            }
                        }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            if (currentTab == tab) return@setOnClickListener
                            playClickFeedback(this)
                            currentTab = tab
                            selectedPackages.clear()
                            if (currentTab == AotPickerTab.UNCOMPILED && compileMode == "reset") {
                                compileMode = "speed-profile"
                            }
                            updateTabs()
                            updateModeChips()
                            updateFilterChips()
                            refreshList()
                            updateDialogButtons()
                        }
                    }
                    tabContainer.addView(
                        tabView,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) leftMargin = dp(4)
                        }
                    )
                }
            }

            var showDialogSubtitles = false
            val expandedRowPkgs = mutableSetOf<String>()

            listView = ListView(this@MaxHzActivity).apply {
                dividerHeight = 0
                listAdapter = object : ArrayAdapter<UserAppChoice>(
                    this@MaxHzActivity,
                    android.R.layout.simple_list_item_1,
                    visibleApps
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val app = getItem(position) ?: visibleApps[position]
                        val isChecked = selectedPackages.contains(app.packageName)
                        val compiledMode = compiledMap[app.packageName]
                        val showSub = showDialogSubtitles || expandedRowPkgs.contains(app.packageName)
                        return LinearLayout(this@MaxHzActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = dp(52)
                            setPadding(dp(8), dp(7), dp(8), dp(7))
                            layoutParams = AbsListView.LayoutParams(
                                AbsListView.LayoutParams.MATCH_PARENT,
                                AbsListView.LayoutParams.WRAP_CONTENT
                            )
                            val iconDrawable = iconCache.getOrPut(app.packageName) {
                                runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                            }
                            addView(ImageView(this@MaxHzActivity).apply {
                                if (iconDrawable != null) {
                                    setImageDrawable(iconDrawable)
                                } else {
                                    setImageResource(R.drawable.ic_maxhz_apps_24)
                                    setColorFilter(themeColors.primary)
                                }
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                                rightMargin = dp(12)
                            })
                            addView(LinearLayout(this@MaxHzActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(LinearLayout(this@MaxHzActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(TextView(this@MaxHzActivity).apply {
                                        text = app.label
                                        textSize = 14.5f
                                        setTextColor(if (isChecked) themeColors.primary else themeColors.onSurface)
                                        setTypeface(typeface, if (isChecked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                                        includeFontPadding = false
                                        maxLines = 1
                                        ellipsize = TextUtils.TruncateAt.END
                                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                                    if (compiledMode != null) {
                                        addView(TextView(this@MaxHzActivity).apply {
                                            text = if (compiledMode == "speed") "已全量" else "已热点"
                                            textSize = 10.5f
                                            setTextColor(if (isDark) 0xFF34D399.toInt() else 0xFF059669.toInt())
                                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                                            includeFontPadding = false
                                            setPadding(dp(7), dp(2), dp(7), dp(2))
                                            background = GradientDrawable().apply {
                                                cornerRadius = dp(999).toFloat()
                                                setColor(if (isDark) 0x2610B981 else 0x1F10B981)
                                            }
                                        }, LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                        ).apply { leftMargin = dp(6) })
                                    } else {
                                        addView(TextView(this@MaxHzActivity).apply {
                                            text = "未编译"
                                            textSize = 10.5f
                                            setTextColor(if (isDark) 0xFF94A3B8.toInt() else 0xFF64748B.toInt())
                                            setTypeface(typeface, android.graphics.Typeface.NORMAL)
                                            includeFontPadding = false
                                            setPadding(dp(7), dp(2), dp(7), dp(2))
                                            background = GradientDrawable().apply {
                                                cornerRadius = dp(999).toFloat()
                                                setColor(if (isDark) 0x1FFFFFFF else 0x0F000000)
                                            }
                                        }, LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                        ).apply { leftMargin = dp(6) })
                                    }
                                })
                                if (showSub) {
                                    addView(TextView(this@MaxHzActivity).apply {
                                        text = "${app.packageName} · ${if (app.systemApp) "系统应用" else "用户应用"}"
                                        textSize = 11.5f
                                        setTextColor(themeColors.onSurfaceVariant)
                                        includeFontPadding = false
                                        setPadding(0, dp(2), 0, 0)
                                        maxLines = 1
                                        ellipsize = TextUtils.TruncateAt.END
                                    })
                                }
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            addView(CheckBox(this@MaxHzActivity).apply {
                                this.isChecked = isChecked
                                isClickable = false
                                isFocusable = false
                                buttonTintList = ColorStateList.valueOf(themeColors.primary)
                            }, LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { leftMargin = dp(6) })
                        }
                    }
                }
                adapter = listAdapter
                setOnItemClickListener { _, _, which, _ ->
                    val app = visibleApps.getOrNull(which) ?: return@setOnItemClickListener
                    if (selectedPackages.contains(app.packageName)) {
                        selectedPackages.remove(app.packageName)
                    } else {
                        selectedPackages.add(app.packageName)
                    }
                    listAdapter.notifyDataSetChanged()
                    updateSelectionSummary()
                }
                setOnItemLongClickListener { _, view, which, _ ->
                    val app = visibleApps.getOrNull(which) ?: return@setOnItemLongClickListener false
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    if (!expandedRowPkgs.remove(app.packageName)) {
                        expandedRowPkgs.add(app.packageName)
                    }
                    listAdapter.notifyDataSetChanged()
                    true
                }
            }

            emptyHintView = TextView(this@MaxHzActivity).apply {
                gravity = Gravity.CENTER
                textSize = 13.5f
                setTextColor(themeColors.onSurfaceVariant)
                setLineSpacing(0f, 1.3f)
                setPadding(dp(16), dp(40), dp(16), dp(40))
                visibility = View.GONE
            }

            val rootView = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), 0)

                tabContainer = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (isDark) 0xFF20232B.toInt() else 0xFFEAEFF8.toInt())
                    }
                }
                addView(tabContainer, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) })

                modeContainer = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(6))
                }
                addView(modeContainer)

                modeDescView = TextView(this@MaxHzActivity).apply {
                    textSize = 11.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setLineSpacing(0f, 1.2f)
                    setPadding(dp(2), 0, dp(2), dp(8))
                    visibility = View.VISIBLE
                }
                addView(modeDescView)

                addView(EditText(this@MaxHzActivity).apply {
                    hint = "搜索应用名称或包名"
                    textSize = 13.5f
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT
                    setTextColor(themeColors.onSurface)
                    setHintTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (isDark) 0xFF282C37.toInt() else 0xFFE6EAF2.toInt())
                    }
                    minHeight = dp(40)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            searchQuery = s?.toString().orEmpty()
                            refreshList()
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40)
                ).apply { bottomMargin = dp(8) })

                filterContainer = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(6))
                }
                addView(filterContainer)

                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), 0, dp(4), dp(6))
                    selectionCountView = TextView(this@MaxHzActivity).apply {
                        text = "未编译共 0 个应用"
                        textSize = 12f
                        setTextColor(themeColors.primary)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    addView(selectionCountView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MaxHzActivity).apply {
                        styleOptionBTogglePill(this, showDialogSubtitles)
                        setOnClickListener { v ->
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            showDialogSubtitles = !showDialogSubtitles
                            expandedRowPkgs.clear()
                            styleOptionBTogglePill(this, showDialogSubtitles)
                            listAdapter.notifyDataSetChanged()
                        }
                    })
                    addView(TextView(this@MaxHzActivity).apply {
                        text = "全选"
                        textSize = 12f
                        setTextColor(themeColors.primary)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(dp(10), dp(4), dp(6), dp(4))
                        setOnClickListener {
                            visibleApps.forEach { selectedPackages.add(it.packageName) }
                            listAdapter.notifyDataSetChanged()
                            updateSelectionSummary()
                        }
                    })
                    addView(TextView(this@MaxHzActivity).apply {
                        text = "清空"
                        textSize = 12f
                        setTextColor(themeColors.onSurfaceVariant)
                        setPadding(dp(6), dp(4), dp(4), dp(4))
                        setOnClickListener {
                            selectedPackages.clear()
                            listAdapter.notifyDataSetChanged()
                            updateSelectionSummary()
                        }
                    })
                })

                val listContainer = FrameLayout(this@MaxHzActivity).apply {
                    addView(listView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    addView(emptyHintView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                }
                addView(listContainer, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(330)
                ))

                updateTabs()
                updateModeChips()
                updateFilterChips()
                refreshList()
            }

            pickerDialog = MaterialAlertDialogBuilder(this@MaxHzActivity)
                .setTitle("自选应用 ART 预编译加速")
                .setView(rootView)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始执行", null)
                .showUnified()

            pickerDialog?.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                if (selectedPackages.isEmpty()) {
                    val actionVerb = if (compileMode == "reset") "清除缓存" else "编译加速"
                    Toast.makeText(this@MaxHzActivity, "请至少勾选 1 个需要${actionVerb}的应用", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val appMap = allApps.associateBy { it.packageName }
                val targetList = selectedPackages.map { pkg ->
                    val app = appMap[pkg]
                    pkg to (app?.label ?: pkg)
                }
                pickerDialog?.dismiss()
                runSelectedAppsAotWithProgress(targetList, compileMode, onCompleteState)
            }
            updateDialogButtons()
        }
    }

    private var aotLiveProgressDialog: androidx.appcompat.app.AlertDialog? = null

    private fun runSelectedAppsAotWithProgress(
        appList: List<Pair<String, String>>,
        compileMode: String,
        onCompleteState: () -> Unit
    ) {
        if (appList.isEmpty()) return
        if (!runCatching { MaxHzShell.isReady() }.getOrDefault(false)) {
            Toast.makeText(this, "特权通道未就绪，请先启动 Shizuku 或授予 Root 权限", Toast.LENGTH_SHORT).show()
            return
        }
        val isReset = compileMode == "reset"
        val modeTitle = when (compileMode) {
            "speed" -> "自选应用全量深度编译"
            "speed-profile" -> "自选应用热点极速优化"
            else -> "重置自选应用编译缓存"
        }
        val actualMode = if (isReset) "--reset" else compileMode
        AotCompileService.start(this@MaxHzActivity, modeTitle, actualMode, appList)
        showAotLiveProgressDialog(onCompleteState)
    }

    private fun runAotCompilationWithProgress(
        title: String,
        isFull: Boolean,
        onCompleteState: () -> Unit
    ) {
        if (!runCatching { MaxHzShell.isReady() }.getOrDefault(false)) {
            Toast.makeText(this, "特权通道未就绪，请先启动 Shizuku 或授予 Root 权限", Toast.LENGTH_SHORT).show()
            return
        }
        val loadingDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("正在统计待优化的应用列表，请稍候...")
            .setCancelable(false)
            .showUnified()

        lifecycleScope.launch {
            val appList = withContext(Dispatchers.IO) {
                val corePackages = listOf(
                    "com.sec.android.app.launcher",
                    "com.android.systemui",
                    "com.sec.android.gallery3d",
                    "com.android.settings",
                    "com.sec.android.app.sbrowser",
                    "com.miui.home",
                    "com.android.launcher",
                    "com.bbk.launcher2",
                    "com.hihonor.android.launcher",
                    "com.meizu.flyme.launcher"
                )
                val targetPackages = mutableListOf<Pair<String, String>>()
                for (pkg in corePackages) {
                    try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        if (appInfo.enabled && (appInfo.flags and ApplicationInfo.FLAG_HAS_CODE) != 0) {
                            val label = appInfo.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                            targetPackages.add(pkg to label)
                        }
                    } catch (_: Throwable) {}
                }
                if (isFull) {
                    val installed = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.getInstalledApplications(0)
                        }
                    } catch (_: Throwable) {
                        emptyList<ApplicationInfo>()
                    }
                    val existing = targetPackages.map { it.first }.toSet()
                    for (info in installed) {
                        if (!info.enabled) continue
                        if ((info.flags and ApplicationInfo.FLAG_HAS_CODE) == 0) continue
                        val pkg = info.packageName ?: continue
                        if (pkg == packageName) continue
                        if (existing.contains(pkg)) continue
                        if (!isSystemApp(info)) {
                            val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                            targetPackages.add(pkg to label)
                        }
                    }
                }
                targetPackages
            }

            if (!loadingDialog.isShowing) return@launch
            runCatching { loadingDialog.dismiss() }

            if (appList.isEmpty()) {
                Toast.makeText(this@MaxHzActivity, "未检测到可优化的应用", Toast.LENGTH_SHORT).show()
                return@launch
            }

            AotCompileService.start(this@MaxHzActivity, title, "speed-profile", appList)
            showAotLiveProgressDialog(onCompleteState)
        }
    }

    private fun showAotLiveProgressDialog(onCompleteState: (() -> Unit)? = null) {
        if (aotLiveProgressDialog?.isShowing == true) return
        val isDark = isDarkMode()

        val progressBar = ProgressBar(this@MaxHzActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(themeColors.primary)
            indeterminateTintList = ColorStateList.valueOf(themeColors.primary)
            progressBackgroundTintList = ColorStateList.valueOf(if (isDark) 0x33FFFFFF.toInt() else 0x1A000000.toInt())
        }
        val statusTextView = TextView(this@MaxHzActivity).apply {
            text = "正在初始化预编译..."
            textSize = 15f
            setTextColor(themeColors.onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val currentAppTextView = TextView(this@MaxHzActivity).apply {
            text = "正在连接特权编译器..."
            textSize = 13f
            setTextColor(themeColors.onSurfaceVariant)
            setLineSpacing(0f, 1.25f)
        }

        val contentLayout = LinearLayout(this@MaxHzActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(statusTextView)
            addView(progressBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(10)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            })
            addView(currentAppTextView)
        }

        val initialTitle = AotCompileService.stateFlow.value.title.ifBlank { "应用预编译加速" }

        val dialog = MaterialAlertDialogBuilder(this@MaxHzActivity)
            .setTitle(initialTitle)
            .setView(contentLayout)
            .setCancelable(false)
            .setPositiveButton("转到后台") { d, _ ->
                d.dismiss()
                Toast.makeText(this@MaxHzActivity, "已转入后台继续编译，可在通知栏查看变动进度与耗时", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消编译") { d, _ ->
                AotCompileService.cancel(this@MaxHzActivity)
                d.dismiss()
                Toast.makeText(this@MaxHzActivity, "正在停止预编译...", Toast.LENGTH_SHORT).show()
            }
            .create()

        aotLiveProgressDialog = dialog
        dialog.show()
        applyUnifiedDialogWindowStyle(dialog)

        var observeJob: Job? = null
        observeJob = lifecycleScope.launch {
            AotCompileService.stateFlow.collect { state ->
                if (!dialog.isShowing) {
                    observeJob?.cancel()
                    return@collect
                }

                if (state.title.isNotBlank()) {
                    dialog.setTitle(state.title)
                }

                val currentStep = state.currentStep
                val total = state.total
                val percent = if (total > 0) (currentStep * 100) / total else 0

                if (total <= 1 && currentStep == 0) {
                    progressBar.isIndeterminate = true
                } else {
                    progressBar.isIndeterminate = false
                    progressBar.progress = percent
                }

                val actionWord = if (state.compileMode == "--reset" || state.compileMode == "reset") "重置" else "优化"
                statusTextView.text = if (total > 0) {
                    "正在$actionWord [$currentStep/$total] ($percent%)"
                } else {
                    "正在准备环境..."
                }

                val label = state.currentLabel.ifBlank { "准备中..." }
                val pkg = state.currentPkg
                val currentSec = state.currentElapsedSec
                val totalSec = state.totalElapsedSec
                val timeInfo = if (currentSec > 0) "已耗时 ${currentSec}s" else "正在处理..."
                val totalTimeInfo = if (totalSec < 60) "${totalSec}秒" else "${totalSec / 60}分${totalSec % 60}秒"

                currentAppTextView.text = buildString {
                    append("当前应用：$label（$timeInfo）")
                    if (pkg.isNotBlank()) {
                        append("\n包名：$pkg")
                    }
                    append("\n总耗时：$totalTimeInfo · 成功已完成 ${state.successCount} 个")
                    if (state.compileMode == "speed") {
                        append("\n全量深度编译中，大型应用耗时较长，请耐心等待")
                    }
                }

                if (!state.isRunning) {
                    dialog.dismiss()
                    observeJob?.cancel()
                    if (state.isCompleted) {
                        MaxHzPrefs.setMDexoptEnabled(this@MaxHzActivity, true)
                        onCompleteState?.invoke()
                        showAotCompletionSummaryDialog(state)
                    } else if (state.isCancelled) {
                        Toast.makeText(
                            this@MaxHzActivity,
                            "已停止预编译（已完成 ${state.successCount} / ${state.total} 个应用）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            observeJob?.cancel()
            if (aotLiveProgressDialog === dialog) {
                aotLiveProgressDialog = null
            }
        }
    }

    private fun showAotCompletionSummaryDialog(state: AotCompileService.TaskState) {
        val isReset = state.compileMode == "reset" || state.compileMode == "--reset"
        val totalSec = state.totalElapsedSec
        val timeStr = if (totalSec < 60) "${totalSec}秒" else "${totalSec / 60}分${totalSec % 60}秒"
        val titleText = when {
            isReset -> if (state.successCount > 0) "编译缓存已重置" else "重置未生效"
            state.successCount == state.total -> "${state.title}已完成"
            state.successCount > 0 -> "${state.title}部分完成 (${state.successCount} / ${state.total})"
            else -> "${state.title}未成功"
        }

        val msg = buildString {
            if (isReset) {
                append("已成功重置 ${state.successCount} / ${state.total} 个应用的编译缓存（总耗时 $timeStr）。")
            } else if (state.successCount == 0) {
                append("所选 ${state.total} 个应用均未能完成预编译：\n\n")
            } else {
                append("已成功为 ${state.successCount} / ${state.total} 个应用完成预编译加速（总耗时 $timeStr）！\n冷启动与界面响应速度已显著提升。\n\n")
            }
            if (state.succeededSummary.isNotEmpty()) {
                append("已完成项：\n").append(state.succeededSummary.take(10).joinToString("\n"))
                if (state.succeededSummary.size > 10) {
                    append("\n…等共 ${state.succeededSummary.size} 个应用")
                }
            }
            if (state.failedSummary.isNotEmpty()) {
                append("\n\n未完成项：\n").append(state.failedSummary.take(5).joinToString("\n"))
            }
        }

        MaterialAlertDialogBuilder(this@MaxHzActivity)
            .setTitle(titleText)
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .showUnified()
    }

    private fun hardenScreenOffKeepAlive(): MaxHzShell.Result {
        if (!runCatching { MaxHzShell.isReady() }.getOrDefault(false)) {
            return MaxHzShell.Result(-1, "", "特权通道未就绪，请先启动 Shizuku 或授予 Root。")
        }
        val pkg = packageName
        return MaxHzShell.exec("""
            cmd appops set $pkg ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
            cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null || true
            cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            dumpsys deviceidle whitelist +$pkg 2>/dev/null || true
            am set-standby-bucket $pkg active 2>/dev/null || true
            echo "已成功加固：已禁用息屏权限自动回收，已授权无限制后台活动并加入白名单。"
        """.trimIndent())
    }

    private fun repairToolsSection(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(singleActionCard(
                "息屏防杀与权限防回收",
                "一键解除系统权限自动撤销，将本应用加入 Doze 白名单与常驻待机桶，防止息屏后无障碍与服务被杀。",
                "一键加固",
                "息屏防杀与权限保活加固",
                "执行底层加固：解除 AUTO_REVOKE 权限回收，加入后台白名单，保持 active 待机桶。",
                { hardenScreenOffKeepAlive() },
                refreshServiceAfter = false
            ))
            addSpacedView(singleActionCard(
                "总恢复",
                "恢复本应用修改的系统状态，保留用户创建的应用名单。",
                "执行",
            "恢复系统默认接管",
            "执行总恢复：省电、刷新率、Doze、电池保护和系统调优交回系统默认接管；保留用户创建的应用名单。",
                { restoreAllAndUpdateUi() },
                refreshServiceAfter = false
            ), gap)
        }
    }

    private fun restoreAllAndUpdateUi(): MaxHzShell.Result {
        if (!runCatching { MaxHzShell.isReady() }.getOrDefault(false)) {
            return MaxHzShell.Result(
                -3,
                "",
                "无权限执行总恢复。请先启动 Shizuku，并授予本应用权限后重试。"
            )
        }
        val nightGuardPackages = MaxHzPrefs.nightWakeupGuardPackages(this)
            .plus(MaxHzPrefs.nightWakeupGuardAppliedPackages(this))
            .distinct()
        runCatching { stopService(Intent(this, MaxHzService::class.java)) }

        val result = safeRestoreStep("总恢复") {
            MaxHzController.restoreAllPublicDefaults(nightGuardPackages)
        }
        if (result.code == 0) {
            MaxHzPrefs.setControlEnabled(this, false)
            MaxHzPrefs.setCompatibleSaverEnabled(this, false)
            MaxHzPrefs.setQuickDozeEnabled(this, false)
            MaxHzPrefs.setNightWakeupGuardEnabled(this, false)
            MaxHzPrefs.setScanGuardEnabled(this, false)
            MaxHzPrefs.setBatteryProtectionEnabled(this, false)
            MaxHzPrefs.setPassThroughEnabled(this, false)
            MaxHzPrefs.setGameTileMode(this, 0)
            MaxHzPrefs.markSuperPerformanceState(this, false)
            MaxHzPrefs.setGameAutoPerformanceEnabled(this, false)
            MaxHzController.quickSwitchGameDefault()
            MaxHzPrefs.clearNightWakeupGuardAppliedPackages(this)
            MaxHzPrefs.clearMFeatureStates(this)
            MaxHzPrefs.clearResult(this, SYSTEM_RESIDUE_RESULT_KEY)
            MaxHzPrefs.clearResult(this, "force_resizable")
        }
        if (result.code == 0) {
            runCatching { updateMaxHzServiceState() }
        }
        return result
    }

    private fun safeRestoreStep(label: String, block: () -> MaxHzShell.Result): MaxHzShell.Result {
        return try {
            block()
        } catch (t: Throwable) {
            MaxHzShell.Result(-2, "", "$label 异常：${t.message ?: t.javaClass.simpleName}")
        }
    }

    protected fun openRepairToolsSection(): View = repairToolsSection()
    protected fun restoreAllSystemDefaults(): MaxHzShell.Result = restoreAllAndUpdateUi()

    protected fun applyLiquidGlassCardStyle(card: MaterialCardView, radiusDp: Int = CARD_RADIUS_DP) {
        val radiusPx = dp(radiusDp).toFloat()
        val isDark = isDarkMode()
        card.radius = radiusPx
        card.cardElevation = 0f
        card.maxCardElevation = 0f
        card.useCompatPadding = false
        card.preventCornerOverlap = false
        card.strokeWidth = 0
        card.setCardBackgroundColor(Color.TRANSPARENT)
        val cardBgColor = if (isDark) 0xFF181A20.toInt() else 0xFFFFFFFF.toInt()
        card.background = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(cardBgColor)
        }
    }

    private fun card(content: LinearLayout.() -> Unit): MaterialCardView {
        return MaterialCardView(this).apply {
            applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP))
                installClickFeedback(this)
                setOnClickListener {
                    playClickFeedback(this)
                    collapseVisibleResultInside(this)
                }
                content()
            })
        }
    }

    private fun compactCard(content: LinearLayout.() -> Unit): MaterialCardView {
        return MaterialCardView(this).apply {
            applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                installClickFeedback(this)
                setOnClickListener {
                    playClickFeedback(this)
                    collapseVisibleResultInside(this)
                }
                content()
            })
        }
    }

    private fun errorCard(title: String, message: String): MaterialCardView {
        return card {
            addView(TextView(this@MaxHzActivity).apply {
                text = title
                textSize = 18f
                setTextColor(themeColors.error)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MaxHzActivity).apply {
                text = message.ifBlank { "未知错误" }
                textSize = 13f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun featureCard(
        title: String,
        subtitle: String,
        headerStateView: View? = null,
        content: LinearLayout.() -> Unit
    ): MaterialCardView {
        return MaterialCardView(this).apply {
            applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP))
                installClickFeedback(this)
                setOnClickListener {
                    playClickFeedback(this)
                    collapseVisibleResultInside(this)
                }
                val headerContainer = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val icon = featureIconContainer(title)
                        addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                            rightMargin = dp(16)
                        })
                        addView(TextView(this@MaxHzActivity).apply {
                            text = featureTitle(title)
                            textSize = 16f
                            setTextColor(themeColors.onSurface)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            includeFontPadding = false
                            maxLines = 1
                            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 13, 16, 1, TypedValue.COMPLEX_UNIT_SP)
                        }, LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ))
                    })
                    headerStateView?.let {
                        addView(it, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6) })
                    }
                }
                addView(headerContainer)
                val subTextView = TextView(this@MaxHzActivity).apply {
                    text = subtitle
                    textSize = 13.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(4), 0, dp(6))
                }
                addView(subTextView)
                bindOptionBSubtitle(this, subTextView)
                bindOptionBSubtitle(headerContainer, subTextView)
                content()
            })
        }
    }

    private fun showRootRequiredDialog(featureName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("需要 Root 权限")
            .setMessage("“$featureName”需要直接向底层硬件内核驱动或电源管理芯片写入控制指令，必须具备已授权的 Root 权限（如 KernelSU / Magisk / APatch）。\n\n当前设备未检测到 Root 授权，已自动禁用该功能以确保系统稳定与安全。")
            .setPositiveButton("我知道了", null)
            .showUnified()
    }

    private fun toggleCard(
        title: String,
        subtitle: String,
        actionLabel: String,
        actionExplanation: String,
        actionBlock: () -> MaxHzShell.Result,
        restoreLabel: String,
        restoreExplanation: String,
        restoreBlock: () -> MaxHzShell.Result,
        stateProvider: () -> String,
        requireRoot: Boolean = false,
        afterToggle: (() -> Unit)? = null
    ): View {
        val hasRoot = MaxHzShell.isRootAvailable()
        val isBlocked = requireRoot && !hasRoot
        val displaySubtitle = if (isBlocked) {
            "$subtitle\n需 Root 权限 (未检测到 Root 授权，已自动禁用)"
        } else if (requireRoot) {
            "$subtitle\n需 Root 权限 (已获得 Root 硬件直通)"
        } else {
            subtitle
        }

        return MaterialCardView(this).apply {
            applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
            if (isBlocked) {
                alpha = 0.7f
            }
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP))
                if (isBlocked) {
                    setOnClickListener {
                        showRootRequiredDialog(title)
                    }
                }

                val icon = featureIconContainer(title)
                addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    rightMargin = dp(16)
                })

                lateinit var subTextView: TextView
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MaxHzActivity).apply {
                        text = featureTitle(title)
                        textSize = 16f
                        setTextColor(themeColors.onSurface)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    subTextView = TextView(this@MaxHzActivity).apply {
                        text = displaySubtitle
                        textSize = 13f
                        setTextColor(if (isBlocked) themeColors.error else themeColors.onSurfaceVariant)
                        includeFontPadding = false
                        setPadding(0, dp(4), 0, 0)
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    addView(subTextView)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                })
                bindOptionBSubtitle(this, subTextView)

                var suppress = false
                val toggle = MaterialSwitch(this@MaxHzActivity).apply {
                    showText = false
                    minWidth = dp(52)
                    minimumWidth = dp(52)
                    isChecked = if (isBlocked) false else stateTextMeansOn(stateProvider())
                    isEnabled = !isBlocked
                    LiquidGlassTheme.styleIosSwitch(this, isDarkMode())
                    setOnCheckedChangeListener { _, checked ->
                        if (isBlocked) {
                            isChecked = false
                            showRootRequiredDialog(title)
                            return@setOnCheckedChangeListener
                        }
                        if (suppress) return@setOnCheckedChangeListener
                        val (label, explanation, block) = if (checked) {
                            Triple(actionLabel, actionExplanation, actionBlock)
                        } else {
                            Triple(restoreLabel, restoreExplanation, restoreBlock)
                        }
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try { block() } catch (t: Throwable) { MaxHzShell.Result(-2, "", t.message.orEmpty()) }
                            }
                            if (result.code != 0) {
                                suppress = true
                                isChecked = !checked
                                suppress = false
                                Toast.makeText(this@MaxHzActivity, "操作失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                            } else {
                                afterToggle?.invoke()
                            }
                        }
                    }
                }
                addView(toggle, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            })
        }
    }

    private fun actionCard(
        title: String,
        subtitle: String,
        actionText: String,
        actionLabel: String,
        actionExplanation: String,
        actionBlock: () -> MaxHzShell.Result,
        restoreText: String,
        restoreLabel: String,
        restoreExplanation: String,
        restoreBlock: () -> MaxHzShell.Result,
        stateProvider: (() -> String)? = null,
        requireRoot: Boolean = false,
        actionConfirmation: (() -> ConfirmationPrompt?)? = null
    ): View {
        if (stateProvider != null) {
            return toggleCard(
                title,
                subtitle,
                actionLabel,
                actionExplanation,
                actionBlock,
                restoreLabel,
                restoreExplanation,
                restoreBlock,
                stateProvider,
                requireRoot
            )
        }
        val hasRoot = MaxHzShell.isRootAvailable()
        val isBlocked = requireRoot && !hasRoot
        val displaySubtitle = if (isBlocked) {
            "$subtitle\n需 Root 权限 (未检测到 Root 授权，已自动禁用)"
        } else if (requireRoot) {
            "$subtitle\n需 Root 权限 (已获得 Root 硬件直通)"
        } else {
            subtitle
        }

        return featureCard(title, displaySubtitle) {
            if (isBlocked) {
                alpha = 0.7f
            }
            addSpacedView(horizontalActions(
                button(actionText) {
                    if (isBlocked) {
                        showRootRequiredDialog(title)
                        return@button
                    }
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { actionBlock() } catch (t: Throwable) { MaxHzShell.Result(-2, "", t.message.orEmpty()) }
                        }
                        if (result.code == 0) {
                            Toast.makeText(this@MaxHzActivity, "$actionLabel 已生效", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MaxHzActivity, "操作失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                button(restoreText) {
                    if (isBlocked) {
                        showRootRequiredDialog(title)
                        return@button
                    }
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { restoreBlock() } catch (t: Throwable) { MaxHzShell.Result(-2, "", t.message.orEmpty()) }
                        }
                        if (result.code == 0) {
                            Toast.makeText(this@MaxHzActivity, "$restoreLabel 已生效", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MaxHzActivity, "操作失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ), dp(16))
        }
    }

    private fun showActionProgressDialog(title: String, message: String): androidx.appcompat.app.AlertDialog {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(this@MaxHzActivity).apply {
                isIndeterminate = true
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(16)
            })
            addView(TextView(this@MaxHzActivity).apply {
                text = message
                textSize = 15f
                setTextColor(themeColors.onSurface)
            })
        }
        return MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(layout)
            .setCancelable(false)
            .showUnified()
    }

    private fun singleActionCard(
        title: String,
        subtitle: String,
        actionText: String,
        actionLabel: String,
        actionExplanation: String,
        actionBlock: () -> MaxHzShell.Result,
        stateProvider: (() -> String)? = null,
        requireRoot: Boolean = false,
        refreshServiceAfter: Boolean = true
    ): View {
        val hasRoot = MaxHzShell.isRootAvailable()
        val isBlocked = requireRoot && !hasRoot
        val displaySubtitle = if (isBlocked) {
            "$subtitle\n需 Root 权限 (未检测到 Root 授权，已自动禁用)"
        } else if (requireRoot) {
            "$subtitle\n需 Root 权限 (已获得 Root 硬件直通)"
        } else {
            subtitle
        }

        val stateView = stateProvider?.let { statusBadge(it()) }
        return featureCard(title, displaySubtitle, headerBadges(stateView)) {
            if (isBlocked) {
                alpha = 0.7f
            }
            addSpacedView(horizontalActions(
                button(actionText) {
                    if (isBlocked) {
                        showRootRequiredDialog(title)
                        return@button
                    }
                    val progressDialog = showActionProgressDialog(actionLabel, "正在执行中，请稍候...")
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { actionBlock() } catch (t: Throwable) { MaxHzShell.Result(-2, "", t.message.orEmpty()) }
                        }
                        if (progressDialog.isShowing) {
                            runCatching { progressDialog.dismiss() }
                        }
                        if (result.code == 0) {
                            updateStatusText(stateView, stateProvider?.invoke().orEmpty())
                            if (refreshServiceAfter) runCatching { updateMaxHzServiceState() }
                            Toast.makeText(this@MaxHzActivity, "$actionLabel 已生效", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MaxHzActivity, "操作失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ), dp(10))
        }
    }

    private fun dualActionCard(
        title: String,
        subtitle: String,
        firstText: String,
        firstLabel: String,
        firstExplanation: String,
        firstBlock: () -> MaxHzShell.Result,
        secondText: String,
        secondLabel: String,
        secondExplanation: String,
        secondBlock: () -> MaxHzShell.Result,
        restoreText: String,
        restoreLabel: String,
        restoreExplanation: String,
        restoreBlock: () -> MaxHzShell.Result,
        stateProvider: (() -> String)? = null,
        requireRoot: Boolean = false,
        firstConfirmation: (() -> ConfirmationPrompt?)? = null,
        secondConfirmation: (() -> ConfirmationPrompt?)? = null
    ): View {
        val hasRoot = MaxHzShell.isRootAvailable()
        val isBlocked = requireRoot && !hasRoot
        val displaySubtitle = if (isBlocked) {
            "$subtitle\n需 Root 权限 (未检测到 Root 授权，已自动禁用)"
        } else if (requireRoot) {
            "$subtitle\n需 Root 权限 (已获得 Root 硬件直通)"
        } else {
            subtitle
        }

        val stateView = stateProvider?.let { statusBadge(it()) }
        return featureCard(title, displaySubtitle, headerBadges(stateView)) {
            if (isBlocked) {
                alpha = 0.7f
            }
            addSpacedView(horizontalActions(
                button(firstText) {
                    if (isBlocked) {
                        showRootRequiredDialog(title)
                        return@button
                    }
                    val progressDialog = showActionProgressDialog(firstLabel, "正在执行中，请稍候...")
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { firstBlock() } catch (t: Throwable) { MaxHzShell.Result(-2, "", t.message.orEmpty()) }
                        }
                        if (progressDialog.isShowing) {
                            runCatching { progressDialog.dismiss() }
                        }
                        if (result.code == 0) {
                            updateStatusText(stateView, stateProvider?.invoke().orEmpty())
                            Toast.makeText(this@MaxHzActivity, "$firstLabel 已生效", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MaxHzActivity, "操作失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                button(secondText) {
                    if (isBlocked) {
                        showRootRequiredDialog(title)
                        return@button
                    }
                    val progressDialog = showActionProgressDialog(secondLabel, "正在执行中，请稍候...")
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { secondBlock() } catch (t: Throwable) { MaxHzShell.Result(-2, "", t.message.orEmpty()) }
                        }
                        if (progressDialog.isShowing) {
                            runCatching { progressDialog.dismiss() }
                        }
                        if (result.code == 0) {
                            updateStatusText(stateView, stateProvider?.invoke().orEmpty())
                            Toast.makeText(this@MaxHzActivity, "$secondLabel 已生效", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MaxHzActivity, "操作失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                button(restoreText) {
                    if (isBlocked) {
                        showRootRequiredDialog(title)
                        return@button
                    }
                    val progressDialog = showActionProgressDialog(restoreLabel, "正在恢复中，请稍候...")
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { restoreBlock() } catch (t: Throwable) { MaxHzShell.Result(-2, "", t.message.orEmpty()) }
                        }
                        if (progressDialog.isShowing) {
                            runCatching { progressDialog.dismiss() }
                        }
                        if (result.code == 0) {
                            updateStatusText(stateView, stateProvider?.invoke().orEmpty())
                            Toast.makeText(this@MaxHzActivity, "$restoreLabel 已生效", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MaxHzActivity, "操作失败：${commandFailureReason(result)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ), dp(6))
        }
    }

    private fun statusBadge(text: String): TextView {
        return TextView(this).apply {
            textSize = 11.5f
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = dp(26)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(2), dp(12), dp(2))
            maxLines = 1
            updateStatusText(this, text)
        }
    }

    private fun updateStatusText(view: TextView?, text: String) {
        view ?: return
        val changed = view.text?.toString() != text
        view.text = text
        val isDark = isDarkMode()
        // 原先文字色取「跟填充同色相的另一个值」：浅色警告填充 #D97A05 配文字
        // #D97706，实测只有 1.03:1，字完全糊进底里。现在填充与文字由同一份
        // 语义判定（badgeKind）驱动，文字再按 WCAG 相对亮度收敛到 4.5:1。
        val kind = LiquidGlassTheme.badgeKind(text)
        val preferred = when (kind) {
            LiquidGlassTheme.BadgeKind.SUCCESS -> if (isDark) 0xFF34D399.toInt() else 0xFF059669.toInt()
            LiquidGlassTheme.BadgeKind.WARNING -> if (isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
            LiquidGlassTheme.BadgeKind.MUTED -> themeColors.onSurfaceVariant
            else -> themeColors.primary
        }
        view.setTextColor(LiquidGlassTheme.badgeText(kind, isDark, preferred))
        view.background = LiquidGlassTheme.createBadgeDrawable(text, isDark)
        if (changed) {
            view.alpha = 0.65f
            view.animate().alpha(1f).setDuration(160L).start()
        }
    }

    private fun switchActionRow(
        stateProvider: () -> String,
        stateView: TextView?,
        actionText: String,
        actionLabel: String,
        actionExplanation: String,
        actionBlock: () -> MaxHzShell.Result,
        restoreText: String,
        restoreLabel: String,
        restoreExplanation: String,
        restoreBlock: () -> MaxHzShell.Result,
        resultView: TextView,
        actionConfirmation: (() -> ConfirmationPrompt?)?,
        afterSync: (() -> Unit)? = null
    ): View {
        var suppress = false
        lateinit var toggle: MaterialSwitch
        lateinit var titleView: TextView
        lateinit var summaryView: TextView

        fun checkedNow(): Boolean = stateTextMeansOn(stateProvider())

        fun syncState() {
            val stateText = stateProvider()
            updateStatusText(stateView, stateText)
            suppress = true
            toggle.isChecked = stateTextMeansOn(stateText)
            titleView.text = if (toggle.isChecked) "当前状态：已开启" else "当前状态：已关闭"
            summaryView.text = if (toggle.isChecked) "轻触右侧开关可恢复系统默认" else "轻触右侧开关可一键开启"
            afterSync?.invoke()
            suppress = false
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(10))
            background = rounded(themeColors.surfaceContainerHigh, dp(22).toFloat())
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                titleView = TextView(this@MaxHzActivity).apply {
                    textSize = 15.5f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                summaryView = TextView(this@MaxHzActivity).apply {
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(titleView)
                addView(summaryView)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            toggle = MaterialSwitch(this@MaxHzActivity).apply {
                showText = false
                minWidth = dp(52)
                minimumWidth = dp(52)
                isChecked = checkedNow()
                setOnCheckedChangeListener { _, checked ->
                    if (suppress) return@setOnCheckedChangeListener
                    titleView.text = if (checked) "应用中" else "恢复中"
                    summaryView.text = if (checked) actionLabel else restoreLabel
                    isEnabled = false
                    val finish = {
                        isEnabled = true
                        syncState()
                    }
                    if (checked) {
                        runCommandMaybeConfirmed(
                            resultView,
                            actionLabel,
                            actionExplanation,
                            actionConfirmation,
                            onFinished = finish,
                            onCancelled = finish,
                            block = actionBlock
                        )
                    } else {
                        runCommand(
                            resultView,
                            restoreLabel,
                            restoreExplanation,
                            onFinished = finish,
                            block = restoreBlock
                        )
                    }
                }
            }
            addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) })
            syncState()
        }
    }

    private fun stateTextMeansOn(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        return normalized.startsWith("开") ||
            normalized.contains("开启") ||
            normalized.contains("启用") ||
            normalized.contains("已开启") ||
            normalized.contains("已启用") ||
            normalized.contains("部分生效") ||
            normalized.contains("性能") ||
            normalized.contains("省电") ||
            normalized.contains("旁路开启") ||
            normalized.contains("电池开启")
    }

    private fun statusStyleFor(text: String): StatusStyle {
        val normalized = text.trim()
        val lower = normalized.lowercase(Locale.US)
        return when {
            normalized.contains("失败") ||
            normalized.contains("错误") ||
            normalized.contains("异常") ||
            normalized.contains("未通过") ||
            normalized.contains("后台错误") ||
                normalized.contains("需要处理") ||
            normalized.contains("权限缺失") ||
                normalized.contains("未连接") ||
                normalized.contains("服务异常") ||
                normalized.contains("服务未运行") ||
                lower.contains("error") -> StatusStyle(themeColors.error, themeColors.errorContainer)

            normalized.contains("建议观察") ||
                normalized.contains("建议谨慎处理") ||
                normalized.contains("建议注意") ||
                normalized.contains("未确认") ||
                normalized.contains("等待") ||
                normalized.contains("候选") ||
                normalized.contains("历史错误") ||
                normalized.contains("可能残留") ||
                normalized.contains("部分生效") ||
                normalized.contains("需要开启") ||
                normalized.contains("待授权") ||
                normalized.contains("未生效") ||
                normalized.contains("未应用") ||
                normalized.contains("需无障碍") -> StatusStyle(themeColors.onWarningContainer, themeColors.warningContainer)

            normalized.contains("已关闭") ||
                normalized.contains("关闭") ||
                normalized.contains("未启用") ||
                normalized.contains("未开启") ||
                normalized.contains("未配置") ||
                normalized.contains("未检测") ||
                normalized.contains("空名单") ||
                normalized.contains("不适用") ||
                normalized == "未运行" ||
                normalized == "未启用" ||
                normalized == "无" ||
                normalized == "关" -> StatusStyle(themeColors.onSurfaceVariant, themeColors.surfaceContainerHigh)

            normalized.contains("已启用") ||
                normalized.contains("已开启") ||
                normalized.contains("已运行") ||
                normalized.contains("运行中") ||
                normalized.contains("已确认") ||
                normalized.contains("已应用") ||
                normalized.contains("已恢复") ||
                normalized.contains("已连接") ||
                normalized == "正常" ||
                normalized.contains("开") ||
                normalized.contains("名单") ||
                normalized.contains("配置") ||
                normalized.contains("性能") ||
                normalized.contains("省电") -> StatusStyle(themeColors.onPrimaryContainer, themeColors.primaryContainer)

            else -> StatusStyle(themeColors.onSecondaryContainer, themeColors.secondaryContainer)
        }
    }

    private fun featureTitle(title: String): String {
        return title
    }


    private fun featureIconTintColor(): Int {
        return themeColors.onPrimaryContainer
    }

    private fun featureIconBackgroundColor(): Int {
        return themeColors.primaryContainer
    }

    private fun featureIconView(title: String, color: Int = featureIconTintColor()): ImageView {
        val iconRes = featureIconRes(title)
        return ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(color)
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun featureIconContainer(title: String, color: Int = featureIconTintColor()): FrameLayout {
        val (c1, _) = LiquidGlassTheme.categoryColorsFor(title)
        val isDark = isDarkMode()
        val icon = featureIconView(title, c1)
        val pastelBg = Color.argb(
            if (isDark) 0x38 else 0x24,
            Color.red(c1),
            Color.green(c1),
            Color.blue(c1)
        )
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(pastelBg)
            }
            addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun resultActionText(label: String): String {
        return when {
            label == "电池保护设置" -> "设置已应用"
            label == "读取电池状态" -> "已读取电池状态"
            label.contains("恢复") -> "已恢复"
            label.contains("关闭") || label.contains("停止") -> "已停止"
            label.contains("读取") || label.contains("检测") || label.contains("检查") -> "已读取状态"
            label.contains("扫描") -> "已生成扫描建议"
            label.contains("保存") -> "已保存"
            label.contains("删除") -> "已删除"
            label.contains("开启") || label.contains("启动") -> "已启动"
            label.contains("应用") || label.contains("写入") || label.contains("更新") -> "已应用"
            else -> "已执行"
        }
    }

    private fun commandResultLine(label: String, result: MaxHzShell.Result): String {
        if (label == "电池保护设置") {
            return if (result.code == 0) {
                "设置已应用"
            } else {
                "设置未应用\n原因：Shizuku 未运行或未授权"
            }
        }
        if (label == "读取电池状态" && result.code != 0) {
            return "无法读取电池状态\n请检查 Shizuku 状态后重试。"
        }
        return if (result.code == 0) {
            resultActionText(label)
        } else {
            "操作未完成\n原因：${commandFailureReason(result)}"
        }
    }

    private fun commandResultText(label: String, explanation: String, outcome: CommandOutcome): String {
        val result = outcome.result
        if (label == "电池保护设置") {
            return buildBatteryProtectionResultText(result, outcome.changes, outcome.elapsedMs)
        }
        if (label == "读取电池状态" && result.code != 0) {
            return buildString {
                append("无法读取电池状态\n\n")
                append("• 请检查 Shizuku 授权状态后重试。")
            }
        }
        if (label == "检测系统残留") {
            return buildSystemResidueResultText(result, explanation, outcome.elapsedMs)
        }
        return buildString {
            if (result.code == 0) {
                append(label).append("已开启\n\n")
                val cleanOutput = formatCommandMessage(result.out)
                if (cleanOutput.isNotBlank() && !cleanOutput.contains("没有返回需要展示的详细信息")) {
                    append(cleanOutput).append("\n\n")
                } else if (explanation.isNotBlank()) {
                    append("• 说明：").append(explanation).append("\n\n")
                }
                if (outcome.changes.isNotBlank()) {
                    append("• 系统状态：").append(outcome.changes).append("\n\n")
                }
                append("状态：已成功由系统底层生效接管。")
            } else {
                append(label).append("未完成\n\n")
                append("• 失败原因：").append(commandFailureReason(result)).append('\n')
                append("• 建议操作：").append(commandNextStep(label, result))
                if (result.err.isNotBlank()) {
                    append("\n• 错误详情：").append(formatCommandMessage(result.err))
                }
            }
        }
    }

    private fun commandVerificationLevel(result: MaxHzShell.Result): String? {
        return result.out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("MAXHZ_RESULT_LEVEL=") }
            ?.substringAfter('=')
            ?.takeIf { it in setOf("effective", "partial", "configured") }
    }

    private fun buildSystemResidueResultText(result: MaxHzShell.Result, explanation: String, elapsedMs: Long? = null): String {
        val lines = result.out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val residueItems = lines
            .filter { it.startsWith("可能残留：") }
            .filterNot { isIgnoredBatteryProtectionResidueLine(it) }
            .map { userFacingResidueLine(it.removePrefix("可能残留：").trim()) }
            .distinct()
        val systemSaverOn = lines.any { it.contains("系统省电模式已开启") }
        val nightGuardCount = residueItems.count { it.contains("待机省电护栏") }
        val firstImpact = when {
            nightGuardCount > 0 -> "待机省电护栏可能仍在限制部分 App"
            residueItems.any { it.contains("快速待机") || it.contains("Doze") } -> "快速待机参数可能仍是自定义状态"
            residueItems.any { it.contains("图形") || it.contains("GPU") || it.contains("Vulkan") } -> "图形性能相关设置可能仍是自定义状态"
            residueItems.any { it.contains("性能") || it.contains("低热") } -> "性能或温控相关设置可能仍是自定义状态"
            residueItems.isNotEmpty() -> residueItems.first()
            else -> ""
        }
        return buildString {
            if (result.code != 0) {
                append("系统残留检测未完成\n\n")
                append("• 失败原因：").append(commandFailureReason(result)).append('\n')
                append("• 建议操作：请确认 Shizuku 授权状态。")
            } else if (residueItems.isEmpty()) {
                append("未发现明显系统残留\n\n")
                append("• 系统状态：所有核心系统参数均处于干净、标准出厂状态。\n")
                if (systemSaverOn) {
                    append("• 提示：系统省电模式当前已开启（属于手机当前开关状态，非异常残留）。\n")
                }
                append("\n建议：无需处理。")
            } else {
                append("发现 ").append(residueItems.size).append(" 项系统自定义配置\n\n")
                append("• 当前影响：").append(firstImpact).append('\n')
                if (residueItems.isNotEmpty()) {
                    append("• 检测清单：\n")
                    residueItems.forEach { append("  - ").append(it).append('\n') }
                }
                append("\n建议：").append(
                    if (nightGuardCount > 0) {
                        "如相关 App 需恢复全速后台，可进入待机异常唤醒排查恢复；若正常使用无需处理。"
                    } else {
                        "如需彻底恢复出厂默认接管，可执行“总恢复”。"
                    }
                )
            }
        }
    }

    private fun userFacingResidueLine(raw: String): String {
        val label = raw.substringBefore('=').trim()
        return when {
            label.contains("三星低热模式") -> "三星低热模式仍有自定义状态"
            label.contains("性能限制") -> "系统性能限制仍有自定义状态"
            label.contains("Quick Doze") || label.contains("Doze") -> "快速待机参数仍有自定义设置"
            label.contains("省电常量") -> "系统省电参数仍有自定义设置"
            label.contains("电池保护上限") -> "电池保护上限仍有自定义设置"
            label.contains("电池保护回充") -> "电池保护回充仍有自定义设置"
            label.contains("旁路充电") -> "旁路充电仍有自定义状态"
            label.contains("GPU") -> "GPU 渲染设置仍有自定义状态"
            label.contains("Vulkan") || label.contains("HWUI") -> "图形渲染设置仍有自定义状态"
            label.contains("ZRAM") -> "ZRAM 设置仍有自定义状态"
            label.contains("同步开关") -> "账号同步可能被关闭"
            label.contains("蓝牙后台扫描") -> "蓝牙后台扫描可能被关闭"
            label.contains("Wi-Fi 后台扫描") -> "Wi-Fi 后台扫描可能被关闭"
            label.contains("附近设备扫描") -> "附近设备扫描可能被关闭"
            label.contains("Night Guard") -> "待机省电护栏可能仍限制 ${raw.substringAfterLast('：', "").ifBlank { "某个应用" }}"
            label.isNotBlank() -> label
            else -> "发现一项可能残留"
        }
    }

    private fun isIgnoredBatteryProtectionResidueLine(line: String): Boolean {
        val normalized = line.removePrefix("可能残留：").trim()
        return normalized.contains("电池保护上限") ||
            normalized.contains("电池保护回充") ||
            normalized.contains("battery_protection_threshold") ||
            normalized.contains("battery_protection_limit") ||
            normalized.contains("battery_protection_recharge_level") ||
            normalized.contains("recharge_level")
    }

    private fun commandFailureReason(result: MaxHzShell.Result): String {
        val raw = "${result.err}\n${result.out}"
        return when {
            raw.contains("Shizuku", ignoreCase = true) ||
                raw.contains("未授权") ||
                raw.contains("未运行") -> "Shizuku 未运行或未授权"
            raw.contains("无障碍") ||
                raw.contains("accessibility", ignoreCase = true) -> "无障碍服务未开启"
            raw.contains("悬浮窗") ||
                raw.contains("overlay", ignoreCase = true) -> "悬浮窗权限未开启"
            raw.contains("permission", ignoreCase = true) ||
                raw.contains("denied", ignoreCase = true) ||
                raw.contains("权限") -> "权限不足"
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("超时") -> "系统暂时没有响应，已停止等待"
            raw.isBlank() -> "系统没有返回明确原因"
            else -> formatCommandMessage(raw).lineSequence().firstOrNull()?.take(80) ?: "系统暂时无法完成操作"
        }
    }

    private fun commandNextStep(label: String, result: MaxHzShell.Result): String {
        if (result.code == 0) return "无需处理。"
        val reason = commandFailureReason(result)
        return when {
            reason.contains("Shizuku") -> "请确认 Shizuku 正在运行，并已授权本应用。"
            reason.contains("无障碍") -> "请开启 Shizuku / MaxHz 无障碍服务后重试。"
            reason.contains("悬浮窗") -> "请开启悬浮窗权限后重试。"
            reason.contains("权限") -> "请检查相关权限或重新授权 Shizuku。"
            reason.contains("响应") || reason.contains("等待") -> "请稍后重试；如果经常出现，请先关闭其它正在执行的系统工具。"
            label.contains("读取") || label.contains("检测") -> "请确认 Shizuku 可用后再次读取。"
            else -> "请稍后重试；如仍失败，请查看技术详情。"
        }
    }

    private fun buildBatteryProtectionResultText(result: MaxHzShell.Result, changes: String, elapsedMs: Long? = null): String {
        return buildString {
            if (result.code == 0) {
                append("电池保护设置已生效\n\n")
                if (changes.isNotBlank()) {
                    append("• 系统状态：").append(changes).append("\n\n")
                }
                append("状态：已由三星电池服务生效接管。")
            } else {
                append("电池保护设置未生效\n\n")
                append("• 失败原因：").append(batteryProtectionFailureReason(result)).append('\n')
                append("• 建议操作：请确认 Shizuku 正在运行，并已授权本应用。")
            }
        }
    }

    private fun batteryProtectionFailureReason(result: MaxHzShell.Result): String {
        val raw = "${result.err}\n${result.out}"
        return when {
            raw.contains("Shizuku", ignoreCase = true) ||
                raw.contains("未授权") ||
                raw.contains("未运行") -> "Shizuku 未运行或未授权"
            raw.contains("permission", ignoreCase = true) ||
                raw.contains("denied", ignoreCase = true) ||
                raw.contains("权限") -> "权限不足"
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("超时") -> "系统暂时没有响应，已停止等待"
            else -> "系统暂时无法应用设置"
        }
    }

    private fun riskBadge(level: RiskLevel): TextView {
        return TextView(this).apply {
            text = level.label
            textSize = 9.5f
            includeFontPadding = false
            setTextColor(riskTextColor(level))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = rounded(riskBackgroundColor(level), dp(999).toFloat())
            maxLines = 1
        }
    }

    private fun safetyForTitle(title: String): FeatureSafety? {
        return when {
            title.contains("只激活工具") -> FeatureSafety(
                RiskLevel.LOW,
                "只启动工具链，不主动修改省电、刷新率、温控或后台规则。"
            )
            title.contains("总恢复") -> FeatureSafety(
                RiskLevel.LOW,
                "恢复类操作：清理本项目主要残留，交回系统默认接管。"
            )
            title.contains("日用省电") -> FeatureSafety(
                RiskLevel.MEDIUM,
                "冲突处理：每 App 刷新率优先于日用省电；没有单独规则时保持日用省电策略。"
            )
            title.contains("快速待机") -> FeatureSafety(
                RiskLevel.MEDIUM,
                "Doze 会更快进入待机；通讯/推送核心不建议强压，避免通知延迟。"
            )
            title.contains("待机省电护栏") -> FeatureSafety(
                RiskLevel.MEDIUM,
                "只限制手动加入名单的 App；通讯、推送、电话、健康类不要随便加入。"
            )
            title.contains("同步/扫描") -> FeatureSafety(
                RiskLevel.MEDIUM,
                "会关闭后台同步和扫描开关；不关闭蓝牙、Wi-Fi 或移动网络本体。"
            )
            title.contains("每 App") -> FeatureSafety(
                RiskLevel.MEDIUM,
                "前台 App 规则优先于日用省电；离开目标 App 会恢复亮屏自适应。"
            )
            title.contains("FPS") -> FeatureSafety(
                RiskLevel.LOW,
                "悬浮窗只读帧率信息，不写刷新率、省电或温控设置。"
            )
            title.contains("户外模式") -> FeatureSafety(
                RiskLevel.MEDIUM,
                "会请求三星系统临时提高户外亮度；系统仍会执行约 15 分钟限制和温控保护。"
            )
            title.contains("电池保护") -> FeatureSafety(
                RiskLevel.MEDIUM,
                "电池保护只写上限/回充；旁路充电独立控制，恢复时交回系统默认接管。"
            )
            title.contains("强制可调整窗口") -> FeatureSafety(
                RiskLevel.LOW,
                "只影响分屏/窗口兼容性，不直接参与功耗控制。"
            )
            title.contains("温控保护") -> FeatureSafety(
                RiskLevel.LOW,
                "清理危险温控残留，不启用温控放宽尝试。"
            )
            title.contains("M 原版") ||
                title.contains("M GPU") ||
                title.contains("M Vulkan") ||
                title.contains("M 网络") ||
                title.contains("M 触控") ||
                title.contains("M ZRAM") -> FeatureSafety(
                RiskLevel.HIGH,
                "M 原版脚本可能覆盖系统默认调度；建议单项测试，异常后用对应恢复。"
            )
            else -> null
        }
    }

    private fun shortSafetyNote(title: String, safety: FeatureSafety): String {
        return when {
            title.contains("只激活工具") -> "不改省电、刷新率、温控或后台规则。"
            title.contains("总恢复") -> "清理主要残留，交回系统接管。"
            title.contains("日用省电") -> "优先级：每 App 规则 > 日用省电。"
            title.contains("快速待机") -> "更快进入 Doze；通讯/推送应用慎用强压。"
            title.contains("待机省电护栏") -> "只限制手动加入名单的 App。"
            title.contains("同步/扫描") -> "关闭后台同步和扫描，不关网络本体。"
            title.contains("每 App") -> "离开目标 App 后恢复亮屏自适应。"
            title.contains("FPS") -> "只读 FPS，不写系统策略。"
            title.contains("户外模式") -> "临时最高亮度；约 15 分钟并受系统温控限制。"
            title.contains("电池保护") -> "电池保护和旁路充电互相独立。"
            title.contains("强制可调整窗口") -> "只影响分屏/窗口兼容。"
            title.contains("温控保护") -> "只清理危险温控残留。"
            title.contains("M 原版") ||
                title.contains("M GPU") ||
                title.contains("M Vulkan") ||
                title.contains("M 网络") ||
                title.contains("M 触控") ||
                title.contains("M ZRAM") -> "高风险脚本，建议单项测试后及时恢复。"
            else -> safety.note
        }
    }

    private fun stateText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11.5f
            setTextColor(themeColors.onSurfaceVariant)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun headerBadges(vararg views: View?): View? {
        val badges = views.filterNotNull()
        if (badges.isEmpty()) return null
        if (badges.size == 1) return badges.first()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            badges.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
        }
    }

    private fun resultBadge(key: String): TextView {
        val state = loadResultState(key)
        return statusBadge(if (state?.collapsed == false) "收起" else "查看详情").apply {
            visibility = if (state != null) View.VISIBLE else View.GONE
            setTextColor(themeColors.onPrimaryContainer)
            background = rounded(themeColors.primaryContainer, dp(999).toFloat())
            installClickFeedback(this, foregroundRipple = false)
            setOnClickListener {
                val state = resultStates[key] ?: return@setOnClickListener
                playClickFeedback(this)
                saveResultState(key, state.copy(collapsed = false))
                updateResultViews(key)
            }
            resultBadges[key] = this
        }
    }

    private fun resultText(key: String): TextView {
        loadResultState(key)
        return TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            gravity = Gravity.START
            setTextIsSelectable(false)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(themeColors.surfaceContainerHigh, dp(CARD_RADIUS_DP).toFloat())
            installClickFeedback(this, foregroundRipple = false)
            setTextColor(themeColors.onSurface)
            tag = ResultTag(key, "")
            setOnClickListener {
                playClickFeedback(this)
                toggleResultText(this)
            }
            resultViews[key] = this
            updateResultViews(key)
        }
    }

    private data class UserAppChoice(
        val label: String,
        val packageName: String,
        val systemApp: Boolean = false,
        val trustClass: MaxHzPackagePolicy.AppTrustClass = MaxHzPackagePolicy.AppTrustClass.USER_APP
    )

    private data class AppDisplayInfo(
        val label: String,
        val kind: String,
        val trustClass: MaxHzPackagePolicy.AppTrustClass
    )

    private val appDisplayCache = java.util.concurrent.ConcurrentHashMap<String, AppDisplayInfo>()

    private fun rememberAppDisplay(
        packageName: String,
        label: String,
        trustClass: MaxHzPackagePolicy.AppTrustClass
    ) {
        appDisplayCache[packageName] = AppDisplayInfo(label, appKindDescription(trustClass), trustClass)
    }

    private fun loadAppDisplayInfo(packageName: String): AppDisplayInfo {
        appDisplayCache[packageName]?.let { return it }
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
            val trustClass = MaxHzPackagePolicy.trustClass(this, info)
            AppDisplayInfo(label, appKindDescription(trustClass), trustClass).also {
                appDisplayCache[packageName] = it
            }
        } catch (_: Throwable) {
            val trustClass = MaxHzPackagePolicy.trustClass(this, packageName)
            AppDisplayInfo(packageName, appKindDescription(trustClass), trustClass).also {
                appDisplayCache[packageName] = it
            }
        }
    }

    private fun preloadAppDisplayInfo(packageNames: Collection<String>) {
        packageNames
            .filter { isPackageNameValid(it) }
            .distinct()
            .forEach { loadAppDisplayInfo(it) }
    }

    private fun loadDozeWhitelistTargetApps(whitelist: Set<String>): List<UserAppChoice> {
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
        } catch (_: Throwable) {
            emptyList<ApplicationInfo>()
        }
        val installedPackages = installed.associateBy { it.packageName }
        val visible = installed
            .asSequence()
            .filter { it.enabled }
            .filterNot { it.packageName == packageName }
            .map {
                val label = it.loadLabel(packageManager)?.toString()?.takeIf { label -> label.isNotBlank() } ?: it.packageName
                val trustClass = MaxHzPackagePolicy.trustClass(this, it)
                val systemApp = trustClass != MaxHzPackagePolicy.AppTrustClass.USER_APP
                rememberAppDisplay(it.packageName, label, trustClass)
                UserAppChoice(
                    label = label,
                    packageName = it.packageName,
                    systemApp = systemApp,
                    trustClass = trustClass
                )
            }
        val whitelistOnly = whitelist
            .asSequence()
            .filterNot { it in installedPackages }
            .map { UserAppChoice(label = it, packageName = it, systemApp = true, trustClass = MaxHzPackagePolicy.AppTrustClass.SUSPECTED_PRELOAD) }
        return (visible + whitelistOnly)
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<UserAppChoice> { it.packageName in whitelist }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    private fun appPickerRowView(app: UserAppChoice, showSubtitle: Boolean = false): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            layoutParams = AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(this@MaxHzActivity).apply {
                text = app.label
                textSize = 14f
                setTextColor(themeColors.onSurface)
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (showSubtitle) {
                addView(TextView(this@MaxHzActivity).apply {
                    text = "${appKindShort(app.trustClass)} · ${app.packageName}"
                    textSize = 11.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
        }
    }

    private fun appPickerContentView(
        apps: List<UserAppChoice>,
        description: String?,
        onSelected: (UserAppChoice) -> Unit
    ): View {
        val visibleApps = mutableListOf<UserAppChoice>()
        var selectedFilter = if (apps.any { !it.systemApp }) AppListFilter.USER else AppListFilter.ALL
        var searchQuery = ""
        var showDialogSubtitles = false
        val expandedRowPkgs = mutableSetOf<String>()
        lateinit var filterContainer: LinearLayout
        lateinit var listAdapter: ArrayAdapter<UserAppChoice>
        var descView: TextView? = null
        var redrawFilters: () -> Unit = {}

        fun filteredApps(): List<UserAppChoice> {
            return apps.filter { app ->
                val matchesFilter = when (selectedFilter) {
                    AppListFilter.ALL -> true
                    AppListFilter.USER -> !app.systemApp
                    AppListFilter.SYSTEM -> app.systemApp
                }
                if (!matchesFilter) return@filter false
                if (searchQuery.isBlank()) return@filter true
                val query = searchQuery.lowercase(Locale.getDefault())
                app.label.lowercase(Locale.getDefault()).contains(query) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(query)
            }
        }

        fun refreshList() {
            visibleApps.clear()
            visibleApps.addAll(filteredApps())
            listAdapter.notifyDataSetChanged()
        }

        fun applyFilter(filter: AppListFilter) {
            selectedFilter = filter
            redrawFilters()
            refreshList()
        }

        fun selectFilter(filter: AppListFilter) {
            if (filter == AppListFilter.SYSTEM &&
                !releasePrefs().getBoolean(KEY_SYSTEM_APP_FILTER_CONFIRMED, false)
            ) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("显示系统应用？")
                    .setMessage("系统应用、预装组件和核心服务建议谨慎处理。普通用户一般只需要处理用户安装的 App。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续查看") { _, _ ->
                        releasePrefs().edit().putBoolean(KEY_SYSTEM_APP_FILTER_CONFIRMED, true).apply()
                        applyFilter(filter)
                    }
                    .showUnified()
            } else {
                applyFilter(filter)
            }
        }

        fun updateFilters() {
            filterContainer.removeAllViews()
            listOf(
                AppListFilter.ALL to "全部",
                AppListFilter.USER to "用户应用",
                AppListFilter.SYSTEM to "系统应用"
            ).forEachIndexed { index, (filter, label) ->
                filterContainer.addView(filterChip(label, selectedFilter == filter) {
                    selectFilter(filter)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
        }
        redrawFilters = { updateFilters() }

        val listView = ListView(this).apply {
            dividerHeight = 0
            listAdapter = object : ArrayAdapter<UserAppChoice>(this@MaxHzActivity, android.R.layout.simple_list_item_1, visibleApps) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val app = getItem(position) ?: visibleApps[position]
                    return appPickerRowView(app, showDialogSubtitles || expandedRowPkgs.contains(app.packageName))
                }
            }
            adapter = listAdapter
            setOnItemClickListener { _, _, which, _ ->
                visibleApps.getOrNull(which)?.let(onSelected)
            }
            setOnItemLongClickListener { _, view, which, _ ->
                val app = visibleApps.getOrNull(which) ?: return@setOnItemLongClickListener false
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                if (!expandedRowPkgs.remove(app.packageName)) {
                    expandedRowPkgs.add(app.packageName)
                }
                listAdapter.notifyDataSetChanged()
                true
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
                addView(TextView(this@MaxHzActivity).apply {
                    text = "应用列表（长按单行可查看包名）"
                    textSize = 11.5f
                    setTextColor(themeColors.onSurfaceVariant)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MaxHzActivity).apply {
                    styleOptionBTogglePill(this, showDialogSubtitles)
                    setOnClickListener { v ->
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        showDialogSubtitles = !showDialogSubtitles
                        expandedRowPkgs.clear()
                        descView?.visibility = if (showDialogSubtitles) View.VISIBLE else View.GONE
                        styleOptionBTogglePill(this, showDialogSubtitles)
                        listAdapter.notifyDataSetChanged()
                    }
                })
            })
            description?.takeIf { it.isNotBlank() }?.let {
                descView = TextView(this@MaxHzActivity).apply {
                    text = it
                    textSize = 13f
                    setTextColor(themeColors.onSurfaceVariant)
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, 0, 0, dp(12))
                    visibility = View.GONE
                }
                addView(descView)
            }
            addView(EditText(this@MaxHzActivity).apply {
                hint = "搜索应用"
                textSize = 14f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                setTextColor(themeColors.onSurface)
                setHintTextColor(themeColors.onSurfaceVariant)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = rounded(themeColors.surfaceContainerHigh, dp(22).toFloat())
                minHeight = dp(44)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchQuery = s?.toString().orEmpty()
                        refreshList()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { bottomMargin = dp(10) })
            filterContainer = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            addView(filterContainer)
            addView(listView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            ))
            updateFilters()
            refreshList()
        }
    }

    protected fun showUserAppPicker(onSelected: (packageName: String, label: String) -> Unit) {
        val loadingDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rules_select_app_hint)
            .setView(emptyText("正在加载应用列表..."))
            .setNegativeButton("取消", null)
            .showUnified()
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadRefreshRateTargetApps() }
            if (!loadingDialog.isShowing) return@launch
            loadingDialog.dismiss()
            if (apps.isEmpty()) {
                statusTextLater("没有识别到可设置刷新率的应用。请确认应用列表权限。")
                return@launch
            }
            lateinit var dialog: androidx.appcompat.app.AlertDialog
            dialog = MaterialAlertDialogBuilder(this@MaxHzActivity)
                .setTitle(R.string.rules_select_app_hint)
                .setView(appPickerContentView(apps, null) { app ->
                    onSelected(app.packageName, app.label)
                    dialog.dismiss()
                })
                .showUnified()
                .also {
                    it.setOnDismissListener {
                        ensureMaxHzContentVisible()
                    }
                }
        }
    }

    private fun showGameAutoAppPicker(onSelected: (UserAppChoice) -> Unit) {
        val loadingDialog = MaterialAlertDialogBuilder(this)
            .setTitle("添加游戏")
            .setView(emptyText("正在加载应用列表..."))
            .setNegativeButton("取消", null)
            .showUnified()
        lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { loadGameAutoTargetApps() }
                if (!loadingDialog.isShowing) return@launch
                loadingDialog.dismiss()
                if (apps.isEmpty()) {
                    statusTextLater("没有可添加的应用。")
                    return@launch
                }
                lateinit var dialog: androidx.appcompat.app.AlertDialog
                dialog = MaterialAlertDialogBuilder(this@MaxHzActivity)
                    .setTitle("添加游戏")
                    .setView(appPickerContentView(
                        apps,
                        "默认选择游戏或需要高性能的应用。只有名单内应用进入前台时才会自动切换。"
                    ) { app ->
                        onSelected(app)
                        dialog.dismiss()
                    })
                    .showUnified()
                    .also {
                        it.setOnDismissListener {
                            ensureMaxHzContentVisible()
                        }
                    }
            } catch (e: Throwable) {
                if (loadingDialog.isShowing) loadingDialog.dismiss()
                statusTextLater("加载应用列表失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showNightWakeupGuardAppPicker(onSelected: (UserAppChoice) -> Unit) {
        val loadingDialog = MaterialAlertDialogBuilder(this)
            .setTitle("添加到待机护栏")
            .setView(emptyText("正在加载应用列表..."))
            .setNegativeButton("取消", null)
            .showUnified()
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadNightWakeupGuardTargetApps() }
            if (!loadingDialog.isShowing) return@launch
            loadingDialog.dismiss()
            if (apps.isEmpty()) {
                statusTextLater("没有识别到可加入待机护栏的应用。")
                return@launch
            }
            lateinit var dialog: androidx.appcompat.app.AlertDialog
            dialog = MaterialAlertDialogBuilder(this@MaxHzActivity)
                .setTitle("添加到待机护栏")
                .setView(appPickerContentView(
                    apps,
                    "默认优先显示用户应用。系统组件请谨慎添加。"
                ) { app ->
                    onSelected(app)
                    dialog.dismiss()
                })
                .showUnified()
        }
    }

    private fun loadRefreshRateTargetApps(): List<UserAppChoice> {
        val apps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
        } catch (_: Throwable) {
            emptyList<ApplicationInfo>()
        }
        return apps
            .asSequence()
            .filter { it.enabled }
            .filter { MaxHzPackagePolicy.isRefreshRateTargetApp(this, it) }
            .map {
                val label = safeLoadLabel(it)
                val trustClass = MaxHzPackagePolicy.trustClass(this, it)
                val systemApp = trustClass != MaxHzPackagePolicy.AppTrustClass.USER_APP
                rememberAppDisplay(it.packageName, label, trustClass)
                UserAppChoice(
                    label = label,
                    packageName = it.packageName,
                    systemApp = systemApp,
                    trustClass = trustClass
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<UserAppChoice> { it.systemApp }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    private fun loadNightWakeupGuardTargetApps(): List<UserAppChoice> {
        val guarded = MaxHzPrefs.nightWakeupGuardPackages(this).toSet()
        val appFlags = PackageManager.MATCH_DISABLED_COMPONENTS
        val apps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(appFlags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(appFlags)
            }
        } catch (_: Throwable) {
            emptyList<ApplicationInfo>()
        }
        return apps
            .asSequence()
            .filter { isPackageNameValid(it.packageName) }
            .filterNot { it.packageName == packageName }
            .filterNot { it.packageName in guarded }
            .map {
                val label = it.loadLabel(packageManager)?.toString()?.takeIf { label -> label.isNotBlank() } ?: it.packageName
                val trustClass = MaxHzPackagePolicy.trustClass(this, it)
                val systemApp = trustClass != MaxHzPackagePolicy.AppTrustClass.USER_APP
                rememberAppDisplay(it.packageName, label, trustClass)
                UserAppChoice(
                    label = label,
                    packageName = it.packageName,
                    systemApp = systemApp,
                    trustClass = trustClass
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<UserAppChoice> { it.systemApp }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    private fun safeLoadLabel(info: ApplicationInfo): String {
        return runCatching {
            info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: info.packageName
    }

    private fun loadGameAutoTargetApps(): List<UserAppChoice> {
        val existing = MaxHzPrefs.gameAutoPerformancePackages(this).toSet()
        val apps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
        } catch (_: Throwable) {
            emptyList<ApplicationInfo>()
        }
        return apps
            .asSequence()
            .filter { it.enabled }
            .filter { isPackageNameValid(it.packageName) }
            .filterNot { it.packageName == packageName }
            .filterNot { it.packageName in existing }
            .map {
                val label = safeLoadLabel(it)
                val trustClass = MaxHzPackagePolicy.trustClass(this, it)
                val systemApp = trustClass != MaxHzPackagePolicy.AppTrustClass.USER_APP
                rememberAppDisplay(it.packageName, label, trustClass)
                UserAppChoice(
                    label = label,
                    packageName = it.packageName,
                    systemApp = systemApp,
                    trustClass = trustClass
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<UserAppChoice> { it.systemApp }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    private fun isSystemApp(info: ApplicationInfo): Boolean {
        val flags = info.flags
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun openBatteryUsageSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun horizontalActions(vararg views: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            val maxTextLen = views.maxOfOrNull { (it as? TextView)?.text?.toString()?.replace(" ✓", "")?.length ?: 0 } ?: 0
            val columns = when {
                isLargeFontMode() -> if (views.size == 3 && maxTextLen <= 4) 3 else 1
                views.size == 1 -> 1
                views.size == 3 && maxTextLen <= 5 -> 3
                views.size == 3 -> 2
                views.size == 6 && maxTextLen <= 4 -> 3
                views.size == 4 -> 2
                else -> 2
            }
            views.toList().chunked(columns).forEachIndexed { rowIndex, rowViews ->
                val row = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                rowViews.forEachIndexed { colIndex, view ->
                    row.addView(view, LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (colIndex > 0) leftMargin = dp(BUTTON_SPACING_DP)
                    })
                }
                repeat(columns - rowViews.size) { fillerIndex ->
                    row.addView(View(this@MaxHzActivity), LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        leftMargin = if (rowViews.isNotEmpty() || fillerIndex > 0) dp(BUTTON_SPACING_DP) else 0
                    })
                }
                addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIndex > 0) topMargin = dp(BUTTON_SPACING_DP)
                })
            }
        }
    }

    private fun isLargeFontMode(): Boolean {
        return resources.configuration.fontScale >= 1.15f
    }

    /**
     * 分区大标题（26sp 大字 + 13.5sp 副标题）。
     *
     * 2026-08-29 起全部调用点已移除：用户要求把顶栏的大字与副标题一起删掉，
     * 四个分区（首页 / 屏幕与显示 / 性能与调度 / 电池与省电）都改成直接从
     * 功能卡片开始。方法保留，便于日后需要标题时直接复用。
     *
     * @param subtitle 副标题，传 null 或留空则不渲染。
     */
    protected fun sectionTitle(title: String, subtitle: String? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(10))
            addView(TextView(this@MaxHzActivity).apply {
                text = title
                textSize = 26f
                setTextColor(themeColors.onSurface)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
            })
            if (!subtitle.isNullOrEmpty()) {
                addView(TextView(this@MaxHzActivity).apply {
                    text = subtitle
                    textSize = 13.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }
    }

    private fun groupTitle(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(2))
            val headerRow = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val indicator = View(this@MaxHzActivity).apply {
                    val indicatorColor = themeColors.primary
                    background = rounded(indicatorColor, dp(2).toFloat())
                }
                addView(indicator, LinearLayout.LayoutParams(dp(4), dp(14)).apply {
                    rightMargin = dp(8)
                })
                addView(TextView(this@MaxHzActivity).apply {
                    text = title
                    textSize = 14.5f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                })
            }
            addView(headerRow)
            val subView = TextView(this@MaxHzActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(themeColors.onSurfaceVariant)
                includeFontPadding = false
                setPadding(dp(12), dp(3), 0, 0)
            }
            addView(subView)
            bindOptionBSubtitle(this, subView)
            bindOptionBSubtitle(headerRow, subView)
        }
    }

    private fun button(text: String, onClick: () -> Unit): TextView =
        button(text, ButtonRole.SECONDARY, onClick)

    private fun button(text: String, role: ButtonRole, onClick: () -> Unit): TextView {
        val isDark = isDarkMode()
        val isPrimary = role == ButtonRole.PRIMARY
        val isDanger = role == ButtonRole.DANGER
        val isSelected = text.contains("✓")
        return MaterialButton(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            includeFontPadding = false
            minHeight = dp(BUTTON_HEIGHT_DP)
            minimumHeight = dp(BUTTON_HEIGHT_DP)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            val radiusPx = dp(BUTTON_RADIUS_DP).toFloat()
            cornerRadius = dp(BUTTON_RADIUS_DP)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 13f
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            setLineSpacing(0f, 1.05f)
            strokeWidth = 0
            backgroundTintList = null
            if (isSelected) {
                background = GradientDrawable().apply {
                    cornerRadius = radiusPx
                    setColor(themeColors.primary)
                }
                setTextColor(themeColors.onPrimary)
            } else {
                background = LiquidGlassTheme.createButtonDrawable(
                    cornerRadiusPx = radiusPx,
                    isDark = isDark,
                    isPrimary = isPrimary,
                    isDanger = isDanger
                )
                val textColor = when {
                    isDanger -> if (isDark) 0xFFFCA5A5.toInt() else 0xFFDC2626.toInt()
                    isPrimary -> themeColors.onPrimary
                    else -> themeColors.onSurface
                }
                setTextColor(textColor)
            }
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener {
                playClickFeedback(this)
                onClick()
            }
        }
    }

    private fun buttonRoleFor(text: String): ButtonRole {
        return ButtonRole.SECONDARY
    }

    private fun switchRow(text: String, checked: Boolean, onChanged: (Boolean) -> Unit): MaterialSwitch {
        return MaterialSwitch(this).apply {
            this.text = text
            textSize = 15f
            isChecked = checked
            LiquidGlassTheme.styleIosSwitch(this, isDarkMode())
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
    }

    private fun groupCard(content: LinearLayout.() -> Unit): MaterialCardView {
        return MaterialCardView(this).apply {
            applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(CARD_PADDING_DP), dp(CARD_SECTION_SPACING_DP), dp(CARD_PADDING_DP), dp(CARD_SECTION_SPACING_DP))
                installClickFeedback(this)
                setOnClickListener {
                    playClickFeedback(this)
                    collapseVisibleResultInside(this)
                }
                content()
            })
        }
    }

    private fun divider(): View {
        return View(this).apply {
            val isDark = isDarkMode()
            setBackgroundColor(if (isDark) 0x14FFFFFF.toInt() else 0x0A000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(0, dp(8), 0, dp(8))
            setLineSpacing(0f, 1.25f)
        }
    }

    private fun twoLineListRow(title: String, summary: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(44)
            setPadding(0, dp(5), 0, dp(5))
            val shortRight = summary.lineSequence().firstOrNull()?.substringBefore('(')?.substringBefore('（')?.trim().orEmpty()
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MaxHzActivity).apply {
                    text = title
                    textSize = 15f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (shortRight.isNotEmpty()) {
                    addView(TextView(this@MaxHzActivity).apply {
                        text = shortRight.take(18)
                        textSize = 13f
                        setTextColor(themeColors.primary)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        includeFontPadding = false
                        maxLines = 1
                        setPadding(dp(8), 0, 0, 0)
                    })
                }
            })
            val subView = TextView(this@MaxHzActivity).apply {
                text = summary
                textSize = 12f
                setTextColor(themeColors.onSurfaceVariant)
                includeFontPadding = false
                setPadding(0, dp(2), 0, 0)
                setLineSpacing(0f, 1.15f)
            }
            addView(subView)
            bindOptionBSubtitle(this, subView)
        }
    }

    private fun switchListRow(
        title: String,
        summary: String,
        checked: Boolean,
        onChanged: (Boolean, MaterialSwitch, TextView) -> Unit
    ): View {
        lateinit var toggle: MaterialSwitch
        lateinit var summaryView: TextView
        var programmatic = false
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(0, dp(4), 0, dp(4))
            installClickFeedback(this)
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MaxHzActivity).apply {
                    text = title
                    textSize = 15f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                summaryView = TextView(this@MaxHzActivity).apply {
                    text = summary
                    textSize = 12f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(2), 0, 0)
                    setLineSpacing(0f, 1.15f)
                }
                addView(summaryView)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (summary.isNotBlank()) {
                bindOptionBSubtitle(this, summaryView)
            } else {
                summaryView.visibility = View.GONE
            }
            toggle = MaterialSwitch(this@MaxHzActivity).apply {
                showText = false
                isChecked = checked
                minHeight = 0
                minimumHeight = 0
                LiquidGlassTheme.styleIosSwitch(this, isDarkMode())
                setOnCheckedChangeListener { _, isChecked ->
                    if (programmatic) return@setOnCheckedChangeListener
                    onChanged(isChecked, this, summaryView)
                }
            }
            addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) })
            setOnClickListener {
                if (toggle.isEnabled) {
                    toggle.isChecked = !toggle.isChecked
                }
            }
        }
    }

    private fun expandableSwitchListRow(
        title: String,
        collapsedSummary: String,
        expandedSummary: String,
        checked: Boolean,
        onChanged: (Boolean, MaterialSwitch) -> Unit
    ): View {
        var expanded = false
        lateinit var toggle: MaterialSwitch
        lateinit var summaryView: TextView
        lateinit var titleView: TextView
        var programmatic = false
        fun updateText() {
            titleView.text = if (expanded) "$title ▾" else "$title ▸"
            summaryView.text = if (expanded) expandedSummary else collapsedSummary
            summaryView.maxLines = if (expanded) Int.MAX_VALUE else 2
            summaryView.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(0, dp(5), 0, dp(5))
            val textArea = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                installClickFeedback(this)
                titleView = TextView(this@MaxHzActivity).apply {
                    textSize = 16f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                summaryView = TextView(this@MaxHzActivity).apply {
                    textSize = 13.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(2), 0, 0)
                    setLineSpacing(0f, 1.12f)
                }
                addView(titleView)
                addView(summaryView)
                setOnClickListener {
                    expanded = !expanded
                    updateText()
                    playClickFeedback(this)
                }
            }
            addView(textArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            toggle = MaterialSwitch(this@MaxHzActivity).apply {
                showText = false
                isChecked = checked
                LiquidGlassTheme.styleIosSwitch(this, isDarkMode())
                setOnCheckedChangeListener { _, isChecked ->
                    if (programmatic) return@setOnCheckedChangeListener
                    onChanged(isChecked, this)
                }
            }
            addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) })
            updateText()
        }
    }

    private fun filterChip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val isDark = isDarkMode()
        return MaterialButton(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 12f
            includeFontPadding = false
            minHeight = dp(34)
            minimumHeight = dp(34)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            isSingleLine = true
            maxLines = 1
            ellipsize = null
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            setTextColor(
                if (selected) {
                    themeColors.onPrimary
                } else {
                    themeColors.onSurfaceVariant
                }
            )
            backgroundTintList = ColorStateList.valueOf(
                if (selected) {
                    themeColors.primary
                } else {
                    resolveColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, if (isDark) 0xFF282C37.toInt() else 0xFFE6EAF2.toInt())
                }
            )
            strokeWidth = 0
            isClickable = true
            isFocusable = true
            setOnClickListener {
                playClickFeedback(this)
                onClick()
            }
        }
    }

    private fun numberInput(value: Int): EditText {
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setSelectAllOnFocus(true)
            maxLines = 1
            textSize = 14f
            minHeight = dp(38)
            setPadding(dp(8), 0, dp(8), 0)
        }
    }

    private fun inputRow(
        label1: String,
        input1: EditText,
        label2: String,
        input2: EditText,
        label3: String,
        input3: EditText
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addLabeledInput(label1, input1)
            addLabeledInput(label2, input2)
            addLabeledInput(label3, input3)
        }
    }

    private fun inputRow(
        label1: String,
        input1: EditText,
        label2: String,
        input2: EditText
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addLabeledInput(label1, input1)
            addLabeledInput(label2, input2)
        }
    }

    private fun settingsInputGroup(vararg items: Pair<String, EditText>): LinearLayout {
        return compactInputGroup(
            labelWidthDp = APP_REFRESH_INPUT_LABEL_WIDTH_DP,
            fieldWidthDp = APP_REFRESH_INPUT_FIELD_WIDTH_DP,
            itemSpacingDp = APP_REFRESH_INPUT_ITEM_SPACING_DP,
            items = items
        )
    }

    private fun batteryProtectionInputGroup(vararg items: Pair<String, EditText>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            background = rounded(themeColors.surfaceContainerHigh, dp(CARD_RADIUS_DP).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            items.forEachIndexed { index, item ->
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MaxHzActivity).apply {
                        text = item.first
                        textSize = 14f
                        setTextColor(themeColors.onSurface)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_VERTICAL
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(dp(BATTERY_INPUT_LABEL_WIDTH_DP), dp(INPUT_FIELD_HEIGHT_DP)))
                    addView(View(this@MaxHzActivity), LinearLayout.LayoutParams(dp(INPUT_LABEL_GAP_DP), 1))
                    addView(item.second.apply {
                        minHeight = dp(INPUT_FIELD_HEIGHT_DP)
                        minimumHeight = dp(INPUT_FIELD_HEIGHT_DP)
                        minWidth = 0
                        minimumWidth = 0
                        maxWidth = dp(BATTERY_INPUT_FIELD_WIDTH_DP)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setSingleLine(true)
                        setPadding(dp(4), 0, dp(4), 0)
                    }, LinearLayout.LayoutParams(dp(BATTERY_INPUT_FIELD_WIDTH_DP), dp(INPUT_FIELD_HEIGHT_DP)))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(INPUT_FIELD_HEIGHT_DP)
                ).apply {
                    if (index > 0) topMargin = dp(6)
                })
            }
        }
    }

    private fun compactInputGroup(
        labelWidthDp: Int,
        fieldWidthDp: Int,
        itemSpacingDp: Int,
        items: Array<out Pair<String, EditText>>
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(themeColors.surfaceContainerHigh, dp(CARD_RADIUS_DP).toFloat())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                items.forEachIndexed { index, item ->
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(this@MaxHzActivity).apply {
                            text = item.first
                            textSize = 13f
                            setTextColor(themeColors.onSurface)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER_VERTICAL
                            includeFontPadding = false
                        }, LinearLayout.LayoutParams(dp(labelWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT))
                        addView(View(this@MaxHzActivity), LinearLayout.LayoutParams(dp(INPUT_LABEL_GAP_DP), 1))
                        addView(item.second.apply {
                            minHeight = dp(INPUT_FIELD_HEIGHT_DP)
                            minimumHeight = dp(INPUT_FIELD_HEIGHT_DP)
                            minWidth = 0
                            minimumWidth = 0
                            maxWidth = dp(fieldWidthDp)
                            gravity = Gravity.CENTER
                            includeFontPadding = false
                            setSingleLine(true)
                            setPadding(dp(4), 0, dp(4), 0)
                        }, LinearLayout.LayoutParams(dp(fieldWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT))
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) leftMargin = dp(itemSpacingDp)
                    })
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun batteryProtectionSystemStatusView(message: String): TextView {
        return TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(themeColors.onSurfaceVariant)
            background = rounded(themeColors.surfaceContainerHigh, dp(CARD_RADIUS_DP).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun batteryProtectionButton(text: String, onClick: () -> Unit): TextView {
        return button(text, onClick).apply {
            minHeight = dp(BUTTON_HEIGHT_DP)
            minimumHeight = dp(BUTTON_HEIGHT_DP)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(16), dp(6), dp(16), dp(6))
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun batteryProtectionSingleAction(view: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun batteryProtectionInlineActions(vararg views: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            views.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dp(BUTTON_SPACING_DP)
                })
            }
        }
    }

    private fun clearResultAndRefresh(key: String) {
        MaxHzPrefs.clearResult(this, key)
        resultStates.remove(key)
        resultViews.remove(key)
        resultBadges.remove(key)
        resultContainers.remove(key)
        lastResultView = null
        renderDetail()
        Toast.makeText(this, "结果已清除", Toast.LENGTH_SHORT).show()
    }

    private fun batteryProtectionSystemStatusText(output: String): String {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return if (lines.isNotEmpty()) {
            lines.joinToString("\n")
        } else {
            "当前电池状态\n系统未返回电池信息"
        }
    }

    private fun LinearLayout.addLabeledInput(label: String, input: EditText) {
        addView(TextView(this@MaxHzActivity).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(APP_REFRESH_INPUT_LABEL_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(View(this@MaxHzActivity), LinearLayout.LayoutParams(dp(INPUT_LABEL_GAP_DP), 1))
        addView(input.apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
        }, LinearLayout.LayoutParams(dp(APP_REFRESH_INPUT_FIELD_WIDTH_DP), dp(INPUT_FIELD_HEIGHT_DP)))
    }

    private fun EditText.safeInt(defaultValue: Int): Int {
        return text?.toString()?.toIntOrNull() ?: defaultValue
    }

    private fun isPackageNameValid(packageName: String): Boolean {
        return packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
    }

    private fun animateExpandChange(view: View) {
        view.alpha = 0.78f
        view.animate()
            .alpha(1f)
            .setDuration(170L)
            .start()
    }

    private fun fadeOutThen(view: View, after: () -> Unit) {
        view.animate()
            .alpha(0f)
            .setDuration(170L)
            .withEndAction(after)
            .start()
    }

    private fun renderAppRefreshRules(container: LinearLayout, onDeleted: () -> Unit) {
        container.removeAllViews()
        val rules = MaxHzPrefs.appRefreshRules(this)
        if (rules.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无规则\n添加 App 后才会自动切换刷新率。"
                textSize = 12f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(0, dp(4), 0, dp(8))
                setLineSpacing(0f, 1.25f)
            })
            return
        }
            val appliedPackages = MaxHzPrefs.nightWakeupGuardAppliedPackages(this).toSet()
            val guardApplied = MaxHzPrefs.nightWakeupGuardEnabled(this)
            val primaryText = themeColors.onSurface
        val secondaryText = themeColors.onSurfaceVariant
        val rowBackground = themeColors.surfaceContainerHigh
        rules.forEachIndexed { index, rule ->
            val appName = appLabelForPackage(rule.packageName)
            val modeText = appRefreshRuleModeText(rule)
            lateinit var row: LinearLayout
            row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                background = rounded(rowBackground, dp(CARD_RADIUS_DP).toFloat())
                installClickFeedback(this, foregroundRipple = false)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MaxHzActivity).apply {
                        text = appName
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(primaryText)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MaxHzActivity).apply {
                        text = modeText
                        textSize = 12f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(themeColors.primary)
                        setPadding(dp(8), 0, 0, 0)
                    })
                })
                val sub = TextView(this@MaxHzActivity).apply {
                    text = "${appKindDescriptionForPackage(rule.packageName)}  ·  $modeText  ·  点击删除"
                    textSize = 12f
                    setTextColor(secondaryText)
                    maxLines = 1
                    setPadding(0, dp(2), 0, 0)
                }
                addView(sub)
                bindOptionBSubtitle(this, sub)
                setOnClickListener {
                    playClickFeedback(this)
                    showDeleteAppRefreshRuleDialog(rule, appName, modeText) {
                        fadeOutThen(row, onDeleted)
                    }
                }
            }
            row.alpha = 0f
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (index == 0) dp(8) else dp(6)
            })
            row.animate().alpha(1f).setDuration(160L).start()
        }
    }

    private fun selectedAppRefreshText(): String {
        if (selectedAppRefreshPackage.isBlank() || selectedAppRefreshLabel.isBlank()) {
            return "已选择：未选择应用"
        }
        return "已选择：$selectedAppRefreshLabel（${appKindDescriptionForPackage(selectedAppRefreshPackage)}）"
    }

    private fun renderNightWakeupGuardPackages(container: LinearLayout, onChanged: (() -> Unit)? = null) {
        container.removeAllViews()
        val packages = MaxHzPrefs.nightWakeupGuardPackages(this)
        if (packages.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无手动加入的护栏应用。可点击上方【添加应用】或从扫描建议中选择加入。"
                textSize = 13f
                setTextColor(themeColors.onSurfaceVariant)
                includeFontPadding = false
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        val guardApplied = MaxHzPrefs.nightWakeupGuardEnabled(this)
        val appliedPackages = MaxHzPrefs.nightWakeupGuardAppliedPackages(this).toSet()

        packages.forEachIndexed { index, packageName ->
            val info = loadAppDisplayInfo(packageName)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val iconView = ImageView(this).apply {
                val iconDrawable = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
                if (iconDrawable != null) setImageDrawable(iconDrawable)
                else setImageResource(R.drawable.ic_maxhz_apps_24)
            }
            row.addView(iconView, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })

            val applyState = when {
                guardApplied && packageName in appliedPackages -> "已应用限制"
                guardApplied -> "待应用"
                else -> "未激活"
            }
            val subView = TextView(this@MaxHzActivity).apply {
                text = "${packageName} · ${appKindDescriptionForPackage(packageName)} · $applyState"
                textSize = 11.5f
                setTextColor(themeColors.onSurfaceVariant)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MaxHzActivity).apply {
                    text = info.label
                    textSize = 13.5f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(subView)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bindOptionBSubtitle(row, subView)

            row.addView(button("删除") {
                MaxHzPrefs.removeNightWakeupGuardPackage(this@MaxHzActivity, packageName)
                if (guardApplied) {
                    MaxHzController.restoreNightWakeupGuard(listOf(packageName))
                }
                Toast.makeText(this@MaxHzActivity, "已从护栏中移除 ${info.label}", Toast.LENGTH_SHORT).show()
                renderNightWakeupGuardPackages(container, onChanged)
                onChanged?.invoke()
            }, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))

            container.addView(row)
            if (index < packages.size - 1) {
                container.addView(divider())
            }
        }
    }

    private fun showNightWakeupCurrentRestrictionsDialog(resultView: TextView, onChanged: () -> Unit) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scrollView = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("查看当前限制")
            .setMessage("这里只读取当前后台限制状态，不会自动恢复或修改系统。")
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create()

        dialog.show()
        val isDark = isDarkMode()
        dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
        applyUnifiedDialogWindowStyle(dialog)

        fun loadRestrictions(includeSystemUnknown: Boolean = false) {
            content.removeAllViews()
            content.addView(emptyText(if (includeSystemUnknown) "正在扫描系统未知限制..." else "正在读取本 App 管理的限制..."))
            lifecycleScope.launch {
                val knownPackages = MaxHzPrefs.nightWakeupGuardPackages(this@MaxHzActivity)
                    .plus(MaxHzPrefs.nightWakeupGuardAppliedPackages(this@MaxHzActivity))
                    .distinct()
                val result = withContext(Dispatchers.IO) {
                    MaxHzController.readNightWakeupGuardCurrentRestrictions(
                        extraPackages = knownPackages,
                        includeSystemUnknown = includeSystemUnknown
                    )
                }
                if (!dialog.isShowing) return@launch
                content.removeAllViews()
                if (result.code != 0) {
                    content.addView(emptyText("无法读取当前限制。\n${commandFailureReason(result)}"))
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val packages = parseNightRestrictionItems(result.out).map { it.packageName }
                        .plus(knownPackages)
                    preloadAppDisplayInfo(packages)
                }
                renderNightWakeupCurrentRestrictions(
                    container = content,
                    raw = result.out,
                    resultView = resultView,
                    onChanged = onChanged,
                    includeSystemUnknown = includeSystemUnknown,
                    reload = { loadRestrictions(includeSystemUnknown) },
                    scanSystemUnknown = { loadRestrictions(true) }
                )
            }
        }

        loadRestrictions()
    }

    private fun renderNightWakeupCurrentRestrictions(
        container: LinearLayout,
        raw: String,
        resultView: TextView,
        onChanged: () -> Unit,
        includeSystemUnknown: Boolean,
        reload: () -> Unit,
        scanSystemUnknown: () -> Unit
    ) {
        val items = parseNightRestrictionItems(raw)
        parseNightRestrictionLimit(raw)?.let { (shown, total) ->
            container.addView(emptyText("当前只显示前 $shown 个限制项，共发现约 $total 个。列表过大时分批显示，避免读取时间过长。"))
        }
        val byPackage = items.associateBy { it.packageName }
        val currentList = MaxHzPrefs.nightWakeupGuardPackages(this)
        val appliedHistory = MaxHzPrefs.nightWakeupGuardAppliedPackages(this)
        val knownPackages = currentList.plus(appliedHistory).distinct()
        val knownSet = knownPackages.toSet()
        val unknownRestricted = items
            .filter { it.restricted && it.packageName !in knownSet }
            .sortedBy { appLabelForPackage(it.packageName).lowercase(Locale.getDefault()) }

        container.addView(TextView(this).apply {
            text = "本 App 护栏名单"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColors.onSurface)
            setPadding(0, dp(2), 0, dp(6))
        })
        if (knownPackages.isEmpty()) {
            container.addView(emptyText("当前没有本 App 设置的护栏名单。"))
        } else {
            knownPackages.forEachIndexed { index, packageName ->
                val item = byPackage[packageName]
                container.addView(nightRestrictionRow(
                    packageName = packageName,
                    item = item,
                    source = if (packageName in currentList) "本 App 设置" else "本 App 历史记录",
                    clickable = true
                ) {
                    showNightRestrictionActions(packageName, item, resultView, onChanged, reload)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else dp(8) })
            }
        }

        container.addView(TextView(this).apply {
            text = "系统当前限制 / 来源未知"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColors.onSurface)
            setPadding(0, dp(14), 0, dp(6))
        })
        if (!includeSystemUnknown) {
            container.addView(emptyText("默认只显示本 App 管理过的项目。系统未知限制不会自动扫描，避免读取太慢或误导。"))
            container.addView(button("扫描系统未知限制") {
                scanSystemUnknown()
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        } else if (unknownRestricted.isEmpty()) {
            container.addView(emptyText("未发现其他当前后台限制。"))
        } else {
            unknownRestricted.forEachIndexed { index, item ->
                container.addView(nightRestrictionRow(
                    packageName = item.packageName,
                    item = item,
                    source = "来源未知",
                    clickable = true
                ) {
                    showNightRestrictionActions(item.packageName, item, resultView, onChanged, reload)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else dp(8) })
            }
        }
    }

    private fun nightRestrictionRow(
        packageName: String,
        item: NightRestrictionItem?,
        source: String,
        clickable: Boolean,
        onClick: () -> Unit
    ): View {
        val status = when {
            item == null -> "未安装或未检测到"
            item.restricted -> "当前存在后台限制"
            else -> "当前未发现明显限制"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = clickable
            isFocusable = clickable
            background = rounded(themeColors.surfaceContainerHigh, dp(CARD_RADIUS_DP).toFloat())
            if (clickable) installClickFeedback(this, foregroundRipple = false)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            addView(TextView(this@MaxHzActivity).apply {
                text = appLabelForPackage(packageName)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColors.onSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@MaxHzActivity).apply {
                text = "${appKindDescriptionForPackage(packageName)} · $source · $status"
                textSize = 11.5f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(0, dp(3), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            item?.let {
                addView(TextView(this@MaxHzActivity).apply {
                    text = nightRestrictionSummary(it)
                    textSize = 11.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            if (clickable) {
                setOnClickListener {
                    playClickFeedback(this)
                    onClick()
                }
            }
        }
    }

    private fun showNightRestrictionActions(
        packageName: String,
        item: NightRestrictionItem?,
        resultView: TextView,
        onChanged: () -> Unit,
        reload: () -> Unit
    ) {
        val appName = appLabelForPackage(packageName)
        val inCurrentList = packageName in MaxHzPrefs.nightWakeupGuardPackages(this)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!inCurrentList) {
            actions += "加入本 App 护栏名单" to {
                addNightWakeupGuardPackageWithWhitelistCheck(
                    packageName = packageName,
                    appName = appName,
                    resultView = resultView,
                    onAdded = {
                        onChanged()
                        reload()
                    }
                )
            }
        }
        if (inCurrentList) {
            actions += "移出本 App 名单" to {
                MaxHzPrefs.removeNightWakeupGuardPackage(this, packageName)
                onChanged()
                reload()
                statusTextLater(resultView, "$appName 已移出本 App 护栏名单。已应用到系统的限制不会自动恢复。")
            }
        }
        actions += "恢复该 App 默认接管" to {
            MaterialAlertDialogBuilder(this)
                .setTitle("恢复该 App？")
                .setMessage("$appName\n\n只恢复这个 App 的后台运行、唤醒锁、精确闹钟和待机桶，并从本 App 护栏名单/历史记录中移除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            MaxHzController.restoreNightWakeupGuardPackage(packageName)
                        }
                        if (result.code == 0) {
                            MaxHzPrefs.removeNightWakeupGuardPackage(this@MaxHzActivity, packageName)
                            MaxHzPrefs.removeNightWakeupGuardAppliedPackage(this@MaxHzActivity, packageName)
                            onChanged()
                            reload()
                            showCommandResult(
                                resultView,
                                "恢复单个 App 护栏",
                                "只恢复这个 App 的后台运行、唤醒锁、精确闹钟和待机桶。",
                                result
                            )
                        } else {
                            showCommandResult(
                                resultView,
                                "恢复单个 App 护栏",
                                "只恢复这个 App 的后台运行、唤醒锁、精确闹钟和待机桶。",
                                result
                            )
                        }
                    }
                }
                .showUnified()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(appName)
            .setMessage("${appKindDescriptionForPackage(packageName)}\n${item?.let { nightRestrictionSummary(it) } ?: "当前未检测到系统限制。"}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .showUnified()
    }

    private fun parseNightRestrictionItems(raw: String): List<NightRestrictionItem> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("NG_CURRENT|") }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 3) return@mapNotNull null
                val packageName = parts[1]
                if (!isPackageNameValid(packageName)) return@mapNotNull null
                val values = parts.drop(2)
                    .mapNotNull {
                        val index = it.indexOf('=')
                        if (index <= 0) null else it.substring(0, index) to it.substring(index + 1)
                    }
                    .toMap()
                NightRestrictionItem(
                    packageName = packageName,
                    bucket = values["bucket"].orEmpty().ifBlank { "未知" },
                    restricted = values["restricted"] == "1",
                    runRestricted = values["run"] == "1",
                    anyRunRestricted = values["any"] == "1",
                    wakeRestricted = values["wake"] == "1",
                    alarmRestricted = values["alarm"] == "1"
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    private fun parseNightRestrictionLimit(raw: String): Pair<Int, Int>? {
        return raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("NG_LIMIT|") }
            ?.split('|')
            ?.let { parts ->
                val shown = parts.getOrNull(1)?.toIntOrNull()
                val total = parts.getOrNull(2)?.toIntOrNull()
                if (shown != null && total != null && total > shown) shown to total else null
            }
    }

    private fun nightRestrictionSummary(item: NightRestrictionItem): String {
        val reasons = mutableListOf<String>()
        if (item.bucket.equals("restricted", ignoreCase = true) ||
            item.bucket.equals("rare", ignoreCase = true) ||
            (item.bucket.toIntOrNull() ?: 0) >= 40
        ) {
            reasons += "待机桶受限"
        }
        if (item.runRestricted || item.anyRunRestricted) reasons += "后台运行受限"
        if (item.wakeRestricted) reasons += "唤醒锁受限"
        if (item.alarmRestricted) reasons += "精确闹钟受限"
        return if (reasons.isEmpty()) {
            "待机状态：${item.bucket}"
        } else {
            reasons.joinToString(" · ")
        }
    }

    private fun renderNightWakeupGuardStatus(container: LinearLayout, suggestionCount: Int) {
        container.removeAllViews()
        val guardCount = MaxHzPrefs.nightWakeupGuardPackages(this).size
        val applied = MaxHzPrefs.nightWakeupGuardEnabled(this)
        val statusText = "名单 ${guardCount} 个 · ${if (applied) "已应用" else "未应用"}"
        val appliedHistoryCount = MaxHzPrefs.nightWakeupGuardAppliedPackages(this).distinct().size
        val isDark = isDarkMode()
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = outlinedRounded(
                if (isDark) 0xFF252A34.toInt() else 0xFFEFF3F8.toInt(),
                if (isDark) 0x1FFFFFFF.toInt() else 0x0C000000.toInt(),
                dp(10).toFloat()
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(TextView(this@MaxHzActivity).apply {
                text = "当前状态"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColors.onSurface)
            })
            addView(TextView(this@MaxHzActivity).apply {
                text = buildString {
                    append(statusText).append('\n')
                    if (appliedHistoryCount > 0) {
                        append("有 ").append(appliedHistoryCount).append(" 个历史应用项待恢复\n")
                    }
                    append("扫描建议 ").append(suggestionCount).append(" 个\n")
                    append("扫描不会自动限制后台；只有点击“应用护栏”后才会真正生效。")
                    if (suggestionCount > 0) {
                        append("\n候选占比只是本次扫描候选内的相对占比，不是系统真实耗电百分比。")
                    }
                }
                textSize = 12.5f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(0f, 1.2f)
            })
        })
    }

    private fun nightWakeupGuardSuggestionList(): List<MaxHzPrefs.NightWakeupSuggestion> {
        return MaxHzPrefs.nightWakeupGuardSuggestionDetails(this).ifEmpty {
            MaxHzPrefs.nightWakeupGuardSuggestions(this).map {
                MaxHzPrefs.NightWakeupSuggestion(it, "扫描候选，建议结合自身使用场景判断。")
            }
        }
    }

    private fun renderNightWakeupGuardSuggestions(
        container: LinearLayout,
        resultView: TextView,
        scanAnalysisReport: String,
        currentFilter: String = "全部",
        onFilterChanged: ((String) -> Unit)? = null,
        onSelectSuggested: (() -> Unit)? = null,
        onChanged: (String, Boolean, MaterialSwitch) -> Unit
    ) {
        container.removeAllViews()
        val guarded = MaxHzPrefs.nightWakeupGuardPackages(this).toSet()
        val suggestions = nightWakeupGuardSuggestionList()
        if (suggestions.isEmpty()) {
            container.addView(emptyText("尚未进行待机诊断\n点击上方“开始扫描”自动排查前后台频繁持锁与异常唤醒应用。"))
            return
        }
        container.addView(nightScanConclusionView(suggestions.size))
        val report = scanAnalysisReport
        val actionButtons = mutableListOf<View>()
        if (onSelectSuggested != null) {
            val candidates = suggestions.filterIndexed { index, suggestion ->
                suggestion.packageName !in guarded &&
                    nightSuggestionRiskLabel(suggestion, index, suggestions) == "建议处理" &&
                    !MaxHzPackagePolicy.isSystemRelated(this@MaxHzActivity, suggestion.packageName)
            }
            if (candidates.isNotEmpty()) {
                actionButtons.add(button("一键选择建议项 (${candidates.size})") {
                    onSelectSuggested.invoke()
                })
            }
        }
        if (report.isNotBlank()) {
            actionButtons.add(button("查看完整分析报告") {
                showNightScanReport(suggestions, report)
            })
        }
        if (actionButtons.isNotEmpty()) {
            container.addSpacedView(horizontalActions(*actionButtons.toTypedArray()), dp(8))
        }

        // 分类统计
        class SuggestionRiskItem(
            val suggestion: MaxHzPrefs.NightWakeupSuggestion,
            val originalIndex: Int,
            val riskLabel: String
        )
        val itemsWithRisk = suggestions.mapIndexed { index, suggestion ->
            SuggestionRiskItem(
                suggestion = suggestion,
                originalIndex = index,
                riskLabel = nightSuggestionRiskLabel(suggestion, index, suggestions)
            )
        }
        val handleItems = itemsWithRisk.filter { it.riskLabel == "建议处理" }
        val observeItems = itemsWithRisk.filter { it.riskLabel == "建议观察" }
        val cautiousItems = itemsWithRisk.filter { it.riskLabel == "谨慎处理" }
        val avoidItems = itemsWithRisk.filter { it.riskLabel == "不建议处理" }

        // 顶部横向滚动分类 Filter Chips
        val filterChipsScroll = android.widget.HorizontalScrollView(this@MaxHzActivity).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            val chipRow = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(8))
                listOf(
                    "全部" to suggestions.size,
                    "建议处理" to handleItems.size,
                    "建议观察" to observeItems.size,
                    "谨慎处理" to cautiousItems.size,
                    "不建议处理" to avoidItems.size
                ).forEachIndexed { idx, (label, count) ->
                    val isSelected = (label == currentFilter)
                    val chip = filterChip("$label ($count)", isSelected) {
                        onFilterChanged?.invoke(label)
                    }
                    addView(chip, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (idx > 0) leftMargin = dp(6)
                    })
                }
            }
            addView(chipRow)
        }
        container.addView(filterChipsScroll)

        // 分栏卡片渲染器
        fun renderCategorySection(
            title: String,
            desc: String,
            dotColor: Int,
            items: List<SuggestionRiskItem>
        ) {
            if (items.isEmpty()) return
            val sectionHeader = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(6))
                val row = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val dot = View(this@MaxHzActivity).apply {
                        background = rounded(dotColor, dp(4).toFloat())
                    }
                    addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
                    addView(TextView(this@MaxHzActivity).apply {
                        text = "$title (${items.size})"
                        textSize = 13.5f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(themeColors.onSurface)
                    })
                }
                addView(row)
                addView(TextView(this@MaxHzActivity).apply {
                    text = desc
                    textSize = 11.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setPadding(dp(16), dp(2), 0, 0)
                })
            }
            container.addView(sectionHeader)
            container.addView(groupCard {
                items.forEachIndexed { idx, item ->
                    if (idx > 0) addView(divider())
                    addView(nightSuggestionAppRow(
                        suggestion = item.suggestion,
                        index = item.originalIndex,
                        suggestions = suggestions,
                        guarded = item.suggestion.packageName in guarded,
                        onChanged = { enabled, toggle -> onChanged(item.suggestion.packageName, enabled, toggle) }
                    ))
                }
            })
        }

        when (currentFilter) {
            "建议处理" -> {
                if (handleItems.isEmpty()) {
                    container.addView(emptyText("未发现符合「建议处理」的异常应用"))
                } else {
                    renderCategorySection("建议处理", "高频唤醒或大量持锁的用户应用，优先建议加入待机护栏", Color.parseColor("#EF4444"), handleItems)
                }
            }
            "建议观察" -> {
                if (observeItems.isEmpty()) {
                    container.addView(emptyText("未发现符合「建议观察」的候选应用"))
                } else {
                    renderCategorySection("建议观察", "有一定后台活动的应用，可先观察日常待机表现", Color.parseColor("#F59E0B"), observeItems)
                }
            }
            "谨慎处理" -> {
                if (cautiousItems.isEmpty()) {
                    container.addView(emptyText("未发现符合「谨慎处理」的应用"))
                } else {
                    renderCategorySection("谨慎处理", "通讯、推送、健康同步或关键工具，限制后可能延迟接收消息", Color.parseColor("#3B82F6"), cautiousItems)
                }
            }
            "不建议处理" -> {
                if (avoidItems.isEmpty()) {
                    container.addView(emptyText("未发现符合「不建议处理」的应用"))
                } else {
                    renderCategorySection("不建议处理", "系统核心服务与框架底层组件，通常不建议限制", Color.parseColor("#94A3B8"), avoidItems)
                }
            }
            else -> {
                renderCategorySection("建议处理", "高频唤醒或大量持锁的用户应用，优先建议加入待机护栏", Color.parseColor("#EF4444"), handleItems)
                renderCategorySection("建议观察", "有一定后台活动的应用，可先观察日常待机表现", Color.parseColor("#F59E0B"), observeItems)
                renderCategorySection("谨慎处理", "通讯、推送、健康同步或关键工具，限制后可能延迟接收消息", Color.parseColor("#3B82F6"), cautiousItems)
                renderCategorySection("不建议处理", "系统核心服务与框架底层组件，通常不建议限制", Color.parseColor("#94A3B8"), avoidItems)
            }
        }

        // 核心服务自动豁免展示（微信、电话、GMS等有活动但已受保护）
        val protectedLines = report.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") && (it.contains("核心服务") || it.contains("自动保护") || it.contains("默认豁免")) }
            .toList()
        if (protectedLines.isNotEmpty()) {
            var protectedExpanded = false
            lateinit var protectedDetailContainer: LinearLayout
            lateinit var protectedHeaderTitle: TextView
            container.addSpacedView(card {
                val header = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    installClickFeedback(this, foregroundRipple = false)
                    val infoLayout = LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        protectedHeaderTitle = TextView(this@MaxHzActivity).apply {
                            text = "已自动保护的核心服务 (${protectedLines.size} 项) ▸"
                            textSize = 13.5f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(themeColors.onSurface)
                        }
                        addView(protectedHeaderTitle)
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "微信、电话等通讯与系统服务已自动豁免，防止漏接重要消息"
                            textSize = 11.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, dp(2), 0, 0)
                        })
                    }
                    addView(infoLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener {
                        playClickFeedback(this)
                        protectedExpanded = !protectedExpanded
                        protectedHeaderTitle.text = "已自动保护的核心服务 (${protectedLines.size} 项) ${if (protectedExpanded) "▾" else "▸"}"
                        protectedDetailContainer.visibility = if (protectedExpanded) View.VISIBLE else View.GONE
                    }
                }
                addView(header)
                protectedDetailContainer = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    setPadding(0, dp(8), 0, 0)
                    protectedLines.forEachIndexed { idx, line ->
                        if (idx > 0) addView(divider())
                        val cleaned = line.removePrefix("- ").trim()
                        val parts = cleaned.split("：", limit = 2)
                        val pkgOrName = parts.getOrNull(0)?.trim().orEmpty()
                        val reasonText = parts.getOrNull(1)?.trim() ?: cleaned
                        val appLabel = appLabelForPackage(pkgOrName)
                        addView(LinearLayout(this@MaxHzActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(6), 0, dp(6))
                            val iconView = ImageView(this@MaxHzActivity).apply {
                                val iconDrawable = runCatching { packageManager.getApplicationIcon(pkgOrName) }.getOrNull()
                                if (iconDrawable != null) setImageDrawable(iconDrawable)
                                else setImageResource(R.drawable.ic_maxhz_apps_24)
                            }
                            addView(iconView, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
                            addView(LinearLayout(this@MaxHzActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(TextView(this@MaxHzActivity).apply {
                                    text = if (appLabel != pkgOrName) "$appLabel ($pkgOrName)" else appLabel
                                    textSize = 13f
                                    setTextColor(themeColors.onSurface)
                                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                                })
                                addView(TextView(this@MaxHzActivity).apply {
                                    text = reasonText
                                    textSize = 11.5f
                                    setTextColor(themeColors.onSurfaceVariant)
                                    setPadding(0, dp(1), 0, 0)
                                })
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        })
                    }
                }
                addView(protectedDetailContainer)
            }, dp(CARD_SECTION_SPACING_DP))
        }
    }

    private fun nightSuggestionAppRow(
        suggestion: MaxHzPrefs.NightWakeupSuggestion,
        index: Int,
        suggestions: List<MaxHzPrefs.NightWakeupSuggestion>,
        guarded: Boolean,
        onChanged: (Boolean, MaterialSwitch) -> Unit
    ): View {
        var expanded = false
        lateinit var toggle: MaterialSwitch
        lateinit var summaryView: TextView
        lateinit var expandIndicator: TextView
        lateinit var expandedDetailView: TextView

        val appName = appLabelForPackage(suggestion.packageName)
        val appKind = appKindDescriptionForPackage(suggestion.packageName)
        val percent = nightSuggestionPercent(suggestion, suggestions)
        val riskLabel = nightSuggestionRiskLabel(suggestion, index, suggestions)
        val compactSummary = nightSuggestionCompactSummary(suggestion, index, suggestions, guarded)
        val fullSummary = nightSuggestionSummary(suggestion, index, suggestions, guarded)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))

            val mainRow = LinearLayout(this@MaxHzActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                installClickFeedback(this)

                // App 图标
                val iconView = ImageView(this@MaxHzActivity).apply {
                    val iconDrawable = runCatching { packageManager.getApplicationIcon(suggestion.packageName) }.getOrNull()
                    if (iconDrawable != null) setImageDrawable(iconDrawable)
                    else setImageResource(R.drawable.ic_maxhz_apps_24)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(iconView, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })

                // 文本列
                val textCol = LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.VERTICAL

                    val titleRow = LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        addView(TextView(this@MaxHzActivity).apply {
                            text = appName
                            textSize = 15f
                            setTextColor(themeColors.onSurface)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                        val riskBadge = TextView(this@MaxHzActivity).apply {
                            text = riskLabel
                            textSize = 10.5f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setPadding(dp(7), dp(2), dp(7), dp(2))
                            val isDark = isDarkMode()
                            when (riskLabel) {
                                "建议处理" -> {
                                    setTextColor(if (isDark) 0xFFFF6B6B.toInt() else 0xFFD32F2F.toInt())
                                    background = rounded(if (isDark) 0x33FF5252.toInt() else 0x1AE53935.toInt(), dp(999).toFloat())
                                }
                                "建议观察" -> {
                                    setTextColor(if (isDark) 0xFFFFB74D.toInt() else 0xFFE65100.toInt())
                                    background = rounded(if (isDark) 0x33FFA726.toInt() else 0x1AFB8C00.toInt(), dp(999).toFloat())
                                }
                                else -> {
                                    setTextColor(themeColors.onSurfaceVariant)
                                    background = rounded(themeColors.surfaceContainerHigh, dp(999).toFloat())
                                }
                            }
                        }
                        addView(riskBadge, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { leftMargin = dp(6) })

                        percent?.let { p ->
                            addView(TextView(this@MaxHzActivity).apply {
                                text = "约 $p%"
                                textSize = 11f
                                setTextColor(themeColors.onSurfaceVariant)
                            }, LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { leftMargin = dp(4) })
                        }

                        expandIndicator = TextView(this@MaxHzActivity).apply {
                            text = " ▸"
                            textSize = 12f
                            setTextColor(themeColors.onSurfaceVariant)
                        }
                        addView(expandIndicator, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { leftMargin = dp(2) })
                    }
                    addView(titleRow)

                    summaryView = TextView(this@MaxHzActivity).apply {
                        text = "$appKind · $compactSummary"
                        textSize = 12.5f
                        setTextColor(themeColors.onSurfaceVariant)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    }
                    addView(summaryView)
                }
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                // 开关
                var programmatic = false
                toggle = MaterialSwitch(this@MaxHzActivity).apply {
                    showText = false
                    isChecked = guarded
                    LiquidGlassTheme.styleIosSwitch(this, isDarkMode())
                    setOnCheckedChangeListener { _, isChecked ->
                        if (programmatic) return@setOnCheckedChangeListener
                        onChanged(isChecked, this)
                    }
                }
                addView(toggle, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) })

                bindOptionBSubtitle(this, summaryView)
                setOnClickListener {
                    expanded = !expanded
                    expandIndicator.text = if (expanded) " ▾" else " ▸"
                    expandedDetailView.visibility = if (expanded) View.VISIBLE else View.GONE
                    summaryView.visibility = if (expanded || detailSubtitlesExpanded) View.VISIBLE else View.GONE
                    summaryView.maxLines = if (expanded) 3 else 1
                    playClickFeedback(this)
                }
            }
            addView(mainRow)

            expandedDetailView = TextView(this@MaxHzActivity).apply {
                visibility = View.GONE
                textSize = 12f
                setTextColor(themeColors.onSurfaceVariant)
                setLineSpacing(0f, 1.2f)
                setPadding(dp(50), dp(4), dp(8), dp(4))
                text = fullSummary
            }
            addView(expandedDetailView)
        }
    }

    private fun addNightWakeupGuardPackageWithWhitelistCheck(
        packageName: String,
        appName: String,
        resultView: TextView,
        onAdded: () -> Unit,
        onCancelled: () -> Unit = {},
        skipSystemRelatedWarning: Boolean = false
    ) {
        if (!isPackageNameValid(packageName)) {
            onCancelled()
            statusTextLater(resultView, "包名无效，未加入待机省电护栏：$packageName")
            return
        }

        fun addPackage(message: String) {
            MaxHzPrefs.addNightWakeupGuardPackage(this, packageName)
            onAdded()
            statusTextLater(resultView, message)
        }

        if (!skipSystemRelatedWarning && MaxHzPackagePolicy.isSystemRelated(this, packageName)) {
            val trustClass = MaxHzPackagePolicy.trustClass(this, packageName)
            val message = if (trustClass == MaxHzPackagePolicy.AppTrustClass.CORE_SERVICE) {
                "$appName 属于系统相关应用，可能影响通知、同步、电话、推送或系统功能。\n\n该应用更接近核心系统服务，不建议加入待机省电护栏。"
            } else {
                "$appName 属于系统相关应用，可能影响通知、同步、电话、推送或系统功能。\n\n建议只把明确耗电的普通用户应用加入护栏。"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("系统相关应用")
                .setMessage(message)
                .setNegativeButton("取消") { _, _ ->
                    onCancelled()
                    statusTextLater(resultView, "已取消加入待机省电护栏：$appName。")
                }
                .setOnCancelListener {
                    onCancelled()
                    statusTextLater(resultView, "已取消加入待机省电护栏：$appName。")
                }
                .setPositiveButton("仍然加入") { _, _ ->
                    addNightWakeupGuardPackageWithWhitelistCheck(
                        packageName = packageName,
                        appName = appName,
                        resultView = resultView,
                        onAdded = onAdded,
                        onCancelled = onCancelled,
                        skipSystemRelatedWarning = true
                    )
                }
                .showUnified()
            return
        }

        if (!MaxHzShell.isReady()) {
            addPackage("已加入待机省电护栏：$appName。未检测系统后台保护状态：Shizuku 未运行。点“应用护栏”后才会开始限制。")
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MaxHzController.readDozeWhitelistPackages() }
                    .getOrElse { MaxHzShell.Result(-2, "", it.message ?: it.javaClass.simpleName) }
            }
            if (result.code != 0) {
                addPackage("已加入待机省电护栏：$appName。未能确认系统后台保护状态：${commandFailureReason(result)}。点“应用护栏”后才会开始限制。")
                return@launch
            }

            val inWhitelist = parsePackageLines(result.out).any { it == packageName }
            if (!inWhitelist) {
                addPackage("已加入待机省电护栏：$appName。点“应用护栏”后才会开始限制。")
                return@launch
            }

            MaterialAlertDialogBuilder(this@MaxHzActivity)
                .setTitle("与系统后台保护冲突")
                .setMessage(
                    "$appName 当前在系统后台保护名单中，可能对应三星电池设置里的“不受限制”或系统保护状态。\n\n" +
                        "加入待机省电护栏后，本工具会限制它的后台运行、唤醒锁和精确闹钟，可能导致通知、同步或后台任务延迟。"
                )
                .setNegativeButton("取消") { _, _ ->
                    onCancelled()
                    statusTextLater(resultView, "已取消加入待机省电护栏：$appName。")
                }
                .setOnCancelListener {
                    onCancelled()
                    statusTextLater(resultView, "已取消加入待机省电护栏：$appName。")
                }
                .setPositiveButton("仍然加入护栏") { _, _ ->
                    addPackage("已加入待机省电护栏：$appName。该应用原本受系统后台保护，应用护栏后可能影响通知、同步或后台任务。")
                }
                .showUnified()
        }
    }

    private fun appRefreshRuleModeText(rule: MaxHzPrefs.AppRefreshRule): String {
        return if (rule.adaptive) {
            "自适应 ${rule.minHz}-${rule.maxHz}Hz"
        } else {
            "静态 ${rule.maxHz}Hz"
        }
    }

    private fun showDeleteAppRefreshRuleDialog(
        rule: MaxHzPrefs.AppRefreshRule,
        appName: String,
        modeText: String,
        onDeleted: () -> Unit
    ) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("删除刷新率规则")
            .setMessage("$appName\n$modeText\n\n删除此 App 的刷新率规则？\n\n删除后不会影响系统刷新率设置，只是停止自动切换。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
	                    val result = withContext(Dispatchers.IO) {
	                        MaxHzPrefs.removeAppRefreshRule(this@MaxHzActivity, rule.packageName)
                        try {
                            MaxHzController.applyScreenOnAdaptive()
                        } catch (t: Throwable) {
                            MaxHzShell.Result(-2, "", t.message ?: t.javaClass.simpleName)
	                        }
	                    }
	                    if (selectedAppRefreshPackage == rule.packageName) {
	                        selectedAppRefreshPackage = ""
	                        selectedAppRefreshLabel = ""
	                    }
	                    onDeleted()
	                    statusTextLater(
                        if (result.code == 0) "已删除 $appName 的刷新率规则，并恢复系统默认自适应刷新率。"
                        else "规则已删除，但刷新率恢复未通过：${result.err.ifBlank { result.out }}"
                    )
                }
            }
            .create()

        dialog.show()
        val isDark = isDarkMode()
        dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
        applyUnifiedDialogWindowStyle(dialog)
    }

    private fun appLabelForPackage(packageName: String): String {
        appDisplayCache[packageName]?.let { return it.label }
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
            rememberAppDisplay(packageName, label, MaxHzPackagePolicy.trustClass(this, info))
            label
        } catch (_: Throwable) {
            packageName
        }
    }

    private fun appKindDescription(systemApp: Boolean): String {
        return if (systemApp) "系统组件" else "用户应用"
    }

    private fun appKindDescription(trustClass: MaxHzPackagePolicy.AppTrustClass): String {
        return if (trustClass == MaxHzPackagePolicy.AppTrustClass.USER_APP) {
            "用户应用"
        } else {
            "系统组件"
        }
    }

    private fun appKindShort(trustClass: MaxHzPackagePolicy.AppTrustClass): String {
        return if (trustClass == MaxHzPackagePolicy.AppTrustClass.USER_APP) "用户应用" else "系统组件"
    }

    private fun appKindDescriptionForPackage(packageName: String): String {
        appDisplayCache[packageName]?.let { return it.kind }
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
            val trustClass = MaxHzPackagePolicy.trustClass(this, info)
            rememberAppDisplay(packageName, label, trustClass)
            appKindDescription(trustClass)
        } catch (_: Throwable) {
            appKindDescription(MaxHzPackagePolicy.trustClass(this, packageName))
        }
    }

    private fun parseRefreshRates(text: String): List<Int> {
        return Regex("\\d+")
            .findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..240 }
            .distinct()
            .sorted()
            .toList()
    }

    private fun parsePackageLines(text: String): List<String> {
        return text.lineSequence()
            .flatMap { line ->
                Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
                    .findAll(line)
                    .map { it.value }
            }
            .filter { isPackageNameValid(it) }
            .distinct()
            .toList()
    }

    private fun parseDozeHistory(text: String): List<DozeHistoryItem> {
        val raw = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.equals("Idling history:", ignoreCase = true) }
            .filterNot { it.startsWith("--") }
            .toList()
        return raw.mapNotNull { line ->
            val cleaned = line.removePrefix("*").trim()
            val parts = cleaned.split(Regex("\\s+"), limit = 2)
            when {
                parts.size == 2 && parts[0].contains(Regex("[0-9]")) -> {
                    DozeHistoryItem(parts[0].trimEnd(':'), normalizeDozeState(parts[1]))
                }
                cleaned.contains("mState=") || cleaned.contains("mLightState=") -> {
                    DozeHistoryItem("当前状态", normalizeDozeState(cleaned))
                }
                cleaned.contains("idle", ignoreCase = true) || cleaned.contains("normal", ignoreCase = true) -> {
                    DozeHistoryItem("记录", normalizeDozeState(cleaned))
                }
                else -> null
            }
        }
    }

    private fun normalizeDozeState(text: String): String {
        return text
            .replace("IDLE_MAINTENANCE", "待机维护窗口")
            .replace("LIGHT_IDLE_MAINTENANCE", "待机维护窗口")
            .replace("LIGHT_IDLE", "浅度待机省电")
            .replace("IDLE", "深度待机省电")
            .replace("ACTIVE", "普通状态")
            .replace("INACTIVE", "普通状态")
            .trim()
    }

    private fun nightScanConclusionView(suggestionCount: Int): View {
        val isDark = isDarkMode()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = outlinedRounded(
                if (isDark) 0xFF252A34.toInt() else 0xFFEFF3F8.toInt(),
                if (isDark) 0x1FFFFFFF.toInt() else 0x0C000000.toInt(),
                dp(10).toFloat()
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(TextView(this@MaxHzActivity).apply {
                text = "扫描结论"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColors.onSurface)
            })
            addView(TextView(this@MaxHzActivity).apply {
                text = nightScanConclusionText(suggestionCount)
                textSize = 12.5f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun nightScanConclusionText(suggestionCount: Int): String {
        val firstBatch = when {
            suggestionCount <= 0 -> 0
            suggestionCount <= 3 -> suggestionCount
            else -> 2
        }
        return buildString {
            append("发现 ").append(suggestionCount).append(" 个待机唤醒或持锁活跃的 App。\n")
            if (firstBatch > 0) {
                append("建议先处理 ").append(firstBatch).append(" 个标记为【建议处理】的高风险 App，观察待机改善情况。\n")
            } else {
                append("当前待机质量优良，未发现过度唤醒的第三方应用。\n")
            }
            append("扫描不会自动限制任何 App；只有加入名单并点击“应用护栏”后才会生效。\n")
            append("候选占比是本次扫描候选内的相对占比，不是手机真实耗电百分比。")
        }
    }

    private fun showNightScanReport(
        suggestions: List<MaxHzPrefs.NightWakeupSuggestion>,
        advancedReport: String
    ) {
        MaterialAlertDialogBuilder(this@MaxHzActivity)
            .setTitle("扫描分析报告")
            .setMessage(nightScanUserReport(suggestions))
            .setPositiveButton("知道了", null)
            .setNeutralButton("高级详情") { _, _ ->
                MaterialAlertDialogBuilder(this@MaxHzActivity)
                    .setTitle("高级详情")
                    .setMessage(cleanNightScanAdvancedReport(advancedReport))
                    .setPositiveButton("知道了", null)
                    .showUnified()
            }
            .showUnified()
    }

    private fun nightScanUserReport(suggestions: List<MaxHzPrefs.NightWakeupSuggestion>): String {
        return buildString {
            append(nightScanConclusionText(suggestions.size))
            append("\n\n建议查看：\n")
            suggestions.take(5).forEachIndexed { index, suggestion ->
                append(index + 1)
                    .append(". ")
                    .append(appLabelForPackage(suggestion.packageName))
                    .append("（")
                    .append(appKindDescriptionForPackage(suggestion.packageName))
                    .append("）")
                    .append("：")
                    .append(nightSuggestionPrimaryReasons(suggestion.detail))
                    .append('\n')
            }
            if (suggestions.size > 5) {
                append("还有 ").append(suggestions.size - 5).append(" 个候选，可在列表中展开查看。\n")
            }
            append("\n建议：先只处理最可疑的 1 个 App。通讯、电话、日历、健康、系统安全类 App 不建议加入护栏。")
        }.trimEnd()
    }

    private fun nightSuggestionSummary(
        suggestion: MaxHzPrefs.NightWakeupSuggestion,
        index: Int,
        suggestions: List<MaxHzPrefs.NightWakeupSuggestion>,
        enabled: Boolean
    ): String {
        return nightSuggestionDisplayText(suggestion, index, suggestions, enabled, includeAdvanced = true)
    }

    private fun nightSuggestionCompactSummary(
        suggestion: MaxHzPrefs.NightWakeupSuggestion,
        index: Int,
        suggestions: List<MaxHzPrefs.NightWakeupSuggestion>,
        enabled: Boolean
    ): String {
        return nightSuggestionDisplayText(suggestion, index, suggestions, enabled, includeAdvanced = false)
    }

    private fun nightSuggestionDisplayText(
        suggestion: MaxHzPrefs.NightWakeupSuggestion,
        index: Int,
        suggestions: List<MaxHzPrefs.NightWakeupSuggestion>,
        enabled: Boolean,
        includeAdvanced: Boolean
    ): String {
        val percent = nightSuggestionPercent(suggestion, suggestions)
        val riskLabel = nightSuggestionRiskLabel(suggestion, index, suggestions)
        val reason = nightSuggestionPrimaryReasons(suggestion.detail)
        return buildString {
            append(riskLabel)
            percent?.let { append(" · 约 ").append(it).append("%") }
            append(" · ")
            append(reason)
            if (includeAdvanced) {
                append('\n')
                append(
                    when {
                        riskLabel == "不建议处理" -> "处理影响：可能影响系统、电话、推送或安全功能，默认不建议加入护栏。"
                        riskLabel == "谨慎处理" -> "处理影响：可能影响通知、同步或后台服务，建议先观察。"
                        enabled -> "处理状态：已加入名单；必须点击“应用护栏”后才会真正生效。"
                        else -> "处理影响：可能延迟该 App 的通知或后台同步，建议处理后观察一晚。"
                    }
                )
                append('\n')
                append("占比说明：本次扫描候选内的相对占比，不是手机真实耗电百分比。")
                append("\n技术详情：").append(nightSuggestionAdvancedDetail(suggestion.detail))
            }
        }
    }

    private fun nightSuggestionPercent(
        suggestion: MaxHzPrefs.NightWakeupSuggestion,
        suggestions: List<MaxHzPrefs.NightWakeupSuggestion>
    ): Int? {
        val score = nightSuggestionScore(suggestion.detail)
        val totalScore = suggestions.sumOf { nightSuggestionScore(it.detail) ?: 0.0 }
        return score?.takeIf { totalScore > 0.0 }?.let { (it * 100.0 / totalScore).toInt().coerceIn(1, 100) }
    }

    private fun nightSuggestionScore(detail: String): Double? {
        return Regex("熄屏约\\s*([0-9.]+)\\s*mAh")
            .find(detail)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
    }

    private fun nightSuggestionLevel(index: Int, percent: Int?, cautious: Boolean): String {
        if (cautious) return "建议谨慎处理"
        return when {
            index == 0 || (percent != null && percent >= 35) -> "高"
            index <= 2 || (percent != null && percent >= 15) -> "中"
            else -> "低"
        }
    }

    private fun nightSuggestionRiskLabel(
        suggestion: MaxHzPrefs.NightWakeupSuggestion,
        index: Int,
        suggestions: List<MaxHzPrefs.NightWakeupSuggestion>
    ): String {
        val trustClass = MaxHzPackagePolicy.trustClass(this, suggestion.packageName)
        if (trustClass == MaxHzPackagePolicy.AppTrustClass.CORE_SERVICE) return "不建议处理"
        if (trustClass != MaxHzPackagePolicy.AppTrustClass.USER_APP || isCautiousNightGuardPackage(suggestion.packageName)) return "谨慎处理"
        val percent = nightSuggestionPercent(suggestion, suggestions)
        val hasWakeLock = suggestion.detail.contains("持锁")
        val highWakeup = Regex("唤醒\\s*(\\d+)").find(suggestion.detail)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { it >= 40 } ?: false
        return if (index == 0 || (percent != null && percent >= 25) || hasWakeLock || highWakeup) "建议处理" else "建议观察"
    }

    private fun isCoreNightGuardPackage(packageName: String): Boolean {
        return packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.android.phone" ||
            packageName == "com.android.server.telecom" ||
            packageName == "com.android.providers.settings" ||
            packageName == "com.android.providers.telephony" ||
            packageName == "com.android.providers.media" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName == "com.android.packageinstaller" ||
            packageName == "com.android.settings" ||
            packageName == "com.samsung.android.app.telephonyui" ||
            packageName == "com.samsung.android.incallui" ||
            packageName == "com.sec.phone"
    }

    private fun isCautiousNightGuardPackage(packageName: String): Boolean {
        return packageName.startsWith("com.google.") ||
            packageName.startsWith("com.android.") ||
            packageName.startsWith("com.samsung.") ||
            packageName.startsWith("com.sec.") ||
            packageName.contains("push", ignoreCase = true) ||
            packageName.contains("message", ignoreCase = true) ||
            packageName.contains("dialer", ignoreCase = true) ||
            packageName.contains("phone", ignoreCase = true) ||
            packageName.contains("health", ignoreCase = true) ||
            packageName in setOf(
                "com.tencent.mm",
                "com.tencent.tim",
                "com.tencent.mobileqq",
                "com.whatsapp",
                "com.google.android.gms",
                "com.google.android.gsf"
            )
    }

    private fun nightSuggestionSimpleReason(detail: String): String {
        return nightSuggestionPrimaryReasons(detail)
    }

    private fun nightSuggestionPrimaryReasons(detail: String): String {
        val compact = detail.replace(Regex("\\s+"), " ").trim()
        if (compact.contains("alarm", ignoreCase = true) && !compact.contains("熄屏约") && !compact.contains("唤醒")) return "后台定时唤醒较活跃"
        val factors = nightSuggestionFactors(compact)
        if (factors.isEmpty()) return "熄屏后仍较活跃"
        val top = factors.first()
        val second = factors.getOrNull(1)
        return if (second != null && second.value >= top.value * 0.75) {
            "${top.label} · ${second.label}"
        } else {
            top.label
        }
    }

    private fun nightSuggestionFactors(detail: String): List<NightFactor> {
        val compact = detail.replace(Regex("\\s+"), " ").trim()
        val wakeVal = nightFactorValue(compact, "唤醒")
        val factors = listOf(
            NightFactor("后台持锁阻碍休眠", if (compact.contains("持锁")) 60.0 else 0.0),
            NightFactor("后台 CPU 活动较多", nightFactorValue(compact, "CPU")),
            NightFactor("后台网络活动较多", nightFactorValue(compact, "网络")),
            NightFactor("后台唤醒较多", wakeVal),
            NightFactor("无线活动较多", nightFactorValue(compact, "蓝牙") + nightFactorValue(compact, "Wi-Fi")),
            NightFactor("熄屏后仍使用定位或传感器", nightFactorValue(compact, "定位") + nightFactorValue(compact, "传感器")),
            NightFactor("后台媒体活动较多", nightFactorValue(compact, "媒体")),
            NightFactor("后台相机活动较多", nightFactorValue(compact, "相机")),
            NightFactor("后台定时唤醒较活跃", if (compact.contains("alarm", ignoreCase = true) || wakeVal > 15) 15.0 else 0.0)
        )
        return factors.filter { it.value > 0.0 }
            .sortedByDescending { it.value }
    }

    private fun nightFactorValue(text: String, label: String): Double {
        return Regex("${Regex.escape(label)}\\s+([0-9.]+)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull() ?: 0.0
    }

    private fun nightSuggestionAdvancedDetail(detail: String): String {
        return cleanNightMetricText(detail)
            .replace(Regex("\\s+"), " ")
            .replace(Regex("standby=([0-9]+|未知)"), "待机分组=$1")
            .trim()
            .ifBlank { "扫描候选，建议结合自身使用场景判断。" }
    }

    private fun cleanNightScanAdvancedReport(report: String): String {
        return report.lineSequence()
            .map { cleanNightMetricText(it.trim()) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { "没有需要展示的扫描详情。" }
    }

    private fun cleanNightMetricText(text: String): String {
        if (text.isBlank()) return text
        val separator = when {
            text.contains("；") -> "；"
            text.contains(";") -> ";"
            else -> null
        } ?: return text
        val parts = text.split(separator)
        if (parts.size < 2) return text
        val cleanedParts = parts.mapIndexedNotNull { index, part ->
            val trimmed = part.trim()
            if (index == 0 || !containsNightMetric(trimmed)) {
                trimmed.takeIf { it.isNotBlank() }
            } else {
                filterNightMetricSegment(trimmed).takeIf { it.isNotBlank() }
            }
        }
        return cleanedParts.joinToString("；")
    }

    private fun containsNightMetric(text: String): Boolean {
        return Regex("(CPU|网络|唤醒|蓝牙|Wi-Fi|传感器|定位|媒体|相机|持锁)\\s*[0-9.]*").containsMatchIn(text)
    }

    private fun filterNightMetricSegment(text: String): String {
        val delim = if (text.contains("·")) "·" else "/"
        val metrics = text.split(delim)
            .map { it.trim() }
            .filter { metric ->
                val match = Regex("^(CPU|网络|唤醒|蓝牙|Wi-Fi|传感器|定位|媒体|相机)\\s+([0-9.]+)").find(metric)
                val value = match?.groupValues?.getOrNull(2)?.toDoubleOrNull()
                value == null || value > 0.05 || metric.contains("持锁")
            }
        return metrics.joinToString(" · ")
    }

    private fun parseNightWakeupSuggestionDetails(text: String): List<MaxHzPrefs.NightWakeupSuggestion> {
        return text.lineSequence()
            .mapNotNull { line ->
                if (!line.startsWith("候选|")) return@mapNotNull null
                val parts = line.split('|', limit = 3)
                val packageName = parts.getOrNull(1)?.trim().orEmpty()
                if (!isPackageNameValid(packageName)) return@mapNotNull null
                MaxHzPrefs.NightWakeupSuggestion(
                    packageName = packageName,
                    detail = parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() } ?: "扫描候选，建议结合自身使用场景判断。"
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    /**
     * 性能类开关的互斥前置：跑性能命令之前，先把省电 / 深度休眠退干净。
     *
     * 背景（这就是用户遇到的「核心失败」）：`applyMPerformance` 内部会执行
     * `low_power 0` / `battery_saver_enabled 0` 把系统省电关掉，但**完全不碰
     * `compatibleSaverEnabled` 这个 pref**。于是：
     *   1. 省电开关的 isOn 读 pref = true  → 显示「开」
     *   2. 状态文字读系统省电 = OFF        → 只能报「核心失败」
     *   3. 用户再去关省电开关 → `restoreDailySaver()` 校验「系统省电没恢复到
     *      预期值」直接 exit 4 → 弹「恢复失败」，卡死在错误态里出不来
     *
     * 这里在性能命令之前先把恢复命令跑一遍并同步 pref，让 pref 与系统状态
     * 始终一致。恢复命令本身失败也要继续往下走：它失败恰恰说明系统已经不在
     * 省电态，此时跑性能命令是安全的；硬拦下反而会让用户什么都开不了。
     */
    private suspend fun withSaverReleased(block: suspend () -> MaxHzShell.Result): MaxHzShell.Result {
        if (MaxHzPrefs.compatibleSaverEnabled(this)) {
            MaxHzController.restoreDailySaver()
            // 无论恢复命令是否成功都要清 pref：走到这里说明系统省电已经不归
            // 省电开关管了，留着 true 只会让那个开关一直显示「已被覆盖」。
            MaxHzPrefs.setCompatibleSaverEnabled(this, false)
        }
        if (MaxHzPrefs.quickDozeEnabled(this)) {
            MaxHzController.restoreQuickDoze()
            MaxHzPrefs.setQuickDozeEnabled(this, false)
        }
        return block()
    }

    private fun combineResults(vararg results: MaxHzShell.Result): MaxHzShell.Result {
        val code = results.firstOrNull { it.code != 0 }?.code ?: 0
        val out = results.map { it.out }.filter { it.isNotBlank() }.joinToString("\n")
        val err = results.map { it.err }.filter { it.isNotBlank() }.joinToString("\n")
        return MaxHzShell.Result(code, out, err)
    }

    private fun appendOperationLog(event: String, result: String) {
        val cleanEvent = event.replace('|', '/').replace('\n', ' ').trim()
        val cleanResult = result.replace('|', '/').replace('\n', ' ').trim()
        if (cleanEvent.isBlank()) return
        val item = listOf(operationLogTime(), cleanEvent, cleanResult.ifBlank { "已记录" }).joinToString("|")
        val logs = operationLogPrefs()
            .getString(OPERATION_LOG_KEY, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toMutableList()
        logs.add(0, item)
        operationLogPrefs().edit()
            .putString(OPERATION_LOG_KEY, logs.take(OPERATION_LOG_LIMIT).joinToString("\n"))
            .apply()
    }

    private fun readOperationLogs(): List<OperationLogItem> {
        return operationLogPrefs()
            .getString(OPERATION_LOG_KEY, "")
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|', limit = 3)
                if (parts.size < 3) null else OperationLogItem(parts[0], parts[1], parts[2])
            }
            .toList()
    }

    private fun clearOperationLogs() {
        operationLogPrefs().edit()
            .remove(OPERATION_LOG_KEY)
            .remove(OPERATION_LAST_BACKGROUND_ERROR_KEY)
            .apply()
        Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show()
    }

    private fun operationLogPrefs() = getSharedPreferences(OPERATION_LOG_PREFS, Context.MODE_PRIVATE)

    private fun releasePrefs() = getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE)

    private fun operationLogTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    protected fun syncStatusChangeLogs() {
        val shizukuReady = MaxHzShell.isReady()
        lastKnownShizukuReady?.let { previous ->
            if (previous != shizukuReady) {
                appendOperationLog(
                    if (shizukuReady) "Shizuku已连接" else "Shizuku已断开",
                    if (shizukuReady) "正常" else "异常"
                )
            }
        }
        lastKnownShizukuReady = shizukuReady

        val accessibilityEnabled = MaxHzAccessibilityService.isEnabled(this)
        lastKnownAccessibilityEnabled?.let { previous ->
            if (previous != accessibilityEnabled) {
                appendOperationLog(
                    if (accessibilityEnabled) "无障碍状态变化" else "无障碍状态变化",
                    if (accessibilityEnabled) "已开启" else "未启用"
                )
            }
        }
        lastKnownAccessibilityEnabled = accessibilityEnabled
    }

    private fun syncBackgroundErrorLog() {
        val error = MaxHzPrefs.resultText(this, BACKGROUND_ERROR_RESULT_KEY).orEmpty().trim()
        if (error.isBlank()) return
        val lastLogged = operationLogPrefs().getString(OPERATION_LAST_BACKGROUND_ERROR_KEY, "").orEmpty()
        if (lastLogged == error) return
        appendOperationLog("后台执行失败", "异常")
        operationLogPrefs().edit().putString(OPERATION_LAST_BACKGROUND_ERROR_KEY, error).apply()
    }

    private fun recordCommandOperation(label: String, result: MaxHzShell.Result) {
        val success = result.code == 0
        val status = if (success) "正常" else "异常（代码 ${result.code}）"
        val event = when {
            label == "待机省电护栏" && success -> "应用护栏成功"
            label == "恢复待机护栏" && success -> "恢复护栏成功"
            label == "实时 FPS 监视器" && success -> "FPS Monitor已启动"
            label == "关闭 FPS 监视器" && success -> "FPS Monitor已停止"
            label == "开启户外模式" && success -> "户外模式已开启"
            label == "关闭户外模式" && success -> "户外模式已关闭"
            label == "电池保护" && success -> "电池保护应用"
            label.contains("保存每 App 刷新率") && success -> "刷新率规则已保存"
            label.contains("恢复当前 App 刷新率") && success -> "刷新率已恢复"
            label.contains("恢复所有 App 刷新率") && success -> "刷新率已恢复"
            !success -> "$label 执行未通过"
            else -> return
        }
        appendOperationLog(event, status)
    }

    private fun enabledState(enabled: Boolean): String {
        return if (enabled) "开启" else "关闭"
    }

    /**
     * 省电开关的状态文字。
     *
     * 第三态以前叫「核心失败」，看不出是谁干的、该怎么救。实际含义是：
     * pref 记着「开」，但系统省电已经被某个性能命令关掉了（见
     * [withSaverReleased] 的注释）。改成「已被性能模式覆盖」，至少让用户
     * 知道是别的开关干的，关掉那个开关或者重新开一次省电就能恢复。
     */
    private fun dailySaverState(): String {
        if (!MaxHzPrefs.compatibleSaverEnabled(this)) return "关闭"
        return if (isSystemPowerSaveModeOn()) "开启" else "已被性能模式覆盖"
    }

    private fun isSystemPowerSaveModeOn(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager?.isPowerSaveMode == true
    }

    private fun batteryProtectionState(): String {
        return if (MaxHzPrefs.batteryProtectionEnabled(this)) "电池开启" else "电池关闭"
    }

    private fun passThroughState(): String {
        return if (MaxHzPrefs.passThroughEnabled(this)) "旁路开启" else "旁路关闭"
    }

    private fun fpsMonitorState(): String {
        return if (MaxHzPrefs.fpsMonitorRunning(this)) "已开启" else "已关闭"
    }

    protected fun isOutdoorModeActive(): Boolean = outdoorModeActive()

    private fun outdoorModeActive(): Boolean {
        // 户外模式是三星私有 Settings.System 项，普通 app 用 ContentResolver 直读
        // 可能拿到默认值而非真实值；必须走 Shizuku 特权读取。读不到时才回退到
        // 上次已知状态。
        return MaxHzController.readOutdoorModeEnabled() ?: lastKnownOutdoorModeEnabled
    }

    private fun outdoorModeState(): String {
        return if (outdoorModeActive()) "已开启" else "已关闭"
    }

    private fun passThroughRuntimeState(text: String): String {
        val normalized = text.trim()
        return when {
            normalized.contains("可能正在旁路") ||
                normalized.contains("电池电流接近 0") -> "旁路运行中"
            normalized.contains("缺少充电器条件") ||
                normalized.contains("未接入充电器") ||
                normalized.contains("当前未识别为官方要求的 PD/PPS") -> "条件不足"
            normalized.contains("已接受请求，但未确认硬件旁路") -> "已请求未确认"
            normalized.contains("未接受旁路请求") ||
                normalized.contains("请求开关：关闭或未被系统接受") -> "未运行"
            normalized.contains("请求开关：已开启") ||
                normalized.contains("已写入 pass_through=1") -> "已请求"
            else -> passThroughState()
        }
    }

    private fun passThroughRuntimeSummary(state: String): String {
        return when (state) {
            "旁路运行中" -> "旁路充电状态\n正在旁路运行\n手机正在连接充电器使用，电池充电可能已暂停或接近暂停。"
            "已请求未确认" -> "旁路充电状态\n已发起请求，尚未确认\n系统已接受请求，但当前条件可能还未进入真正旁路。"
            "已请求", "旁路开启" -> "旁路充电状态\n已发起旁路请求\n请在接入合适充电器后点击“确认旁路状态”，确认是否正在旁路运行。"
            "条件不足" -> "旁路充电状态\n当前条件不足\n请确认已接入支持的 PD/PPS 充电器，电量、温度和系统服务状态也会影响旁路。"
            "未运行" -> "旁路充电状态\n当前未旁路\n系统未接受旁路请求，或当前仍在正常给电池充电。"
            else -> "旁路充电状态\n未开启\n点击“开启旁路”后，再点击“确认旁路状态”确认是否真正运行。"
        }
    }

    private fun currentMainModeLabel(): String {
        return when {
            MaxHzPrefs.compatibleSaverEnabled(this) -> "日用省电"
            MaxHzPrefs.mCpuResponseEnabled(this) -> "增强处理"
            else -> "官方默认"
        }
    }

    private fun currentRefreshPolicyLabel(): String {
        val rules = MaxHzPrefs.appRefreshRules(this).size
        return when {
            rules > 0 -> "每 App 规则 ${rules} 个"
            MaxHzPrefs.compatibleSaverEnabled(this) -> "省电自适应高刷"
            MaxHzPrefs.mRefresh120Enabled(this) -> "120Hz 锁定"
            else -> "系统自适应"
        }
    }

    private fun currentStandbyPolicyLabel(): String {
        val guardCount = MaxHzPrefs.nightWakeupGuardPackages(this).size
        val parts = mutableListOf<String>()
        if (MaxHzPrefs.quickDozeEnabled(this)) parts += "Quick Doze"
        if (guardCount > 0) parts += "护栏 $guardCount 个"
        if (MaxHzPrefs.scanGuardEnabled(this)) parts += "同步/扫描护栏"
        return parts.joinToString(" + ").ifBlank { "系统默认" }
    }

    private fun currentBatteryPolicyLabel(): String {
        val parts = mutableListOf<String>()
        if (MaxHzPrefs.batteryProtectionEnabled(this)) parts += "自定义保护"
        if (MaxHzPrefs.passThroughEnabled(this)) parts += "旁路请求"
        return parts.joinToString(" + ").ifBlank { "系统默认" }
    }

    private fun currentMAdvancedLabel(): String {
        val count = listOf(
            MaxHzPrefs.mCpuResponseEnabled(this),
            MaxHzPrefs.gameAutoPerformanceEnabled(this),
            MaxHzPrefs.mTombstoneEnabled(this),
            MaxHzPrefs.mLocationThrottleEnabled(this),
            MaxHzPrefs.mAutostartLimitEnabled(this),
            MaxHzPrefs.mRefresh120Enabled(this),
            MaxHzPrefs.mDexoptEnabled(this)
        ).count { it }
        return if (count > 0) "已开 $count 项" else "关闭"
    }

    private fun mPerformanceSchedulingState(): String {
        val enabled = mutableListOf<String>()
        if (MaxHzPrefs.gameAutoPerformanceEnabled(this)) enabled += "游戏自动"
        if (MaxHzPrefs.mCpuResponseEnabled(this)) enabled += "增强处理"
        if (MaxHzPrefs.mTombstoneEnabled(this)) enabled += "墓碑"
        if (MaxHzPrefs.mLocationThrottleEnabled(this)) enabled += "定位"
        if (MaxHzPrefs.mAutostartLimitEnabled(this)) enabled += "自启动"
        if (MaxHzPrefs.mRefresh120Enabled(this)) enabled += "120Hz"
        return when {
            enabled.isEmpty() -> "关闭"
            enabled.size <= 2 -> enabled.joinToString(" + ")
            else -> "已开 ${enabled.size} 项"
        }
    }

    private fun gameAutoPerformanceState(): String {
        if (!MaxHzPrefs.gameAutoPerformanceEnabled(this)) return "关闭"
        val count = MaxHzPrefs.gameAutoPerformancePackages(this).size
        if (count == 0) return "未选游戏"
        if (!MaxHzAccessibilityService.isEnabled(this)) return "需无障碍"
        return if (MaxHzPrefs.gameAutoSessionActive(this)) "游戏中" else "已开启 · $count 个"
    }

    private fun mMemoryCompileState(): String {
        return if (MaxHzPrefs.mDexoptEnabled(this)) "已编译" else "关闭"
    }

    private fun currentInteractionRisk(): RiskLevel {
        val mAdvancedMedium = MaxHzPrefs.mCpuResponseEnabled(this) ||
            MaxHzPrefs.mTombstoneEnabled(this) ||
            MaxHzPrefs.mLocationThrottleEnabled(this) ||
            MaxHzPrefs.mAutostartLimitEnabled(this) ||
            MaxHzPrefs.mDexoptEnabled(this)
        return when {
            MaxHzPrefs.mCpuResponseEnabled(this) && MaxHzPrefs.compatibleSaverEnabled(this) -> RiskLevel.HIGH
            MaxHzPrefs.mRefresh120Enabled(this) -> RiskLevel.MEDIUM
            mAdvancedMedium || MaxHzPrefs.nightWakeupGuardPackages(this).isNotEmpty() -> RiskLevel.MEDIUM
            MaxHzPrefs.compatibleSaverEnabled(this) ||
                MaxHzPrefs.scanGuardEnabled(this) ||
                MaxHzPrefs.batteryProtectionEnabled(this) ||
                MaxHzPrefs.appRefreshRules(this).isNotEmpty() -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    private fun buildInteractionAuditText(): String {
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()

        if (MaxHzPrefs.mCpuResponseEnabled(this) && MaxHzPrefs.compatibleSaverEnabled(this)) {
            warnings += "三星增强处理和日用省电同时处于开启状态，建议只保留一个。"
        }
        if (MaxHzPrefs.mCpuResponseEnabled(this)) {
            warnings += "三星增强处理（CPU 瞬时响应）已开启，提升跟手度。"
        }
        if (MaxHzPrefs.mTombstoneEnabled(this) || MaxHzPrefs.mAutostartLimitEnabled(this)) {
            warnings += "进程休眠墓碑或自启动限制已开启，后台缓存进程将被冻结以降低功耗。"
        }
        if (MaxHzPrefs.mLocationThrottleEnabled(this)) {
            warnings += "后台高频定位限制已开启，改善待机偷电与发热。"
        }
        if (MaxHzPrefs.mDexoptEnabled(this)) {
            warnings += "AOT 编译优化已执行。"
        }
        if (MaxHzPrefs.mRefresh120Enabled(this) && MaxHzPrefs.appRefreshRules(this).isNotEmpty()) {
            warnings += "120Hz 锁定和每 App 刷新率同时存在，建议只保留一种。"
        }
        if (MaxHzPrefs.nightWakeupGuardPackages(this).isNotEmpty()) {
            suggestions += "待机护栏只建议加入明确高唤醒应用，不建议加入音乐、地图、电话、通讯核心。"
        }
        if (MaxHzPrefs.scanGuardEnabled(this)) {
            suggestions += "同步/扫描护栏适合压后台扫描，但如果云同步异常，可单独恢复。"
        }
        if (MaxHzPrefs.appRefreshRules(this).isNotEmpty()) {
            suggestions += "每 App 刷新率已启用，离开目标 App 后恢复系统自适应。"
        }
        if (MaxHzPrefs.passThroughEnabled(this)) {
            suggestions += "全局旁路供电已开启，系统直接由充电器供电。"
        }
        if (warnings.isEmpty() && suggestions.isEmpty()) {
            return "检查结果\n未发现冲突。\n建议：日常使用保持当前配置或根据需要开启调优项目。"
        }
        return buildString {
            append("检查结果\n")
            if (warnings.isNotEmpty()) {
                append("状态项：\n")
                warnings.forEachIndexed { index, item -> append(index + 1).append(". ").append(item).append('\n') }
            }
            if (suggestions.isNotEmpty()) {
                append("建议：\n")
                suggestions.forEachIndexed { index, item -> append(index + 1).append(". ").append(item).append('\n') }
            }
        }.trimEnd()
    }

    private fun sectionLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColors.onSurface)
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun classicOverviewSection(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MaxHzActivity).apply {
                text = "详细运行状态只读取本 App 记录和当前可检测状态，不执行任何系统操作。"
                textSize = 12.5f
                setTextColor(themeColors.onSurfaceVariant)
                setPadding(dp(16), dp(2), dp(16), dp(12))
                setLineSpacing(0f, 1.25f)
            })
            addView(card {
                addView(sectionLabel("运行状态"))
                dashboardItems().forEach { item ->
                    addView(dashboardRow(item.title, item.state))
                    item.detail.takeIf { it.isNotBlank() }?.let { detail ->
                        addView(TextView(this@MaxHzActivity).apply {
                            text = detail
                            textSize = 11.5f
                            setTextColor(themeColors.onSurfaceVariant)
                            setPadding(0, 0, 0, dp(4))
                        })
                    }
                }
            })
            addSpacedView(card {
                addView(sectionLabel("策略总览"))
                addView(dashboardRow("主模式", currentMainModeLabel()))
                addView(dashboardRow("刷新率", currentRefreshPolicyLabel()))
                addView(dashboardRow("待机后台", currentStandbyPolicyLabel()))
                addView(dashboardRow("电池保护", currentBatteryPolicyLabel()))
                addView(dashboardRow("系统调优", currentMAdvancedLabel()))
            }, gap)
            addSpacedView(card {
                addView(sectionLabel("冲突检查"))
                addView(TextView(this@MaxHzActivity).apply {
                    text = buildInteractionAuditText()
                    textSize = 12.5f
                    setTextColor(themeColors.onSurface)
                    setLineSpacing(0f, 1.22f)
                })
        }, gap)
    }
    }

    protected fun gameAutoPerformanceCard(): View {
        val packages = MaxHzPrefs.gameAutoPerformancePackages(this)
            .sortedBy { appLabelForPackage(it).lowercase(Locale.getDefault()) }
        val exitDelaySeconds = MaxHzPrefs.gameAutoPerformanceExitDelaySeconds(this)
        val isServiceEnabled = MaxHzPrefs.gameAutoPerformanceEnabled(this@MaxHzActivity)
        val isAccessibilityReady = MaxHzAccessibilityService.isEnabled(this@MaxHzActivity)
        val currentTarget = MaxHzPrefs.gameAutoTargetProfile(this@MaxHzActivity)
        val targetLabel = when (currentTarget) {
            MaxHzPrefs.GAME_PROFILE_DEFAULT -> "系统默认"
            MaxHzPrefs.GAME_PROFILE_SAVER -> "省电自适应高刷"
            else -> "游戏性能模式"
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // 卡片 1：游戏模式总控
            addView(card {
                val heroSub = TextView(this@MaxHzActivity).apply {
                    text = "识别前台游戏自动生效极致性能，离开即刻还原"
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setPadding(0, dp(3), 0, 0)
                }
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val icon = featureIconContainer("游戏自动切换")
                    addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        rightMargin = dp(14)
                    })
                    addView(LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@MaxHzActivity).apply {
                            text = "游戏模式自动切换"
                            textSize = 16.5f
                            setTextColor(themeColors.onSurface)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            includeFontPadding = false
                        })
                        addView(heroSub)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(statusBadge(gameAutoPerformanceState()))
                })

                addSpacedView(switchRow(
                    "启用游戏自动切换",
                    isServiceEnabled
                ) { enabled ->
                    if (enabled) {
                        if (!MaxHzShell.isReady()) {
                            Toast.makeText(this@MaxHzActivity, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
                            renderDetail()
                            return@switchRow
                        }
                        if (!isAccessibilityReady) {
                            Toast.makeText(this@MaxHzActivity, "无障碍服务尚未开启，请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                            AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity)
                            renderDetail()
                            return@switchRow
                        }
                    }
                    MaxHzPrefs.setGameAutoPerformanceEnabled(this@MaxHzActivity, enabled)
                    updateMaxHzServiceState()
                    if (enabled) {
                        Toast.makeText(this@MaxHzActivity, "游戏自动切换已开启", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MaxHzActivity, "游戏自动切换已关闭", Toast.LENGTH_SHORT).show()
                    }
                    renderDetail()
                }, dp(12))

                if (!isAccessibilityReady) {
                    addSpacedView(TextView(this@MaxHzActivity).apply {
                        text = "无障碍即时切换服务未开启，无法识别前台游戏。\n点击此处立即前往开启无障碍服务"
                        textSize = 12.5f
                        setTextColor(themeColors.onWarningContainer)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        setLineSpacing(0f, 1.25f)
                        background = rounded(themeColors.warningContainer, dp(14).toFloat())
                        setOnClickListener {
                            AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity)
                        }
                    }, dp(8))
                }

                val strategySummarySub = TextView(this@MaxHzActivity).apply {
                    val customCount = packages.count {
                        MaxHzPrefs.gamePackageCustomProfile(this@MaxHzActivity, it) != null
                    }
                    val customHint = if (customCount > 0) "（已为 ${customCount} 款单独指定）" else ""
                    text = "当前策略：默认切「$targetLabel」$customHint · 离开缓冲 ${exitDelaySeconds}s · 名单 ${packages.size} 款"
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                }
                addSpacedView(strategySummarySub, dp(8))
                bindOptionBSubtitle(this, heroSub, strategySummarySub)
            })

            // 卡片 2：目标模式与温控策略
            addSpacedView(card {
                addView(TextView(this@MaxHzActivity).apply {
                    text = "默认进游戏目标模式"
                    textSize = 15f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                })
                val targetDescSub = TextView(this@MaxHzActivity).apply {
                    text = "作为未单独指定游戏的默认档位；下方游戏名单支持为每款游戏分别指定「系统默认」或「性能模式」。"
                    textSize = 12f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setLineSpacing(0f, 1.25f)
                }
                addSpacedView(targetDescSub, dp(4))
                bindOptionBSubtitle(this, targetDescSub)
                addSpacedView(horizontalActions(
                    button(if (currentTarget == MaxHzPrefs.GAME_PROFILE_DEFAULT) "系统默认 ✓" else "系统默认") {
                        if (!MaxHzShell.isReady()) {
                            Toast.makeText(this@MaxHzActivity, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
                            return@button
                        }
                        MaxHzPrefs.setGameAutoTargetProfile(this@MaxHzActivity, MaxHzPrefs.GAME_PROFILE_DEFAULT)
                        Toast.makeText(this@MaxHzActivity, "默认进游戏模式已设为：系统默认", Toast.LENGTH_SHORT).show()
                        renderDetail()
                    },
                    button(if (currentTarget == MaxHzPrefs.GAME_PROFILE_SAVER) "省电自适应高刷 ✓" else "省电自适应高刷") {
                        if (!MaxHzShell.isReady()) {
                            Toast.makeText(this@MaxHzActivity, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
                            return@button
                        }
                        MaxHzPrefs.setGameAutoTargetProfile(this@MaxHzActivity, MaxHzPrefs.GAME_PROFILE_SAVER)
                        Toast.makeText(this@MaxHzActivity, "默认进游戏模式已设为：省电自适应高刷", Toast.LENGTH_SHORT).show()
                        renderDetail()
                    },
                    button(if (currentTarget == MaxHzPrefs.GAME_PROFILE_PERFORMANCE) "游戏性能模式 ✓" else "游戏性能模式") {
                        if (!MaxHzShell.isReady()) {
                            Toast.makeText(this@MaxHzActivity, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
                            return@button
                        }
                        MaxHzPrefs.setGameAutoTargetProfile(this@MaxHzActivity, MaxHzPrefs.GAME_PROFILE_PERFORMANCE)
                        Toast.makeText(this@MaxHzActivity, "默认进游戏模式已设为：游戏性能模式", Toast.LENGTH_SHORT).show()
                        renderDetail()
                    }
                ), dp(6))

                addSpacedView(TextView(this@MaxHzActivity).apply {
                    text = "切到后台保持时间"
                    textSize = 15f
                    setTextColor(themeColors.onSurface)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                }, dp(8))
                addSpacedView(horizontalActions(
                    button(if (exitDelaySeconds == 30) "30 秒 ✓" else "30 秒") {
                        MaxHzPrefs.setGameAutoPerformanceExitDelaySeconds(this@MaxHzActivity, 30)
                        Toast.makeText(this@MaxHzActivity, "切到后台保持时间已设为 30 秒", Toast.LENGTH_SHORT).show()
                        renderDetail()
                    },
                    button(if (exitDelaySeconds == 60) "60 秒 (推荐) ✓" else "60 秒 (推荐)") {
                        MaxHzPrefs.setGameAutoPerformanceExitDelaySeconds(this@MaxHzActivity, 60)
                        Toast.makeText(this@MaxHzActivity, "切到后台保持时间已设为 60 秒", Toast.LENGTH_SHORT).show()
                        renderDetail()
                    },
                    button(if (exitDelaySeconds == 120) "120 秒 ✓" else "120 秒") {
                        MaxHzPrefs.setGameAutoPerformanceExitDelaySeconds(this@MaxHzActivity, 120)
                        Toast.makeText(this@MaxHzActivity, "切到后台保持时间已设为 120 秒", Toast.LENGTH_SHORT).show()
                        renderDetail()
                    }
                ), dp(6))
            }, gap)

            // 卡片 3：游戏名单管理
            addSpacedView(card {
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MaxHzActivity).apply {
                        text = "游戏名单 (${packages.size})"
                        textSize = 15f
                        setTextColor(themeColors.onSurface)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(createOptionBTogglePill())
                })

                val actionButtons = mutableListOf<View>()
                actionButtons.add(button("添加游戏") {
                    if (!MaxHzShell.isReady()) {
                        Toast.makeText(this@MaxHzActivity, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
                        return@button
                    }
                    if (!isAccessibilityReady) {
                        Toast.makeText(this@MaxHzActivity, "无障碍服务尚未开启，请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                        AccessibilityHelper.openInstantSwitchSettings(this@MaxHzActivity)
                        return@button
                    }
                    showGameAutoAppPicker { app ->
                        MaxHzPrefs.addGameAutoPerformancePackage(this@MaxHzActivity, app.packageName)
                        Toast.makeText(this@MaxHzActivity, "已加入 ${app.label}（点击右侧模式按钮可单独切换默认/性能）", Toast.LENGTH_SHORT).show()
                        renderDetail()
                    }
                })
                actionButtons.add(button("添加到快捷栏") {
                    if (!MaxHzShell.isReady()) {
                        Toast.makeText(this@MaxHzActivity, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
                        return@button
                    }
                    requestGameModeTile()
                })
                if (packages.isNotEmpty()) {
                    actionButtons.add(button("清空名单") {
                        MaterialAlertDialogBuilder(this@MaxHzActivity)
                            .setTitle("清空游戏名单")
                            .setMessage("确定要清空已添加的全部游戏吗？\n清空后将仅由系统自动识别主流游戏。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("清空") { _, _ ->
                                MaxHzPrefs.setGameAutoPerformancePackages(this@MaxHzActivity, emptySet())
                                Toast.makeText(this@MaxHzActivity, "游戏名单已清空", Toast.LENGTH_SHORT).show()
                                renderDetail()
                            }
                            .showUnified()
                    })
                }
                addSpacedView(horizontalActions(*actionButtons.toTypedArray()), dp(6))

                addSpacedView(gameAutoPackageList(packages), dp(8))
            }, gap)

            // 卡片 4：运行机制与特性说明
            addSpacedView(card {
                val mechSub = TextView(this@MaxHzActivity).apply {
                    text = "• 零后台轮询：仅在前台应用切换事件到达时瞬间触发，平时完全休眠，不耗待机电量。\n• 按游戏独立分档：日常开「省电自适应」时，可将轻度游戏设为「系统默认」（仅解除省电限制不发热），重度游戏设为「性能模式」。\n• 状态完整还原：退出游戏或超过保持时间后，100% 恢复到进游戏前的日常模式与设置，不留耗电尾巴。"
                    textSize = 12.5f
                    setTextColor(themeColors.onSurfaceVariant)
                    setLineSpacing(0f, 1.35f)
                    includeFontPadding = false
                }
                addView(LinearLayout(this@MaxHzActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MaxHzActivity).apply {
                        text = "运行机制与节能优势"
                        textSize = 15f
                        setTextColor(themeColors.onSurface)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(createOptionBTogglePill { listOf(mechSub) })
                })
                addSpacedView(mechSub, dp(8))
                val runtimeResultView = resultText(GAME_AUTO_RUNTIME_RESULT_KEY)
                addSpacedView(runtimeResultView, dp(6))
                bindOptionBSubtitle(this, mechSub)
            }, gap)


        }
    }

    private fun gameAutoOptionGroup(
        title: String,
        options: List<Pair<String, String>>,
        selected: String,
        onSelected: (String) -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MaxHzActivity).apply {
                text = title
                textSize = 13.5f
                setTextColor(themeColors.onSurface)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
            })
            addSpacedView(horizontalActions(*options.map { (value, label) ->
                button(if (value == selected) "$label ✓" else label) { onSelected(value) }
            }.toTypedArray()), dp(8))
        }
    }

    private fun gameAutoPackageList(packages: List<String>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (packages.isEmpty()) {
                addView(TextView(this@MaxHzActivity).apply {
                    text = "暂无手动指定的游戏。系统会自动识别已安装的主流游戏；您也可以随时点击上方【添加游戏】自由添加任意游戏或应用。"
                    textSize = 13f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setLineSpacing(0f, 1.3f)
                })
            } else {
                val tipSub = TextView(this@MaxHzActivity).apply {
                    text = "点击每款游戏右侧的模式胶囊，可单独指定该游戏进前台时切「系统默认」或「性能模式」。"
                    textSize = 12f
                    setTextColor(themeColors.onSurfaceVariant)
                    includeFontPadding = false
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, 0, 0, dp(6))
                }
                addView(tipSub)
                bindOptionBSubtitle(tipSub, tipSub)
                packages.forEachIndexed { index, packageName ->
                    val info = loadAppDisplayInfo(packageName)
                    val customProfile = MaxHzPrefs.gamePackageCustomProfile(this@MaxHzActivity, packageName)
                    val effectiveProfile = MaxHzPrefs.gamePackageTargetProfile(this@MaxHzActivity, packageName)
                    val modePillText = when (effectiveProfile) {
                        MaxHzPrefs.GAME_PROFILE_DEFAULT -> "系统默认 ▾"
                        MaxHzPrefs.GAME_PROFILE_SAVER -> "省电高刷 ▾"
                        else -> "性能模式 ▾"
                    }
                    val (pillBg, pillFg) = when (effectiveProfile) {
                        MaxHzPrefs.GAME_PROFILE_DEFAULT -> themeColors.secondaryContainer to themeColors.onSecondaryContainer
                        MaxHzPrefs.GAME_PROFILE_SAVER -> themeColors.tertiaryContainer to themeColors.onTertiaryContainer
                        else -> themeColors.primaryContainer to themeColors.onPrimaryContainer
                    }
                    val subtitlePrefix = if (customProfile != null) "专属档位" else "跟随默认"
                    val row = LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, dp(8))
                    }
                    val iconView = ImageView(this@MaxHzActivity).apply {
                        val iconDrawable = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
                        if (iconDrawable != null) setImageDrawable(iconDrawable)
                        else setImageResource(R.drawable.ic_maxhz_apps_24)
                    }
                    row.addView(iconView, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })
                    val pkgSubView = TextView(this@MaxHzActivity).apply {
                        text = "$subtitlePrefix · $packageName"
                        textSize = 11.5f
                        setTextColor(themeColors.onSurfaceVariant)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    }
                    val textCol = LinearLayout(this@MaxHzActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setOnClickListener {
                            showGamePackageProfileDialog(packageName, info.label)
                        }
                        addView(TextView(this@MaxHzActivity).apply {
                            text = info.label
                            textSize = 13.5f
                            setTextColor(themeColors.onSurface)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            includeFontPadding = false
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        addView(pkgSubView)
                    }
                    row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    bindOptionBSubtitle(textCol, pkgSubView)
                    val modePill = TextView(this@MaxHzActivity).apply {
                        text = modePillText
                        textSize = 12f
                        setTextColor(pillFg)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setPadding(dp(10), dp(7), dp(10), dp(7))
                        background = rounded(pillBg, dp(10).toFloat())
                        setOnClickListener {
                            showGamePackageProfileDialog(packageName, info.label)
                        }
                    }
                    row.addView(modePill, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = dp(6)
                    })
                    row.addView(button("删除") {
                        MaxHzPrefs.removeGameAutoPerformancePackage(this@MaxHzActivity, packageName)
                        Toast.makeText(this@MaxHzActivity, "已移除 ${info.label}", Toast.LENGTH_SHORT).show()
                        renderDetail()
                    }, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(row)
                    if (index < packages.lastIndex) {
                        addView(divider())
                    }
                }
            }
        }
    }

    private fun showGamePackageProfileDialog(packageName: String, appLabel: String) {
        val globalTarget = MaxHzPrefs.gameAutoTargetProfile(this)
        val globalLabel = when (globalTarget) {
            MaxHzPrefs.GAME_PROFILE_DEFAULT -> "系统默认"
            MaxHzPrefs.GAME_PROFILE_SAVER -> "省电自适应高刷"
            else -> "游戏性能模式"
        }
        val currentCustom = MaxHzPrefs.gamePackageCustomProfile(this, packageName)
        val optionValues = listOf<String?>(
            null,
            MaxHzPrefs.GAME_PROFILE_DEFAULT,
            MaxHzPrefs.GAME_PROFILE_PERFORMANCE,
            MaxHzPrefs.GAME_PROFILE_SAVER
        )
        val optionLabels = arrayOf(
            "跟随全局默认（当前：$globalLabel）",
            "系统默认（解除省电降频，标准高刷不发热）",
            "游戏性能模式（满血 CPU/GPU 与触控加速）",
            "省电自适应高刷（维持省电与自适应高刷）"
        )
        val checkedIndex = optionValues.indexOf(currentCustom).coerceAtLeast(0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("$appLabel · 进游戏模式")
            .setSingleChoiceItems(optionLabels, checkedIndex) { dialog, which ->
                val selectedProfile = optionValues.getOrNull(which)
                MaxHzPrefs.setGamePackageCustomProfile(this, packageName, selectedProfile)
                val effectiveLabel = when (selectedProfile ?: globalTarget) {
                    MaxHzPrefs.GAME_PROFILE_DEFAULT -> "系统默认"
                    MaxHzPrefs.GAME_PROFILE_SAVER -> "省电自适应高刷"
                    else -> "游戏性能模式"
                }
                val prefix = if (selectedProfile == null) "已设为跟随默认" else "已单独设为"
                Toast.makeText(
                    this,
                    "$appLabel $prefix：$effectiveLabel",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                renderDetail()
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        val isDark = isDarkMode()
        dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
        applyUnifiedDialogWindowStyle(dialog)
    }

    private fun dashboardRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@MaxHzActivity).apply {
                text = label
                textSize = 12f
                setTextColor(themeColors.onSurfaceVariant)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
            addView(TextView(this@MaxHzActivity).apply {
                text = value
                textSize = 12.5f
                gravity = Gravity.END
                setTextColor(themeColors.onSurface)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
        }
    }

    private fun showFeatureHelp(title: String, subtitle: String, safety: FeatureSafety?) {
        val message = buildString {
            append(subtitle)
            append("\n\n说明：").append(safety?.note ?: "这个功能属于辅助工具，不直接参与核心省电/刷新率策略。")
            append("\n\n建议：执行前先看当前状态；如需还原，可直接使用本功能的开关或总恢复。")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .showUnified()
    }

    private fun dailySaverConflictConfirmation(): ConfirmationPrompt? {
        val mRefresh120 = MaxHzPrefs.mRefresh120Enabled(this)
        if (!mRefresh120) return null
        return ConfirmationPrompt(
            title = "切换到日用省电模式？",
            message = "当前已开启 120Hz 锁定。\n\n继续后会清理明显冲突项，再开启日用省电，避免刷新率和省电策略叠加。"
        )
    }

    private fun withSuccessfulState(result: MaxHzShell.Result, update: () -> Unit): MaxHzShell.Result {
        if (result.code == 0) update()
        return result
    }

    private fun appRefreshState(): String {
        val count = MaxHzPrefs.appRefreshRules(this).size
        if (count > 0 && !MaxHzAccessibilityService.isEnabled(this)) return "未启用 · 需无障碍"
        return if (count > 0) "已配置 ${count} 个" else "关闭"
    }

    private fun appRefreshInstantState(): String {
        return if (MaxHzAccessibilityService.isEnabled(this)) "即时切换开" else "即时切换关"
    }

    private fun nightWakeupGuardState(): String {
        val enabled = MaxHzPrefs.nightWakeupGuardEnabled(this)
        val count = MaxHzPrefs.nightWakeupGuardPackages(this).size
        return when {
            !enabled -> "未激活"
            count == 0 -> "待添加名单"
            else -> "已开启 · $count 个"
        }
    }

    protected fun LinearLayout.addSpacedView(view: View, topMargin: Int) {
        addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin })
    }

    private fun startMaxHzService() {
        startService(Intent(this, MaxHzService::class.java))
    }

    protected fun updateMaxHzServiceState() {
        if (MaxHzPrefs.requiresBackgroundService(this)) {
            startMaxHzService()
        } else {
            stopService(Intent(this, MaxHzService::class.java))
        }
        // Compose 页的状态文本 / 开关状态在 tick 变化时重读
        MaxHzComposeRefreshBus.bump()
    }

    protected fun startFpsMonitorResult(): MaxHzShell.Result {
        if (!MaxHzShell.isReady()) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return MaxHzShell.Result(-1, "", getString(R.string.toast_service_not_running_start_first))
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限尚未授予，请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            return MaxHzShell.Result(-1, "已打开悬浮窗权限页面", "请先允许“显示在其他应用上层”，回来后再点开启。")
        }
        startService(Intent(this, MaxHzFpsMonitorService::class.java))
        Thread.sleep(300)
        return if (MaxHzPrefs.fpsMonitorRunning(this)) {
            MaxHzShell.Result(0, "实时 FPS 监视器已确认运行。双击悬浮窗也可以关闭，拖动可移动位置。", "")
        } else {
            MaxHzShell.Result(0, "已请求开启实时 FPS 监视器，但尚未确认悬浮窗运行。请确认悬浮窗权限、Shizuku 状态，或返回页面刷新状态。", "")
        }
    }

    protected fun stopFpsMonitorResult(): MaxHzShell.Result {
        stopService(Intent(this, MaxHzFpsMonitorService::class.java))
        MaxHzPrefs.setFpsMonitorRunning(this, false)
        return MaxHzShell.Result(0, "实时 FPS 监视器已关闭。", "")
    }

    private fun statusTextLater(target: TextView, text: String) {
        if (lastResultView != null && lastResultView !== target) {
            lastResultView?.let { collapseResultText(it) }
        }
        showResultText(target, text)
        lastResultView = target
    }

    private fun statusTextLater(text: String) {
        lastResultView?.let {
            statusTextLater(it, text)
        } ?: Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun showCommandResult(
        target: TextView,
        label: String,
        explanation: String,
        result: MaxHzShell.Result,
        elapsedMs: Long? = null
    ) {
        statusTextLater(target, commandResultText(label, explanation, CommandOutcome(result, "", elapsedMs ?: 0L)))
    }

    protected fun actionBarInset(): Int {
        val value = TypedValue()
        val actionBarHeight = if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, value, true)) {
            TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
        } else {
            0
        }
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else 0
        return actionBarHeight + statusBarHeight
    }

    private fun runCommand(
        target: TextView,
        label: String,
        explanation: String,
        onFinished: (() -> Unit)? = null,
        onResult: ((MaxHzShell.Result) -> Unit)? = null,
        refreshServiceAfter: Boolean = true,
        captureSafetySnapshot: Boolean = false,
        block: () -> MaxHzShell.Result
    ) {
        if (lastResultView != null && lastResultView !== target) {
            lastResultView?.let { collapseResultText(it) }
        }
        showResultText(target, "正在处理…")
        lastResultView = target
        setCommandButtonsEnabled(false)
        lifecycleScope.launch {
            var commandFinished = false
            val slowNotice = launch {
                delay(SLOW_COMMAND_NOTICE_MS)
                if (!commandFinished) {
                    showResultText(target, "正在处理，请稍候…")
                }
            }
            try {
                val shouldCaptureSnapshot = captureSafetySnapshot || shouldCaptureSafetySnapshot(label)
                val outcome = withContext(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    val before = if (shouldCaptureSnapshot) {
                        runCatching { MaxHzController.readSafetySnapshot() }.getOrNull()
                    } else {
                        null
                    }
                    val result = try {
                        block()
                    } catch (t: Throwable) {
                        MaxHzShell.Result(-2, "", t.message ?: t.javaClass.simpleName)
                    }
                    val after = if (shouldCaptureSnapshot) {
                        runCatching { MaxHzController.readSafetySnapshot() }.getOrNull()
                    } else {
                        null
                    }
                    val changes = if (shouldCaptureSnapshot) buildSafetyChanges(before, after) else ""
                    CommandOutcome(result, changes, System.currentTimeMillis() - startedAt)
                }
                commandFinished = true
                slowNotice.cancel()
                val result = outcome.result
                showResultText(target, commandResultText(label, explanation, outcome))
                recordCommandOperation(label, result)
                onResult?.invoke(result)
                onFinished?.invoke()
                if (refreshServiceAfter && result.code == 0) {
                    runCatching { updateMaxHzServiceState() }
                }
            } finally {
                commandFinished = true
                slowNotice.cancel()
                setCommandButtonsEnabled(true)
            }
        }
    }

    private fun setCommandButtonsEnabled(enabled: Boolean) {
        setCommandButtonsEnabled(pageContent, enabled)
    }

    private fun setCommandButtonsEnabled(view: View, enabled: Boolean) {
        if (view.tag == COMMAND_BUTTON_TAG) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.58f
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setCommandButtonsEnabled(view.getChildAt(index), enabled)
            }
        }
    }

    private fun formatElapsed(elapsedMs: Long): String {
        return if (elapsedMs < 1000) {
            "${elapsedMs}ms"
        } else {
            String.format(Locale.US, "%.1fs", elapsedMs / 1000f)
        }
    }

    private fun shouldCaptureSafetySnapshot(label: String): Boolean {
        return label.contains("残留") ||
            label.contains("系统诊断")
    }

    private fun runCommandMaybeConfirmed(
        target: TextView,
        label: String,
        explanation: String,
        confirmationProvider: (() -> ConfirmationPrompt?)?,
        onFinished: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null,
        block: () -> MaxHzShell.Result
    ) {
        val confirmation = confirmationProvider?.invoke()
        if (confirmation == null) {
            runCommand(target, label, explanation, onFinished = onFinished, block = block)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(confirmation.title)
            .setMessage(confirmation.message)
            .setNegativeButton(confirmation.negativeText) { _, _ ->
                onCancelled?.invoke()
            }
            .setOnCancelListener {
                onCancelled?.invoke()
            }
            .setPositiveButton(confirmation.positiveText) { _, _ ->
                runCommand(target, label, explanation, onFinished = onFinished, block = block)
            }
            .showUnified()
    }

    private fun formatCommandMessage(text: String): String {
        val rawSettingLine = Regex("^[A-Za-z0-9_.-]+=[^=].*")
        val visible = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("MAXHZ_RESULT_LEVEL=") || it.startsWith("MAXHZ_STATE_") }
            .filterNot { line -> isRawCommandOutput(line, rawSettingLine) }
            .map { replacePackageNamesForUser(it) }
            .toList()
        summarizeKnownOutput(visible)?.let { return it }
        return visible.joinToString("\n").ifBlank { "没有返回需要展示的详细信息。" }
    }

    private fun replacePackageNamesForUser(line: String): String {
        val packagePattern = Regex("\\b[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+\\b")
        return packagePattern.replace(line) { match ->
            val packageName = match.value
            if (!packageNameLooksLikeAndroidPackage(packageName)) return@replace packageName
            val label = appLabelForPackage(packageName)
            if (label == packageName) "未知应用" else label
        }
    }

    private fun packageNameLooksLikeAndroidPackage(value: String): Boolean {
        if (!value.contains(".")) return false
        if (value.startsWith("android.", ignoreCase = true)) return false
        val first = value.substringBefore('.')
        return first in setOf("com", "org", "net", "me", "io", "tv", "cn", "jp", "kr", "de", "ru", "au", "uk", "moe", "app")
    }

    private fun isRawCommandOutput(line: String, rawSettingLine: Regex): Boolean {
        if (line.startsWith("候选|")) return true
        if (line.startsWith("adb shell")) return true
        if (line.startsWith("settings ")) return true
        if (line.startsWith("cmd ")) return true
        if (line.startsWith("dumpsys ")) return true
        if (line.startsWith("content call ")) return true
        if (line.startsWith("java.") || line.startsWith("at ")) return true
        if (rawSettingLine.matches(line)) {
            return !line.startsWith("型号=") &&
                !line.startsWith("厂商=") &&
                !line.startsWith("Android版本=") &&
                !line.startsWith("SDK版本=") &&
                !line.startsWith("支持刷新率=") &&
                !line.startsWith("推荐亮屏范围=")
        }
        return false
    }

    private fun summarizeKnownOutput(lines: List<String>): String? {
        val compileLines = lines.filter { it.startsWith("编译 ") }
        if (compileLines.isNotEmpty()) {
            val success = compileLines.count { it.contains("Success", ignoreCase = true) }
            val failed = compileLines.size - success
            return buildString {
                append("编译任务结果：通过 ").append(success).append(" 个")
                if (failed > 0) append("，未通过/跳过 ").append(failed).append(" 个")
                append("。")
            }
        }
        return null
    }

    private fun buildSafetyChanges(
        beforeResult: MaxHzShell.Result?,
        afterResult: MaxHzShell.Result?
    ): String {
        if (beforeResult?.code != 0 || afterResult?.code != 0) return ""
        val before = parseSafetySnapshot(beforeResult.out)
        val after = parseSafetySnapshot(afterResult.out)
        if (before.isEmpty() || after.isEmpty()) return ""
        val changes = after.keys
            .plus(before.keys)
            .distinct()
            .mapNotNull { key ->
                val oldValue = before[key] ?: return@mapNotNull null
                val newValue = after[key] ?: return@mapNotNull null
                if (oldValue == newValue) null else snapshotLabel(key)
            }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (changes.isEmpty()) return ""
        val visible = changes.take(5)
        return if (changes.size > visible.size) {
            "已改变 ${changes.size} 项设置：${visible.joinToString("、")} 等。"
        } else {
            "已改变：${visible.joinToString("、")}。"
        }
    }

    private fun parseSafetySnapshot(text: String): Map<String, String> {
        return text.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) return@mapNotNull null
                line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
    }

    private fun snapshotLabel(key: String): String {
        return when (key) {
            "secure.refresh_rate_mode" -> "刷新模式"
            "secure.refresh_rate" -> "固定刷新率"
            "secure.user_refresh_rate" -> "用户刷新率"
            "system.min_refresh_rate" -> "最低刷新率"
            "system.peak_refresh_rate" -> "最高刷新率"
            "system.user_refresh_rate" -> "系统用户刷新率"
            "system.display_refresh_rate" -> "显示刷新率"
            "system.display_min_refresh_rate" -> "显示最低刷新率"
            "system.display_outdoor_mode" -> "户外模式"
            "global.low_power", "global.battery_saver_enabled" -> "系统省电"
            "global.low_power_mode", "global.low_power_sticky" -> "系统省电"
            "global.restricted_device_performance" -> "系统性能限制"
            "global.sem_low_heat_mode" -> "三星低热模式"
            "global.adaptive_power_saving_setting", "global.dynamic_power_savings_enabled" -> "自适应省电"
            "global.device_idle_enabled" -> "Doze 开关"
            "global.device_idle_constants" -> "Doze 参数"
            "global.battery_saver_constants" -> "省电常量"
            "global.master_sync_enabled" -> "账号同步"
            "global.ble_scan_always_enabled" -> "蓝牙后台扫描"
            "global.wifi_scan_always_enabled" -> "Wi-Fi 后台扫描"
            "global.nearby_scanning_enabled", "system.nearby_scanning_enabled" -> "附近设备扫描"
            "global.protect_battery", "system.protect_battery" -> "电池保护"
            "global.battery_protection_threshold", "system.battery_protection_threshold" -> "充电上限"
            "global.battery_protection_limit" -> "充电上限"
            "global.battery_protection_recharge_level", "system.battery_protection_recharge_level" -> "回充阈值"
            "global.recharge_level" -> "回充阈值"
            "system.pass_through", "global.pass_through_charging", "global.bypass_charging" -> "旁路充电"
            "secure.show_refresh_rate", "system.show_refresh_rate", "global.show_refresh_rate" -> "刷新率显示"
            "global.pms_settings_refresh_rate_enabled", "global.psm_refresh_rate_tag", "global.sem_power_mode_refresh_rate" -> "省电高刷策略"
            else -> "其他系统设置"
        }
    }

    private fun loadThemeColors(): ThemeColors {
        val blackTheme = isShizukuBlackTheme()
        val useSystem = ThemeHelper.isUsingSystemColor()
        if (isDarkMode()) {
            val pageColor = if (blackTheme) 0xFF000000.toInt()
                else if (useSystem) resolveColor(android.R.attr.colorBackground, 0xFF0D0F12.toInt())
                else 0xFF0D0F12.toInt()
            val shizukuCardColor = if (blackTheme) 0xFF000000.toInt()
                else if (useSystem) resolveColor(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF181A20.toInt())
                else 0xFF181A20.toInt()
            val maxSurfaceHigh = if (blackTheme) 0xFF101216.toInt()
                else if (useSystem) resolveColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF21242C.toInt())
                else 0xFF21242C.toInt()
            val primaryColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorPrimary, 0xFF277AF7.toInt())
                else 0xFF277AF7.toInt()
            val onSurfaceColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xFFECEFF4.toInt())
                else 0xFFECEFF4.toInt()
            val onSurfaceVariantColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF949BA8.toInt())
                else 0xFF949BA8.toInt()
            val outlineColor = if (blackTheme) 0x26FFFFFF
                else if (useSystem) resolveColor(com.google.android.material.R.attr.colorOutlineVariant, 0x1AFFFFFF)
                else 0x1AFFFFFF
            val primaryContainerColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF162946.toInt())
                else 0xFF162946.toInt()
            val onPrimaryContainerColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer, primaryColor)
                else 0xFF277AF7.toInt()

            return ThemeColors(
                page = pageColor,
                surface = shizukuCardColor,
                surfaceContainer = shizukuCardColor,
                surfaceContainerHigh = maxSurfaceHigh,
                outline = outlineColor,
                onSurface = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariantColor,
                primary = primaryColor,
                onPrimary = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt()) else 0xFFFFFFFF.toInt(),
                primaryContainer = primaryContainerColor,
                onPrimaryContainer = onPrimaryContainerColor,
                secondaryContainer = shizukuCardColor,
                onSecondaryContainer = onSurfaceColor,
                tertiaryContainer = shizukuCardColor,
                onTertiaryContainer = onSurfaceColor,
                errorContainer = if (useSystem) resolveColor(com.google.android.material.R.attr.colorErrorContainer, 0xFF5D2226.toInt()) else 0xFF5D2226.toInt(),
                onErrorContainer = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnErrorContainer, 0xFFFFDAD6.toInt()) else 0xFFFFDAD6.toInt(),
                warningContainer = maxSurfaceHigh,
                onWarningContainer = onSurfaceVariantColor,
                error = if (useSystem) resolveColor(com.google.android.material.R.attr.colorError, 0xFFFFB4AB.toInt()) else 0xFFFFB4AB.toInt()
            )
        }
        val pageColor = if (useSystem) resolveColor(android.R.attr.colorBackground, 0xFFF5F6F9.toInt()) else 0xFFF5F6F9.toInt()
        val shizukuCardColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorSurfaceContainer, 0xFFFFFFFF.toInt()) else 0xFFFFFFFF.toInt()
        val maxSurfaceHigh = if (useSystem) resolveColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFF0F2F6.toInt()) else 0xFFF0F2F6.toInt()
        val primaryColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorPrimary, 0xFF3482FF.toInt()) else 0xFF3482FF.toInt()
        val onSurfaceColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xFF1A1C1E.toInt()) else 0xFF1A1C1E.toInt()
        val onSurfaceVariantColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF6B7280.toInt()) else 0xFF6B7280.toInt()
        val outlineColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOutlineVariant, 0x0C000000) else 0x0C000000
        val primaryContainerColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFEBF3FF.toInt()) else 0xFFEBF3FF.toInt()
        val onPrimaryContainerColor = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer, primaryColor) else primaryColor

        return ThemeColors(
            page = pageColor,
            surface = shizukuCardColor,
            surfaceContainer = shizukuCardColor,
            surfaceContainerHigh = maxSurfaceHigh,
            outline = outlineColor,
            onSurface = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariantColor,
            primary = primaryColor,
            onPrimary = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt()) else 0xFFFFFFFF.toInt(),
            primaryContainer = primaryContainerColor,
            onPrimaryContainer = onPrimaryContainerColor,
            secondaryContainer = shizukuCardColor,
            onSecondaryContainer = onSurfaceColor,
            tertiaryContainer = shizukuCardColor,
            onTertiaryContainer = onSurfaceColor,
            errorContainer = if (useSystem) resolveColor(com.google.android.material.R.attr.colorErrorContainer, 0xFFFFECE8.toInt()) else 0xFFFFECE8.toInt(),
            onErrorContainer = if (useSystem) resolveColor(com.google.android.material.R.attr.colorOnErrorContainer, 0xFF9A2D2D.toInt()) else 0xFF9A2D2D.toInt(),
            warningContainer = maxSurfaceHigh,
            onWarningContainer = onSurfaceVariantColor,
            error = if (useSystem) resolveColor(com.google.android.material.R.attr.colorError, 0xFFA12A2A.toInt()) else 0xFFA12A2A.toInt()
        )
    }

    protected fun isShizukuBlackTheme(): Boolean {
        return isDarkMode() && ThemeHelper.isBlackNightTheme(this)
    }

    // ============ 背景样式（设置项：极光渐变 / 纯净浅色 / 纯净深色）============

    /** 当前背景样式是否为纯色（纯净浅色 / 纯净深色，不含自定义壁纸） */
    protected fun isFlatBackground(): Boolean {
        return when (ShizukuSettings.getBackgroundStyle()) {
            ShizukuSettings.BACKGROUND_STYLE_SOLID_LIGHT,
            ShizukuSettings.BACKGROUND_STYLE_SOLID_DARK -> true
            else -> false
        }
    }

    /**
     * 背景样式强制的明暗档位：
     * null = 极光（跟随「深色主题」设置），true/false = 纯净深色 / 纯净浅色。
     */
    protected fun forcedBackgroundDark(): Boolean? {
        return when (ShizukuSettings.getBackgroundStyle()) {
            ShizukuSettings.BACKGROUND_STYLE_SOLID_DARK -> true
            ShizukuSettings.BACKGROUND_STYLE_SOLID_LIGHT -> false
            else -> null
        }
    }

    /** 页面顶栏（状态栏 + 标题栏）应铺的底色：与内容区顶部严丝合缝 */
    protected fun topBarColor(): Int {
        return if (isFlatBackground()) {
            if (isDarkMode()) 0xff0A0E18.toInt() else 0xffF2F6FC.toInt()
        } else {
            if (isDarkMode()) 0xff070A12.toInt() else 0xffF8FBFE.toInt()
        }
    }

    /**
     * 顶部（状态栏 + 标题栏）原本是纯黑实底 + 白字，与下方页面背景完全割裂。
     * 现在改成：只要不是黑色夜间主题，顶栏一律透明，让内容区背景（壁纸 / 极光 /
     * 纯色）从 y=0 一路铺到状态栏，四种模式统一为「全屏背景」。
     *
     * 可行依据：`AppBarActivity.setContentView` 把内容视图 addView 到 rootView 的
     * **index 0、MATCH_PARENT**，再把 toolbar_container bringChildToFront —— 也就是
     * 内容区本来就铺满整屏（含状态栏），顶栏只是浮在上面。此前无壁纸时给顶栏铺了
     * `topBarColor()` 单色，而内容区顶部被极光光晕染成 (226,237,246)，
     * 于是出现「上白下浅蓝」的断层（实测 y=0~369 恒为 248,251,254）。
     *
     * 注意：不能用 window.setBackgroundDrawable —— rikka 的窗口背景管理器会拦截
     * 并抛 RuntimeException，从而中断 onCreate 导致首页 Compose 完全不渲染。
     * 因此改为直接给 statusBarColor 与 toolbar_container 上色（标准 View API）。
     */
    protected fun applyUnifiedTopBar() {
        val dark = isDarkMode()
        val blackTheme = isShizukuBlackTheme()

        // 黑色夜间主题：顶栏纯黑，与内容区纯黑背景统一
        if (blackTheme) {
            window.statusBarColor = Color.BLACK
            WindowCompat.getInsetsController(window, window.decorView)
                ?.isAppearanceLightStatusBars = false
            findViewById<View>(R.id.toolbar_container)?.let {
                it.setBackgroundColor(Color.BLACK)
                it.elevation = 0f
            }
            val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            toolbar?.setBackgroundColor(Color.BLACK)
            toolbar?.elevation = 0f
            toolbar?.setTitleTextColor(0xFFFFFFFF.toInt())
            // 黑色主题下背景是纯黑，标题不需要光晕即可读，保持字形 100% 清晰。
            toolbar?.let { applyTitleHalo(it, clear = true) }
            return
        }

        // ---- 除黑色夜间主题外，顶栏一律透明：壁纸 / 极光 / 纯色全部全屏贯通 ----
        val hasWallpaper = ShizukuSettings.getWallpaperUri() != null

        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView)
            ?.isAppearanceLightStatusBars = !dark

        val container = findViewById<View>(R.id.toolbar_container)
        container?.let {
            val topBarBg = if (hasWallpaper && dark) {
                makeTopScrim()
            } else if (hasWallpaper) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xCCF7F9FC.toInt())
                    setStroke(dp(1), 0x14000000.toInt())
                }
            } else {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (dark) 0xF4181C24.toInt() else 0xF6FFFFFF.toInt())
                    setStroke(dp(1), if (dark) 0x24FFFFFF.toInt() else 0x14000000.toInt())
                }
            }
            it.background = topBarBg
            it.elevation = 0f
            (it as? AppBarLayout)?.statusBarForeground = null
        }
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.background = null
        toolbar?.elevation = 0f
        toolbar?.popupTheme = if (dark) R.style.ThemeOverlay_App_PopupMenu_Dark else R.style.ThemeOverlay_App_PopupMenu
        // 标题文字跟随页面明暗（与正文 onSurface 同色），不再固定白色
        toolbar?.setTitleTextColor(if (dark) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt())
        // 反向光晕 radius 14~20f 会让标题出现明显 ghost/虚影；改为用顶部遮罩
        // 兜底可读性（深色+壁纸已有 makeTopScrim），标题字形保持清晰。
        toolbar?.let { applyTitleHalo(it, clear = true) }
    }

    /**
     * 顶部暗化渐变（仅深色 + 有壁纸时使用）。
     *
     * 从状态栏顶端的 54% 黑渐变到底部全透明：既压得住标题/状态栏图标，
     * 又让壁纸纹理透出来 —— 比整块纯色遮罩更接近「跟界面同步」。
     */
    private fun makeTopScrim(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            // 四段而非两段：toolbar_container 高 369px，标题文字落在 y=195~303，
            // 用「顶部→0」的两段渐变时衰减到标题处只剩 17% alpha，压不住底
            // （实测仅到 3.50:1）。四段让遮罩在标题区仍保持 ~40% 强度，
            // 到容器底边才归零，与下方内容区平滑衔接、不留分界线。
            intArrayOf(
                0x99000000.toInt(),
                0x8A000000.toInt(),
                0x6A000000.toInt(),
                0x00000000.toInt()
            )
        )

    /**
     * 清除 Toolbar 标题的反向光晕。
     *
     * 之前用 14~20f 阴影做壁纸兜底，但会造成明显 ghost/虚影；改为顶部遮罩
     * 保证可读性，标题字形保持 100% 清晰。
     */
    private fun applyTitleHalo(
        toolbar: androidx.appcompat.widget.Toolbar,
        @Suppress("UNUSED_PARAMETER") dark: Boolean = false,
        clear: Boolean = true
    ) {
        val titleText = toolbar.title?.toString() ?: return
        fun findTitle(v: View): android.widget.TextView? {
            if (v is android.widget.TextView && v.text?.toString() == titleText) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    findTitle(v.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        val tv = findTitle(toolbar) ?: return
        if (clear) {
            tv.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    private fun clearTopBarBackgrounds(root: View) {
        fun walk(v: View) {
            val name = v.javaClass.name
            if (name.contains("AppBar") || name.contains("Toolbar")) {
                v.background = null
                v.elevation = 0f
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        if (root is ViewGroup) walk(root)
    }

    // ============ 页面切换过渡 ============

    /** 切入页面：淡入，消除瞬切造成的「闪一下」 */
    protected fun fadeInPage(view: View, duration: Long = 190L) {
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().cancel()
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { view.alpha = 1f }
            .start()
        // 兜底：addView 后 View 尚未 attach 到 window，ViewPropertyAnimator 会静默
        // 不执行，导致 alpha 永久停在 0、整页透明。延后置 1 保证可见。
        view.postDelayed({ view.alpha = 1f }, duration + 80)
    }

    /** 切出页面：淡出后再 GONE，与淡入形成交叉过渡 */
    protected fun fadeOutPage(view: View, duration: Long = 130L) {
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.GONE
            return
        }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }
            .start()
        view.postDelayed({
            view.visibility = View.GONE
            view.alpha = 1f
        }, duration + 80)
    }

    /** 仅在页面之前不可见时才淡入，避免同页切换（如 MaxHz 三 tab 互切）重复闪一下 */
    protected fun showWithFadeIfHidden(view: View) {
        if (view.visibility == View.VISIBLE) return
        fadeInPage(view)
    }

    protected fun isDarkMode(): Boolean {
        // 背景样式选了「纯净浅色 / 纯净深色」时，明暗由它一锤定音
        forcedBackgroundDark()?.let { return it }
        // 直接读取设置 - 夜间模式偏好，而不是只信 resources.configuration.uiMode。
        // 原因：AppCompatDelegate.setDefaultNightMode 只对 AppCompatActivity
        // 链路生效。MaxHzActivity 的基类（rikka MaterialActivity）不一定走
        // AppCompatDelegate 的覆盖逻辑，因此系统主题（Activity theme）会跟着
        // values-night 走暗色资源，但 resources.configuration.uiMode 不一定被
        // 翻成 NIGHT_YES —— 这就导致 “页面背景已变深、文字色却仍按浅色取” 的
        // 混搭。直接读 ShizukuSettings 与“设置页的 3 个点 / 跟随系统”口径完全一致。
        return when (ShizukuSettings.getNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun riskTextColor(level: RiskLevel): Int {
        return when (level) {
            RiskLevel.LOW -> themeColors.onTertiaryContainer
            RiskLevel.MEDIUM -> themeColors.onSecondaryContainer
            RiskLevel.HIGH -> themeColors.onErrorContainer
        }
    }

    private fun riskBackgroundColor(level: RiskLevel): Int {
        return when (level) {
            RiskLevel.LOW -> themeColors.tertiaryContainer
            RiskLevel.MEDIUM -> themeColors.secondaryContainer
            RiskLevel.HIGH -> themeColors.errorContainer
        }
    }

    protected fun resolveColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
        } else fallback
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun outlinedRounded(color: Int, strokeColor: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), strokeColor)
        }
    }

    private fun View.applyCapsuleBackground(color: Int, strokeColor: Int? = null) {
        fun updateBackground() {
            val radius = ((height.takeIf { it > 0 } ?: dp(BUTTON_HEIGHT_DP)) / 2f)
            val shape = if (strokeColor == null) {
                rounded(color, radius)
            } else {
                outlinedRounded(color, strokeColor, radius)
            }
            background = rippleBackground(shape)
        }
        updateBackground()
        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.post { updateBackground() }
        }
    }

    private fun rippleColor(): Int {
        return if (isDarkMode()) 0x44ffffff else 0x22000000
    }

    private fun rippleBackground(content: Drawable): RippleDrawable {
        return RippleDrawable(ColorStateList.valueOf(rippleColor()), content, null)
    }

    private fun rippleForeground(): RippleDrawable {
        return RippleDrawable(
            ColorStateList.valueOf(rippleColor()),
            null,
            ColorDrawable(Color.WHITE)
        )
    }

    protected fun installClickFeedback(view: View, foregroundRipple: Boolean = true) {
        view.isClickable = true
        view.isFocusable = true
        if (foregroundRipple) {
            view.foreground = rippleForeground()
        } else {
            view.background = rippleBackground(view.background ?: ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun playClickFeedback(view: View, pressed: Boolean = false) {
        if (!pressed) return
        view.animate().cancel()
        view.animate()
            .alpha(0.82f)
            .scaleX(0.98f)
            .scaleY(0.98f)
            .setDuration(60L)
            .withEndAction {
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100L)
                    .start()
            }
            .start()
    }

    protected fun dp(value: Int): Int = (value * density).toInt()

    private fun collapseVisibleResultInside(view: View) {
        if (view is TextView && view === lastResultView) return
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is TextView && child === lastResultView && child.visibility == View.VISIBLE) {
                    collapseResultText(child)
                    return
                }
                collapseVisibleResultInside(child)
            }
        }
    }

    private fun showResultText(target: TextView, text: String) {
        val displayText = replacePackageNamesForUser(text)
        val key = (target.tag as? ResultTag)?.key ?: run {
            resultKeyCounter += 1
            "result_$resultKeyCounter"
        }
        target.tag = ResultTag(key, displayText)
        saveResultState(key, ResultState(displayText, collapsed = shouldCollapseResultByDefault(displayText)))
        lastResultView = target
        updateResultViews(key)
    }

    private fun collapseResultText(target: TextView) {
        val tag = target.tag as? ResultTag
        val key = tag?.key
        val fullText = resultStates[key]?.text ?: tag?.text ?: target.text?.toString().orEmpty()
        if (fullText.isBlank()) {
            target.visibility = View.GONE
            return
        }
        if (key == null) {
            target.text = collapsedResultSummary(fullText)
            target.visibility = View.VISIBLE
        } else {
            saveResultState(key, ResultState(fullText, collapsed = true))
            updateResultViews(key)
        }
    }

    private fun toggleResultText(target: TextView) {
        val key = (target.tag as? ResultTag)?.key ?: return
        val state = resultStates[key] ?: return
        saveResultState(key, state.copy(collapsed = !state.collapsed))
        updateResultViews(key)
    }

    private fun updateResultViews(key: String) {
        val state = resultStates[key]
        val resultView = resultViews[key]
        val badge = resultBadges[key]
        val container = resultContainers[key]
        if (state == null) {
            resultView?.visibility = View.GONE
            badge?.visibility = View.GONE
            container?.visibility = View.GONE
            return
        }
        container?.visibility = if (state.text.isBlank()) View.GONE else View.VISIBLE
        badge?.apply {
            text = if (state.collapsed) "查看详情" else "收起"
            visibility = View.VISIBLE
            setOnClickListener {
                playClickFeedback(this)
                saveResultState(key, state.copy(collapsed = !state.collapsed))
                updateResultViews(key)
            }
        }
        resultView?.apply {
            tag = ResultTag(key, state.text)
            text = if (state.collapsed) collapsedResultSummary(state.text) else state.text
            visibility = if (state.text.isBlank()) View.GONE else View.VISIBLE
            if (state.collapsed) {
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            } else {
                maxLines = Int.MAX_VALUE
                ellipsize = null
            }
            clearFocus()
        }
    }

    private fun shouldCollapseResultByDefault(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        val lineCount = clean.lineSequence().count { it.isNotBlank() }
        return lineCount > 3 ||
            clean.contains("功能原理：") ||
            clean.contains("关键变化：") ||
            clean.contains("运行情况：") ||
            clean.contains("错误信息：") ||
            clean.contains("高级详情：") ||
            clean.contains("技术详情：") ||
            clean.contains("Shell", ignoreCase = true)
    }

    private fun collapsedResultSummary(text: String): String {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return ""
        if (lines.first() == "最近一次操作结果") {
            return userFacingResultSummary(lines)
        }
        val summary = mutableListOf<String>()
        lines.firstOrNull()?.let { summary += friendlyLegacyResultLine(it) }
        lines.firstOrNull { it.startsWith("执行结果：") }?.let {
            summary += friendlyLegacyResultLine(it.removePrefix("执行结果："))
        }
        lines.firstOrNull { it.startsWith("错误信息：") }?.let {
            summary += "原因：${commandFailureReason(MaxHzShell.Result(-1, "", it))}"
        }
        lines.firstOrNull { it.startsWith("建议：") }?.let {
            normalizeNextStep(it.removePrefix("建议："))?.let { step -> summary += step }
        }
        if (summary.size == 1) {
            lines.drop(1).take(1).forEach { summary += friendlyLegacyResultLine(it) }
        }
        return summary.distinct().take(3).joinToString("\n")
    }

    private fun userFacingResultSummary(lines: List<String>): String {
        val visibleLines = lines.takeWhile { it != "技术详情：" }
        val bodyLines = visibleLines.dropWhile { it == "最近一次操作结果" }
        val summary = mutableListOf<String>()
        bodyLines.firstOrNull()?.let { summary += friendlyLegacyResultLine(it) }
        bodyLines.firstOrNull { it.startsWith("原因：") }?.let { summary += it }
        bodyLines.firstOrNull { it.startsWith("结果：") }?.let { summary += it }
        bodyLines.firstOrNull { it.startsWith("下一步建议：") || it.startsWith("建议：") }?.let {
            val rawStep = if (it.startsWith("建议：")) it.removePrefix("建议：") else it.removePrefix("下一步建议：")
            normalizeNextStep(rawStep)?.let { step -> summary += step }
        }
        if (summary.none { it.startsWith("下一步建议：") }) {
            val isFailed = bodyLines.any {
                it.contains("未完成") ||
                    it.contains("未应用") ||
                    it.contains("失败") ||
                    it.startsWith("原因：")
            }
            summary += if (isFailed) {
                "下一步建议：请稍后重试；如仍失败，请查看技术详情。"
            } else {
                "下一步建议：无需处理。"
            }
        }
        return summary.distinct().take(3).joinToString("\n")
    }

    private fun normalizeNextStep(raw: String): String? {
        val step = raw.trim()
        if (step.isBlank()) return null
        return "下一步建议：$step"
    }

    private fun friendlyLegacyResultLine(line: String): String {
        val clean = line.trim()
        return when {
            clean.startsWith("执行结果：") -> friendlyLegacyResultLine(clean.removePrefix("执行结果："))
            clean.startsWith("错误信息：") -> "原因：${commandFailureReason(MaxHzShell.Result(-1, "", clean))}"
            clean.contains("失败") || clean.contains("未通过") || clean.contains("未完成") -> "未完成"
            clean.contains("成功") || clean.contains("完成") || clean.contains("已执行") -> "已完成"
            else -> clean
        }
    }

    private fun collapseAllResults() {
        resultStates.toMap().forEach { (key, state) ->
            if (!state.collapsed) {
                saveResultState(key, state.copy(collapsed = true))
            }
        }
    }

    private fun loadResultState(key: String): ResultState? {
        resultStates[key]?.let { return it }
        val rawText = MaxHzPrefs.resultText(this, key)?.takeIf { it.isNotBlank() } ?: return null
        val text = replacePackageNamesForUser(sanitizePersistedResultText(key, rawText))
        val state = ResultState(text, collapsed = true)
        resultStates[key] = state
        if (text != rawText) {
            MaxHzPrefs.saveResult(this, key, text, true)
        } else {
            MaxHzPrefs.setResultCollapsed(this, key, true)
        }
        return state
    }

    private fun sanitizePersistedResultText(key: String, text: String): String {
        if (key == BATTERY_PROTECTION_RESULT_KEY) {
            sanitizeBatteryProtectionResultText(text)?.let { return it }
        }
        if (key == SYSTEM_RESIDUE_RESULT_KEY && (
                text.contains("可能残留：") ||
                    text.contains("Quick Doze 自定义参数") ||
                    text.contains("返回代码：")
                )
        ) {
            return buildSystemResidueResultText(MaxHzShell.Result(0, text, ""), "旧版本保存的检测结果已转换为用户版摘要。")
        }
        sanitizeLegacyCommandResultText(text)?.let { return it }
        val rawSettingLine = Regex("^[A-Za-z0-9_.-]+=[^=].*")
        if (
            !text.contains("实际输出：") &&
            !text.contains("->") &&
            text.lineSequence().none { isRawCommandOutput(it.trim(), rawSettingLine) }
        ) {
            return text
        }

        val cleaned = mutableListOf<String>()
        var skippedOldChanges = false
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("实际输出：") -> {
                    cleaned += "运行情况：旧版本保存的调试细节已简化，请重新执行查看新格式。"
                    skippedOldChanges = false
                }
                trimmed.startsWith("关键变化：") && text.contains("->") -> {
                    cleaned += "关键变化：旧版本保存的详细键值已隐藏，请重新执行查看新格式。"
                    skippedOldChanges = true
                }
                skippedOldChanges && trimmed.startsWith("- ") && trimmed.contains("->") -> Unit
                isRawCommandOutput(trimmed, rawSettingLine) -> {
                    skippedOldChanges = false
                }
                trimmed.startsWith("- ") && trimmed.contains("->") -> {
                    skippedOldChanges = false
                }
                else -> {
                    cleaned += line
                    skippedOldChanges = false
                }
            }
        }
        return cleaned.joinToString("\n").trim().ifBlank { "旧版本保存的调试细节已简化，请重新执行查看新格式。" }
    }

    private fun sanitizeLegacyCommandResultText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.startsWith("最近一次操作结果")) return null
        if (!trimmed.contains("执行结果：") && !trimmed.contains("错误信息：")) return null
        val lines = trimmed.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val resultLine = lines.firstOrNull { it.startsWith("执行结果：") }.orEmpty()
        val failed = resultLine.contains("失败") || resultLine.contains("未通过")
        return buildString {
            append("最近一次操作结果\n")
            if (failed) {
                append("操作未完成\n")
                append("原因：").append(commandFailureReason(MaxHzShell.Result(-1, "", trimmed))).append('\n')
            } else {
                append(resultLine.removePrefix("执行结果：").ifBlank { "已完成" }).append('\n')
            }
            append("技术详情：\n")
            append("旧结果内容：").append(trimmed)
        }
    }

    private fun sanitizeBatteryProtectionResultText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.startsWith("最近一次操作结果")) return null
        val isOldBatteryProtectionResult = trimmed.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim() == "电池保护" ||
            trimmed.contains("执行结果：失败") ||
            trimmed.contains("失败（代码")
        if (!isOldBatteryProtectionResult) return null
        val code = Regex("代码\\s*(-?\\d+)").find(trimmed)?.groupValues?.getOrNull(1)
        val rawError = trimmed.lineSequence()
            .dropWhile { !it.trim().startsWith("错误信息：") }
            .drop(1)
            .joinToString("\n")
            .trim()
            .ifBlank { null }
        val result = MaxHzShell.Result(
            code?.toIntOrNull() ?: -1,
            "",
            rawError ?: trimmed
        )
        return buildBatteryProtectionResultText(result, "")
    }

    private fun saveResultState(key: String, state: ResultState) {
        resultStates[key] = state
        MaxHzPrefs.saveResult(this, key, state.text, state.collapsed)
    }

    private fun stableResultKey(title: String): String {
        return "card_" + title.hashCode().toUInt().toString(16)
    }

    companion object {
        private const val BUTTON_HEIGHT_DP = 42
        private const val COMMAND_BUTTON_TAG = "maxhz_command_button"
        private const val SLOW_COMMAND_NOTICE_MS = 2000L
        private const val BUTTON_RADIUS_DP = 14
        private const val BUTTON_SPACING_DP = 8
        private const val DASHBOARD_TILE_HEIGHT_DP = 64
        private const val DASHBOARD_TILE_SPACING_DP = 8
        private const val DASHBOARD_ACTION_HEIGHT_DP = 40
        private const val INPUT_ROW_HEIGHT_DP = 44
        private const val INPUT_ROW_SPACING_DP = 8
        private const val APP_REFRESH_INPUT_LABEL_WIDTH_DP = 56
        private const val APP_REFRESH_INPUT_FIELD_WIDTH_DP = 56
        private const val APP_REFRESH_INPUT_ITEM_SPACING_DP = 12
        private const val BATTERY_INPUT_LABEL_WIDTH_DP = 96
        private const val BATTERY_INPUT_FIELD_WIDTH_DP = 72
        private const val BATTERY_INPUT_ITEM_SPACING_DP = 8
        private const val INPUT_LABEL_GAP_DP = 4
        private const val INPUT_FIELD_HEIGHT_DP = 44
        const val CARD_RADIUS_DP = 18
        private const val CARD_PADDING_DP = 12
        private const val CARD_SECTION_SPACING_DP = 8
        private const val BATTERY_PROTECTION_RESULT_KEY = "battery_protection"
        private const val BACKGROUND_ERROR_RESULT_KEY = "background_error"
        private const val GAME_AUTO_RUNTIME_RESULT_KEY = "game_auto_runtime"
        private const val SYSTEM_RESIDUE_RESULT_KEY = "system_residue"
        private const val RELEASE_PREFS = "maxhz_release_prefs"
        private const val KEY_NIGHT_GUARD_RISK_CONFIRMED = "night_guard_risk_confirmed"
        private const val KEY_SYSTEM_APP_FILTER_CONFIRMED = "system_app_filter_confirmed"
        private const val OPERATION_LOG_PREFS = "maxhz_operation_logs"
        private const val OPERATION_LOG_KEY = "operation_logs"
        private const val OPERATION_LAST_BACKGROUND_ERROR_KEY = "last_background_error"
        private const val OPERATION_LOG_LIMIT = 50
    }
}

// 图标资源映射（从 MaxHzActivity 类内移出，供 Compose 页共用）
internal fun featureIconRes(title: String): Int {
    return when {
        title.contains("CPU") || title.contains("算力") || title.contains("调度") || title.contains("响应") || title.contains("性能调度") -> R.drawable.ic_maxhz_cpu_24
        title.contains("GPU") || title.contains("图形") || title.contains("Vulkan") || title.contains("渲染") -> R.drawable.ic_maxhz_gpu_24
        title.contains("网络") || title.contains("触控") || title.contains("TCP") -> R.drawable.ic_maxhz_network_touch_24
        title.contains("编译") || title.contains("ART") || title.contains("AOT") || title.contains("Dex2Oat") || title.contains("内存") || title.contains("ZRAM") -> R.drawable.ic_maxhz_code_24
        title.contains("墓碑") || title.contains("冻结") || title.contains("Doze") || title.contains("待机") || title.contains("定位") -> R.drawable.ic_outline_dark_mode_24
        title.contains("满血") || title.contains("超级性能") || title.contains("性能") || title.contains("调优") -> R.drawable.ic_maxhz_speed_24
        title.contains("电池") || title.contains("旁路") || title.contains("省电") -> R.drawable.ic_maxhz_battery_24
        title.contains("游戏") -> R.drawable.ic_maxhz_game_24
        title.contains("护栏") || title.contains("保护") || title.contains("温控") || title.contains("安全") || title.contains("守护") -> R.drawable.ic_maxhz_shield_24
        title.contains("同步") || title.contains("扫描") -> R.drawable.ic_maxhz_sync_24
        title.contains("刷新率") || title.contains("自适应") || title.contains("帧率") -> R.drawable.ic_maxhz_speed_24
        title.contains("FPS") || title.contains("监视器") -> R.drawable.ic_maxhz_chart_24
        title.contains("户外") || title.contains("强光") || title.contains("状态栏") -> R.drawable.ic_maxhz_sun_24
        title.contains("应用") || title.contains("App") || title.contains("单个") || title.contains("分别设置") || title.contains("无障碍") -> R.drawable.ic_maxhz_apps_24
        title.contains("恢复") || title.contains("修复") || title.contains("重置") || title.contains("残留") -> R.drawable.ic_maxhz_restore_24
        title.contains("窗口") || title.contains("多任务") -> R.drawable.ic_maxhz_window_24
        title.contains("工具") -> R.drawable.ic_maxhz_tool_24
        title.contains("无线") || title.contains("调试") -> R.drawable.ic_maxhz_network_touch_24
        title.contains("电脑") || title.contains("ADB") -> R.drawable.ic_maxhz_tool_24
        title.contains("Root") -> R.drawable.ic_server_ok_24dp
        title.contains("终端") || title.contains("命令") -> R.drawable.ic_maxhz_code_24
        title.contains("手册") || title.contains("指南") || title.contains("帮助") -> R.drawable.ic_maxhz_sync_24
        title.contains("Shizuku") -> R.drawable.ic_settings_outline_24dp
        else -> R.drawable.ic_maxhz_speed_24
    }
}
