package moe.shizuku.manager.home

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.R
import moe.shizuku.manager.maxhz.BackdropProvider
import moe.shizuku.manager.maxhz.GLASS_DOCK_CLEARANCE_DP
import moe.shizuku.manager.maxhz.GlassButtonStyle
import moe.shizuku.manager.maxhz.GlassCard
import moe.shizuku.manager.maxhz.GlassDockNav
import moe.shizuku.manager.maxhz.GlassLight
import moe.shizuku.manager.maxhz.GlassPillButton
import moe.shizuku.manager.maxhz.IconBadge
import moe.shizuku.manager.maxhz.MaxHzPrefs
import moe.shizuku.manager.maxhz.MaxHzShell
import moe.shizuku.manager.maxhz.TitaniumDark
import moe.shizuku.manager.maxhz.glassBackgroundBrush
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.utils.Contrast
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog


/**
 * Miuix 风格全面重构的主控制台：
 *
 * - 统一采用 `top.yukonga.miuix.kmp` 组件库（`Scaffold`、`Card`、`SmallTitle`、`SwitchPreference`、
 *   `ArrowPreference`、`WindowDropdownPreference`、`WindowDialog`、`TextButton`、`TextField`、`overScrollVertical`）
 * - 所有核心弹窗（充电上限保护、电池运行信息与健康度、停止服务确认）均使用 Miuix 顶层窗口级 `WindowDialog` 弹簧弹窗渲染，彻底杜绝底部悬浮栏遮挡
 */

/** 首页所有交互回调（实现方为 HomeActivity） */
class HomeActions(
    val onAuthorizedAppsClick: () -> Unit,
    val onRishClick: () -> Unit,
    val onWirelessStartClick: () -> Unit,
    val onWirelessPairClick: () -> Unit,
    val onAutoPairingAssistantClick: () -> Unit = {},
    val onOpenDevSettingsClick: () -> Unit = {},
    val onOpenDisableAppCompatibilityPoliciesClick: () -> Unit = {},
    val onHotspotClick: () -> Unit = {},
    val onDhizukuClick: () -> Unit = {},
    val onStartAdbClick: () -> Unit,
    val onStartRootClick: () -> Unit = {},
    val onHelpClick: () -> Unit,
    val onAboutClick: () -> Unit,
    val onStopServiceClick: () -> Unit,
    val onConfirmStopService: () -> Unit = {},
    val onModeChange: (Int) -> Unit = {},
    val onTogglePassThrough: (Boolean) -> Unit = {},
    val onToggleBatteryProtection: (Boolean) -> Unit = {},
    val onOpenBatteryProtectionConfigClick: () -> Unit = {},
    val onApplyBatteryProtectionConfig: (limit: Int, recharge: Int, onResult: (Boolean, String) -> Unit) -> Unit = { _, _, _ -> },
    val onRestoreBatteryProtectionDefault: (onResult: (Boolean, String) -> Unit) -> Unit = { _ -> },
    val onToggleGameAutoSwitch: (Boolean) -> Unit = {},
    val onToggleFpsMonitor: (Boolean) -> Unit = {},
    val onToggleOutdoorMode: (Boolean) -> Unit = {},
    val onToggleTombstone: (Boolean) -> Unit = {},
    val onToggleQuickDoze: (Boolean) -> Unit = {},
    val onOpenQuickDozeConfigClick: () -> Unit = {},
    val onToggleAutostartLimit: (Boolean) -> Unit = {},
    val onToggleLocationThrottle: (Boolean) -> Unit = {},
    val onToggleScanGuard: (Boolean) -> Unit = {},
    val onOpenAppLaunchBoostClick: () -> Unit = {},
    val onToggleForceResizable: (Boolean) -> Unit = {},
    val onBatteryInfoClick: () -> Unit = {},
    val onFetchBatteryInfo: (onResult: (String) -> Unit) -> Unit = { _ -> },
    val onRestoreDefaultsClick: () -> Unit = {},
    val onOpenLaboratoryClick: () -> Unit = {},
    val onOpenGameAutoConfigClick: () -> Unit = {},
    val onSelectAppForAppRule: ((onSelected: (packageName: String, label: String) -> Unit) -> Unit) = {},
    val onSaveAppRule: (MaxHzPrefs.AppRefreshRule) -> Unit = {},
    val onDeleteAppRule: (packageName: String) -> Unit = {},
    val onClearAppRules: () -> Unit = {},
    val onOpenAccessibilityClick: () -> Unit = {},
    val onDisableAccessibilityClick: () -> Unit = {},
    val onNightWakeupGuardClick: () -> Unit = {},
    val onCalibrateLtpoAdaptiveClick: () -> Unit = {},
    val onSelectAppForGameAuto: ((onSelected: (packageName: String, label: String) -> Unit) -> Unit) = {},
    val onStaticGameIntervention: (packageName: String, enable: Boolean) -> Unit = { _, _ -> },
    val onApplyQuickDozeConfig: (enabled: Boolean, minutes: Int, noMaintenance: Boolean, onResult: (Boolean, String) -> Unit) -> Unit = { _, _, _, _ -> },
    val onReadQuickDozeStatus: (onResult: (String) -> Unit) -> Unit = { _ -> },
    val onRunAppLaunchBoostAction: (actionType: Int, onResult: (Boolean, String) -> Unit) -> Unit = { _, _ -> },
    val onOpenCustomAotPickerClick: () -> Unit = {},
    val onSyncStates: () -> Unit = {},
    val onExecuteRestoreAllDefaults: (onResult: (Boolean, String) -> Unit) -> Unit = { _ -> }
)

