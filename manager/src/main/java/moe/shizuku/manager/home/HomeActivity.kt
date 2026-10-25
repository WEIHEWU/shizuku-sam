package moe.shizuku.manager.home

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import moe.shizuku.manager.admin.DhizukuAdminReceiver
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import moe.shizuku.manager.maxhz.MaxHzComposeRefreshBus
import moe.shizuku.manager.maxhz.GlassAppTheme
import moe.shizuku.manager.maxhz.getSystemOrAppPrimaryColor
import moe.shizuku.manager.maxhz.MaxHzShell
import moe.shizuku.manager.utils.AccessibilityHelper
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.adb.AdbPairingDetector
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.adb.AdbPortProber
import moe.shizuku.manager.utils.DhizukuHelper
import moe.shizuku.manager.utils.HotspotHelper
import moe.shizuku.manager.databinding.AboutDialogBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.maxhz.MaxHzPrefs
import moe.shizuku.manager.maxhz.MaxHzController
import moe.shizuku.manager.maxhz.EntryItem
import moe.shizuku.manager.maxhz.LiquidGlassTheme
import moe.shizuku.manager.maxhz.ToggleAction
import moe.shizuku.manager.maxhz.MaxHzActivity
import moe.shizuku.manager.maxhz.CompatSettingsAutoScroller
import moe.shizuku.manager.maxhz.MaxHzAccessibilityService
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.shell.ShellTutorialActivity
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.AppIconCache
import rikka.core.util.ClipboardUtils
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.html.text.HtmlCompat
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku

abstract class HomeActivity : MaxHzActivity() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkServerStatus()
        appsModel.load()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        checkServerStatus()
    }

    private val homeModel by viewModels { HomeViewModel() }
    private val appsModel by appsViewModel()

    private var isShizukuTabActive = true

    /**
     * 阶段 A：SHIZUKU 首页的 Compose 承载视图（背景 + 玻璃卡片全部
     * drawBackdrop）。切到其他 tab 时隐藏，切回时复用，不重建。
     */
    private var homeComposeView: ComposeView? = null

    /**
     * Compose 首页状态快照：由下方 LiveData 观察者推送，驱动首页重组。
     * 早期版本在 setContent 内调用 observeAsState()，而手动 addView 的 ComposeView
     * 没有 LocalLifecycleOwner，release 构建会静默丢弃整棵组合树（白屏）。改用 Activity
     * 持有的 mutableStateOf 快照即可，组合不再依赖 LifecycleOwner。
     */
    private val homeStatusState = mutableStateOf<ServiceStatus?>(null)
    private val homeGrantedState = mutableIntStateOf(0)
    private val isRootAvailableState = mutableStateOf(false)
    private val isAutoPairingState = mutableStateOf(false)
    private val activeAdbPortState = mutableIntStateOf(-1)
    private val isWifiConnectedState = mutableStateOf(true)
    private val isHotspotActiveState = mutableStateOf(false)
    private val isDeviceOwnerState = mutableStateOf(false)

    // 方案 A：极简控制台调度与高频体验开关状态快照
    private val gameTileModeState = mutableIntStateOf(0)
    private val passThroughState = mutableStateOf(false)
    private val batteryProtectionState = mutableStateOf(false)
    private val batteryLimitState = mutableIntStateOf(80)
    private val batteryRechargeState = mutableIntStateOf(75)
    private val gameAutoSwitchState = mutableStateOf(false)
    private val gameAutoCountState = mutableIntStateOf(0)
    private val fpsMonitorState = mutableStateOf(false)
    private val outdoorModeState = mutableStateOf(false)
    private val tombstoneState = mutableStateOf(false)
    private val quickDozeState = mutableStateOf(false)
    private val autostartLimitState = mutableStateOf(false)
    private val locationThrottleState = mutableStateOf(false)
    private val scanGuardState = mutableStateOf(false)
    private val appLaunchBoostState = mutableStateOf(false)
    private val forceResizableState = mutableStateOf(false)
    private val currentDockPage = mutableStateOf(Page.SHIZUKU)

    // 方案 A：Tab 2 分应用刷新率规则状态快照
    private val appRulesState = mutableStateOf<List<MaxHzPrefs.AppRefreshRule>>(emptyList())
    private val isInstantSwitchActiveState = mutableStateOf(false)
    private val supportedRefreshRatesState = mutableStateOf<List<Int>>(listOf(10, 24, 30, 48, 60, 90, 120))
    private var lastRecordedThemeKey: String? = null
    private var autoRestartAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastRecordedThemeKey = computeUserThemeKey()
        syncSchemeAStates()

        homeModel.serviceStatus.observe(this) {
            homeStatusState.value = homeModel.serviceStatus.value?.data
            if (it.status == Status.SUCCESS) {
                val status = homeModel.serviceStatus.value?.data ?: return@observe
                if (status.isRunning) {
                    autoRestartAttempted = true
                    ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
                } else if (!autoRestartAttempted &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB &&
                    checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                ) {
                    val sysPropPort = EnvironmentUtils.getAdbTcpPort()
                    val discoveredPort = activeAdbPortState.intValue
                    val hasLivePort = sysPropPort in 1..65535 || discoveredPort in 1..65535
                    if (hasLivePort) {
                        autoRestartAttempted = true
                        moe.shizuku.manager.adb.AdbStartWorker.enqueue(this)
                    }
                }
                // Compose 版通过 homeStatusState 快照自动重组，无需手动重渲染
                if (selectedPage == Page.SHIZUKU && homeComposeView == null) {
                    renderShizukuDslPage()
                }
            }
        }
        appsModel.grantedCount.observe(this) {
            homeGrantedState.intValue = appsModel.grantedCount.value?.data ?: 0
            if (it.status == Status.SUCCESS) {
                if (selectedPage == Page.SHIZUKU && homeComposeView == null) {
                    renderShizukuDslPage()
                }
            }
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        installUnifiedBackNavigation()
        setupInitialPage()
        updateMaxHzServiceState()
        syncStatusChangeLogs()
        if (Shizuku.pingBinder()) {
            moe.shizuku.manager.receiver.ShizukuReceiverStarter.cancelAdbStart(this)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val rootAvail = MaxHzShell.isRootAvailable()
            val wifi = HotspotHelper.isWifiConnected(this@HomeActivity)
            val hotspot = HotspotHelper.isHotspotActive(this@HomeActivity)
            val port = AdbPortProber.findActiveLoopbackPort(this@HomeActivity)
            withContext(Dispatchers.Main) {
                isRootAvailableState.value = rootAvail
                isWifiConnectedState.value = wifi
                isHotspotActiveState.value = hotspot
                activeAdbPortState.intValue = port
            }
        }

        if (intent?.getBooleanExtra("open_night_guard", false) == true) {
            pushDetail("待机异常唤醒排查") { nightWakeupGuardSection() }
        }
        if (intent?.getBooleanExtra("EXTRA_AUTO_START_AFTER_PAIRING", false) == true) {
            lifecycleScope.launch {
                delay(350)
                handleWirelessAdbStartClick()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_night_guard", false)) {
            pushDetail("待机异常唤醒排查") { nightWakeupGuardSection() }
        }
        if (intent.getBooleanExtra("EXTRA_AUTO_START_AFTER_PAIRING", false)) {
            lifecycleScope.launch {
                delay(350)
                handleWirelessAdbStartClick()
            }
        }
    }

    private fun syncSchemeAStates() {
        val tileMode = MaxHzPrefs.gameTileMode(this)
        gameTileModeState.intValue = when {
            tileMode == 2 || MaxHzPrefs.superPerformanceEnabled(this) -> 2
            tileMode == 1 || MaxHzPrefs.compatibleSaverEnabled(this) -> 1
            else -> 0
        }
        passThroughState.value = MaxHzPrefs.passThroughEnabled(this)
        batteryProtectionState.value = MaxHzPrefs.batteryProtectionEnabled(this)
        batteryLimitState.intValue = MaxHzPrefs.batteryLimit(this)
        batteryRechargeState.intValue = MaxHzPrefs.batteryRecharge(this)
        gameAutoSwitchState.value = MaxHzPrefs.gameAutoPerformanceEnabled(this)
        gameAutoCountState.intValue = MaxHzPrefs.gameAutoPerformancePackages(this).size
        fpsMonitorState.value = MaxHzPrefs.fpsMonitorRunning(this)
        outdoorModeState.value = isOutdoorModeActive()
        tombstoneState.value = MaxHzPrefs.mTombstoneEnabled(this)
        quickDozeState.value = MaxHzPrefs.quickDozeEnabled(this)
        autostartLimitState.value = MaxHzPrefs.mAutostartLimitEnabled(this)
        locationThrottleState.value = MaxHzPrefs.mLocationThrottleEnabled(this)
        scanGuardState.value = MaxHzPrefs.scanGuardEnabled(this)
        appLaunchBoostState.value = MaxHzPrefs.mDexoptEnabled(this)
        forceResizableState.value = MaxHzPrefs.resultText(this, "force_resizable") == "1"
        appRulesState.value = MaxHzPrefs.appRefreshRules(this)
        isInstantSwitchActiveState.value = AccessibilityHelper.isInstantSwitchEnabled(this)
        val cached = MaxHzPrefs.supportedRefreshRates(this)
        if (cached.isNotEmpty()) {
            supportedRefreshRatesState.value = cached
        }
    }

    override fun setupInitialPage() {
        val targetPageStr = intent?.getStringExtra("extra_target_page")
        when (targetPageStr?.lowercase()) {
            "refresh" -> renderPage(Page.REFRESH)
            "power" -> renderPage(Page.POWER)
            else -> showShizukuTab()
        }
    }

    protected fun showShizukuTab() {
        renderPage(Page.SHIZUKU)
    }

    protected fun showMaxHzTab(page: Page) {
        renderPage(page)
    }

    override fun renderPage(page: Page, restoreScrollY: Int?) {
        selectedPage = page
        dockSelectedTabState.intValue = dockIndexFor(page)
        detailStack.clear()
        showMaxHzScrollView()
        if (page == Page.SHIZUKU || page == Page.POWER || page == Page.REFRESH) {
            isShizukuTabActive = true
            navBar?.visibility = View.GONE
            updateSystemBarPadding()
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.title = when (page) {
                Page.SHIZUKU -> "Shizuku"
                Page.POWER -> getString(R.string.dock_tab_hardware)
                Page.REFRESH -> getString(R.string.dock_tab_rules)
                else -> "Shizuku"
            }
            updateNavSelection()
            homeModel.reload()
            appsModel.load()
            currentDockPage.value = page
            renderShizukuComposePage()
            return
        }
        // 其他非 Dock 页面用 View 渲染
        hideHomeCompose()
        isShizukuTabActive = false
        super.renderPage(page, restoreScrollY)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = getString(R.string.dock_tab_rules)
        updateNavSelection()
    }

    override fun dockIndexFor(page: Page): Int = when (page) {
        Page.SHIZUKU -> 0
        Page.POWER -> 1
        Page.REFRESH -> 2
        else -> 0
    }

    override fun handleDockTabClick(index: Int) {
        val target = when (index) {
            0 -> Page.SHIZUKU
            1 -> Page.POWER
            2 -> Page.REFRESH
            else -> Page.SHIZUKU
        }
        if (target == selectedPage) return
        renderPage(target)
    }

    /**
     * 阶段 A：SHIZUKU 首页改为 Compose 渲染（背景 + 玻璃卡片 drawBackdrop）。
     * 业务逻辑（状态轮询、启动方式、对话框）仍在本 Activity。
     */
    private fun renderShizukuComposePage() {
        hideMaxHzScrollView()
        hideMaxHzCompose()
        // 复用已有的 ComposeView：
        // 屏幕页 → 点击主页时，homeComposeView 只是被 hideHomeCompose 设为
        // GONE（并未 removeView），其 Composition 还活着（AbstractComposeView
        // 默认 DisposeOnDetachedFromWindowOrReleasedFromPool，我们没 detach），
        // 直接重新可见即可，不需要重建。
        //
        // 复用消除了之前 removeView 到 addView 之间 1-2 帧的容器为空期
        // （暗闪，frame 亮度从 106 跌到 27，正是 theme windowBackground）。
        val existing = homeComposeView
        if (existing != null) {
            existing.animate().cancel()
            // 关键：清掉 fadeOutPage 挂的 postDelayed 兜底回调。
            // 它会在 210ms 后无条件把 view 置 GONE，不清除的话复用后页面
            // 会先显示、再被这次延时任务抹成空白。
            existing.removeCallbacks(null)
            existing.alpha = 1f
            existing.visibility = View.VISIBLE
            return
        }
        val cv = ComposeView(this).apply {
            setContent {
                val dark = moe.shizuku.manager.maxhz.rememberEffectiveIsDark()
                val blackTheme = moe.shizuku.manager.maxhz.rememberEffectiveBlackNight(dark)
                // 必须包 MaterialTheme：否则 LocalIndication 无水波纹实现，
                // 所有 .clickable 元素按下时没有任何反馈动画。
                GlassAppTheme(isDark = dark) {
                    val resolvedPrimary = getSystemOrAppPrimaryColor(dark)
                    ShizukuHomeScreen(
                        isDark = dark,
                        currentTab = when (currentDockPage.value) {
                            Page.POWER -> 1
                            Page.REFRESH -> 2
                            else -> 0
                        },
                        status = homeStatusState.value,
                        grantedCount = homeGrantedState.intValue,
                        primaryColor = resolvedPrimary,
                    topInsetPx = actionBarInset(),
                    bottomInsetPx = systemBottomInsetState.intValue,
                    fullHeightPx = (glassRoot?.height ?: 0).toFloat().takeIf { it > 0f }
                        ?: resources.displayMetrics.heightPixels * 0.93f,
                    isRootAvailable = isRootAvailableState.value,
                    isAutoPairingEnabled = isAutoPairingState.value,
                    activePort = activeAdbPortState.intValue,
                    isWifiConnected = isWifiConnectedState.value,
                    isHotspotActive = isHotspotActiveState.value,
                    isDeviceOwner = isDeviceOwnerState.value,
                    gameTileMode = gameTileModeState.intValue,
                    isPassThroughActive = passThroughState.value,
                    isGameAutoSwitchActive = gameAutoSwitchState.value,
                    gameAutoPackageCount = gameAutoCountState.intValue,
                    isFpsMonitorActive = fpsMonitorState.value,
                    isOutdoorModeActive = outdoorModeState.value,
                    isBatteryProtectionActive = batteryProtectionState.value,
                    batteryProtectionLimit = batteryLimitState.intValue,
                    batteryProtectionRecharge = batteryRechargeState.intValue,
                    isTombstoneActive = tombstoneState.value,
                    isQuickDozeActive = quickDozeState.value,
                    isAutostartLimitActive = autostartLimitState.value,
                    isLocationThrottleActive = locationThrottleState.value,
                    isScanGuardActive = scanGuardState.value,
                    isAppLaunchBoostActive = appLaunchBoostState.value,
                    isForceResizableActive = forceResizableState.value,
                    flatBackground = isFlatBackground(),
                    wallpaperUri = ShizukuSettings.getWallpaperUri()?.let { Uri.parse(it) },
                    wallpaperFit = ShizukuSettings.getWallpaperFit(),
                    isBlackNightTheme = blackTheme,
                    appRules = appRulesState.value,
                    isInstantSwitchActive = isInstantSwitchActiveState.value,
                    supportedRefreshRates = supportedRefreshRatesState.value,
                    onTabClick = { index -> handleDockTabClick(index) },
                    actions = buildHomeActions()
                )
                }
            }
        }
        homeComposeView = cv
        contentContainer.addView(
            cv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // 首次创建直接显示，不用 fadeInPage（alpha=0→1 动画在 Compose 未组合完成时
        // 导致整页透明，用户感知为"空白需双击"）。
        cv.alpha = 1f
        cv.visibility = View.VISIBLE
    }

    private fun hideHomeCompose() {
        homeComposeView?.let { fadeOutPage(it) }
    }


    /** Compose 首页的交互回调（复用本 Activity 已有的启动逻辑） */
    private fun buildHomeActions() = HomeActions(
        onAuthorizedAppsClick = {
            if (isServiceRunning()) {
                startActivity(Intent(this, ApplicationManagementActivity::class.java))
            } else {
                Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            }
        },
        onRishClick = {
            if (isServiceRunning()) {
                startActivity(Intent(this, ShellTutorialActivity::class.java))
            } else {
                Toast.makeText(this, R.string.toast_service_not_running, Toast.LENGTH_SHORT).show()
            }
        },
        onWirelessStartClick = { handleWirelessAdbStartClick() },
        onWirelessPairClick = { handleWirelessAdbPairClick() },
        onAutoPairingAssistantClick = { handleAutoPairingAssistantClick() },
        onOpenDevSettingsClick = { handleOpenDevSettingsClick() },
        onOpenDisableAppCompatibilityPoliciesClick = { handleOpenDisableAppCompatibilityPoliciesClick() },
        onHotspotClick = {
            HotspotHelper.openTetheringSettings(this)
            Toast.makeText(this, R.string.toast_hotspot_opened, Toast.LENGTH_LONG).show()
        },
        onDhizukuClick = { handleDhizukuClick() },
        onStartAdbClick = { handleStartAdbClick() },
        onStartRootClick = { handleStartRootClick() },
        onHelpClick = { handleHelpClick() },
        onAboutClick = { showAboutDialog() },
        onStopServiceClick = {
            val dialog = MaterialAlertDialogBuilder(this)
                .setMessage(R.string.dialog_stop_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ShizukuSettings.setLastSettledStateRunning(false)
                    try { Shizuku.exit() } catch (_: Throwable) {}
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.show()
            val isDark = isDarkMode()
            dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
            applyUnifiedDialogWindowStyle(dialog)
        },
        onConfirmStopService = {
            ShizukuSettings.setLastSettledStateRunning(false)
            try { Shizuku.exit() } catch (_: Throwable) {}
        },
        onModeChange = { mode -> handleModeChange(mode) },
        onTogglePassThrough = { on -> handleTogglePassThrough(on) },
        onToggleBatteryProtection = { on -> handleToggleBatteryProtection(on) },
        onOpenBatteryProtectionConfigClick = {
            showBatteryProtectionDialog {
                syncSchemeAStates()
            }
        },
        onApplyBatteryProtectionConfig = { limit, recharge, onResult ->
            applyBatteryProtectionFromUi(
                limit = limit,
                recharge = recharge,
                onChanged = { syncSchemeAStates() },
                onResult = onResult
            )
        },
        onRestoreBatteryProtectionDefault = { onResult ->
            restoreBatteryProtectionFromUi(
                onChanged = { syncSchemeAStates() },
                onResult = onResult
            )
        },
        onToggleGameAutoSwitch = { on -> handleToggleGameAutoSwitch(on) },
        onToggleFpsMonitor = { on -> handleToggleFpsMonitor(on) },
        onToggleOutdoorMode = { on -> handleToggleOutdoorMode(on) },
        onToggleTombstone = { on -> handleToggleTombstone(on) },
        onToggleQuickDoze = { on -> handleToggleQuickDoze(on) },
        onOpenQuickDozeConfigClick = {
            pushDetail("熄屏深度休眠") { openQuickDozeSection() }
        },
        onToggleAutostartLimit = { on -> handleToggleAutostartLimit(on) },
        onToggleLocationThrottle = { on -> handleToggleLocationThrottle(on) },
        onToggleScanGuard = { on -> handleToggleScanGuard(on) },
        onOpenAppLaunchBoostClick = {
            pushDetail("应用启动加速") {
                LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    mOriginalMemoryCompile(this)
                }
            }
        },
        onToggleForceResizable = { on -> handleToggleForceResizable(on) },
        onBatteryInfoClick = { showBatteryInfoDialog() },
        onFetchBatteryInfo = { onResult -> fetchBatteryInfoFromUi(onResult) },
        onRestoreDefaultsClick = { handleRestoreDefaultsClick() },
        onOpenLaboratoryClick = { handleOpenLaboratoryClick() },
        onOpenGameAutoConfigClick = { pushDetail(getString(R.string.detail_title_game_auto)) { gameAutoPerformanceCard() } },
        onSelectAppForAppRule = { callback ->
            val hasPrivilege = isServiceRunning() || isRootAvailableState.value
            if (!hasPrivilege) {
                Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            } else if (!AccessibilityHelper.isInstantSwitchEnabled(this)) {
                Toast.makeText(this, "无障碍服务尚未开启，请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                AccessibilityHelper.openInstantSwitchSettings(this)
            } else {
                showUserAppPicker { pkg, label ->
                    callback(pkg, label)
                }
            }
        },
        onSaveAppRule = { rule ->
            val hasPrivilege = isServiceRunning() || isRootAvailableState.value
            if (!hasPrivilege) {
                Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            } else if (!AccessibilityHelper.isInstantSwitchEnabled(this)) {
                Toast.makeText(this, "无障碍服务尚未开启，请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                AccessibilityHelper.openInstantSwitchSettings(this)
            } else {
                MaxHzPrefs.saveAppRefreshRule(this, rule)
                appRulesState.value = MaxHzPrefs.appRefreshRules(this)
                updateMaxHzServiceState()
                Toast.makeText(this, R.string.rules_toast_saved, Toast.LENGTH_SHORT).show()
            }
        },
        onDeleteAppRule = { pkg ->
            MaxHzPrefs.removeAppRefreshRule(this, pkg)
            appRulesState.value = MaxHzPrefs.appRefreshRules(this)
            updateMaxHzServiceState()
            Toast.makeText(this, R.string.rules_toast_deleted, Toast.LENGTH_SHORT).show()
        },
        onClearAppRules = {
            MaxHzPrefs.clearAppRefreshRules(this)
            appRulesState.value = emptyList()
            updateMaxHzServiceState()
            Toast.makeText(this, R.string.rules_toast_cleared, Toast.LENGTH_SHORT).show()
        },
        onOpenAccessibilityClick = {
            AccessibilityHelper.handleInstantSwitchClick(this) {
                isInstantSwitchActiveState.value = true
                updateMaxHzServiceState()
            }
        },
        onNightWakeupGuardClick = {
            pushDetail("待机异常唤醒排查") { nightWakeupGuardSection() }
        },
        onCalibrateLtpoAdaptiveClick = { handleCalibrateLtpoAdaptiveClick() }
    )

    private fun handleOpenDevSettingsClick() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", "toggle_adb_wireless")
            })
        }
        try {
            startActivity(intent)
            Toast.makeText(this, R.string.toast_dev_settings_opened, Toast.LENGTH_SHORT).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_dev_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleOpenDisableAppCompatibilityPoliciesClick() {
        CompatSettingsAutoScroller.request()
        val isSamsung = EnvironmentUtils.isSamsung()
        val prefKey = if (isSamsung) "sec_more_settings" else "disable_app_compatibility_policies"
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(":settings:fragment_args_key", prefKey)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", prefKey)
            })
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_dev_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDhizukuClick() {
        DhizukuHelper.showSetupDialog(this) {
            isDeviceOwnerState.value = DhizukuHelper.isPrivileged(this)
        }
    }

    private fun handleHelpClick() {
        CustomTabsHelper.launchUrlOrCopy(this, Helps.HOME.get())
    }

    private fun handleModeChange(mode: Int) {
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (!hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        val prevMode = gameTileModeState.intValue
        gameTileModeState.intValue = mode
        lifecycleScope.launch {
            val (msg, finalMode) = withContext(Dispatchers.IO) {
                when (mode) {
                    1 -> {
                        val r = MaxHzController.applyDailySaver(
                            MaxHzPrefs.quickDozeMaintenanceMinutes(this@HomeActivity),
                            MaxHzPrefs.quickDozeNoMaintenance(this@HomeActivity)
                        )
                        if (r.code == 0) {
                            MaxHzPrefs.setGameTileMode(this@HomeActivity, 1)
                            MaxHzPrefs.setCompatibleSaverEnabled(this@HomeActivity, true)
                            MaxHzPrefs.markSuperPerformanceState(this@HomeActivity, false)
                            MaxHzPrefs.setQuickDozeEnabled(this@HomeActivity, true)
                            getString(R.string.toast_mode_switched_saver) to 1
                        } else {
                            val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                            getString(R.string.toast_mode_switch_failed, err) to prevMode
                        }
                    }
                    2 -> {
                        val relaxed = MaxHzPrefs.gameAutoThermalRelaxed(this@HomeActivity)
                        val r = MaxHzController.quickSwitchGamePerformance(relaxed)
                        if (r.code == 0) {
                            MaxHzPrefs.setGameTileMode(this@HomeActivity, 2)
                            MaxHzPrefs.setCompatibleSaverEnabled(this@HomeActivity, false)
                            MaxHzPrefs.markSuperPerformanceState(this@HomeActivity, true, relaxed)
                            MaxHzPrefs.setQuickDozeEnabled(this@HomeActivity, false)
                            getString(R.string.toast_mode_switched_game) to 2
                        } else {
                            val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                            getString(R.string.toast_mode_switch_failed, err) to prevMode
                        }
                    }
                    else -> {
                        val r = MaxHzController.quickSwitchGameDefault()
                        if (r.code == 0) {
                            MaxHzPrefs.setGameTileMode(this@HomeActivity, 0)
                            MaxHzPrefs.setCompatibleSaverEnabled(this@HomeActivity, false)
                            MaxHzPrefs.markSuperPerformanceState(this@HomeActivity, false)
                            MaxHzPrefs.setQuickDozeEnabled(this@HomeActivity, false)
                            getString(R.string.toast_mode_switched_default) to 0
                        } else {
                            val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                            getString(R.string.toast_mode_switch_failed, err) to prevMode
                        }
                    }
                }
            }
            gameTileModeState.intValue = finalMode
            quickDozeState.value = MaxHzPrefs.quickDozeEnabled(this@HomeActivity)
            Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_SHORT).show()
            updateMaxHzServiceState()
        }
    }

    private fun handleTogglePassThrough(on: Boolean) {
        val prev = passThroughState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        passThroughState.value = on
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (on) MaxHzController.applyPassThroughMode(MaxHzPrefs.batteryLimit(this@HomeActivity))
                else MaxHzController.restorePassThroughMode()
            }
            if (r.code == 0) {
                MaxHzPrefs.setPassThroughEnabled(this@HomeActivity, on)
                Toast.makeText(this@HomeActivity, if (on) R.string.toast_passthrough_enabled else R.string.toast_passthrough_disabled, Toast.LENGTH_SHORT).show()
                updateMaxHzServiceState()
            } else {
                passThroughState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, getString(R.string.toast_passthrough_toggle_failed, err), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleBatteryProtection(on: Boolean) {
        val prev = batteryProtectionState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        batteryProtectionState.value = on
        lifecycleScope.launch {
            val limit = MaxHzPrefs.batteryLimit(this@HomeActivity)
            val recharge = MaxHzPrefs.batteryRecharge(this@HomeActivity)
            val r = withContext(Dispatchers.IO) {
                if (on) MaxHzController.applyBatteryProtection(limit, recharge)
                else MaxHzController.restoreBatteryProtection()
            }
            if (r.code == 0) {
                MaxHzPrefs.setBatteryProtectionEnabled(this@HomeActivity, on)
                batteryLimitState.intValue = limit
                batteryRechargeState.intValue = recharge
                Toast.makeText(
                    this@HomeActivity,
                    if (on) "充电上限保护已开启（${limit}% 停充 / ${recharge}% 复充）" else "充电上限保护已恢复默认",
                    Toast.LENGTH_SHORT
                ).show()
                updateMaxHzServiceState()
            } else {
                batteryProtectionState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, "充电上限保护切换失败：$err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleTombstone(on: Boolean) {
        val prev = tombstoneState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        tombstoneState.value = on
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (on) MaxHzController.applyMTombstoneMode()
                else MaxHzController.restoreMTombstoneMode()
            }
            if (r.code == 0) {
                MaxHzPrefs.setMTombstoneEnabled(this@HomeActivity, on)
                Toast.makeText(
                    this@HomeActivity,
                    if (on) "后台进程休眠（墓碑）已开启" else "后台进程休眠（墓碑）已恢复默认",
                    Toast.LENGTH_SHORT
                ).show()
                updateMaxHzServiceState()
            } else {
                tombstoneState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, "后台进程休眠切换失败：$err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleQuickDoze(on: Boolean) {
        val prev = quickDozeState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        quickDozeState.value = on
        lifecycleScope.launch {
            val mins = MaxHzPrefs.quickDozeMaintenanceMinutes(this@HomeActivity)
            val noMaint = MaxHzPrefs.quickDozeNoMaintenance(this@HomeActivity)
            val r = withContext(Dispatchers.IO) {
                if (on) MaxHzController.applyQuickDoze(mins, noMaint)
                else MaxHzController.restoreQuickDoze()
            }
            if (r.code == 0) {
                MaxHzPrefs.setQuickDozeEnabled(this@HomeActivity, on)
                Toast.makeText(
                    this@HomeActivity,
                    if (on) "熄屏深度休眠已开启" else "熄屏深度休眠已恢复默认",
                    Toast.LENGTH_SHORT
                ).show()
                updateMaxHzServiceState()
            } else {
                quickDozeState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, "熄屏深度休眠切换失败：$err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleAutostartLimit(on: Boolean) {
        val prev = autostartLimitState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        autostartLimitState.value = on
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (on) MaxHzController.applyMAutostartLimit()
                else MaxHzController.restoreMAutostartLimit()
            }
            if (r.code == 0) {
                MaxHzPrefs.setMAutostartLimitEnabled(this@HomeActivity, on)
                Toast.makeText(
                    this@HomeActivity,
                    if (on) "限制后台自启已开启（含三星/Android 原生关联唤醒拦截）" else "限制后台自启已恢复默认",
                    Toast.LENGTH_SHORT
                ).show()
                updateMaxHzServiceState()
            } else {
                autostartLimitState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, "限制后台自启切换失败：$err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleLocationThrottle(on: Boolean) {
        val prev = locationThrottleState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        locationThrottleState.value = on
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (on) MaxHzController.applyMLocationThrottle()
                else MaxHzController.restoreMLocationThrottle()
            }
            if (r.code == 0) {
                MaxHzPrefs.setMLocationThrottleEnabled(this@HomeActivity, on)
                Toast.makeText(
                    this@HomeActivity,
                    if (on) "限制后台定位已开启（静默间隔延长至 30 分钟）" else "限制后台定位已恢复默认",
                    Toast.LENGTH_SHORT
                ).show()
                updateMaxHzServiceState()
            } else {
                locationThrottleState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, "限制后台定位切换失败：$err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleScanGuard(on: Boolean) {
        val prev = scanGuardState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        scanGuardState.value = on
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (on) MaxHzController.disableAutoSync()
                else MaxHzController.enableAutoSync()
            }
            if (r.code == 0) {
                MaxHzPrefs.setScanGuardEnabled(this@HomeActivity, on)
                Toast.makeText(
                    this@HomeActivity,
                    if (on) "限制扫描与同步已开启" else "限制扫描与同步已恢复默认",
                    Toast.LENGTH_SHORT
                ).show()
                updateMaxHzServiceState()
            } else {
                scanGuardState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, "限制扫描与同步切换失败：$err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleForceResizable(on: Boolean) {
        val prev = forceResizableState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        forceResizableState.value = on
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (on) MaxHzController.keepForceResizableActivities()
                else MaxHzController.restoreForceResizableActivities()
            }
            if (r.code == 0) {
                MaxHzPrefs.saveResult(
                    this@HomeActivity,
                    "force_resizable",
                    if (on) "1" else "0",
                    true
                )
                Toast.makeText(
                    this@HomeActivity,
                    if (on) "强制所有应用分屏/小窗已开启" else "强制所有应用分屏/小窗已恢复默认",
                    Toast.LENGTH_SHORT
                ).show()
                updateMaxHzServiceState()
            } else {
                forceResizableState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, "强制分屏切换失败：$err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleGameAutoSwitch(on: Boolean) {
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            gameAutoSwitchState.value = false
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (on && !AccessibilityHelper.isInstantSwitchEnabled(this)) {
            gameAutoSwitchState.value = false
            Toast.makeText(this, "无障碍服务尚未开启，请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            AccessibilityHelper.openInstantSwitchSettings(this)
            return
        }
        gameAutoSwitchState.value = on
        MaxHzPrefs.setGameAutoPerformanceEnabled(this, on)
        Toast.makeText(this, if (on) R.string.toast_game_auto_enabled else R.string.toast_game_auto_disabled, Toast.LENGTH_SHORT).show()
        updateMaxHzServiceState()
    }

    private fun handleToggleFpsMonitor(on: Boolean) {
        val prev = fpsMonitorState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            fpsMonitorState.value = false
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (on && !Settings.canDrawOverlays(this)) {
            fpsMonitorState.value = false
            Toast.makeText(this, "悬浮窗权限尚未授予，请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            return
        }
        fpsMonitorState.value = on
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (on) startFpsMonitorResult() else stopFpsMonitorResult()
            }
            if (r.code == 0) {
                Toast.makeText(this@HomeActivity, if (on) R.string.toast_fps_enabled else R.string.toast_fps_disabled, Toast.LENGTH_SHORT).show()
                updateMaxHzServiceState()
            } else {
                fpsMonitorState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, getString(R.string.toast_fps_toggle_failed, err), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleToggleOutdoorMode(on: Boolean) {
        val prev = outdoorModeState.value
        val hasPrivilege = isServiceRunning() || isRootAvailableState.value
        if (on && !hasPrivilege) {
            Toast.makeText(this, R.string.toast_service_not_running_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        outdoorModeState.value = on
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                MaxHzController.setOutdoorMode(on)
            }
            if (r.code == 0) {
                lastKnownOutdoorModeEnabled = on
                Toast.makeText(this@HomeActivity, if (on) R.string.toast_outdoor_enabled else R.string.toast_outdoor_disabled, Toast.LENGTH_SHORT).show()
                updateMaxHzServiceState()
            } else {
                outdoorModeState.value = prev
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, if (on) getString(R.string.toast_outdoor_enable_failed, err) else getString(R.string.toast_outdoor_disable_failed, err), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleRestoreDefaultsClick() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_defaults_dialog_title)
            .setMessage(R.string.restore_defaults_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_defaults_confirm) { _, _ ->
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        restoreAllSystemDefaults()
                    }
                    if (r.code == 0) {
                        gameTileModeState.intValue = 0
                        passThroughState.value = false
                        batteryProtectionState.value = false
                        gameAutoSwitchState.value = false
                        fpsMonitorState.value = false
                        outdoorModeState.value = false
                        tombstoneState.value = false
                        quickDozeState.value = false
                        autostartLimitState.value = false
                        locationThrottleState.value = false
                        scanGuardState.value = false
                        appLaunchBoostState.value = false
                        forceResizableState.value = false
                        Toast.makeText(this@HomeActivity, R.string.restore_defaults_success, Toast.LENGTH_LONG).show()
                    } else {
                        val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                        Toast.makeText(this@HomeActivity, getString(R.string.restore_defaults_failed, err), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .create()
        dialog.show()
        val isDark = isDarkMode()
        dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
        applyUnifiedDialogWindowStyle(dialog)
    }

    private fun handleCalibrateLtpoAdaptiveClick() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                MaxHzController.calibrateLtpoAdaptivePowerSaving()
            }
            if (r.code == 0) {
                Toast.makeText(this@HomeActivity, R.string.toast_calibrate_ltpo_success, Toast.LENGTH_LONG).show()
                updateMaxHzServiceState()
            } else {
                val err = r.err.ifBlank { r.out }.ifBlank { "(${r.code})" }
                Toast.makeText(this@HomeActivity, getString(R.string.toast_calibrate_ltpo_failed, err), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleOpenLaboratoryClick() {
        val isDark = isDarkMode()
        val dm = resources.displayMetrics
        val dialogWidth = minOf((dm.widthPixels * 0.92f).toInt(), dp(500))

        var dialog: android.app.Dialog? = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            background = unifiedDialogCardBackground(isDark)
            clipToOutline = true
            elevation = dp(16).toFloat()
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        // Title Row
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@HomeActivity).apply {
                text = getString(R.string.geek_lab_dialog_title)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColors.onSurface)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val closeBtn = TextView(this@HomeActivity).apply {
                text = "✕"
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColors.onSurfaceVariant)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                background = modalHeaderButtonBackground(isDark, 16)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    dialog?.dismiss()
                }
            }
            addView(closeBtn)
        }
        root.addView(titleRow)

        // Divider
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
            setBackgroundColor(if (isDark) 0x1FFFFFFF.toInt() else 0x12000000.toInt())
        }
        root.addView(divider)

        // Options List (Compact, beautiful, tightly wrapped, no emoji)
        data class LabOption(val title: String, val desc: String, val action: () -> Unit)
        val items = listOf(
            LabOption(getString(R.string.geek_lab_opt_unlock_restricted), "免除受限设置安全限制与无障碍权限保护") {
                dialog?.dismiss()
                handleUnlockRestrictedSettings()
            },
            LabOption(getString(R.string.geek_lab_opt_process_manager), "管理应用 Doze 待机与后台保活白名单") {
                dialog?.dismiss()
                pushDetail(getString(R.string.detail_title_doze_whitelist)) { dozeWhitelistSection() }
            },
            LabOption(getString(R.string.geek_lab_opt_system_diagnostics), "检测特权环境、SELinux 状态与系统参数") {
                dialog?.dismiss()
                handleSystemDiagnostics()
            }
        )

        items.forEachIndexed { index, item ->
            val title = item.title
            val desc = item.desc
            val action = item.action
            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dp(8)
                }
                setPadding(dp(16), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(themeColors.surfaceContainerHigh)
                }
                isClickable = true
                isFocusable = true
                installClickFeedback(this)
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                }

                // Text Container
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@HomeActivity).apply {
                        text = title
                        textSize = 14.5f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(themeColors.onSurface)
                        includeFontPadding = false
                    })
                    addView(TextView(this@HomeActivity).apply {
                        text = desc
                        textSize = 11.5f
                        setTextColor(themeColors.onSurfaceVariant)
                        includeFontPadding = false
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                // Chevron
                addView(TextView(this@HomeActivity).apply {
                    text = "›"
                    textSize = 18f
                    setTextColor(themeColors.onSurfaceVariant)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            }
            root.addView(itemView)
        }

        dialog = android.app.Dialog(this, R.style.ThemeOverlay_App_MaterialAlertDialog).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
        dialog.window?.let { win ->
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            win.setLayout(dialogWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setDimAmount(0.55f)
            win.setGravity(Gravity.CENTER)
            win.setWindowAnimations(android.R.style.Animation_Dialog)
        }
        dialog.show()
    }

    private fun handleUnlockRestrictedSettings() {
        val isDo = DhizukuHelper.isPrivileged(this)
        if (isDo) {
            try {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                val admin = DhizukuAdminReceiver.getComponentName(this)
                if (dpm != null && dpm.isDeviceOwnerApp(packageName)) {
                    dpm.setPermittedAccessibilityServices(admin, null)
                }
            } catch (_: Throwable) {}
        }
        val isPrivileged = runCatching { MaxHzShell.isReady() }.getOrDefault(false)
        if (isPrivileged) {
            lifecycleScope.launch {
                val pkg = packageName
                withContext(Dispatchers.IO) {
                    MaxHzShell.exec("cmd appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
                    MaxHzShell.exec("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
                    MaxHzShell.exec("cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore")
                    MaxHzShell.exec("cmd appops set $pkg RUN_IN_BACKGROUND allow")
                    MaxHzShell.exec("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
                    MaxHzShell.exec("dumpsys deviceidle whitelist +$pkg")
                    MaxHzShell.exec("am set-standby-bucket $pkg active")
                }
                Toast.makeText(this@HomeActivity, R.string.toast_restricted_settings_unlocked, Toast.LENGTH_LONG).show()
                AccessibilityHelper.openInstantSwitchSettings(this@HomeActivity)
            }
        } else {
            AccessibilityHelper.openAppDetailsForRestrictedSettings(this)
        }
    }

    private fun handleSystemDiagnostics() {
        lifecycleScope.launch {
            val status = homeModel.serviceStatus.value?.data
            val isRunning = status?.isRunning == true
            val isRoot = status?.uid == 0
            val apiVersion = status?.apiVersion ?: 0
            val isDo = DhizukuHelper.isPrivileged(this@HomeActivity)
            val isAccInstant = AccessibilityHelper.isInstantSwitchEnabled(this@HomeActivity)

            val isPrivileged = runCatching { MaxHzShell.isReady() }.getOrDefault(false)
            val bypassSupported = if (isPrivileged) {
                withContext(Dispatchers.IO) {
                    val res = MaxHzShell.exec("ls -l /sys/class/power_supply/battery/store_mode").out.trim()
                    res.isNotEmpty()
                }
            } else false

            val msg = buildString {
                append("【核心特权通道】\n")
                if (isRunning) {
                    val modeName = if (isRoot) "KernelSU / Root 极速通道 (UID 0)" else "无线调试 / Shizuku 通道 (UID 2000)"
                    append("运行状态: 已就绪 ($modeName, API v$apiVersion)\n")
                } else if (isDo) {
                    append("运行状态: Device Owner 直通模式\n")
                } else {
                    append("运行状态: 未就绪 (需启动 Shizuku 或授予 Root)\n")
                }

                append("\n【应用即时切换无障碍】\n")
                if (isAccInstant) {
                    append("服务状态: 已就绪 (前台 App 毫秒级锁帧与自适应切换)\n")
                } else {
                    append("服务状态: 未启用 (需在系统“无障碍”开启 MaxHz 即时切换)\n")
                }

                append("\n【硬件与驱动特性】\n")
                if (bypassSupported) {
                    append("旁路供电: 已支持 (内核 store_mode 节点直通)\n")
                } else {
                    append("旁路供电: ").append(if (isPrivileged) "未检测到内核节点" else "需特权通道检测").append("\n")
                }

                append("\n【当前运行设备】\n")
                append("机型: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n")
                append("系统: Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")")
            }

            val dialog = MaterialAlertDialogBuilder(this@HomeActivity)
                .setTitle(R.string.geek_lab_opt_system_diagnostics)
                .setMessage(msg)
                .setPositiveButton(R.string.dialog_close, null)
                .create()
            dialog.show()
            val isDark = isDarkMode()
            dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
            applyUnifiedDialogWindowStyle(dialog)
        }
    }

    private fun isServiceRunning(): Boolean {
        return homeModel.serviceStatus.value?.data?.isRunning == true
    }

    /**
     * iOS 26 纯 Kotlin DSL 体系构建的 Shizuku 核心控制中枢 (方案 B)
     */
    private fun renderShizukuDslPage() {
        pageContent.removeAllViews()

        val status = homeModel.serviceStatus.value?.data
        val grantedCount = appsModel.grantedCount.value?.data ?: 0
        val isRunning = status?.isRunning == true

        // 1. Large Title 顶栏视区已按用户要求整体移除（原「Shizuku」大字 +
        //    「系统级 ADB / Root 特权服务与跨应用权限中枢」副标题），
        //    页面直接从状态卡片开始。

        // 2. iOS 26 极致通透高晶液态玻璃 Hero 状态总览卡片
        pageContent.addSpacedView(createHeroStatusCard(status, grantedCount), gap)

        // 3. 权限与终端管理群组 (iOS Inset Grouped)
        pageContent.addSpacedView(
            entryGroupCard(
                "权限与终端管理",
                listOf(
                    EntryItem(
                        title = "已授权应用管理",
                        subtitle = if (isRunning) "查看与管理已获得特权授权的第三方应用 (${grantedCount} 个)" else "Shizuku 服务未运行，无法管理应用",
                        onClick = {
                            if (isRunning) {
                                startActivity(Intent(this, ApplicationManagementActivity::class.java))
                            } else {
                                Toast.makeText(this, "Shizuku 服务尚未运行，请先在下方启动", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ),
                    EntryItem(
                        title = "Shizuku 终端命令 (rish)",
                        subtitle = if (isRunning) "在 Android 终端命令行中直接调用 Root/ADB 权限" else "服务未运行，终端不可用",
                        onClick = {
                            if (isRunning) {
                                startActivity(Intent(this, ShellTutorialActivity::class.java))
                            } else {
                                Toast.makeText(this, "Shizuku 服务尚未运行", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                )
            ),
            gap
        )

        // 4. 服务启动引导群组
        pageContent.addSpacedView(
            entryGroupCard(
                "启动服务方式",
                listOf(
                    EntryItem(
                        title = "通过无线调试启动",
                        subtitle = "已配对后一键启动 Shizuku 服务 (免电脑，重启前有效)",
                        onClick = { handleWirelessAdbStartClick() }
                    ),
                    EntryItem(
                        title = "免输入自动配对助手",
                        subtitle = if (AdbPairingAccessibilityService.isEnabled(this))
                            "助手已就绪，点击前往开发者选项配对"
                        else
                            "一键前往开启系统无障碍，弹出配对码时秒级自动配对 (免手动输码)",
                        onClick = { handleAutoPairingAssistantClick() }
                    ),
                    EntryItem(
                        title = "配对教程与手动输入",
                        subtitle = "首次使用图文向导，或手动输入 6 位配对码 (Android 11+)",
                        onClick = { handleWirelessAdbPairClick() }
                    ),
                    EntryItem(
                        title = "通过连接电脑启动",
                        subtitle = "通过 USB 连接电脑执行 adb 脚本命令激活",
                        onClick = { handleStartAdbClick() }
                    ),
                    EntryItem(
                        title = "通过 Root 权限启动",
                        subtitle = "已获取 Root 权限设备一键免电脑启动",
                        onClick = { handleStartRootClick() }
                    )
                )
            ),
            gap
        )

        // 5. 帮助与官方文档群组
        pageContent.addSpacedView(
            entryGroupCard(
                "帮助与指南",
                listOf(
                    EntryItem(
                        title = "Shizuku 官方使用手册",
                        subtitle = "查看工作原理、使用限制与开发文档",
                        onClick = { CustomTabsHelper.launchUrlOrCopy(this, Helps.HOME.get()) }
                    ),
                    EntryItem(
                        title = "关于 Shizuku",
                        subtitle = "查看应用协议、版本信息与作者致谢",
                        onClick = { showAboutDialog() }
                    )
                )
            ),
            gap
        )

        // 底部悬浮胶囊 Dock 避让区
        pageContent.addView(Space(this).apply {
            minimumHeight = dp(80)
        })
    }

    /**
     * iOS 26 物理微透镜与蓝宝石倒角高光 Hero 状态总览卡片
     */
    private fun createHeroStatusCard(status: ServiceStatus?, grantedCount: Int): View {
        val isDark = isDarkMode()
        val ok = status?.isRunning == true
        val isRoot = status?.uid == 0
        val apiVersion = status?.apiVersion ?: 0
        val patchVersion = status?.patchVersion ?: 0

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@HomeActivity).apply {
                text = "服务运行状态"
                textSize = 12.5f
                setTextColor(themeColors.primary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(18), dp(14), dp(18), dp(6))
                includeFontPadding = false
            })
            addView(MaterialCardView(this@HomeActivity).apply {
                applyLiquidGlassCardStyle(this, CARD_RADIUS_DP)
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))

                    // 顶部状态行
                    addView(LinearLayout(this@HomeActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        // Apple Squircle 图标 (36x36dp 苹果翠绿 / 绯红微渐变底座)
                        val iconContainer = FrameLayout(this@HomeActivity).apply {
                            val iconBg = GradientDrawable().apply {
                                cornerRadius = dp(9).toFloat()
                                orientation = GradientDrawable.Orientation.TL_BR
                                colors = if (ok) {
                                    intArrayOf(0xFF34C759.toInt(), 0xFF249D44.toInt())
                                } else {
                                    intArrayOf(0xFFFF3B30.toInt(), 0xFFE02B20.toInt())
                                }
                            }
                            background = iconBg
                            val iconView = ImageView(this@HomeActivity).apply {
                                setImageDrawable(
                                    ContextCompat.getDrawable(
                                        this@HomeActivity,
                                        if (ok) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp
                                    )
                                )
                                setColorFilter(Color.WHITE)
                                val p = dp(6)
                                setPadding(p, p, p, p)
                            }
                            addView(iconView, FrameLayout.LayoutParams(dp(36), dp(36)))
                        }
                        addView(iconContainer, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                            rightMargin = dp(14)
                        })

                        // 标题与副标题
                        addView(LinearLayout(this@HomeActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(TextView(this@HomeActivity).apply {
                                text = if (ok) getString(R.string.home_status_service_is_running, "Shizuku")
                                else getString(R.string.home_status_service_not_running, "Shizuku")
                                textSize = 16.5f
                                setTextColor(if (isDark) 0xFFFFFFFF.toInt() else 0xFF1E293B.toInt())
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                includeFontPadding = false
                            })
                            addView(TextView(this@HomeActivity).apply {
                                text = if (ok) "已通过 ${if (isRoot) "Root" else "ADB"} 激活 · API v$apiVersion.$patchVersion"
                                else "Shizuku 服务未运行，请在下方选择启动方式"
                                textSize = 13f
                                setTextColor(if (isDark) 0xFFE2E8F0.toInt() else 0xFF475569.toInt())
                                includeFontPadding = false
                                setPadding(0, dp(3), 0, 0)
                            })
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                        // iOS 26 晶体实色高亮胶囊徽章
                        addView(TextView(this@HomeActivity).apply {
                            text = if (ok) (if (isRoot) "ROOT 运行中" else "ADB 运行中") else "未运行"
                            textSize = 11.5f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            val pillBg = GradientDrawable().apply {
                                cornerRadius = dp(14).toFloat()
                                setColor(if (ok) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
                            }
                            background = pillBg
                            setPadding(dp(10), dp(4), dp(10), dp(4))
                            includeFontPadding = false
                        })
                    })

                    // 1.5px 极细光感分隔线
                    addView(View(this@HomeActivity).apply {
                        setBackgroundColor(if (isDark) 0x33FFFFFF.toInt() else 0x1A000000.toInt())
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply {
                        topMargin = dp(14)
                        bottomMargin = dp(14)
                    })

                    // 3 个 iOS 26 晶体数据微磁贴 (Vision Pro Glass Tiles)
                    addView(LinearLayout(this@HomeActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        fun createTile(label: String, value: String, isAccent: Boolean): View {
                            val tileShape = GradientDrawable().apply {
                                cornerRadius = dp(12).toFloat()
                                if (isDark) {
                                    setColor(0x40000000.toInt())
                                    setStroke(dp(1), 0x66FFFFFF.toInt())
                                } else {
                                    // iOS 26 Liquid Glass: 半透明磨砂磁贴
                                    setColor(0x1A000000.toInt())   // ~10% 黑, 微妙深度
                                    setStroke(dp(1), 0x2A000000.toInt()) // 细描边
                                }
                            }
                            return LinearLayout(this@HomeActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                background = tileShape
                                setPadding(dp(10), dp(8), dp(10), dp(8))
                                addView(TextView(this@HomeActivity).apply {
                                    text = label
                                    textSize = 11f
                                    setTextColor(if (isDark) 0xFFE2E8F0.toInt() else 0xFF64748B.toInt())
                                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                                    includeFontPadding = false
                                })
                                addView(TextView(this@HomeActivity).apply {
                                    text = value
                                    textSize = 13.5f
                                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                                    setTextColor(
                                        // 绿=正常（语义色保留），另一档走主题强调色
                                        if (isAccent) (if (ok) 0xFF4ADE80.toInt() else themeColors.primary)
                                        else if (isDark) 0xFFFFFFFF.toInt() else 0xFF334155.toInt()
                                    )
                                    includeFontPadding = false
                                    setPadding(0, dp(2), 0, 0)
                                })
                            }
                        }

                        val tile1 = createTile("服务版本", if (ok) "v$apiVersion.$patchVersion.0" else "未加载", isAccent = ok)
                        val tile2 = createTile("运行权限", if (isRoot) "ROOT 模式" else if (ok) "ADB 模式" else "无", isAccent = ok)
                        val tile3 = createTile("已授权", "$grantedCount 个应用", isAccent = grantedCount > 0)

                        addView(tile1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
                        addView(tile2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
                        addView(tile3, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    })

                    // 快速动作胶囊按钮
                    if (ok && isRoot) {
                        addView(LinearLayout(this@HomeActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.END
                            setPadding(0, dp(14), 0, 0)

                            val stopBtn = MaterialButton(this@HomeActivity).apply {
                                text = "停止服务"
                                textSize = 13f
                                setTextColor(if (isDark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt())
                                background = LiquidGlassTheme.createButtonDrawable(dp(16).toFloat(), isDark, isDanger = true)
                                setOnClickListener {
                                    MaterialAlertDialogBuilder(this@HomeActivity)
                                        .setMessage(R.string.dialog_stop_message)
                                        .setPositiveButton(android.R.string.ok) { _, _ ->
                                            ShizukuSettings.setLastSettledStateRunning(false)
                                            try { Shizuku.exit() } catch (_: Throwable) {}
                                        }
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .showUnified()
                                }
                            }
                            val restartBtn = MaterialButton(this@HomeActivity).apply {
                                text = "重启服务"
                                textSize = 13f
                                setTextColor(Color.WHITE)
                                background = LiquidGlassTheme.createButtonDrawable(dp(16).toFloat(), isDark, isPrimary = true)
                                setOnClickListener {
                                    handleStartRootClick()
                                }
                            }

                            addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { rightMargin = dp(8) })
                            addView(restartBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
                        })
                    } else if (!ok && (EnvironmentUtils.isRooted() || EnvironmentUtils.isRootManagerInstalled(this@HomeActivity))) {
                        addView(LinearLayout(this@HomeActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.END
                            setPadding(0, dp(14), 0, 0)

                            val rootStartBtn = MaterialButton(this@HomeActivity).apply {
                                text = "通过 Root 快速启动"
                                textSize = 13f
                                setTextColor(Color.WHITE)
                                background = LiquidGlassTheme.createButtonDrawable(dp(16).toFloat(), isDark, isPrimary = true)
                                setOnClickListener {
                                    handleStartRootClick()
                                }
                            }
                            addView(rootStartBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
                        })
                    }
                })
            })
        }
    }

    /**
     * 通过无线调试「启动」服务：Android 11+ 弹 AdbDialogFragment（自动发现 ADB 端口并启动，
     * 端口可用时点端口号也可直接启动）；旧版本走 TCP 端口直连。
     */
    private fun handleWirelessAdbStartClick() {
        if (rikka.shizuku.Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 服务已在运行中", Toast.LENGTH_SHORT).show()
            return
        }

        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(contentResolver, "adb_allowed_connection_time", 0L)
                Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
            }
        }

        if (!EnvironmentUtils.isAdbEnabled(this)) {
            WadbEnableUsbDebuggingDialogFragment().show(supportFragmentManager)
            return
        }

        lifecycleScope.launch {
            val detectedPort = activeAdbPortState.intValue
            val isValidDetectedPort = detectedPort > 0
            val activePort = withContext(Dispatchers.IO) {
                if (isValidDetectedPort && AdbPortProber.isPortOpen(detectedPort, 200)) {
                    detectedPort
                } else {
                    AdbPortProber.findActiveLoopbackPort(this@HomeActivity, 200)
                }
            }

            if (activePort > 0) {
                val intent = Intent(this@HomeActivity, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                    putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                    putExtra(StarterActivity.EXTRA_PORT, activePort)
                }
                startActivity(intent)
                return@launch
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AdbDialogFragment().show(supportFragmentManager)
                return@launch
            }

            val port = EnvironmentUtils.getAdbTcpPort().takeIf { it > 0 } ?: 5555
            val intent = Intent(this@HomeActivity, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                putExtra(StarterActivity.EXTRA_PORT, port)
            }
            startActivity(intent)
        }
    }

    /**
     * 通过无线调试「配对」：Android 11+ 若处于副屏/多屏环境直接弹配对框，否则进入配对引导页
     * （引导页内 manualPairing 按钮再弹 AdbPairDialogFragment 输入配对码）。
     */
    private fun handleWirelessAdbPairClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasExternalDisplay = (display?.displayId ?: -1) > 0
            if (hasExternalDisplay) {
                AdbPairDialogFragment().show(supportFragmentManager)
            } else {
                startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
            }
            return
        }
        Toast.makeText(this, R.string.toast_wireless_pairing_requires_android_11, Toast.LENGTH_SHORT).show()
    }

    private fun handleAutoPairingAssistantClick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, R.string.toast_wireless_pairing_requires_android_11, Toast.LENGTH_SHORT).show()
            return
        }
        AdbPairingDetector.request()
        if (AdbPairingAccessibilityService.isEnabled(this)) {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            }
            try {
                startActivity(intent)
                Toast.makeText(this, R.string.toast_auto_pairing_ready, Toast.LENGTH_LONG).show()
            } catch (e: ActivityNotFoundException) {
                AdbPairingDetector.cancel()
                Toast.makeText(this, R.string.toast_dev_settings_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            AccessibilityHelper.openPairingAssistantSettings(this)
        }
    }

    private fun handleStartAdbClick() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_adb_button_view_command)
            .setMessage(
                HtmlCompat.fromHtml(
                    getString(R.string.home_adb_dialog_view_command_message, Starter.adbCommand)
                )
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (ClipboardUtils.put(this, Starter.adbCommand)) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_copied_to_clipboard, Starter.adbCommand),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.home_adb_dialog_view_command_button_send) { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.home_adb_dialog_view_command_button_send)))
            }
            .create()
        dialog.show()
        val isDark = isDarkMode()
        dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
        applyUnifiedDialogWindowStyle(dialog)
    }

    private fun handleStartRootClick() {
        val intent = Intent(this, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, true)
        }
        startActivity(intent)
    }

    private fun showAboutDialog() {
        val binding = AboutDialogBinding.inflate(LayoutInflater.from(this), null, false)
        binding.sourceCode.movementMethod = LinkMovementMethod.getInstance()
        binding.sourceCode.text = getString(
            R.string.about_view_source_code,
            "<b><a href=\"https://github.com/RikkaApps/Shizuku\">GitHub</a></b>"
        ).toHtml()
        binding.icon.setImageBitmap(
            AppIconCache.getOrLoadBitmap(
                this,
                applicationInfo,
                Process.myUid() / 100000,
                resources.getDimensionPixelOffset(R.dimen.default_app_icon_size)
            )
        )
        binding.versionName.text = packageManager.getPackageInfo(packageName, 0).versionName
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .create()
        dialog.show()
        val isDark = isDarkMode()
        dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
        applyUnifiedDialogWindowStyle(dialog)
    }

    override fun pushDetail(
        title: String,
        keepScroll: Boolean,
        skipScrollTop: Boolean,
        targetDiagnosticKey: String?,
        builder: () -> View
    ) {
        super.pushDetail(title, keepScroll, skipScrollTop, targetDiagnosticKey, builder)
    }

    override fun popDetail(): Boolean {
        val popped = super.popDetail()
        if (popped) {
            syncSchemeAStates()
        }
        return popped
    }

    override fun onDetailModalDismissed() {
        super.onDetailModalDismissed()
        syncSchemeAStates()
    }

    private fun installUnifiedBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popDetail()) return
                if (!isShizukuTabActive) {
                    showShizukuTab()
                    return
                }
                finish()
            }
        })
    }

    override fun finishToHome() {
        showShizukuTab()
    }

    override fun onResume() {
        super.onResume()
        AccessibilityHelper.ensureAccessibilityAlive(this)
        moe.shizuku.manager.maxhz.GlassThemeState.sync()
        AdbPairingDetector.cancelIfReturnedToApp()
        lastRecordedThemeKey = computeUserThemeKey()
        checkServerStatus()
        syncSchemeAStates()
    }

    private fun checkServerStatus() {
        homeModel.reload()
        isAutoPairingState.value = AdbPairingAccessibilityService.isEnabled(this)
        isDeviceOwnerState.value = DhizukuHelper.isPrivileged(this)
        if (isDeviceOwnerState.value) {
            DhizukuHelper.ensureSelfPermissions(this)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val rootAvail = MaxHzShell.isRootAvailable()
            val wifi = HotspotHelper.isWifiConnected(this@HomeActivity)
            val hotspot = HotspotHelper.isHotspotActive(this@HomeActivity)
            val port = AdbPortProber.findActiveLoopbackPort(this@HomeActivity)
            withContext(Dispatchers.Main) {
                isRootAvailableState.value = rootAvail
                isWifiConnectedState.value = wifi
                isHotspotActiveState.value = hotspot
                activeAdbPortState.intValue = port
                if (!Shizuku.pingBinder() &&
                    !autoRestartAttempted &&
                    port in 1..65535 &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB &&
                    checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                ) {
                    autoRestartAttempted = true
                    moe.shizuku.manager.adb.AdbStartWorker.enqueue(this@HomeActivity)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (popDetail()) return true
            if (!isShizukuTabActive) {
                showShizukuTab()
                return true
            }
        }
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            R.id.action_stop -> {
                if (!Shizuku.pingBinder()) {
                    return true
                }
                val dialog = MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.dialog_stop_message)
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                        ShizukuSettings.setLastSettledStateRunning(false)
                        try {
                            Shizuku.exit()
                        } catch (_: Throwable) {
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                dialog.show()
                val isDark = isDarkMode()
                dialog.window?.setBackgroundDrawable(unifiedDialogCardBackground(isDark))
                applyUnifiedDialogWindowStyle(dialog)
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