@Composable
fun ShizukuHomeScreen(
    isDark: Boolean,
    currentTab: Int = 0, // 0: 特权服务, 1: 性能硬件, 2: 应用规则
    status: ServiceStatus?,
    grantedCount: Int,
    primaryColor: Color,
    topInsetPx: Int,
    bottomInsetPx: Int,
    fullHeightPx: Float,
    isRootAvailable: Boolean = false,
    isAutoPairingEnabled: Boolean = false,
    activePort: Int = -1,
    isWifiConnected: Boolean = true,
    isHotspotActive: Boolean = false,
    isDeviceOwner: Boolean = false,
    gameTileMode: Int = 0,
    isPassThroughActive: Boolean = false,
    isBatteryProtectionActive: Boolean = false,
    batteryProtectionLimit: Int = 80,
    batteryProtectionRecharge: Int = 75,
    isGameAutoSwitchActive: Boolean = false,
    gameAutoPackageCount: Int = 0,
    isFpsMonitorActive: Boolean = false,
    isOutdoorModeActive: Boolean = false,
    isTombstoneActive: Boolean = false,
    isQuickDozeActive: Boolean = false,
    isAutostartLimitActive: Boolean = false,
    isLocationThrottleActive: Boolean = false,
    isScanGuardActive: Boolean = false,
    isAppLaunchBoostActive: Boolean = false,
    isForceResizableActive: Boolean = false,
    flatBackground: Boolean = false,
    wallpaperUri: Uri? = null,
    wallpaperFit: String = "crop",
    isBlackNightTheme: Boolean = false,
    appRules: List<MaxHzPrefs.AppRefreshRule> = emptyList(),
    isInstantSwitchActive: Boolean = false,
    supportedRefreshRates: List<Int> = listOf(10, 24, 30, 48, 60, 90, 120),
    onTabClick: (Int) -> Unit,
    actions: HomeActions
) {
    val density = LocalDensity.current
    val bottomInsetDp = with(density) { bottomInsetPx.toDp() }
    val ok = status?.isRunning == true

    val effectiveDark = isDark || isBlackNightTheme
    val bgBrush = glassBackgroundBrush(isDark = isDark, isBlackNightTheme = isBlackNightTheme)

    val shizukuListState = rememberLazyListState()
    val perfListState = rememberLazyListState()
    val rulesListState = rememberLazyListState()

    // Miuix 原生 OverlayDialog 状态
    var showStopConfirmDialog by remember { mutableStateOf(false) }
    var showBatteryProtectionDialog by remember { mutableStateOf(false) }
    var showBatteryInfoDialog by remember { mutableStateOf(false) }
    var showGameAutoDialog by remember { mutableStateOf(false) }
    var showQuickDozeDialog by remember { mutableStateOf(false) }
    var showAppLaunchBoostDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var autoUpdateReleaseInfo by remember { mutableStateOf<moe.shizuku.manager.update.AppUpdater.ReleaseInfo?>(null) }

    LaunchedEffect(Unit) {
        if (moe.shizuku.manager.update.AppUpdater.shouldPerformAutoCheck()) {
            moe.shizuku.manager.update.AppUpdater.checkForUpdate().onSuccess { info ->
                moe.shizuku.manager.update.AppUpdater.markAutoCheckPerformed()
                if (info != null) {
                    autoUpdateReleaseInfo = info
                }
            }
        }
    }

    if (autoUpdateReleaseInfo != null) {
        moe.shizuku.manager.update.AppUpdateDialog(
            releaseInfo = autoUpdateReleaseInfo,
            isDark = effectiveDark,
            primaryColor = primaryColor,
            onDismiss = { autoUpdateReleaseInfo = null }
        )
    }

    BackdropProvider(
        modifier = Modifier.fillMaxSize(),
        overlay = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                GlassDockNav(
                    isDark = isDark,
                    isBlackNightTheme = isBlackNightTheme,
                    selectedIndex = currentTab,
                    bottomInsetDp = bottomInsetDp,
                    onTabClick = onTabClick
                )
            }
        }
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { _ ->
        when (currentTab) {
            0 -> {
                // ==========================================================
                // Tab 0：【特权服务】（Miuix 风格核心控制台）
                // ==========================================================
                var tab0HeaderExpanded by remember { mutableStateOf(false) }
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                    state = shizukuListState,
                    contentPadding = PaddingValues(
                        top = with(density) { topInsetPx.toDp() },
                        start = 14.dp,
                        end = 14.dp,
                        bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "section-adb", contentType = "header") {
                        SectionHeader(
                            title = "Shizuku Sam",
                            subtitle = if (ok) stringResource(R.string.home_header_shizuku_running_subtitle)
                            else stringResource(R.string.home_header_shizuku_stopped_subtitle),
                            isDark = effectiveDark,
                            primaryColor = primaryColor,
                            expanded = tab0HeaderExpanded,
                            onToggle = { tab0HeaderExpanded = !tab0HeaderExpanded }
                        )
                    }

                    item(key = "hero", contentType = "hero") {
                        HeroCard(
                            isDark = effectiveDark,
                            isBlackNightTheme = isBlackNightTheme,
                            primaryColor = primaryColor,
                            status = status,
                            grantedCount = grantedCount,
                            activePort = activePort,
                            isWifiConnected = isWifiConnected,
                            isDeviceOwner = isDeviceOwner,
                            groupExpanded = tab0HeaderExpanded,
                            actions = actions,
                            onRequestStopDialog = { showStopConfirmDialog = true }
                        )
                    }

                    if (!ok) {
                        item(key = "adb-pairing", contentType = "pairing") {
                            AdbPairingHubCard(
                                isDark = effectiveDark,
                                isBlackNightTheme = isBlackNightTheme,
                                primaryColor = primaryColor,
                                isAutoPairingEnabled = isAutoPairingEnabled,
                                actions = actions
                            )
                        }

                        item(key = "adb-alt", contentType = "alt") {
                            PrivilegeLaunchMethodsCard(
                                isDark = effectiveDark,
                                isBlackNightTheme = isBlackNightTheme,
                                primaryColor = primaryColor,
                                isDeviceOwner = isDeviceOwner,
                                actions = actions
                            )
                        }
                    } else {
                        item(key = "adb-running-tools", contentType = "tools") {
                            AdbRunningToolsCard(
                                isDark = effectiveDark,
                                isBlackNightTheme = isBlackNightTheme,
                                primaryColor = primaryColor,
                                isRoot = status?.uid == 0,
                                isDeviceOwner = isDeviceOwner,
                                actions = actions
                            )
                        }
                    }

                    item(key = "dockClearance-0", contentType = "spacer") {
                        Spacer(Modifier.height(78.dp))
                    }
                }
            }
            1 -> {
                // ==========================================================
                // Tab 1：【性能硬件】（Miuix 风格三星调度与电池/后台中枢）
                // ==========================================================
                var tab1HeaderExpanded by remember { mutableStateOf(false) }
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                    state = perfListState,
                    contentPadding = PaddingValues(
                        top = with(density) { topInsetPx.toDp() },
                        start = 14.dp,
                        end = 14.dp,
                        bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "section-aux", contentType = "header") {
                        val isRootEnv = isRootAvailable || (status?.uid == 0)
                        SectionHeader(
                            title = stringResource(R.string.home_header_hardware_title),
                            subtitle = if (isRootEnv) "Root 硬件直通 · " + stringResource(R.string.home_header_hardware_subtitle)
                            else "Shizuku 模式 · " + stringResource(R.string.home_header_hardware_subtitle),
                            isDark = effectiveDark,
                            primaryColor = primaryColor,
                            expanded = tab1HeaderExpanded,
                            onToggle = { tab1HeaderExpanded = !tab1HeaderExpanded }
                        )
                    }

                    item(key = "perf-mode", contentType = "perf") {
                        PerformanceModeCard(
                            isDark = effectiveDark,
                            isBlackNightTheme = isBlackNightTheme,
                            primaryColor = primaryColor,
                            currentMode = gameTileMode,
                            onModeChange = actions.onModeChange
                        )
                    }

                    item(key = "quick-switches", contentType = "switches") {
                        QuickSwitchesCard(
                            isDark = effectiveDark,
                            isBlackNightTheme = isBlackNightTheme,
                            primaryColor = primaryColor,
                            isRootAvailable = isRootAvailable || (status?.uid == 0),
                            isPassThroughActive = isPassThroughActive,
                            isBatteryProtectionActive = isBatteryProtectionActive,
                            batteryProtectionLimit = batteryProtectionLimit,
                            batteryProtectionRecharge = batteryProtectionRecharge,
                            isGameAutoSwitchActive = isGameAutoSwitchActive,
                            gameAutoPackageCount = gameAutoPackageCount,
                            isFpsMonitorActive = isFpsMonitorActive,
                            isOutdoorModeActive = isOutdoorModeActive,
                            isQuickDozeActive = isQuickDozeActive,
                            isAppLaunchBoostActive = isAppLaunchBoostActive,
                            holdDownBatteryProtection = showBatteryProtectionDialog,
                            onTogglePassThrough = actions.onTogglePassThrough,
                            onToggleBatteryProtection = actions.onToggleBatteryProtection,
                            onOpenBatteryProtectionConfigClick = { showBatteryProtectionDialog = true },
                            onToggleGameAutoSwitch = actions.onToggleGameAutoSwitch,
                            onToggleFpsMonitor = actions.onToggleFpsMonitor,
                            onToggleOutdoorMode = actions.onToggleOutdoorMode,
                            onToggleQuickDoze = actions.onToggleQuickDoze,
                            onOpenGameAutoConfigClick = { showGameAutoDialog = true },
                            onOpenBatteryInfoDialog = { showBatteryInfoDialog = true },
                            onOpenAppLaunchBoostClick = { showAppLaunchBoostDialog = true }
                        )
                    }

                    item(key = "background-power", contentType = "background") {
                        BackgroundPowerCard(
                            isDark = effectiveDark,
                            isBlackNightTheme = isBlackNightTheme,
                            primaryColor = primaryColor,
                            isTombstoneActive = isTombstoneActive,
                            isQuickDozeActive = isQuickDozeActive,
                            isAutostartLimitActive = isAutostartLimitActive,
                            isLocationThrottleActive = isLocationThrottleActive,
                            isScanGuardActive = isScanGuardActive,
                            onToggleTombstone = actions.onToggleTombstone,
                            onToggleQuickDoze = actions.onToggleQuickDoze,
                            onOpenQuickDozeConfigClick = { showQuickDozeDialog = true },
                            onToggleAutostartLimit = actions.onToggleAutostartLimit,
                            onToggleLocationThrottle = actions.onToggleLocationThrottle,
                            onToggleScanGuard = actions.onToggleScanGuard
                        )
                    }

                    item(key = "quick-actions", contentType = "actions") {
                        QuickActionsCard(
                            isDark = effectiveDark,
                            isBlackNightTheme = isBlackNightTheme,
                            primaryColor = primaryColor,
                            isAppLaunchBoostActive = isAppLaunchBoostActive,
                            isForceResizableActive = isForceResizableActive,
                            holdDownBatteryInfo = showBatteryInfoDialog,
                            onOpenBatteryInfoDialog = { showBatteryInfoDialog = true },
                            onOpenDiagnosticsDialog = { showDiagnosticsDialog = true },
                            actions = actions
                        )
                    }

                    item(key = "dockClearance-1", contentType = "spacer") {
                        Spacer(Modifier.height(78.dp))
                    }
                }
            }
            2 -> {
                // ==========================================================
                // Tab 2：【应用规则】（Miuix 下拉菜单与分应用刷新率规则）
                // ==========================================================
                AppRulesTabContent(
                    isDark = effectiveDark,
                    isBlackNightTheme = isBlackNightTheme,
                    primaryColor = primaryColor,
                    topInsetPx = topInsetPx,
                    bottomInsetDp = bottomInsetDp,
                    rulesListState = rulesListState,
                    appRules = appRules,
                    isInstantSwitchActive = isInstantSwitchActive,
                    supportedRefreshRates = supportedRefreshRates,
                    actions = actions
                )
            }
        }

        // ==========================================================
        // Miuix WindowDialog 1：停止 Shizuku 服务确认弹窗（独立顶层 Window，不惧底部悬浮栏遮挡）
        // ==========================================================
        CenteredAppDialog(
            show = showStopConfirmDialog,
            title = "停止 Shizuku 服务",
            summary = stringResource(R.string.dialog_stop_message),
            onDismissRequest = { showStopConfirmDialog = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassPillButton(
                    text = stringResource(android.R.string.cancel),
                    isDark = effectiveDark,
                    style = GlassButtonStyle.Regular,
                    onClick = { showStopConfirmDialog = false },
                    modifier = Modifier.weight(1f)
                )
                GlassPillButton(
                    text = stringResource(android.R.string.ok),
                    isDark = effectiveDark,
                    style = GlassButtonStyle.Danger,
                    onClick = {
                        showStopConfirmDialog = false
                        actions.onConfirmStopService()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ==========================================================
        // Miuix WindowDialog 2：充电上限保护弹窗
        // ==========================================================
        MiuixBatteryProtectionDialog(
            show = showBatteryProtectionDialog,
            isDark = effectiveDark,
            primaryColor = primaryColor,
            isActive = isBatteryProtectionActive,
            initialLimit = batteryProtectionLimit,
            initialRecharge = batteryProtectionRecharge,
            onDismiss = { showBatteryProtectionDialog = false },
            onApply = { limit, recharge, onResult ->
                actions.onApplyBatteryProtectionConfig(limit, recharge, onResult)
            },
            onRestoreDefault = { onResult ->
                actions.onRestoreBatteryProtectionDefault(onResult)
            }
        )

        // ==========================================================
        // Miuix WindowDialog 3：电池运行信息与健康度弹窗
        // ==========================================================
        MiuixBatteryInfoDialog(
            show = showBatteryInfoDialog,
            isDark = effectiveDark,
            primaryColor = primaryColor,
            onDismiss = { showBatteryInfoDialog = false },
            onFetchBatteryInfo = actions.onFetchBatteryInfo
        )

        // ==========================================================
        // Miuix WindowDialog 4：游戏自动高刷加速配置弹窗（替代旧版 pushDetail 二级页）
        // ==========================================================
        MiuixGameAutoConfigDialog(
            show = showGameAutoDialog,
            isDark = effectiveDark,
            primaryColor = primaryColor,
            isInstantSwitchActive = isInstantSwitchActive,
            actions = actions,
            onDismiss = { showGameAutoDialog = false }
        )

        // ==========================================================
        // Miuix WindowDialog 5：熄屏深度休眠参数弹窗（替代旧版 pushDetail 二级页）
        // ==========================================================
        MiuixQuickDozeConfigDialog(
            show = showQuickDozeDialog,
            isDark = effectiveDark,
            isActive = isQuickDozeActive,
            actions = actions,
            onDismiss = { showQuickDozeDialog = false }
        )

        // ==========================================================
        // Miuix WindowDialog 6：应用冷启动加速与清理弹窗（替代旧版 pushDetail 二级页）
        // ==========================================================
        MiuixAppLaunchBoostDialog(
            show = showAppLaunchBoostDialog,
            isDark = effectiveDark,
            isActive = isAppLaunchBoostActive,
            actions = actions,
            onDismiss = { showAppLaunchBoostDialog = false }
        )

        // ==========================================================
        // Miuix WindowDialog 7：运行状态与覆盖项诊断中心弹窗
        // ==========================================================
        MiuixDiagnosticsCenterDialog(
            show = showDiagnosticsDialog,
            isDark = effectiveDark,
            primaryColor = primaryColor,
            gameTileMode = gameTileMode,
            isPassThroughActive = isPassThroughActive,
            isBatteryProtectionActive = isBatteryProtectionActive,
            batteryProtectionLimit = batteryProtectionLimit,
            batteryProtectionRecharge = batteryProtectionRecharge,
            isGameAutoSwitchActive = isGameAutoSwitchActive,
            gameAutoPackageCount = gameAutoPackageCount,
            isFpsMonitorActive = isFpsMonitorActive,
            isOutdoorModeActive = isOutdoorModeActive,
            isTombstoneActive = isTombstoneActive,
            isQuickDozeActive = isQuickDozeActive,
            isAutostartLimitActive = isAutostartLimitActive,
            isLocationThrottleActive = isLocationThrottleActive,
            isScanGuardActive = isScanGuardActive,
            isAppLaunchBoostActive = isAppLaunchBoostActive,
            isForceResizableActive = isForceResizableActive,
            isInstantSwitchActive = isInstantSwitchActive,
            appRulesCount = appRules.size,
            actions = actions,
            onDismiss = { showDiagnosticsDialog = false }
        )
        }
    }
    }
}

// ==================================================================
// 方案 B 全局公共手势与分组标题组件
// ==================================================================

/**
 * 方案 B 核心手势拦截：
 * 在 PointerEventPass.Initial 监听原地长按（不阻碍普通点击、Switch 切换、下拉弹窗与列表滑动）。
 * 一旦达到长按阈值且手指未滑出 touchSlop，触发震动与回调，并在 Initial 阶段消费后续抬手事件，
 * 彻底防止长按松手时误触整行点击或开关切换。
 */
fun Modifier.onLongPressToggle(
    haptic: HapticFeedback,
    onLongPress: () -> Unit
): Modifier = this.pointerInput(onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val touchSlop = viewConfiguration.touchSlop
        val startPos = down.position

        val releasedOrMoved = withTimeoutOrNull(longPressTimeout) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!change.pressed) {
                    return@withTimeoutOrNull true
                }
                if ((change.position - startPos).getDistance() > touchSlop) {
                    return@withTimeoutOrNull true
                }
            }
        }

        if (releasedOrMoved == null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongPress()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/**
 * 方案 B 分组标题栏：左侧显示分组标题，右侧提供轻量「显示说明 / 隐藏说明」一键展开/收起按钮。
 */
@Composable
fun GroupHeaderWithToggle(
    text: String,
    isDark: Boolean,
    primaryColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        val pillBg = if (expanded) {
            if (isDark) primaryColor.copy(alpha = 0.22f) else primaryColor.copy(alpha = 0.14f)
        } else {
            Color.Transparent
        }
        val pillFg = if (expanded) {
            primaryColor
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.85f)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(pillBg)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle()
                }
                .padding(horizontal = 8.dp, vertical = 2.5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (expanded) "隐藏说明" else "显示说明",
                fontSize = 11.5.sp,
                fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Medium,
                color = pillFg
            )
        }
    }
}

/**
 * Miuix 二级/三级页面统一顶栏：
 * - 左侧 Miuix 圆角返回按钮
 * - 中间大标题（支持长按展开/收起副标题）
 * - 右侧可选「显示说明 / 隐藏说明」胶囊按钮（用于单分组二级页全局切换）
 */
@Composable
fun MiuixSubPageTopBar(
    title: String,
    subtitle: String? = null,
    isDark: Boolean,
    primaryColor: Color,
    expanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
    backSymbol: String = "‹",
    onBackClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var localToggled by remember(expanded) { mutableStateOf(false) }
    val showSub = (expanded ?: false) xor localToggled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressToggle(haptic) {
                if (onToggleExpand != null) onToggleExpand() else localToggled = !localToggled
            }
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) TitaniumDark.Card else GlassLight.Card)
                    .border(1.dp, if (isDark) TitaniumDark.Hairline else GlassLight.Hairline, RoundedCornerShape(12.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBackClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = backSymbol,
                    fontSize = if (backSymbol == "‹") 24.sp else 16.sp,
                    fontWeight = if (backSymbol == "‹") FontWeight.Light else FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onToggleExpand != null && expanded != null) {
                val pillBg = if (expanded) {
                    if (isDark) primaryColor.copy(alpha = 0.22f) else primaryColor.copy(alpha = 0.14f)
                } else {
                    if (isDark) TitaniumDark.Card else GlassLight.Card
                }
                val pillFg = if (expanded) {
                    primaryColor
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(pillBg)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleExpand()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (expanded) "隐藏说明" else "显示说明",
                        fontSize = 11.5.sp,
                        fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Medium,
                        color = pillFg
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = showSub && !subtitle.isNullOrBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 52.dp, top = 4.dp)
                        .clickable {
                            if (onToggleExpand != null) onToggleExpand() else localToggled = !localToggled
                        }
                )
            }
        }
    }
}

// ==================================================================
// Miuix 弹窗组件：充电上限保护 & 电池运行信息与健康度
// ==================================================================

@Composable
private fun MiuixBatteryProtectionDialog(
    show: Boolean,
    isDark: Boolean,
    primaryColor: Color,
    isActive: Boolean,
    initialLimit: Int,
    initialRecharge: Int,
    onDismiss: () -> Unit,
    onApply: (Int, Int, (Boolean, String) -> Unit) -> Unit,
    onRestoreDefault: ((Boolean, String) -> Unit) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showDesc by remember(show) { mutableStateOf(false) }
    var limitText by remember(show, initialLimit) { mutableStateOf(initialLimit.toString()) }
    var rechargeText by remember(show, initialRecharge) { mutableStateOf(initialRecharge.toString()) }
    var statusMessage by remember(show, isActive, initialLimit, initialRecharge) {
        mutableStateOf(
            if (isActive) "当前状态：已开启（${initialLimit}% 停充 / ${initialRecharge}% 复充）"
            else "当前状态：未开启（配置阈值：${initialLimit}% / ${initialRecharge}%）"
        )
    }
    var isWorking by remember(show) { mutableStateOf(false) }

    CenteredAppDialog(
        show = show,
        title = "充电上限保护",
        summary = if (showDesc) "自定义充电截止电量与恢复充电阈值，达到上限后自动停止充电以延长电池循环寿命。" else null,
        onDismissRequest = { if (!isWorking) onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onLongPressToggle(haptic) { showDesc = !showDesc },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 当前状态卡片 + 右侧显示说明按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0x28FFFFFF) else Color(0x80FFFFFF))
                    .border(1.dp, if (isDark) TitaniumDark.Hairline else GlassLight.Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusMessage,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isActive) Color(0xFF10B981) else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (showDesc) "隐藏说明" else "显示说明",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showDesc = !showDesc
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // 三档快捷预设
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    Triple("80/75 日常", 80, 75),
                    Triple("85/80 均衡", 85, 80),
                    Triple("90/85 出行", 90, 85)
                )
                presets.forEach { (label, lim, rec) ->
                    val selected = limitText == lim.toString() && rechargeText == rec.toString()
                    GlassPillButton(
                        text = label,
                        isDark = isDark,
                        style = if (selected) GlassButtonStyle.Prominent(primaryColor) else GlassButtonStyle.Regular,
                        onClick = {
                            limitText = lim.toString()
                            rechargeText = rec.toString()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Miuix 双输入框
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextField(
                    value = limitText,
                    onValueChange = { input ->
                        if (input.length <= 3 && input.all { it.isDigit() }) {
                            limitText = input
                        }
                    },
                    label = "充电上限 (%)",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                TextField(
                    value = rechargeText,
                    onValueChange = { input ->
                        if (input.length <= 3 && input.all { it.isDigit() }) {
                            rechargeText = input
                        }
                    },
                    label = "恢复充电 (%)",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            // 底部按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassPillButton(
                    text = "取消",
                    isDark = isDark,
                    interactive = !isWorking,
                    style = GlassButtonStyle.Regular,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                GlassPillButton(
                    text = "恢复默认",
                    isDark = isDark,
                    interactive = !isWorking,
                    style = GlassButtonStyle.Regular,
                    onClick = {
                        isWorking = true
                        statusMessage = "正在恢复系统默认充电设置..."
                        onRestoreDefault { ok, msg ->
                            isWorking = false
                            statusMessage = msg
                            if (ok) onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                GlassPillButton(
                    text = "应用并开启",
                    isDark = isDark,
                    interactive = !isWorking,
                    style = GlassButtonStyle.Prominent(primaryColor),
                    onClick = {
                        val limit = (limitText.toIntOrNull() ?: 80).coerceIn(50, 100)
                        val recharge = (rechargeText.toIntOrNull() ?: 75).coerceIn(40, (limit - 5).coerceAtLeast(40))
                        limitText = limit.toString()
                        rechargeText = recharge.toString()
                        isWorking = true
                        statusMessage = "正在写入电池保护配置 (${limit}% / ${recharge}%)..."
                        onApply(limit, recharge) { ok, msg ->
                            isWorking = false
                            statusMessage = msg
                            if (ok) onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MiuixBatteryInfoDialog(
    show: Boolean,
    isDark: Boolean,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onFetchBatteryInfo: ((String) -> Unit) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showDesc by remember(show) { mutableStateOf(false) }
    var infoText by remember { mutableStateOf("正在读取硬件电池实时状态...") }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (show) {
            isRefreshing = true
            infoText = "正在读取硬件电池实时状态..."
            onFetchBatteryInfo { result ->
                infoText = result
                isRefreshing = false
            }
        }
    }

    CenteredAppDialog(
        show = show,
        title = "电池运行信息与健康度",
        summary = if (showDesc) "显示当前电量、温度、充电状态、电流、电压、循环数、三星健康度等实时硬件信息。" else null,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onLongPressToggle(haptic) { showDesc = !showDesc },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = if (showDesc) "隐藏说明" else "显示说明",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showDesc = !showDesc
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isDark) Color(0x22FFFFFF) else Color(0x80FFFFFF))
                    .border(1.dp, if (isDark) TitaniumDark.Hairline else GlassLight.Hairline, RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                Text(
                    text = infoText,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassPillButton(
                    text = if (isRefreshing) "读取中..." else "刷新数据",
                    isDark = isDark,
                    interactive = !isRefreshing,
                    style = GlassButtonStyle.Regular,
                    onClick = {
                        isRefreshing = true
                        infoText = "正在刷新最新状态..."
                        onFetchBatteryInfo { result ->
                            infoText = result
                            isRefreshing = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                GlassPillButton(
                    text = "关闭",
                    isDark = isDark,
                    style = GlassButtonStyle.Prominent(primaryColor),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MiuixGameAutoConfigDialog(
    show: Boolean,
    isDark: Boolean,
    primaryColor: Color,
    isInstantSwitchActive: Boolean,
    actions: HomeActions,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val haptic = LocalHapticFeedback.current

    var enabled by remember(show) { mutableStateOf(MaxHzPrefs.gameAutoPerformanceEnabled(context)) }
    var targetProfile by remember(show) { mutableStateOf(MaxHzPrefs.gameAutoTargetProfile(context)) }
    var exitDelaySec by remember(show) { mutableIntStateOf(MaxHzPrefs.gameAutoPerformanceExitDelaySeconds(context)) }
    var thermalRelaxed by remember(show) { mutableStateOf(MaxHzPrefs.gameAutoThermalRelaxed(context)) }
    var bypassCharge by remember(show) { mutableStateOf(MaxHzPrefs.gameAutoBypassCharge(context)) }
    var packages by remember(show) { mutableStateOf(MaxHzPrefs.gameAutoPerformancePackages(context).sorted()) }
    var profileMap by remember(show) { mutableStateOf(MaxHzPrefs.gameAutoPackageProfiles(context)) }

    WindowDialog(
        show = show,
        title = "游戏自动高刷加速与旁路供电",
        summary = "识别前台名单游戏自动切换满血/省电档位与旁路供电，退出游戏后按设定延迟自动恢复原状态（不在冷启动中途重置 cmd game，彻底避免画面仅显示左上角 75% 的分辨率错位问题）。",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isInstantSwitchActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDark) Color(0x22EF4444) else Color(0xFFFFF2F0))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "需开启「即时切换无障碍」以感知前台游戏",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color(0xFFFCA5A5) else Color(0xFFD9383A),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            text = "去开启",
                            onClick = actions.onOpenAccessibilityClick,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
            ) {
                SwitchPreference(
                    title = "启用游戏自动接管",
                    summary = "前台进入下方名单中的游戏时自动触发性能与供电策略",
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        actions.onToggleGameAutoSwitch(on)
                        actions.onSyncStates()
                    }
                )

                val profileValues = listOf(
                    MaxHzPrefs.GAME_PROFILE_PERFORMANCE,
                    MaxHzPrefs.GAME_PROFILE_SAVER,
                    MaxHzPrefs.GAME_PROFILE_DEFAULT
                )
                val profileOptions = listOf(
                    "满血游戏模式 (120Hz + 高采样)",
                    "省电护航模式 (低温持久)",
                    "系统默认 (仅还原不加压)"
                )
                val selectedProfileIdx = profileValues.indexOf(targetProfile).coerceAtLeast(0)
                WindowDropdownPreference(
                    title = "默认目标档位",
                    summary = "未单独指定档位的游戏默认应用的模式",
                    items = profileOptions,
                    selectedIndex = selectedProfileIdx,
                    onSelectedIndexChange = { idx ->
                        val next = profileValues.getOrElse(idx) { MaxHzPrefs.GAME_PROFILE_PERFORMANCE }
                        targetProfile = next
                        MaxHzPrefs.setGameAutoTargetProfile(context, next)
                    }
                )

                val delayValues = listOf(30, 60, 120)
                val delayLabels = listOf("30 秒防抖恢复", "60 秒防抖恢复 (推荐)", "120 秒防抖恢复")
                val selectedDelayIdx = delayValues.indexOf(exitDelaySec).let { if (it >= 0) it else 1 }
                WindowDropdownPreference(
                    title = "退出游戏恢复延迟",
                    summary = "切回桌面或回复消息时延迟恢复，防止短暂切出导致模式频繁闪烁",
                    items = delayLabels,
                    selectedIndex = selectedDelayIdx,
                    onSelectedIndexChange = { idx ->
                        val sec = delayValues.getOrElse(idx) { 60 }
                        exitDelaySec = sec
                        MaxHzPrefs.setGameAutoPerformanceExitDelaySeconds(context, sec)
                    }
                )

                SwitchPreference(
                    title = "温和控温策略 (推荐)",
                    summary = "保留三星 GameService 合理温控阈值，避免长时间机身过热骤降频",
                    checked = thermalRelaxed,
                    onCheckedChange = { on ->
                        thermalRelaxed = on
                        MaxHzPrefs.setGameAutoThermalRelaxed(context, on)
                    }
                )

                SwitchPreference(
                    title = "进入游戏自动开启旁路供电",
                    summary = "游戏前台且连接充电器时直供主板降发热，退出游戏后恢复原充电状态",
                    checked = bypassCharge,
                    onCheckedChange = { on ->
                        bypassCharge = on
                        MaxHzPrefs.setGameAutoBypassCharge(context, on)
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已配置游戏名单 (${packages.size} 款)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                if (packages.isNotEmpty()) {
                    Text(
                        text = "点击档位标签可单独切换",
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
            ) {
                if (packages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "尚未添加任何游戏，点击下方「+ 添加游戏应用」选择需要自动接管的游戏。",
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                } else {
                    Column {
                        packages.forEach { pkg ->
                            val label = remember(pkg) {
                                runCatching {
                                    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                                }.getOrDefault(pkg)
                            }
                            val customProf = profileMap[pkg]
                            val profTag = when (customProf) {
                                MaxHzPrefs.GAME_PROFILE_PERFORMANCE -> "满血"
                                MaxHzPrefs.GAME_PROFILE_SAVER -> "省电"
                                MaxHzPrefs.GAME_PROFILE_DEFAULT -> "默认"
                                else -> "跟随默认"
                            }
                            BasicComponent(
                                title = label,
                                summary = pkg,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val nextProf = when (customProf) {
                                        null -> MaxHzPrefs.GAME_PROFILE_PERFORMANCE
                                        MaxHzPrefs.GAME_PROFILE_PERFORMANCE -> MaxHzPrefs.GAME_PROFILE_SAVER
                                        MaxHzPrefs.GAME_PROFILE_SAVER -> MaxHzPrefs.GAME_PROFILE_DEFAULT
                                        else -> null
                                    }
                                    MaxHzPrefs.setGamePackageCustomProfile(context, pkg, nextProf)
                                    profileMap = MaxHzPrefs.gameAutoPackageProfiles(context)
                                },
                                endActions = {
                                    Text(
                                        text = profTag,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isDark) Color(0xFF60A5FA) else primaryColor,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(if (isDark) Color(0xFF162946) else Color(0xFFEBF3FF))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                MaxHzPrefs.removeGameAutoPerformancePackage(context, pkg)
                                                packages = MaxHzPrefs.gameAutoPerformancePackages(context).sorted()
                                                profileMap = MaxHzPrefs.gameAutoPackageProfiles(context)
                                                actions.onStaticGameIntervention(pkg, false)
                                                actions.onSyncStates()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "✕",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDark) Color(0xFFF87171) else Color(0xFFF54A45)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (packages.isNotEmpty()) {
                    TextButton(
                        text = "清空名单",
                        onClick = {
                            val oldList = packages
                            MaxHzPrefs.setGameAutoPerformancePackages(context, emptySet())
                            packages = emptyList()
                            profileMap = emptyMap()
                            oldList.forEach { pkg -> actions.onStaticGameIntervention(pkg, false) }
                            actions.onSyncStates()
                        },
                        modifier = Modifier.weight(0.9f)
                    )
                }
                TextButton(
                    text = "+ 添加游戏应用",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        actions.onSelectAppForGameAuto { pkg, _ ->
                            MaxHzPrefs.addGameAutoPerformancePackage(context, pkg)
                            if (!enabled) {
                                enabled = true
                                MaxHzPrefs.setGameAutoPerformanceEnabled(context, true)
                            }
                            packages = MaxHzPrefs.gameAutoPerformancePackages(context).sorted()
                            profileMap = MaxHzPrefs.gameAutoPackageProfiles(context)
                            actions.onStaticGameIntervention(pkg, true)
                            actions.onSyncStates()
                        }
                    },
                    modifier = Modifier.weight(1.3f)
                )
                TextButton(
                    text = "完成",
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.8f)
                )
            }
        }
    }
}

@Composable
private fun MiuixQuickDozeConfigDialog(
    show: Boolean,
    isDark: Boolean,
    isActive: Boolean,
    actions: HomeActions,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var enabled by remember(show, isActive) { mutableStateOf(isActive) }
    var noMaintenance by remember(show) { mutableStateOf(MaxHzPrefs.quickDozeNoMaintenance(context)) }
    var maintMinutes by remember(show) { mutableIntStateOf(MaxHzPrefs.quickDozeMaintenanceMinutes(context)) }
    var statusText by remember(show, isActive) {
        mutableStateOf(
            if (isActive) "当前状态：熄屏深度休眠已开启（锁屏后快速收紧 Doze 步进窗口）"
            else "当前状态：未开启（使用 Android 默认 Doze 休眠步进参数）"
        )
    }
    var isWorking by remember(show) { mutableStateOf(false) }

    WindowDialog(
        show = show,
        title = "熄屏深度休眠参数 (Quick Doze)",
        summary = "调整 Android DeviceIdle 步进参数，使手机熄屏后迅速进入 Deep Doze 深度休眠以降低夜间与待机掉电。",
        onDismissRequest = { if (!isWorking) onDismiss() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0xFF28282D) else Color(0xFFF0F2F6))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (enabled) Color(0xFF10B981) else MiuixTheme.colorScheme.onSurface
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
            ) {
                SwitchPreference(
                    title = "开启熄屏快速深度休眠",
                    summary = "熄屏后数秒内进入 Deep Doze，大幅压制后台唤醒",
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )

                val intervalOptions = listOf(30, 60, 80, 120)
                val intervalLabels = listOf("每 30 分钟唤醒维护", "每 60 分钟唤醒维护", "每 80 分钟唤醒维护 (推荐)", "每 120 分钟唤醒维护")
                val selectedIdx = intervalOptions.indexOf(maintMinutes).let { if (it >= 0) it else 2 }
                WindowDropdownPreference(
                    title = "Doze 维护窗口间隔",
                    summary = "深度休眠期间允许系统短暂同步通知的时间间隔",
                    items = intervalLabels,
                    selectedIndex = selectedIdx,
                    enabled = !noMaintenance,
                    onSelectedIndexChange = { idx ->
                        maintMinutes = intervalOptions.getOrElse(idx) { 80 }
                    }
                )

                SwitchPreference(
                    title = "禁用周期维护窗口（极致待机）",
                    summary = "熄屏期间完全推迟维护窗口，待机最省电，但可能延缓非高优推送",
                    checked = noMaintenance,
                    onCheckedChange = { noMaintenance = it }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = "读取实时状态",
                    enabled = !isWorking,
                    onClick = {
                        isWorking = true
                        statusText = "正在读取系统 deviceidle 状态..."
                        actions.onReadQuickDozeStatus { res ->
                            isWorking = false
                            statusText = res
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "异常唤醒排查",
                    enabled = !isWorking,
                    onClick = {
                        onDismiss()
                        actions.onNightWakeupGuardClick()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = "取消",
                    enabled = !isWorking,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "应用配置",
                    enabled = !isWorking,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        isWorking = true
                        statusText = "正在写入 Doze 深度休眠参数..."
                        actions.onApplyQuickDozeConfig(enabled, maintMinutes, noMaintenance) { ok, msg ->
                            isWorking = false
                            statusText = msg
                            if (ok) onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1.3f)
                )
            }
        }
    }
}

@Composable
private fun MiuixAppLaunchBoostDialog(
    show: Boolean,
    isDark: Boolean,
    isActive: Boolean,
    actions: HomeActions,
    onDismiss: () -> Unit
) {
    var statusText by remember(show, isActive) {
        mutableStateOf(
            if (isActive) "当前状态：已完成 Speed-Profile AOT 预编译优化"
            else "通过 Android ART 原生 dexopt 预编译热点代码，提升冷启动与滑动流畅度，并可一键回收冗余缓存。"
        )
    }
    var isWorking by remember(show) { mutableStateOf(false) }

    WindowDialog(
        show = show,
        title = "应用冷启动加速与清理",
        summary = "调用系统级 cmd package bg-dexopt-job 与 pm trim-caches，零后台常驻开销。",
        onDismissRequest = { if (!isWorking) onDismiss() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0xFF28282D) else Color(0xFFF0F2F6))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (isActive && !isWorking) Color(0xFF10B981) else MiuixTheme.colorScheme.onSurface
                )
            }

            TextButton(
                text = if (isWorking) "正在执行系统优化..." else "⚡ 核心与常用应用 Speed-Profile 加速 (推荐)",
                enabled = !isWorking,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    isWorking = true
                    statusText = "正在为核心与常用应用执行 Speed-Profile 编译，请稍候..."
                    actions.onRunAppLaunchBoostAction(0) { _, msg ->
                        isWorking = false
                        statusText = msg
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            TextButton(
                text = "🎯 自选应用 ART 预编译 (热点/全量/已编译管理)",
                enabled = !isWorking,
                onClick = {
                    actions.onOpenCustomAotPickerClick()
                },
                modifier = Modifier.fillMaxWidth()
            )

            TextButton(
                text = "🚀 全量应用后台深度编译 (耗时 1~3 分钟)",
                enabled = !isWorking,
                onClick = {
                    isWorking = true
                    statusText = "正在触发全量应用后台 dexopt 编译任务..."
                    actions.onRunAppLaunchBoostAction(1) { _, msg ->
                        isWorking = false
                        statusText = msg
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = "🧹 回收系统缓存",
                    enabled = !isWorking,
                    onClick = {
                        isWorking = true
                        statusText = "正在回收系统冗余应用缓存 (pm trim-caches)..."
                        actions.onRunAppLaunchBoostAction(2) { _, msg ->
                            isWorking = false
                            statusText = msg
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "恢复默认状态",
                    enabled = !isWorking,
                    onClick = {
                        isWorking = true
                        statusText = "正在恢复默认标记..."
                        actions.onRunAppLaunchBoostAction(3) { _, msg ->
                            isWorking = false
                            statusText = msg
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            TextButton(
                text = "关闭",
                enabled = !isWorking,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MiuixDiagnosticsCenterDialog(
    show: Boolean,
    isDark: Boolean,
    primaryColor: Color,
    gameTileMode: Int,
    isPassThroughActive: Boolean,
    isBatteryProtectionActive: Boolean,
    batteryProtectionLimit: Int,
    batteryProtectionRecharge: Int,
    isGameAutoSwitchActive: Boolean,
    gameAutoPackageCount: Int,
    isFpsMonitorActive: Boolean,
    isOutdoorModeActive: Boolean,
    isTombstoneActive: Boolean,
    isQuickDozeActive: Boolean,
    isAutostartLimitActive: Boolean,
    isLocationThrottleActive: Boolean,
    isScanGuardActive: Boolean,
    isAppLaunchBoostActive: Boolean,
    isForceResizableActive: Boolean,
    isInstantSwitchActive: Boolean,
    appRulesCount: Int,
    actions: HomeActions,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var logRefreshTick by remember(show) { mutableIntStateOf(0) }
    var isRestoring by remember(show) { mutableStateOf(false) }
    var restoreBanner by remember(show) { mutableStateOf<String?>(null) }

    LaunchedEffect(show) {
        if (show) {
            actions.onSyncStates()
            logRefreshTick++
        }
    }

    val channelLabel = remember(show, logRefreshTick) {
        runCatching { MaxHzShell.activePrivilegeChannelLabel() }.getOrDefault("未就绪")
    }
    val activeOverrides = remember(
        show,
        logRefreshTick,
        gameTileMode,
        isPassThroughActive,
        isBatteryProtectionActive,
        batteryProtectionLimit,
        batteryProtectionRecharge,
        isGameAutoSwitchActive,
        gameAutoPackageCount,
        isFpsMonitorActive,
        isOutdoorModeActive,
        isTombstoneActive,
        isQuickDozeActive,
        isAutostartLimitActive,
        isLocationThrottleActive,
        isScanGuardActive,
        isForceResizableActive,
        isInstantSwitchActive,
        appRulesCount
    ) {
        buildList {
            when (gameTileMode) {
                1 -> add("• 全局性能模式：省电自适应高刷模式 (系统省电 + 120Hz 自适应)")
                2 -> add("• 全局性能模式：满血游戏模式 (120Hz + 高触控采样)")
            }
            if (MaxHzPrefs.controlEnabled(context)) {
                add("• 全局常驻刷新率控制：已开启")
            }
            if (appRulesCount > 0) {
                add("• 单应用独立刷新率：已配置 $appRulesCount 款应用 (${if (isInstantSwitchActive) "即时切换生效中" else "无障碍未开启，已暂停"})")
            }
            if (isPassThroughActive) {
                add("• 旁路供电 (暂停 USB PD 充电)：已开启")
            }
            if (isBatteryProtectionActive) {
                add("• 充电上限保护：已开启 (${batteryProtectionLimit}% 停充 / ${batteryProtectionRecharge}% 复充)")
            }
            if (isGameAutoSwitchActive) {
                val activePkg = MaxHzPrefs.gameAutoActivePackage(context)
                val suffix = if (!activePkg.isNullOrBlank()) " [当前接管: $activePkg]" else ""
                add("• 游戏自动高刷加速：已开启 ($gameAutoPackageCount 款游戏)$suffix")
            }
            if (isFpsMonitorActive) add("• 实时 FPS 帧率浮窗：运行中")
            if (isOutdoorModeActive) add("• 户外阳光激发亮度：已开启")
            if (isTombstoneActive) add("• 后台进程休眠 (Cached Apps Freezer)：已开启")
            if (isQuickDozeActive) add("• 熄屏快速深度休眠 (Quick Doze)：已开启")
            if (isAutostartLimitActive) add("• 限制后台自启与关联唤醒：已开启")
            if (isLocationThrottleActive) add("• 限制后台定位频率 (30分钟)：已开启")
            if (isScanGuardActive) add("• 限制后台扫描与自动同步：已开启")
            if (isForceResizableActive) add("• 强制所有应用支持分屏/小窗：已开启")
        }
    }

    val combinedLogs = remember(show, logRefreshTick, channelLabel) {
        val rawErrLogs = MaxHzPrefs.resultText(context, "background_error").orEmpty().trim()
        val errLogs = if (channelLabel != "未就绪" && (rawErrLogs.contains("未检测到已授权的 Root") || rawErrLogs.contains("Shizuku 未运行"))) {
            MaxHzPrefs.clearResult(context, "background_error")
            ""
        } else {
            rawErrLogs
        }
        val gameLogs = MaxHzPrefs.resultText(context, "game_auto_runtime").orEmpty().trim()
        buildString {
            if (errLogs.isNotEmpty()) {
                append("【后台守护自愈与异常记录】\n")
                append(errLogs).append('\n')
            }
            if (gameLogs.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("【游戏自动接管状态流转日志】\n")
                append(gameLogs)
            }
            if (isEmpty()) {
                append("暂无异常记录或游戏切换日志，所有后台任务运行正常。")
            }
        }
    }

    WindowDialog(
        show = show,
        title = "运行状态与覆盖项诊断中心",
        summary = "透明展示本应用当前对系统改写的所有持久化/运行时开关、Shell 会话池复用通道及自愈日志。",
        onDismissRequest = { if (!isRestoring) onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (restoreBanner != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDark) Color(0xFF132E24) else Color(0xFFE6F8F0))
                        .padding(12.dp)
                ) {
                    Text(
                        text = restoreBanner!!,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFF34D399) else Color(0xFF10B981)
                    )
                }
            }

            // 1. 特权通道状态
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0xFF28282D) else Color(0xFFF0F2F6))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "特权执行通道：$channelLabel",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "前台即时切换无障碍：${if (isInstantSwitchActive) "🟢 已启用（仅在配置了刷新率规则或游戏名单时介入）" else "⚪ 未启用（零前台窗口干预）"}",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            // 2. 当前活跃的系统覆盖项
            Text(
                text = "当前生效的系统级覆盖项 (${activeOverrides.size} 项)",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0xFF21242C) else Color(0xFFF5F7FA))
                    .padding(12.dp)
            ) {
                if (activeOverrides.isEmpty()) {
                    Text(
                        text = "🟢 当前无任何系统级覆盖项生效（纯粹原生状态，零后台干预）",
                        fontSize = 12.sp,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = activeOverrides.joinToString("\n"),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }

            // 3. 诊断与运行日志
            Text(
                text = "最近运行与自愈诊断日志",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0xFF21242C) else Color(0xFFF5F7FA))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = combinedLogs,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            // 操作按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = "清空日志",
                    enabled = !isRestoring,
                    onClick = {
                        MaxHzPrefs.clearResult(context, "background_error")
                        MaxHzPrefs.clearResult(context, "game_auto_runtime")
                        logRefreshTick++
                    },
                    modifier = Modifier.weight(0.9f)
                )
                TextButton(
                    text = if (isRestoring) "正在恢复..." else "一键恢复全部默认",
                    enabled = !isRestoring,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        isRestoring = true
                        restoreBanner = "正在撤销全部覆盖项并恢复三星/Android 原生默认设置..."
                        actions.onExecuteRestoreAllDefaults { ok, msg ->
                            isRestoring = false
                            restoreBanner = msg
                            logRefreshTick++
                        }
                    },
                    modifier = Modifier.weight(1.4f)
                )
                TextButton(
                    text = "关闭",
                    enabled = !isRestoring,
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.8f)
                )
            }
        }
    }
}

// ==================================================================
// 0. Miuix 大字页面标题组件（支持方案 B：默认仅显示主标题，右侧可切换「显示说明/隐藏说明」或长按展开）
// ==================================================================

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    isDark: Boolean,
    primaryColor: Color,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    var localToggled by remember(expanded) { mutableStateOf(false) }
    val showSubtitle = expanded xor localToggled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressToggle(haptic) {
                if (onToggle != null) onToggle() else localToggled = !localToggled
            }
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                val pillBg = if (showSubtitle) {
                    if (isDark) primaryColor.copy(alpha = 0.22f) else primaryColor.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                }
                val pillFg = if (showSubtitle) {
                    primaryColor
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.85f)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(pillBg)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (onToggle != null) onToggle() else localToggled = !localToggled
                        }
                        .padding(horizontal = 8.dp, vertical = 2.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (showSubtitle) "隐藏说明" else "显示说明",
                        fontSize = 11.5.sp,
                        fontWeight = if (showSubtitle) FontWeight.SemiBold else FontWeight.Medium,
                        color = pillFg
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = showSubtitle && subtitle != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    lineHeight = 15.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clickable {
                            if (onToggle != null) onToggle() else localToggled = !localToggled
                        }
                )
            }
        }
    }
}

// ==================================================================
// 1. Hero 服务状态与启动通道主卡（带右下角裁切几何水印 + 柔和胶囊标签）
// ==================================================================

@Composable
private fun HeroCard(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    status: ServiceStatus?,
    grantedCount: Int,
    activePort: Int,
    isWifiConnected: Boolean,
    isDeviceOwner: Boolean = false,
    groupExpanded: Boolean = false,
    actions: HomeActions,
    onRequestStopDialog: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var rowToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSubtitle = groupExpanded xor rowToggled

    val ok = status?.isRunning == true
    val isRoot = status?.uid == 0
    val apiVersion = status?.apiVersion ?: 0
    val patchVersion = status?.patchVersion ?: 0
    val runningModeName = if (isRoot) "Root" else "ADB"

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressToggle(haptic) { rowToggled = !rowToggled },
        isDark = isDark,
        isBlackNightTheme = isBlackNightTheme,
        cornerRadius = 20.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
        ) {
            // 右上角半裁切几何圆环水印
            val watermarkColor = if (ok) {
                primaryColor.copy(alpha = if (isDark) 0.22f else 0.14f)
            } else {
                (if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)).copy(alpha = 0.16f)
            }
            Canvas(
                modifier = Modifier
                    .size(108.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 22.dp, y = 10.dp)
            ) {
                val strokeWidth = 8.5.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                drawCircle(
                    color = watermarkColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                if (ok) {
                    val checkPath = Path().apply {
                        moveTo(size.width * 0.30f, size.height * 0.52f)
                        lineTo(size.width * 0.45f, size.height * 0.67f)
                        lineTo(size.width * 0.72f, size.height * 0.37f)
                    }
                    drawPath(
                        path = checkPath,
                        color = watermarkColor,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                } else {
                    drawLine(
                        color = watermarkColor,
                        start = Offset(size.width * 0.5f, size.height * 0.28f),
                        end = Offset(size.width * 0.5f, size.height * 0.54f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = watermarkColor,
                        radius = strokeWidth * 0.55f,
                        center = Offset(size.width * 0.5f, size.height * 0.69f)
                    )
                }
            }

            Column(Modifier.padding(16.dp)) {
                // 顶部：精致状态标题 + 柔和浅蓝/浅珊瑚胶囊标签
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (ok) "运行中" else "未运行",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )

                    val pillBg = if (ok) {
                        if (isDark) primaryColor.copy(alpha = 0.22f) else primaryColor.copy(alpha = 0.14f)
                    } else {
                        if (isDark) Color(0x2EF87171) else Color(0xFFFFECE8)
                    }
                    val pillFg = if (ok) {
                        primaryColor
                    } else {
                        if (isDark) Color(0xFFF87171) else Color(0xFFE5484D)
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(pillBg)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(pillFg)
                        )
                        Text(
                            text = if (ok) "$runningModeName · v$apiVersion.$patchVersion" else "待激活",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = pillFg
                        )
                    }

                    if (isDeviceOwner) {
                        val doBg = if (isDark) Color(0x2938BDF8) else Color(0xFFE0F2FE)
                        val doFg = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
                        Text(
                            text = stringResource(R.string.dhizuku_device_owner_badge),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = doFg,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(doBg)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showSubtitle,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Text(
                        text = if (ok) stringResource(R.string.home_status_activated_summary, runningModeName, apiVersion, patchVersion, "")
                        else if (activePort > 0) stringResource(R.string.home_status_hint_port_found, activePort)
                        else if (!isWifiConnected) stringResource(R.string.home_status_hint_no_wifi)
                        else stringResource(R.string.home_status_hint_ready),
                        fontSize = 12.sp,
                        lineHeight = 16.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .padding(top = 6.dp, end = 48.dp)
                            .clickable { rowToggled = !rowToggled }
                    )
                }

                Spacer(Modifier.height(14.dp))

                if (ok) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile(
                            Modifier.weight(1f),
                            stringResource(R.string.home_tile_service_version),
                            "v$apiVersion.$patchVersion",
                            isAccent = false,
                            ok = true,
                            isDark = isDark,
                            primaryColor = primaryColor
                        )
                        Tile(
                            Modifier.weight(1f),
                            stringResource(R.string.home_tile_execution_mode),
                            if (isRoot) stringResource(R.string.home_tile_mode_root) else stringResource(R.string.home_tile_mode_adb),
                            isAccent = true,
                            ok = true,
                            isDark = isDark,
                            primaryColor = primaryColor
                        )
                        Tile(
                            Modifier.weight(1f),
                            stringResource(R.string.home_tile_authorized_apps),
                            stringResource(R.string.home_tile_apps_count_format, grantedCount),
                            isAccent = grantedCount > 0,
                            ok = true,
                            isDark = isDark,
                            primaryColor = primaryColor
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    GlassPillButton(
                        text = stringResource(R.string.home_btn_manage_authorized_apps, grantedCount),
                        isDark = isDark,
                        style = GlassButtonStyle.Prominent(primaryColor),
                        onClick = actions.onAuthorizedAppsClick,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassPillButton(
                            text = stringResource(R.string.home_btn_terminal_rish),
                            isDark = isDark,
                            style = GlassButtonStyle.Regular,
                            onClick = actions.onRishClick,
                            modifier = Modifier.weight(1f)
                        )
                        GlassPillButton(
                            text = stringResource(R.string.home_btn_stop_service),
                            isDark = isDark,
                            style = GlassButtonStyle.Danger,
                            onClick = onRequestStopDialog,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    GlassPillButton(
                        text = stringResource(R.string.home_btn_adb_start),
                        isDark = isDark,
                        style = GlassButtonStyle.Prominent(primaryColor),
                        onClick = actions.onWirelessStartClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ==================================================================
// 2. 未运行状态：无线调试配对中枢卡 (Miuix GroupHeaderWithToggle + Card + ArrowPreference)
// ==================================================================

@Composable
private fun AdbPairingHubCard(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    isAutoPairingEnabled: Boolean,
    actions: HomeActions
) {
    var groupExpanded by remember { mutableStateOf(false) }
    Column {
        GroupHeaderWithToggle(
            text = stringResource(R.string.home_card_pairing_hub_title),
            isDark = isDark,
            primaryColor = primaryColor,
            expanded = groupExpanded,
            onToggle = { groupExpanded = !groupExpanded }
        )
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            isBlackNightTheme = isBlackNightTheme,
            cornerRadius = 20.dp
        ) {
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_wadb_24),
                iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF277AF6),
                title = stringResource(R.string.home_action_auto_pairing_title),
                subtitle = stringResource(R.string.home_action_auto_pairing_subtitle),
                badgeText = if (isAutoPairingEnabled) stringResource(R.string.home_badge_enabled) else null,
                badgeColor = if (isAutoPairingEnabled) Color(0xFF10B981) else null,
                groupExpanded = groupExpanded,
                onClick = actions.onAutoPairingAssistantClick
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_code_24dp),
                iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                title = stringResource(R.string.home_action_manual_pairing_title),
                subtitle = stringResource(R.string.home_action_manual_pairing_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onWirelessPairClick
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_action_settings_24dp),
                iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                title = stringResource(R.string.home_action_open_dev_settings_title),
                subtitle = stringResource(R.string.home_action_open_dev_settings_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onOpenDevSettingsClick
            )
        }
    }
}

// ==================================================================
// 3. 未运行状态：特权启动方式选择卡
// ==================================================================

@Composable
private fun PrivilegeLaunchMethodsCard(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    isDeviceOwner: Boolean,
    actions: HomeActions
) {
    var groupExpanded by remember { mutableStateOf(false) }
    Column {
        GroupHeaderWithToggle(
            text = stringResource(R.string.home_card_launch_methods_title),
            isDark = isDark,
            primaryColor = primaryColor,
            expanded = groupExpanded,
            onToggle = { groupExpanded = !groupExpanded }
        )
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            isBlackNightTheme = isBlackNightTheme,
            cornerRadius = 20.dp
        ) {
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_root_24dp),
                iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFF54A45),
                title = stringResource(R.string.home_action_root_start_title),
                subtitle = stringResource(R.string.home_action_root_start_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onStartRootClick
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_terminal_24),
                iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B),
                title = stringResource(R.string.home_action_pc_start_title),
                subtitle = stringResource(R.string.home_action_pc_start_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onStartAdbClick
            )
            if (isDeviceOwner) {
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_shield_24),
                    iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                    title = stringResource(R.string.dhizuku_device_owner_label),
                    subtitle = stringResource(R.string.dhizuku_remove_owner_btn),
                    badgeText = stringResource(R.string.home_badge_activated),
                    badgeColor = Color(0xFF0284C7),
                    groupExpanded = groupExpanded,
                    onClick = actions.onDhizukuClick
                )
            }
        }
    }
}

// ==================================================================
// 4. 运行中状态：特权通道与备用连接卡
// ==================================================================

@Composable
private fun AdbRunningToolsCard(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    isRoot: Boolean = false,
    isDeviceOwner: Boolean,
    actions: HomeActions
) {
    var groupExpanded by remember { mutableStateOf(false) }
    Column {
        GroupHeaderWithToggle(
            text = stringResource(R.string.home_card_running_tools_title),
            isDark = isDark,
            primaryColor = primaryColor,
            expanded = groupExpanded,
            onToggle = { groupExpanded = !groupExpanded }
        )
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            isBlackNightTheme = isBlackNightTheme,
            cornerRadius = 20.dp
        ) {
            if (!isRoot) {
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_wadb_24),
                    iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF277AF6),
                    title = stringResource(R.string.home_btn_reconnect_debugging),
                    subtitle = "重新扫描本地回环无线调试端口并刷新 ADB 令牌连接",
                    groupExpanded = groupExpanded,
                    onClick = actions.onWirelessStartClick
                )
            }
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_root_24dp),
                iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFF54A45),
                title = stringResource(R.string.home_action_root_restart_title),
                subtitle = stringResource(R.string.home_action_root_restart_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onStartRootClick
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_terminal_24),
                iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B),
                title = stringResource(R.string.home_action_pc_view_title),
                subtitle = stringResource(R.string.home_action_pc_view_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onStartAdbClick
            )
            if (isDeviceOwner) {
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_shield_24),
                    iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                    title = stringResource(R.string.dhizuku_device_owner_label),
                    subtitle = stringResource(R.string.dhizuku_remove_owner_btn),
                    badgeText = stringResource(R.string.home_badge_activated),
                    badgeColor = Color(0xFF0284C7),
                    groupExpanded = groupExpanded,
                    onClick = actions.onDhizukuClick
                )
            }
        }
    }
}

// ==================================================================
// 5. 核心性能调度分段切换卡（柔和冰蓝点亮式胶囊）
// ==================================================================

@Composable
private fun PerformanceModeCard(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    currentMode: Int,
    onModeChange: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var groupExpanded by remember { mutableStateOf(false) }
    var cardToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSubtitle = groupExpanded xor cardToggled

    Column {
        GroupHeaderWithToggle(
            text = stringResource(R.string.home_card_perf_mode_title),
            isDark = isDark,
            primaryColor = primaryColor,
            expanded = showSubtitle,
            onToggle = { groupExpanded = !showSubtitle }
        )
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .onLongPressToggle(haptic) { cardToggled = !cardToggled },
            isDark = isDark,
            isBlackNightTheme = isBlackNightTheme,
            cornerRadius = 20.dp
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modes = listOf(
                        Triple(0, stringResource(R.string.home_perf_mode_default), "日常均衡"),
                        Triple(1, stringResource(R.string.home_perf_mode_saver), "续航优先"),
                        Triple(2, stringResource(R.string.home_perf_mode_game), "满血释放")
                    )
                    modes.forEach { (mode, label, tag) ->
                        val selected = currentMode == mode
                        val activeBg = when {
                            !selected -> if (isDark) Color(0x22FFFFFF) else Color(0x73FFFFFF)
                            mode == 1 -> if (isDark) Color(0xFF132E24) else Color(0xFFE6F8F0)
                            else -> if (isDark) primaryColor.copy(alpha = 0.24f) else primaryColor.copy(alpha = 0.14f)
                        }
                        val activeBorder = when {
                            !selected -> if (isDark) TitaniumDark.Hairline else GlassLight.Hairline
                            mode == 1 -> if (isDark) Color(0x5534D399) else Color(0x4410B981)
                            else -> if (isDark) primaryColor.copy(alpha = 0.48f) else primaryColor.copy(alpha = 0.32f)
                        }
                        val activeFg = when {
                            !selected -> MiuixTheme.colorScheme.onSurface
                            mode == 1 -> if (isDark) Color(0xFF34D399) else Color(0xFF10B981)
                            else -> primaryColor
                        }
                        val subFg = if (selected) activeFg.copy(alpha = 0.85f)
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(activeBg)
                                .border(1.dp, activeBorder, RoundedCornerShape(14.dp))
                                .clickable {
                                    if (currentMode != mode) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onModeChange(mode)
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = activeFg,
                                maxLines = 1
                            )
                            if (showSubtitle) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (selected) "● $tag" else tag,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    color = subFg,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showSubtitle,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val desc = when (currentMode) {
                        1 -> stringResource(R.string.home_perf_mode_desc_saver)
                        2 -> stringResource(R.string.home_perf_mode_desc_game)
                        else -> stringResource(R.string.home_perf_mode_desc_default)
                    }
                    Text(
                        text = desc,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .padding(top = 10.dp, start = 4.dp, end = 4.dp)
                            .clickable { cardToggled = !cardToggled }
                    )
                }
            }
        }
    }
}

// ==================================================================
// 6. 快捷硬件开关与电池健康卡（全宽 Miuix 纯净卡片，支持方案 B）
// ==================================================================

@Composable
private fun QuickSwitchesCard(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    isRootAvailable: Boolean = false,
    isPassThroughActive: Boolean,
    isBatteryProtectionActive: Boolean = false,
    batteryProtectionLimit: Int = 80,
    batteryProtectionRecharge: Int = 75,
    isGameAutoSwitchActive: Boolean,
    gameAutoPackageCount: Int = 0,
    isFpsMonitorActive: Boolean,
    isOutdoorModeActive: Boolean,
    isQuickDozeActive: Boolean = false,
    isAppLaunchBoostActive: Boolean = false,
    holdDownBatteryProtection: Boolean = false,
    onTogglePassThrough: (Boolean) -> Unit,
    onToggleBatteryProtection: (Boolean) -> Unit = {},
    onOpenBatteryProtectionConfigClick: () -> Unit = {},
    onToggleGameAutoSwitch: (Boolean) -> Unit,
    onToggleFpsMonitor: (Boolean) -> Unit,
    onToggleOutdoorMode: (Boolean) -> Unit,
    onToggleQuickDoze: (Boolean) -> Unit = {},
    onOpenGameAutoConfigClick: () -> Unit = {},
    onOpenBatteryInfoDialog: () -> Unit = {},
    onOpenAppLaunchBoostClick: () -> Unit = {}
) {
    var groupExpanded by remember { mutableStateOf(false) }
    Column {
        GroupHeaderWithToggle(
            text = stringResource(R.string.home_card_quick_switches_title),
            isDark = isDark,
            primaryColor = primaryColor,
            expanded = groupExpanded,
            onToggle = { groupExpanded = !groupExpanded }
        )
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            isBlackNightTheme = isBlackNightTheme,
            cornerRadius = 20.dp
        ) {
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_battery_24),
                iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF10B981),
                title = stringResource(R.string.home_switch_passthrough_title),
                subtitle = "连接充电器时电流直供主板（同步接管三星 Game Booster「游戏时暂停 USB PD 充电」）",
                checked = isPassThroughActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onTogglePassThrough
            )
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_shield_24),
                iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF277AF6),
                title = "充电上限保护 (${batteryProtectionLimit}% / ${batteryProtectionRecharge}%)",
                subtitle = "同步接管三星 One UI「电池保护」阈值，点击此行调参，右侧开关直接启停",
                badgeText = "调参 ›",
                holdDownState = holdDownBatteryProtection,
                checked = isBatteryProtectionActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleBatteryProtection,
                onRowClick = onOpenBatteryProtectionConfigClick
            )
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_game_24),
                iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B),
                title = if (gameAutoPackageCount > 0) {
                    "游戏自动高刷加速 ($gameAutoPackageCount 款)"
                } else {
                    "游戏自动高刷加速"
                },
                subtitle = "进入名单游戏自动切换满血/省电与旁路供电（不破坏三星 Game Booster 画质比例）",
                badgeText = "选游戏 ›",
                checked = isGameAutoSwitchActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleGameAutoSwitch,
                onRowClick = onOpenGameAutoConfigClick
            )
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_chart_24),
                iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                title = "实时 FPS 帧率浮窗",
                subtitle = "在屏幕左上角显示 SurfaceFlinger 纳秒级零延迟实时帧率与功耗",
                checked = isFpsMonitorActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleFpsMonitor
            )
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_sun_24),
                iconTint = if (isDark) Color(0xFFFB923C) else Color(0xFFEA580C),
                title = stringResource(R.string.home_switch_outdoor_title),
                subtitle = stringResource(R.string.home_switch_outdoor_subtitle),
                checked = isOutdoorModeActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleOutdoorMode
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_battery_24),
                iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                title = "电池运行信息与健康度",
                subtitle = "查看实时电量、温度、电流、电压、循环次数与三星原厂健康度",
                badgeText = "实时直读",
                groupExpanded = groupExpanded,
                onClick = onOpenBatteryInfoDialog
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_cpu_24),
                iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFF54A45),
                title = "应用冷启动加速与清理",
                subtitle = "将应用字节码提前编译为 CPU 原生机器码（支持 1~3 秒热点极速编译与 100% 全量编译），消除冷启动与滑动掉帧",
                badgeText = if (isAppLaunchBoostActive) "已优化" else null,
                badgeColor = if (isAppLaunchBoostActive) Color(0xFF10B981) else null,
                groupExpanded = groupExpanded,
                onClick = onOpenAppLaunchBoostClick
            )
        }
    }
}

@Composable
private fun BackgroundPowerCard(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    isTombstoneActive: Boolean,
    isQuickDozeActive: Boolean,
    isAutostartLimitActive: Boolean,
    isLocationThrottleActive: Boolean,
    isScanGuardActive: Boolean,
    onToggleTombstone: (Boolean) -> Unit,
    onToggleQuickDoze: (Boolean) -> Unit,
    onOpenQuickDozeConfigClick: () -> Unit,
    onToggleAutostartLimit: (Boolean) -> Unit,
    onToggleLocationThrottle: (Boolean) -> Unit,
    onToggleScanGuard: (Boolean) -> Unit
) {
    var groupExpanded by remember { mutableStateOf(false) }
    Column {
        GroupHeaderWithToggle(
            text = "后台与深度休眠",
            isDark = isDark,
            primaryColor = primaryColor,
            expanded = groupExpanded,
            onToggle = { groupExpanded = !groupExpanded }
        )
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            isBlackNightTheme = isBlackNightTheme,
            cornerRadius = 20.dp
        ) {
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_shield_24),
                iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                title = "后台进程休眠（墓碑）",
                subtitle = "启用 Android 原生 Freezer 冻结后台缓存进程，防止偷跑 CPU",
                checked = isTombstoneActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleTombstone
            )
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_battery_24),
                iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                title = "熄屏深度休眠参数",
                subtitle = "熄屏后快速进入深度 Doze 睡眠，点击可调节休眠步进参数",
                badgeText = "调参 ›",
                checked = isQuickDozeActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleQuickDoze,
                onRowClick = onOpenQuickDozeConfigClick
            )
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_apps_24),
                iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B),
                title = "限制后台自启",
                subtitle = "拦截后台广播偷起常驻服务与链式关联互拉，保持后台纯净",
                checked = isAutostartLimitActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleAutostartLimit
            )
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_search_24),
                iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF10B981),
                title = "限制后台定位",
                subtitle = "将后台应用定位请求间隔限制为 30 分钟，降低待机耗电",
                checked = isLocationThrottleActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleLocationThrottle
            )
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_sync_24),
                iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF277AF6),
                title = "限制扫描与同步",
                subtitle = "关闭后台隐式 Wi-Fi / 蓝牙 / 附近设备扫描与无用同步",
                checked = isScanGuardActive,
                groupExpanded = groupExpanded,
                onCheckedChange = onToggleScanGuard
            )
        }
    }
}

/**
 * Miuix 风格开关项（支持方案 B：默认仅显示单行主标题，长按单行或点击分组「显示说明」展开副标题）：
 * - 若无独立 `onRowClick`，直接使用 Miuix `SwitchPreference`
 * - 若带 `onRowClick`（如点击行弹出配置、点击右侧 Switch 直接启停），使用 Miuix `BasicComponent` + `Switch`
 */
@Composable
private fun SwitchItemRow(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155),
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badgeText: String? = null,
    holdDownState: Boolean = false,
    groupExpanded: Boolean = false,
    onRowClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    var rowToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSubtitle = groupExpanded xor rowToggled
    val effectiveSummary = if (showSubtitle && subtitle.isNotBlank()) subtitle else null
    val rowPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressToggle(haptic) { rowToggled = !rowToggled }
    ) {
        if (onRowClick == null && badgeText == null) {
            SwitchPreference(
                title = title,
                summary = effectiveSummary,
                checked = checked,
                holdDownState = holdDownState,
                insideMargin = rowPadding,
                onCheckedChange = onCheckedChange,
                startAction = {
                    MiuixRowIcon(isDark = isDark, icon = icon, iconTint = iconTint)
                }
            )
        } else {
            BasicComponent(
                title = title,
                summary = effectiveSummary,
                holdDownState = holdDownState,
                insideMargin = rowPadding,
                onClick = {
                    if (onRowClick != null) {
                        onRowClick()
                    } else {
                        onCheckedChange(!checked)
                    }
                },
                startAction = {
                    MiuixRowIcon(isDark = isDark, icon = icon, iconTint = iconTint)
                },
                endActions = {
                    if (badgeText != null) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(iconTint.copy(alpha = if (isDark) 0.18f else 0.12f))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = iconTint
                            )
                        }
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange
                    )
                }
            )
        }
    }
}

// ==================================================================
// 7. 系统优化与维护入口卡 (Miuix ArrowPreference & SwitchPreference，支持方案 B)
// ==================================================================

@Composable
private fun QuickActionsCard(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    isAppLaunchBoostActive: Boolean = false,
    isForceResizableActive: Boolean = false,
    holdDownBatteryInfo: Boolean = false,
    onOpenBatteryInfoDialog: () -> Unit = {},
    onOpenDiagnosticsDialog: () -> Unit = {},
    actions: HomeActions
) {
    var groupExpanded by remember { mutableStateOf(false) }
    Column {
        GroupHeaderWithToggle(
            text = stringResource(R.string.home_card_quick_actions_title),
            isDark = isDark,
            primaryColor = primaryColor,
            expanded = groupExpanded,
            onToggle = { groupExpanded = !groupExpanded }
        )
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            isBlackNightTheme = isBlackNightTheme,
            cornerRadius = 20.dp
        ) {
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_window_24),
                iconTint = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777),
                title = "强制所有应用分屏/小窗",
                subtitle = "解除窗口限制，让所有应用均支持分屏与多任务悬浮小窗",
                checked = isForceResizableActive,
                groupExpanded = groupExpanded,
                onCheckedChange = actions.onToggleForceResizable
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_speed_24),
                iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF10B981),
                title = "运行状态与覆盖项诊断中心",
                subtitle = "实时透视当前生效的系统级覆盖项、特权通道复用状态与异常恢复日志",
                badgeText = "安全透明",
                onClick = onOpenDiagnosticsDialog
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_shield_24),
                iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                title = stringResource(R.string.home_action_night_guard_title),
                subtitle = stringResource(R.string.home_action_night_guard_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onNightWakeupGuardClick
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_settings_outline_24dp),
                iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                title = stringResource(R.string.home_action_geek_lab_title),
                subtitle = stringResource(R.string.home_action_geek_lab_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onOpenLaboratoryClick
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_restore_24),
                iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFF54A45),
                title = stringResource(R.string.home_action_restore_defaults_title),
                subtitle = stringResource(R.string.home_action_restore_defaults_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onRestoreDefaultsClick
            )
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_help_outline_24dp),
                iconTint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                title = stringResource(R.string.home_action_user_manual_title),
                subtitle = stringResource(R.string.home_action_user_manual_subtitle),
                groupExpanded = groupExpanded,
                onClick = actions.onHelpClick
            )
        }
    }
}

// ==================================================================
// Miuix 通用列表行组件（柔和粉彩圆角图标 + 方案 B 长按单行展开副标题）
// ==================================================================

@Composable
fun MiuixRowIcon(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color
) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(iconTint.copy(alpha = if (isDark) 0.18f else 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconTint),
            modifier = Modifier.size(18.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun AdbActionRow(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155),
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color? = null,
    holdDownState: Boolean = false,
    groupExpanded: Boolean = false,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var rowToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSubtitle = groupExpanded xor rowToggled
    val effectiveSummary = if (showSubtitle && subtitle.isNotBlank()) subtitle else null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressToggle(haptic) { rowToggled = !rowToggled }
    ) {
        ArrowPreference(
            title = title,
            summary = effectiveSummary,
            holdDownState = holdDownState,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
            onClick = onClick,
            startAction = {
                MiuixRowIcon(isDark = isDark, icon = icon, iconTint = iconTint)
            },
            endActions = {
                if (badgeText != null) {
                    val accent = badgeColor ?: iconTint
                    Text(
                        text = badgeText,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(accent.copy(alpha = if (isDark) 0.18f else 0.12f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        )
    }
}

@Composable
private fun Tile(
    modifier: Modifier,
    label: String,
    value: String,
    isAccent: Boolean,
    ok: Boolean,
    isDark: Boolean,
    primaryColor: Color
) {
    val tileShape = RoundedCornerShape(12.dp)
    val tileBgColor = when {
        isAccent && ok -> if (isDark) primaryColor.copy(alpha = 0.22f) else primaryColor.copy(alpha = 0.12f)
        isDark -> Color(0x33384850)
        else -> Color(0x66FFFFFF)
    }
    val tileBorder = when {
        isAccent && ok -> if (isDark) primaryColor.copy(alpha = 0.40f) else primaryColor.copy(alpha = 0.25f)
        isDark -> TitaniumDark.Hairline
        else -> GlassLight.Hairline
    }
    val valueColor = when {
        isAccent && ok -> primaryColor
        else -> MiuixTheme.colorScheme.onSurface
    }

    Column(
        modifier
            .clip(tileShape)
            .background(tileBgColor)
            .border(1.dp, tileBorder, tileShape)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

// ==================================================================
// 8. Tab 2：Miuix 分应用刷新率规则组件（全面支持方案 B）
// ==================================================================

@Composable
private fun AppRulesTabContent(
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    topInsetPx: Int,
    bottomInsetDp: androidx.compose.ui.unit.Dp,
    rulesListState: LazyListState,
    appRules: List<MaxHzPrefs.AppRefreshRule>,
    isInstantSwitchActive: Boolean,
    supportedRefreshRates: List<Int>,
    actions: HomeActions
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val pm = context.packageManager
    val draftPrefs = remember(context) {
        context.getSharedPreferences("maxhz_rule_draft", android.content.Context.MODE_PRIVATE)
    }

    var targetPackage by remember { mutableStateOf<String?>(null) }
    var targetLabel by remember { mutableStateOf<String?>(null) }
    var hasTouchedPreset by remember { mutableStateOf(false) }
    var targetAdaptive by remember {
        mutableStateOf(draftPrefs.getBoolean("draft_adaptive", false))
    }
    var targetMaxHz by remember {
        mutableIntStateOf(draftPrefs.getInt("draft_max_hz", 120))
    }
    var targetMinHz by remember {
        mutableIntStateOf(draftPrefs.getInt("draft_min_hz", 10))
    }

    val updateDraftAdaptive: (Boolean) -> Unit = { value ->
        targetAdaptive = value
        draftPrefs.edit().putBoolean("draft_adaptive", value).apply()
    }
    val updateDraftMaxHz: (Int) -> Unit = { value ->
        targetMaxHz = value
        draftPrefs.edit().putInt("draft_max_hz", value).apply()
    }
    val updateDraftMinHz: (Int) -> Unit = { value ->
        targetMinHz = value
        draftPrefs.edit().putInt("draft_min_hz", value).apply()
    }

    var headerGroupExpanded by remember { mutableStateOf(false) }
    var presetsGroupExpanded by remember { mutableStateOf(false) }
    var formGroupExpanded by remember { mutableStateOf(false) }
    var listGroupExpanded by remember { mutableStateOf(false) }
    var guideGroupExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .overScrollVertical(),
        state = rulesListState,
        contentPadding = PaddingValues(
            top = with(density) { topInsetPx.toDp() },
            start = 14.dp,
            end = 14.dp,
            bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "section-rules-header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(
                    title = stringResource(R.string.home_header_rules_title),
                    subtitle = stringResource(R.string.home_header_rules_subtitle),
                    isDark = isDark,
                    primaryColor = primaryColor,
                    expanded = headerGroupExpanded,
                    onToggle = { headerGroupExpanded = !headerGroupExpanded }
                )
                // 常驻功能边界说明横幅，消除「全局开关 vs 单应用规则」混淆
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isDark) primaryColor.copy(alpha = 0.14f)
                            else primaryColor.copy(alpha = 0.09f)
                        )
                        .border(
                            1.dp,
                            if (isDark) primaryColor.copy(alpha = 0.30f) else primaryColor.copy(alpha = 0.22f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = stringResource(R.string.rules_banner_scope_notice),
                        fontSize = 12.sp,
                        lineHeight = 17.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 前台无障碍感知与即时切换状态卡
        item(key = "card-accessibility-status") {
            var cardToggled by remember(headerGroupExpanded) { mutableStateOf(false) }
            val showSubtitle = headerGroupExpanded xor cardToggled
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .onLongPressToggle(haptic) { cardToggled = !cardToggled },
                isDark = isDark,
                isBlackNightTheme = isBlackNightTheme,
                cornerRadius = 20.dp
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiuixRowIcon(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_maxhz_speed_24),
                            iconTint = if (isInstantSwitchActive) Color(0xFF10B981) else Color(0xFFF54A45)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.rules_card_accessibility_title),
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            AnimatedVisibility(
                                visible = showSubtitle,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Text(
                                    text = stringResource(R.string.rules_card_accessibility_subtitle),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .clickable { cardToggled = !cardToggled }
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        val badgeBg = if (isInstantSwitchActive) {
                            if (isDark) Color(0xFF132E24) else Color(0xFFE6F8F0)
                        } else {
                            if (isDark) Color(0x2EF87171) else Color(0xFFFFECE8)
                        }
                        val badgeFg = if (isInstantSwitchActive) {
                            if (isDark) Color(0xFF34D399) else Color(0xFF10B981)
                        } else {
                            if (isDark) Color(0xFFF87171) else Color(0xFFE5484D)
                        }
                        Text(
                            text = if (isInstantSwitchActive) stringResource(R.string.rules_badge_service_running)
                            else stringResource(R.string.rules_badge_service_disabled),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeFg,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(badgeBg)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                        if (isInstantSwitchActive) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.rules_btn_disable_accessibility),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFFF87171) else Color(0xFFE5484D),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isDark) Color(0x2EF87171) else Color(0xFFFFECE8))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        actions.onDisableAccessibilityClick()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (!isInstantSwitchActive) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isDark) Color(0x22EF4444) else Color(0xFFFFF2F0))
                                .padding(14.dp)
                        ) {
                            Column {
                                AnimatedVisibility(
                                    visible = showSubtitle,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Text(
                                        text = stringResource(R.string.rules_warning_accessibility_locked),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isDark) Color(0xFFFCA5A5) else Color(0xFFD9383A),
                                        modifier = Modifier
                                            .padding(bottom = 10.dp)
                                            .clickable { cardToggled = !cardToggled }
                                    )
                                }
                                GlassPillButton(
                                    text = stringResource(R.string.rules_btn_enable_accessibility),
                                    isDark = isDark,
                                    style = GlassButtonStyle.Prominent(primaryColor),
                                    onClick = actions.onOpenAccessibilityClick,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        // 新建 / 编辑单应用规则卡（第一步选应用 -> 第二步选预设 -> 第三步调参数并保存）
        item(key = "card-rule-form") {
            var selectRowToggled by remember(formGroupExpanded) { mutableStateOf(false) }
            var adaptiveRowToggled by remember(formGroupExpanded) { mutableStateOf(false) }
            var maxHzRowToggled by remember(formGroupExpanded) { mutableStateOf(false) }
            var minHzRowToggled by remember(formGroupExpanded) { mutableStateOf(false) }

            Column {
                GroupHeaderWithToggle(
                    text = stringResource(R.string.rules_card_config_title),
                    isDark = isDark,
                    primaryColor = primaryColor,
                    expanded = formGroupExpanded,
                    onToggle = {
                        formGroupExpanded = !formGroupExpanded
                        presetsGroupExpanded = formGroupExpanded
                    }
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    isDark = isDark,
                    isBlackNightTheme = isBlackNightTheme,
                    cornerRadius = 20.dp
                ) {
                    // 第一步：选择目标应用（置于最顶部，明确此卡片是针对特定应用配置）
                    val showSelectSub = formGroupExpanded xor selectRowToggled
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onLongPressToggle(haptic) { selectRowToggled = !selectRowToggled }
                    ) {
                        ArrowPreference(
                            title = if (targetLabel != null) {
                                stringResource(R.string.rules_select_app_selected, targetLabel!!)
                            } else {
                                stringResource(R.string.rules_select_app_hint)
                            },
                            summary = if (targetPackage != null) {
                                targetPackage
                            } else if (showSelectSub) {
                                "点击从已安装应用中选择需要单独定制刷新率的 App"
                            } else {
                                "未选择应用（请先点击此处选择目标 App）"
                            },
                            onClick = {
                                actions.onSelectAppForAppRule { pkg, label ->
                                    targetPackage = pkg
                                    targetLabel = label
                                    hasTouchedPreset = true
                                    val existingRule = appRules.firstOrNull { it.packageName == pkg }
                                    if (existingRule != null) {
                                        updateDraftAdaptive(existingRule.adaptive)
                                        updateDraftMaxHz(existingRule.maxHz)
                                        updateDraftMinHz(existingRule.minHz)
                                    }
                                }
                            },
                            startAction = {
                                MiuixRowIcon(
                                    isDark = isDark,
                                    icon = painterResource(R.drawable.ic_maxhz_apps_24),
                                    iconTint = primaryColor
                                )
                            }
                        )
                    }

                    // 第二步：该应用的常用场景预设（未选应用且未点击预设时不默认高亮，防止被误认为全局模式）
                    val presets = listOf(
                        Triple(stringResource(R.string.rules_preset_daily), Pair(120, 10), true),
                        Triple(stringResource(R.string.rules_preset_video), Pair(60, 60), false),
                        Triple(stringResource(R.string.rules_preset_reading), Pair(60, 10), true),
                        Triple(stringResource(R.string.rules_preset_game), Pair(120, 120), false)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.rules_card_presets_title),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        presets.chunked(2).forEach { rowPresets ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowPresets.forEach { (name, rates, adaptive) ->
                                    val isSelected = (targetPackage != null || hasTouchedPreset) &&
                                        targetMaxHz == rates.first &&
                                        targetMinHz == rates.second &&
                                        targetAdaptive == adaptive
                                    val chipBg = when {
                                        isSelected && isDark -> primaryColor.copy(alpha = 0.24f)
                                        isSelected && !isDark -> primaryColor.copy(alpha = 0.14f)
                                        isDark -> Color(0x22FFFFFF)
                                        else -> Color(0x73FFFFFF)
                                    }
                                    val chipBorder = when {
                                        isSelected && isDark -> primaryColor.copy(alpha = 0.48f)
                                        isSelected && !isDark -> primaryColor.copy(alpha = 0.32f)
                                        isDark -> TitaniumDark.Hairline
                                        else -> GlassLight.Hairline
                                    }
                                    val chipFg = if (isSelected) primaryColor
                                    else MiuixTheme.colorScheme.onSurface
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(chipBg)
                                            .border(1.dp, chipBorder, RoundedCornerShape(14.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                hasTouchedPreset = true
                                                updateDraftMaxHz(rates.first)
                                                updateDraftMinHz(rates.second)
                                                updateDraftAdaptive(adaptive)
                                            }
                                            .padding(horizontal = 14.dp, vertical = 11.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = name.substringBefore(" ("),
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = chipFg
                                        )
                                        Text(
                                            text = if (adaptive) "${rates.second}~${rates.first}Hz" else "${rates.first}Hz",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isSelected) chipFg.copy(alpha = 0.85f) else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 第三步：该应用的动态降频开关与刷新率上下限
                    val showAdaptiveSub = formGroupExpanded xor adaptiveRowToggled
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onLongPressToggle(haptic) { adaptiveRowToggled = !adaptiveRowToggled }
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.rules_switch_adaptive),
                            summary = if (showAdaptiveSub) {
                                if (targetAdaptive) stringResource(R.string.rules_switch_adaptive_summary_on)
                                else stringResource(R.string.rules_switch_adaptive_summary_off)
                            } else null,
                            checked = targetAdaptive,
                            onCheckedChange = {
                                updateDraftAdaptive(it)
                            }
                        )
                    }

                    val maxRates = listOf(120, 96, 90, 60, 48, 30, 24)
                    val maxRateLabels = maxRates.map { "${it}Hz" }
                    val selectedMaxIndex = maxRates.indexOf(targetMaxHz).coerceAtLeast(0)
                    val showMaxHzSub = formGroupExpanded xor maxHzRowToggled
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onLongPressToggle(haptic) { maxHzRowToggled = !maxHzRowToggled }
                    ) {
                        WindowDropdownPreference(
                            title = stringResource(R.string.rules_label_max_hz),
                            summary = if (showMaxHzSub) "该应用在前台运行时允许达到的最高刷新率上限" else null,
                            items = maxRateLabels,
                            selectedIndex = selectedMaxIndex,
                            enabled = true,
                            onSelectedIndexChange = { idx ->
                                val hz = maxRates.getOrElse(idx) { 120 }
                                updateDraftMaxHz(hz)
                                if (targetMinHz > hz) updateDraftMinHz(hz)
                            }
                        )
                    }

                    if (targetAdaptive) {
                        val minRates = listOf(60, 48, 30, 24, 10).filter { it <= targetMaxHz }
                        val minRateLabels = minRates.map { "${it}Hz" }
                        val selectedMinIndex = minRates.indexOf(targetMinHz).coerceAtLeast(0)
                        val showMinHzSub = formGroupExpanded xor minHzRowToggled
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onLongPressToggle(haptic) { minHzRowToggled = !minHzRowToggled }
                        ) {
                            WindowDropdownPreference(
                                title = stringResource(R.string.rules_label_min_hz),
                                summary = if (showMinHzSub) "在该应用内画面静止时允许 LTPO 降频到的最低刷新率下限" else null,
                                items = minRateLabels,
                                selectedIndex = selectedMinIndex,
                                enabled = true,
                                onSelectedIndexChange = { idx ->
                                    val hz = minRates.getOrElse(idx) { 10 }
                                    updateDraftMinHz(hz)
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val pkg = targetPackage
                        val lbl = targetLabel
                        val hasExisting = pkg != null && appRules.any { it.packageName == pkg }

                        GlassPillButton(
                            text = if (!lbl.isNullOrBlank()) {
                                stringResource(R.string.rules_btn_save_with_app, lbl)
                            } else {
                                stringResource(R.string.rules_btn_select_and_save)
                            },
                            isDark = isDark,
                            style = GlassButtonStyle.Prominent(primaryColor),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (pkg.isNullOrBlank()) {
                                    actions.onSelectAppForAppRule { p, l ->
                                        targetPackage = p
                                        targetLabel = l
                                        val minHz = if (targetAdaptive) targetMinHz else targetMaxHz
                                        actions.onSaveAppRule(
                                            MaxHzPrefs.AppRefreshRule(
                                                packageName = p,
                                                adaptive = targetAdaptive,
                                                maxHz = targetMaxHz,
                                                minHz = minHz
                                            )
                                        )
                                    }
                                } else {
                                    val minHz = if (targetAdaptive) targetMinHz else targetMaxHz
                                    actions.onSaveAppRule(
                                        MaxHzPrefs.AppRefreshRule(
                                            packageName = pkg,
                                            adaptive = targetAdaptive,
                                            maxHz = targetMaxHz,
                                            minHz = minHz
                                        )
                                    )
                                }
                            }
                        )

                        if (hasExisting && pkg != null) {
                            GlassPillButton(
                                text = stringResource(R.string.rules_btn_delete),
                                isDark = isDark,
                                style = GlassButtonStyle.Danger,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    actions.onDeleteAppRule(pkg)
                                    targetPackage = null
                                    targetLabel = null
                                    hasTouchedPreset = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // 已生效规则列表卡（支持方案 B：默认隐藏包名副标题，长按单行或点击「显示说明」展开）
        item(key = "card-rules-list") {
            Column {
                GroupHeaderWithToggle(
                    text = stringResource(R.string.rules_card_list_title, appRules.size),
                    isDark = isDark,
                    primaryColor = primaryColor,
                    expanded = listGroupExpanded,
                    onToggle = { listGroupExpanded = !listGroupExpanded }
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    isDark = isDark,
                    isBlackNightTheme = isBlackNightTheme,
                    cornerRadius = 20.dp
                ) {
                    if (appRules.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.rules_list_empty),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        Column {
                            appRules.forEach { rule ->
                                var itemToggled by remember(rule.packageName, listGroupExpanded) { mutableStateOf(false) }
                                val showPkg = listGroupExpanded xor itemToggled
                                val label = remember(rule.packageName) {
                                    runCatching {
                                        pm.getApplicationInfo(rule.packageName, 0).loadLabel(pm).toString()
                                    }.getOrDefault(rule.packageName)
                                }
                                val baseTag = if (rule.adaptive) "${rule.minHz}~${rule.maxHz}Hz" else "${rule.maxHz}Hz"
                                val ruleTag = if (isInstantSwitchActive) baseTag else "$baseTag · ${stringResource(R.string.rules_rule_status_paused)}"
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onLongPressToggle(haptic) { itemToggled = !itemToggled }
                                ) {
                                    BasicComponent(
                                        title = label,
                                        summary = if (showPkg) rule.packageName else null,
                                        onClick = {
                                            targetPackage = rule.packageName
                                            targetLabel = label
                                            hasTouchedPreset = true
                                            updateDraftAdaptive(rule.adaptive)
                                            updateDraftMaxHz(rule.maxHz)
                                            updateDraftMinHz(rule.minHz)
                                        },
                                        startAction = {
                                            MiuixRowIcon(
                                                isDark = isDark,
                                                icon = painterResource(R.drawable.ic_maxhz_apps_24),
                                                iconTint = primaryColor
                                            )
                                        },
                                        endActions = {
                                            Text(
                                                text = ruleTag,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = primaryColor,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isDark) primaryColor.copy(alpha = 0.22f) else primaryColor.copy(alpha = 0.14f))
                                                    .padding(horizontal = 8.dp, vertical = 3.5.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        actions.onDeleteAppRule(rule.packageName)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "✕",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isDark) Color(0xFFF87171) else Color(0xFFF54A45)
                                                )
                                            }
                                        }
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                GlassPillButton(
                                    text = stringResource(R.string.rules_btn_clear_all),
                                    isDark = isDark,
                                    style = GlassButtonStyle.Regular,
                                    onClick = actions.onClearAppRules
                                )
                            }
                        }
                    }
                }
            }
        }

        // 流媒体高刷兼容策略指南卡 & LTPO 校准双卡（统一分组标题 + 方案 B）
        item(key = "card-stream-compat-guide") {
            var compatToggled by remember(guideGroupExpanded) { mutableStateOf(false) }
            val showCompatSub = guideGroupExpanded xor compatToggled
            Column {
                GroupHeaderWithToggle(
                    text = "高刷进阶与功耗校准",
                    isDark = isDark,
                    primaryColor = primaryColor,
                    expanded = guideGroupExpanded,
                    onToggle = { guideGroupExpanded = !guideGroupExpanded }
                )
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onLongPressToggle(haptic) { compatToggled = !compatToggled },
                    isDark = isDark,
                    isBlackNightTheme = isBlackNightTheme,
                    cornerRadius = 20.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.rules_compat_policy_tip_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        AnimatedVisibility(
                            visible = showCompatSub,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Text(
                                text = stringResource(R.string.rules_compat_policy_tip_desc),
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clickable { compatToggled = !compatToggled }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        GlassPillButton(
                            text = stringResource(R.string.rules_btn_open_dev_options),
                            isDark = isDark,
                            style = GlassButtonStyle.Prominent(primaryColor),
                            onClick = actions.onOpenDisableAppCompatibilityPoliciesClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 屏幕自适应降频与功耗校准卡
        item(key = "card-calibrate-ltpo-power") {
            var ltpoToggled by remember(guideGroupExpanded) { mutableStateOf(false) }
            val showLtpoSub = guideGroupExpanded xor ltpoToggled
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .onLongPressToggle(haptic) { ltpoToggled = !ltpoToggled },
                isDark = isDark,
                isBlackNightTheme = isBlackNightTheme,
                cornerRadius = 20.dp
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.rules_calibrate_ltpo_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    AnimatedVisibility(
                        visible = showLtpoSub,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Text(
                            text = stringResource(R.string.rules_calibrate_ltpo_desc),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable { ltpoToggled = !ltpoToggled }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassPillButton(
                        text = stringResource(R.string.rules_btn_calibrate_ltpo),
                        isDark = isDark,
                        style = GlassButtonStyle.Prominent(primaryColor),
                        onClick = actions.onCalibrateLtpoAdaptiveClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item(key = "dockClearance-rules") {
            Spacer(Modifier.height(78.dp))
        }
    }
}

