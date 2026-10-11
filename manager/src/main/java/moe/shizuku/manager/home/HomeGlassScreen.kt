package moe.shizuku.manager.home

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.shizuku.manager.R
import moe.shizuku.manager.maxhz.GLASS_DOCK_CLEARANCE_DP
import moe.shizuku.manager.maxhz.GlassButtonStyle
import moe.shizuku.manager.maxhz.GlassCard
import moe.shizuku.manager.maxhz.GlassPillButton
import moe.shizuku.manager.maxhz.IconBadge
import moe.shizuku.manager.maxhz.MaxHzPrefs
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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Miuix 风格全面重构的主控制台：
 *
 * - 统一采用 `top.yukonga.miuix.kmp` 组件库（`Scaffold`、`Card`、`SmallTitle`、`SwitchPreference`、
 *   `ArrowPreference`、`OverlayDropdownPreference`、`OverlayDialog`、`TextButton`、`TextField`、`overScrollVertical`）
 * - 所有核心弹窗（充电上限保护、电池运行信息与健康度、停止服务确认）均使用 Miuix 原生 `OverlayDialog` 弹簧弹窗渲染
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
    val onNightWakeupGuardClick: () -> Unit = {},
    val onCalibrateLtpoAdaptiveClick: () -> Unit = {}
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

    val bgColor = if (isBlackNightTheme) {
        Color.Black
    } else if (isDark) {
        Color(0xFF000000)
    } else {
        Color(0xFFF7F7F7)
    }

    val shizukuListState = rememberLazyListState()
    val perfListState = rememberLazyListState()
    val rulesListState = rememberLazyListState()

    // Miuix 原生 OverlayDialog 状态
    var showStopConfirmDialog by remember { mutableStateOf(false) }
    var showBatteryProtectionDialog by remember { mutableStateOf(false) }
    var showBatteryInfoDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = bgColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        when (currentTab) {
            0 -> {
                // ==========================================================
                // Tab 0：【特权服务】（Miuix 风格核心控制台）
                // ==========================================================
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                    state = shizukuListState,
                    contentPadding = PaddingValues(
                        top = with(density) { topInsetPx.toDp() },
                        start = 12.dp,
                        end = 12.dp,
                        bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "section-adb", contentType = "header") {
                        SectionHeader(
                            title = if (ok) stringResource(R.string.home_header_shizuku_running_title)
                            else stringResource(R.string.home_header_shizuku_stopped_title),
                            subtitle = if (ok) stringResource(R.string.home_header_shizuku_running_subtitle)
                            else stringResource(R.string.home_header_shizuku_stopped_subtitle),
                            isDark = isDark,
                            primaryColor = primaryColor
                        )
                    }

                    item(key = "hero", contentType = "hero") {
                        HeroCard(
                            isDark = isDark,
                            primaryColor = primaryColor,
                            status = status,
                            grantedCount = grantedCount,
                            activePort = activePort,
                            isWifiConnected = isWifiConnected,
                            isDeviceOwner = isDeviceOwner,
                            actions = actions,
                            onRequestStopDialog = { showStopConfirmDialog = true }
                        )
                    }

                    if (!ok) {
                        item(key = "adb-pairing", contentType = "pairing") {
                            AdbPairingHubCard(
                                isDark = isDark,
                                primaryColor = primaryColor,
                                isAutoPairingEnabled = isAutoPairingEnabled,
                                actions = actions
                            )
                        }

                        item(key = "adb-alt", contentType = "alt") {
                            PrivilegeLaunchMethodsCard(
                                isDark = isDark,
                                isDeviceOwner = isDeviceOwner,
                                actions = actions
                            )
                        }
                    } else {
                        item(key = "adb-running-tools", contentType = "tools") {
                            AdbRunningToolsCard(
                                isDark = isDark,
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
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                    state = perfListState,
                    contentPadding = PaddingValues(
                        top = with(density) { topInsetPx.toDp() },
                        start = 12.dp,
                        end = 12.dp,
                        bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "section-aux", contentType = "header") {
                        val isRootEnv = isRootAvailable || (status?.uid == 0)
                        SectionHeader(
                            title = stringResource(R.string.home_header_hardware_title),
                            subtitle = if (isRootEnv) "⚡ Root 硬件直通 · " + stringResource(R.string.home_header_hardware_subtitle)
                            else "🛡️ Shizuku 模式 · " + stringResource(R.string.home_header_hardware_subtitle),
                            isDark = isDark,
                            primaryColor = primaryColor
                        )
                    }

                    item(key = "perf-mode", contentType = "perf") {
                        PerformanceModeCard(
                            isDark = isDark,
                            primaryColor = primaryColor,
                            currentMode = gameTileMode,
                            onModeChange = actions.onModeChange
                        )
                    }

                    item(key = "quick-switches", contentType = "switches") {
                        QuickSwitchesCard(
                            isDark = isDark,
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
                            holdDownBatteryProtection = showBatteryProtectionDialog,
                            onTogglePassThrough = actions.onTogglePassThrough,
                            onToggleBatteryProtection = actions.onToggleBatteryProtection,
                            onOpenBatteryProtectionConfigClick = { showBatteryProtectionDialog = true },
                            onToggleGameAutoSwitch = actions.onToggleGameAutoSwitch,
                            onToggleFpsMonitor = actions.onToggleFpsMonitor,
                            onToggleOutdoorMode = actions.onToggleOutdoorMode,
                            onOpenGameAutoConfigClick = actions.onOpenGameAutoConfigClick
                        )
                    }

                    item(key = "background-power", contentType = "background") {
                        BackgroundPowerCard(
                            isDark = isDark,
                            primaryColor = primaryColor,
                            isTombstoneActive = isTombstoneActive,
                            isQuickDozeActive = isQuickDozeActive,
                            isAutostartLimitActive = isAutostartLimitActive,
                            isLocationThrottleActive = isLocationThrottleActive,
                            isScanGuardActive = isScanGuardActive,
                            onToggleTombstone = actions.onToggleTombstone,
                            onToggleQuickDoze = actions.onToggleQuickDoze,
                            onOpenQuickDozeConfigClick = actions.onOpenQuickDozeConfigClick,
                            onToggleAutostartLimit = actions.onToggleAutostartLimit,
                            onToggleLocationThrottle = actions.onToggleLocationThrottle,
                            onToggleScanGuard = actions.onToggleScanGuard
                        )
                    }

                    item(key = "quick-actions", contentType = "actions") {
                        QuickActionsCard(
                            isDark = isDark,
                            primaryColor = primaryColor,
                            isAppLaunchBoostActive = isAppLaunchBoostActive,
                            isForceResizableActive = isForceResizableActive,
                            holdDownBatteryInfo = showBatteryInfoDialog,
                            onOpenBatteryInfoDialog = { showBatteryInfoDialog = true },
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
                    isDark = isDark,
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
        // Miuix OverlayDialog 1：停止 Shizuku 服务确认弹窗
        // ==========================================================
        OverlayDialog(
            show = showStopConfirmDialog,
            title = "停止 Shizuku 服务",
            summary = stringResource(R.string.dialog_stop_message),
            onDismissRequest = { showStopConfirmDialog = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { showStopConfirmDialog = false },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        showStopConfirmDialog = false
                        actions.onConfirmStopService()
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ==========================================================
        // Miuix OverlayDialog 2：充电上限保护弹窗
        // ==========================================================
        MiuixBatteryProtectionDialog(
            show = showBatteryProtectionDialog,
            isDark = isDark,
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
        // Miuix OverlayDialog 3：电池运行信息与健康度弹窗
        // ==========================================================
        MiuixBatteryInfoDialog(
            show = showBatteryInfoDialog,
            isDark = isDark,
            onDismiss = { showBatteryInfoDialog = false },
            onFetchBatteryInfo = actions.onFetchBatteryInfo
        )
    }
}

// ==================================================================
// Miuix 弹窗组件：充电上限保护 & 电池运行信息与健康度
// ==================================================================

@Composable
private fun MiuixBatteryProtectionDialog(
    show: Boolean,
    isDark: Boolean,
    isActive: Boolean,
    initialLimit: Int,
    initialRecharge: Int,
    onDismiss: () -> Unit,
    onApply: (Int, Int, (Boolean, String) -> Unit) -> Unit,
    onRestoreDefault: ((Boolean, String) -> Unit) -> Unit
) {
    var limitText by remember(show, initialLimit) { mutableStateOf(initialLimit.toString()) }
    var rechargeText by remember(show, initialRecharge) { mutableStateOf(initialRecharge.toString()) }
    var statusMessage by remember(show, isActive, initialLimit, initialRecharge) {
        mutableStateOf(
            if (isActive) "当前状态：已开启（${initialLimit}% 停充 / ${initialRecharge}% 复充）"
            else "当前状态：未开启（配置阈值：${initialLimit}% / ${initialRecharge}%）"
        )
    }
    var isWorking by remember(show) { mutableStateOf(false) }

    OverlayDialog(
        show = show,
        title = "充电上限保护",
        summary = "自定义充电截止电量与恢复充电阈值，达到上限后自动停止充电以延长电池循环寿命。",
        onDismissRequest = { if (!isWorking) onDismiss() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 当前状态卡片
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0xFF28282D) else Color(0xFFF0F2F6))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = statusMessage,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isActive) Color(0xFF10B981) else MiuixTheme.colorScheme.onSurface
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
                    TextButton(
                        text = label,
                        onClick = {
                            limitText = lim.toString()
                            rechargeText = rec.toString()
                        },
                        colors = if (selected) ButtonDefaults.textButtonColorsPrimary()
                        else ButtonDefaults.textButtonColors(),
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
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
                TextButton(
                    text = "取消",
                    enabled = !isWorking,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "恢复默认",
                    enabled = !isWorking,
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
                TextButton(
                    text = "应用并开启",
                    enabled = !isWorking,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
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
                    modifier = Modifier.weight(1.2f)
                )
            }
        }
    }
}

@Composable
private fun MiuixBatteryInfoDialog(
    show: Boolean,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onFetchBatteryInfo: ((String) -> Unit) -> Unit
) {
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

    OverlayDialog(
        show = show,
        title = "电池运行信息与健康度",
        summary = "显示当前电量、温度、充电状态、电流、电压、循环数、三星健康度等实时硬件信息。",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isDark) Color(0xFF28282D) else Color(0xFFF0F2F6))
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
                TextButton(
                    text = if (isRefreshing) "读取中..." else "刷新数据",
                    enabled = !isRefreshing,
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
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ==================================================================
// 0. Miuix 分类标题组件
// ==================================================================

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    isDark: Boolean,
    primaryColor: Color
) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp)) {
        Text(
            text = title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = primaryColor
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

// ==================================================================
// 1. Hero 服务状态与启动通道卡 (Miuix Card + Miuix Button)
// ==================================================================

@Composable
private fun HeroCard(
    isDark: Boolean,
    primaryColor: Color,
    status: ServiceStatus?,
    grantedCount: Int,
    activePort: Int,
    isWifiConnected: Boolean,
    isDeviceOwner: Boolean = false,
    actions: HomeActions,
    onRequestStopDialog: () -> Unit
) {
    val ok = status?.isRunning == true
    val isRoot = status?.uid == 0
    val apiVersion = status?.apiVersion ?: 0
    val patchVersion = status?.patchVersion ?: 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    painter = painterResource(
                        if (ok) R.drawable.ic_server_ok_24dp
                        else R.drawable.ic_server_error_24dp
                    ),
                    colors = if (ok) 0xFF34C759 to 0xFF249D44
                    else 0xFFFF3B30 to 0xFFE02B20
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (ok) stringResource(R.string.home_status_service_is_running, "Shizuku")
                        else stringResource(R.string.home_status_service_not_running, "Shizuku"),
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    val runningModeName = if (isRoot) "Root" else "ADB"
                    Text(
                        text = if (ok) stringResource(R.string.home_status_activated_summary, runningModeName, apiVersion, patchVersion, "")
                        else if (activePort > 0) stringResource(R.string.home_status_hint_port_found, activePort)
                        else if (!isWifiConnected) stringResource(R.string.home_status_hint_no_wifi)
                        else stringResource(R.string.home_status_hint_ready),
                        fontSize = 12.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isDeviceOwner) {
                        Text(
                            text = stringResource(R.string.dhizuku_device_owner_badge),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0284C7))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = if (ok) (if (isRoot) stringResource(R.string.home_status_root_running) else stringResource(R.string.home_status_adb_running)) else stringResource(R.string.home_status_not_running),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (ok) Color(0xFF12864A) else Color(0xFFC62828))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            if (ok) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(
                        Modifier.weight(1f), stringResource(R.string.home_tile_service_version),
                        "v$apiVersion.$patchVersion.0",
                        isAccent = true, ok = true, isDark = isDark, primaryColor = primaryColor
                    )
                    Tile(
                        Modifier.weight(1f), stringResource(R.string.home_tile_execution_mode),
                        if (isRoot) stringResource(R.string.home_tile_mode_root) else stringResource(R.string.home_tile_mode_adb),
                        isAccent = true, ok = true, isDark = isDark, primaryColor = primaryColor
                    )
                    Tile(
                        Modifier.weight(1f), stringResource(R.string.home_tile_authorized_apps),
                        stringResource(R.string.home_tile_apps_count_format, grantedCount),
                        isAccent = grantedCount > 0, ok = true, isDark = isDark, primaryColor = primaryColor
                    )
                }

                Spacer(Modifier.height(14.dp))

                TextButton(
                    text = stringResource(R.string.home_btn_manage_authorized_apps, grantedCount),
                    onClick = actions.onAuthorizedAppsClick,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.home_btn_terminal_rish),
                        onClick = actions.onRishClick,
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                        modifier = Modifier.weight(1f)
                    )
                    if (isRoot) {
                        TextButton(
                            text = stringResource(R.string.home_btn_restart_service),
                            onClick = actions.onStartRootClick,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.home_btn_reconnect_debugging),
                            onClick = actions.onWirelessStartClick,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    GlassPillButton(
                        text = stringResource(R.string.home_btn_stop_service),
                        isDark = isDark,
                        style = GlassButtonStyle.Danger,
                        onClick = onRequestStopDialog
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.home_btn_adb_start),
                        onClick = actions.onWirelessStartClick,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1.4f)
                    )
                    TextButton(
                        text = stringResource(R.string.home_btn_root_start),
                        onClick = actions.onStartRootClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ==================================================================
// 2. 未运行状态：无线调试配对中枢卡 (Miuix SmallTitle + Card + ArrowPreference)
// ==================================================================

@Composable
private fun AdbPairingHubCard(
    isDark: Boolean,
    primaryColor: Color,
    isAutoPairingEnabled: Boolean,
    actions: HomeActions
) {
    Column {
        SmallTitle(
            text = stringResource(R.string.home_card_pairing_hub_title),
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
        ) {
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_wadb_24),
                iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                title = stringResource(R.string.home_action_auto_pairing_title),
                subtitle = stringResource(R.string.home_action_auto_pairing_subtitle),
                badgeText = if (isAutoPairingEnabled) stringResource(R.string.home_badge_enabled) else null,
                badgeColor = if (isAutoPairingEnabled) Color(0xFF10B981) else null,
                onClick = actions.onAutoPairingAssistantClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_code_24dp),
                iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                title = stringResource(R.string.home_action_manual_pairing_title),
                subtitle = stringResource(R.string.home_action_manual_pairing_subtitle),
                onClick = actions.onWirelessPairClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_action_settings_24dp),
                iconTint = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                title = stringResource(R.string.home_action_open_dev_settings_title),
                subtitle = stringResource(R.string.home_action_open_dev_settings_subtitle),
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
    isDeviceOwner: Boolean,
    actions: HomeActions
) {
    Column {
        SmallTitle(
            text = stringResource(R.string.home_card_launch_methods_title),
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
        ) {
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_root_24dp),
                iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                title = stringResource(R.string.home_action_root_start_title),
                subtitle = stringResource(R.string.home_action_root_start_subtitle),
                onClick = actions.onStartRootClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_terminal_24),
                iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                title = stringResource(R.string.home_action_pc_start_title),
                subtitle = stringResource(R.string.home_action_pc_start_subtitle),
                onClick = actions.onStartAdbClick
            )
            if (isDeviceOwner) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_shield_24),
                    iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                    title = stringResource(R.string.dhizuku_device_owner_label),
                    subtitle = stringResource(R.string.dhizuku_remove_owner_btn),
                    badgeText = stringResource(R.string.home_badge_activated),
                    badgeColor = Color(0xFF0284C7),
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
    isDeviceOwner: Boolean,
    actions: HomeActions
) {
    Column {
        SmallTitle(
            text = stringResource(R.string.home_card_running_tools_title),
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
        ) {
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_root_24dp),
                iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                title = stringResource(R.string.home_action_root_restart_title),
                subtitle = stringResource(R.string.home_action_root_restart_subtitle),
                onClick = actions.onStartRootClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_terminal_24),
                iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                title = stringResource(R.string.home_action_pc_view_title),
                subtitle = stringResource(R.string.home_action_pc_view_subtitle),
                onClick = actions.onStartAdbClick
            )
            if (isDeviceOwner) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_shield_24),
                    iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                    title = stringResource(R.string.dhizuku_device_owner_label),
                    subtitle = stringResource(R.string.dhizuku_remove_owner_btn),
                    badgeText = stringResource(R.string.home_badge_activated),
                    badgeColor = Color(0xFF0284C7),
                    onClick = actions.onDhizukuClick
                )
            }
        }
    }
}

// ==================================================================
// 5. 核心性能调度分段切换卡 (Miuix 风格)
// ==================================================================

@Composable
private fun PerformanceModeCard(
    isDark: Boolean,
    primaryColor: Color,
    currentMode: Int,
    onModeChange: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column {
        SmallTitle(
            text = stringResource(R.string.home_card_perf_mode_title),
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0xFF141417) else Color(0xFFF0F2F6))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val modes = listOf(
                        0 to stringResource(R.string.home_perf_mode_default),
                        1 to stringResource(R.string.home_perf_mode_saver),
                        2 to stringResource(R.string.home_perf_mode_game)
                    )
                    modes.forEach { (mode, label) ->
                        val selected = currentMode == mode
                        val activeBgColor = when (mode) {
                            1 -> Color(0xFF10B981)
                            2 -> primaryColor
                            else -> if (isDark) Color(0xFF2C2C31) else Color(0xFFFFFFFF)
                        }
                        val activeTextColor = when (mode) {
                            1, 2 -> Color.White
                            else -> MiuixTheme.colorScheme.onSurface
                        }
                        val inactiveTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) activeBgColor else Color.Transparent)
                                .clickable {
                                    if (currentMode != mode) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onModeChange(mode)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) activeTextColor else inactiveTextColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDark) Color(0xFF141417) else Color(0xFFF5F6F8))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val desc = when (currentMode) {
                        1 -> stringResource(R.string.home_perf_mode_desc_saver)
                        2 -> stringResource(R.string.home_perf_mode_desc_game)
                        else -> stringResource(R.string.home_perf_mode_desc_default)
                    }
                    Text(
                        text = desc,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

// ==================================================================
// 6. 常用体验核心开关卡 (Miuix Switch & BasicComponent)
// ==================================================================

@Composable
private fun QuickSwitchesCard(
    isDark: Boolean,
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
    holdDownBatteryProtection: Boolean = false,
    onTogglePassThrough: (Boolean) -> Unit,
    onToggleBatteryProtection: (Boolean) -> Unit = {},
    onOpenBatteryProtectionConfigClick: () -> Unit = {},
    onToggleGameAutoSwitch: (Boolean) -> Unit,
    onToggleFpsMonitor: (Boolean) -> Unit,
    onToggleOutdoorMode: (Boolean) -> Unit,
    onOpenGameAutoConfigClick: () -> Unit = {}
) {
    Column {
        SmallTitle(
            text = stringResource(R.string.home_card_quick_switches_title),
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
        ) {
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_battery_24),
                iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                title = stringResource(R.string.home_switch_passthrough_title),
                subtitle = stringResource(R.string.home_switch_passthrough_subtitle),
                checked = isPassThroughActive,
                onCheckedChange = onTogglePassThrough
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_battery_24),
                iconTint = if (isDark) Color(0xFF10B981) else Color(0xFF047857),
                title = "充电上限保护",
                subtitle = "达到设定电量上限自动停止充电，保护电池循环寿命",
                badgeText = "${batteryProtectionLimit}% / ${batteryProtectionRecharge}% ›",
                checked = isBatteryProtectionActive,
                holdDownState = holdDownBatteryProtection,
                onCheckedChange = onToggleBatteryProtection,
                onRowClick = onOpenBatteryProtectionConfigClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_speed_24),
                iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                title = stringResource(R.string.home_switch_game_auto_title),
                subtitle = if (gameAutoPackageCount > 0)
                    stringResource(R.string.home_switch_game_auto_subtitle_active, gameAutoPackageCount)
                else stringResource(R.string.home_switch_game_auto_subtitle_inactive),
                badgeText = if (gameAutoPackageCount > 0)
                    stringResource(R.string.home_badge_game_count, gameAutoPackageCount)
                else stringResource(R.string.home_badge_configure_list),
                checked = isGameAutoSwitchActive,
                onCheckedChange = onToggleGameAutoSwitch,
                onRowClick = onOpenGameAutoConfigClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_code_24),
                iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                title = stringResource(R.string.home_switch_fps_title),
                subtitle = stringResource(R.string.home_switch_fps_subtitle),
                checked = isFpsMonitorActive,
                onCheckedChange = onToggleFpsMonitor
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_sun_24),
                iconTint = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777),
                title = stringResource(R.string.home_switch_outdoor_title),
                subtitle = stringResource(R.string.home_switch_outdoor_subtitle),
                checked = isOutdoorModeActive,
                onCheckedChange = onToggleOutdoorMode
            )
        }
    }
}

@Composable
private fun BackgroundPowerCard(
    isDark: Boolean,
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
    Column {
        SmallTitle(
            text = "后台与深度休眠",
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
        ) {
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_shield_24),
                iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                title = "后台进程休眠（墓碑）",
                subtitle = "启用 Android 原生 Freezer 冻结后台缓存进程，防止偷跑 CPU",
                checked = isTombstoneActive,
                onCheckedChange = onToggleTombstone
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_battery_24),
                iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                title = "熄屏深度休眠",
                subtitle = "熄屏后快速进入深度 Doze 睡眠，大幅降低夜间待机掉电",
                badgeText = "调参 ›",
                checked = isQuickDozeActive,
                onCheckedChange = onToggleQuickDoze,
                onRowClick = onOpenQuickDozeConfigClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_apps_24),
                iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                title = "限制后台自启",
                subtitle = "拦截后台广播偷起常驻服务与链式关联互拉，保持后台纯净",
                checked = isAutostartLimitActive,
                onCheckedChange = onToggleAutostartLimit
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_search_24),
                iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                title = "限制后台定位",
                subtitle = "将后台应用定位请求间隔限制为 30 分钟，降低待机耗电",
                checked = isLocationThrottleActive,
                onCheckedChange = onToggleLocationThrottle
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_sync_24),
                iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                title = "限制扫描与同步",
                subtitle = "关闭后台隐式 Wi-Fi / 蓝牙 / 附近设备扫描与无用同步",
                checked = isScanGuardActive,
                onCheckedChange = onToggleScanGuard
            )
        }
    }
}

/**
 * Miuix 风格开关项：
 * - 若无独立 `onRowClick`，直接使用 Miuix `SwitchPreference`
 * - 若带 `onRowClick`（如点击行弹出「充电上限保护」配置弹窗、点击右侧 Switch 直接启停），使用 Miuix `BasicComponent` + `Switch`
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
    onRowClick: (() -> Unit)? = null
) {
    if (onRowClick == null && badgeText == null) {
        SwitchPreference(
            title = title,
            summary = subtitle,
            checked = checked,
            holdDownState = holdDownState,
            onCheckedChange = onCheckedChange,
            startAction = {
                MiuixRowIcon(isDark = isDark, icon = icon, iconTint = iconTint)
            }
        )
    } else {
        BasicComponent(
            title = title,
            summary = subtitle,
            holdDownState = holdDownState,
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
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0x33F59E0B) else Color(0x1FF59E0B))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
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

// ==================================================================
// 7. 系统优化与维护入口卡 (Miuix ArrowPreference & SwitchPreference)
// ==================================================================

@Composable
private fun QuickActionsCard(
    isDark: Boolean,
    primaryColor: Color,
    isAppLaunchBoostActive: Boolean = false,
    isForceResizableActive: Boolean = false,
    holdDownBatteryInfo: Boolean = false,
    onOpenBatteryInfoDialog: () -> Unit = {},
    actions: HomeActions
) {
    Column {
        SmallTitle(
            text = stringResource(R.string.home_card_quick_actions_title),
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
        ) {
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_cpu_24),
                iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                title = "应用启动加速",
                subtitle = "ART 机器码 AOT 预编译与内存碎片清理，大幅提升应用冷启动速度",
                badgeText = if (isAppLaunchBoostActive) "已优化" else "去优化",
                badgeColor = if (isAppLaunchBoostActive) Color(0xFF10B981) else null,
                onClick = actions.onOpenAppLaunchBoostClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchItemRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_window_24),
                iconTint = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777),
                title = "强制所有应用分屏/小窗",
                subtitle = "解除窗口限制，让所有应用均支持分屏与多任务悬浮小窗",
                checked = isForceResizableActive,
                onCheckedChange = actions.onToggleForceResizable
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_battery_24),
                iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                title = stringResource(R.string.home_action_battery_info_title),
                subtitle = stringResource(R.string.home_action_battery_info_subtitle),
                badgeText = "实时直读",
                holdDownState = holdDownBatteryInfo,
                onClick = onOpenBatteryInfoDialog
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_shield_24),
                iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                title = stringResource(R.string.home_action_night_guard_title),
                subtitle = stringResource(R.string.home_action_night_guard_subtitle),
                onClick = actions.onNightWakeupGuardClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_maxhz_restore_24),
                iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                title = stringResource(R.string.home_action_restore_defaults_title),
                subtitle = stringResource(R.string.home_action_restore_defaults_subtitle),
                onClick = actions.onRestoreDefaultsClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_settings_outline_24dp),
                iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                title = stringResource(R.string.home_action_geek_lab_title),
                subtitle = stringResource(R.string.home_action_geek_lab_subtitle),
                onClick = actions.onOpenLaboratoryClick
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            AdbActionRow(
                isDark = isDark,
                icon = painterResource(R.drawable.ic_help_outline_24dp),
                iconTint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                title = stringResource(R.string.home_action_user_manual_title),
                subtitle = stringResource(R.string.home_action_user_manual_subtitle),
                onClick = actions.onHelpClick
            )
        }
    }
}

// ==================================================================
// Miuix 通用列表行组件
// ==================================================================

@Composable
private fun MiuixRowIcon(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color
) {
    Box(
        modifier = Modifier
            .padding(end = 14.dp)
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(iconTint.copy(alpha = if (isDark) 0.16f else 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconTint),
            modifier = Modifier.size(20.dp),
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
    onClick: () -> Unit
) {
    ArrowPreference(
        title = title,
        summary = subtitle,
        holdDownState = holdDownState,
        onClick = onClick,
        startAction = {
            MiuixRowIcon(isDark = isDark, icon = icon, iconTint = iconTint)
        },
        endActions = {
            if (badgeText != null) {
                val bg = badgeColor ?: (if (isDark) Color(0xFF2C323D) else Color(0xFFEAEFF6))
                val fg = if (badgeColor != null) Color.White else MiuixTheme.colorScheme.onSurfaceVariantActions
                Text(
                    text = badgeText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = fg,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .padding(horizontal = 6.dp, vertical = 2.5.dp)
                )
            }
        }
    )
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
    val tileBgColor = if (isDark) Color(0xFF141417) else Color(0xFFF3F4F6)

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tileBgColor)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                isAccent && ok -> if (isDark) Color(0xFF34D399) else Color(0xFF059669)
                isAccent -> primaryColor
                else -> MiuixTheme.colorScheme.onSurface
            }
        )
    }
}

// ==================================================================
// 8. Tab 2：Miuix 分应用刷新率规则组件
// ==================================================================

@Composable
private fun AppRulesTabContent(
    isDark: Boolean,
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

    var targetPackage by remember { mutableStateOf<String?>(null) }
    var targetLabel by remember { mutableStateOf<String?>(null) }
    var targetAdaptive by remember { mutableStateOf(true) }
    var targetMaxHz by remember { mutableIntStateOf(120) }
    var targetMinHz by remember { mutableIntStateOf(10) }

    val requireAccessibility: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        android.widget.Toast.makeText(context, R.string.rules_toast_accessibility_required, android.widget.Toast.LENGTH_SHORT).show()
        actions.onOpenAccessibilityClick()
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .overScrollVertical(),
        state = rulesListState,
        contentPadding = PaddingValues(
            top = with(density) { topInsetPx.toDp() },
            start = 12.dp,
            end = 12.dp,
            bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "section-rules-header") {
            SectionHeader(
                title = stringResource(R.string.home_header_rules_title),
                subtitle = stringResource(R.string.home_header_rules_subtitle),
                isDark = isDark,
                primaryColor = primaryColor
            )
        }

        // 前台无障碍感知与即时切换状态卡
        item(key = "card-accessibility-status") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiuixRowIcon(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_maxhz_speed_24),
                            iconTint = if (isInstantSwitchActive) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.rules_card_accessibility_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.rules_card_accessibility_subtitle),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isInstantSwitchActive) stringResource(R.string.rules_badge_service_running)
                            else stringResource(R.string.rules_badge_service_disabled),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isInstantSwitchActive) Color(0xFF10B981) else Color(0xFFEF4444))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (!isInstantSwitchActive) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0x28EF4444) else Color(0x18EF4444))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.rules_warning_accessibility_locked),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
                                )
                                Spacer(Modifier.height(10.dp))
                                TextButton(
                                    text = stringResource(R.string.rules_btn_enable_accessibility),
                                    onClick = actions.onOpenAccessibilityClick,
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        // 常用场景预设卡
        item(key = "card-presets") {
            Column(modifier = if (!isInstantSwitchActive) Modifier.alpha(0.42f) else Modifier) {
                SmallTitle(
                    text = stringResource(R.string.rules_card_presets_title),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presets = listOf(
                            Triple(stringResource(R.string.rules_preset_daily), Pair(120, 10), true),
                            Triple(stringResource(R.string.rules_preset_video), Pair(60, 60), false),
                            Triple(stringResource(R.string.rules_preset_reading), Pair(60, 10), true),
                            Triple(stringResource(R.string.rules_preset_game), Pair(120, 120), false)
                        )
                        presets.forEach { (name, rates, adaptive) ->
                            val isSelected = targetMaxHz == rates.first && targetMinHz == rates.second && targetAdaptive == adaptive
                            val chipBg = if (isSelected) primaryColor.copy(alpha = if (isDark) 0.25f else 0.14f)
                            else if (isDark) Color(0xFF141417) else Color(0xFFF3F4F6)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(chipBg)
                                    .clickable {
                                        if (!isInstantSwitchActive) {
                                            requireAccessibility()
                                            return@clickable
                                        }
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        targetMaxHz = rates.first
                                        targetMinHz = rates.second
                                        targetAdaptive = adaptive
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = name.substringBefore(" ("),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) primaryColor else MiuixTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${rates.second}~${rates.first}Hz",
                                        fontSize = 10.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 目标应用与规则参数卡 (Miuix ArrowPreference + SwitchPreference + OverlayDropdownPreference)
        item(key = "card-rule-form") {
            Column(modifier = if (!isInstantSwitchActive) Modifier.alpha(0.42f) else Modifier) {
                SmallTitle(
                    text = stringResource(R.string.rules_card_config_title),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                ) {
                    ArrowPreference(
                        title = targetLabel ?: stringResource(R.string.rules_select_app_hint),
                        summary = targetPackage ?: "点击从已安装应用中选择目标应用",
                        onClick = {
                            if (!isInstantSwitchActive) {
                                requireAccessibility()
                                return@ArrowPreference
                            }
                            actions.onSelectAppForAppRule { pkg, label ->
                                targetPackage = pkg
                                targetLabel = label
                                val existingRule = appRules.firstOrNull { it.packageName == pkg }
                                if (existingRule != null) {
                                    targetAdaptive = existingRule.adaptive
                                    targetMaxHz = existingRule.maxHz
                                    targetMinHz = existingRule.minHz
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

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    SwitchPreference(
                        title = stringResource(R.string.rules_switch_adaptive),
                        summary = if (targetAdaptive) stringResource(R.string.rules_switch_adaptive_summary_on)
                        else stringResource(R.string.rules_switch_adaptive_summary_off),
                        checked = targetAdaptive,
                        onCheckedChange = {
                            if (!isInstantSwitchActive) {
                                requireAccessibility()
                                return@SwitchPreference
                            }
                            targetAdaptive = it
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    val maxRates = listOf(120, 90, 60, 48)
                    val maxRateLabels = maxRates.map { "${it}Hz" }
                    val selectedMaxIndex = maxRates.indexOf(targetMaxHz).coerceAtLeast(0)
                    OverlayDropdownPreference(
                        title = stringResource(R.string.rules_label_max_hz),
                        summary = "应用在前台时允许达到的最高刷新率上限",
                        items = maxRateLabels,
                        selectedIndex = selectedMaxIndex,
                        enabled = isInstantSwitchActive,
                        onSelectedIndexChange = { idx ->
                            val hz = maxRates.getOrElse(idx) { 120 }
                            targetMaxHz = hz
                            if (targetMinHz > hz) targetMinHz = hz
                        }
                    )

                    if (targetAdaptive) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        val minRates = listOf(60, 48, 30, 24, 10).filter { it <= targetMaxHz }
                        val minRateLabels = minRates.map { "${it}Hz" }
                        val selectedMinIndex = minRates.indexOf(targetMinHz).coerceAtLeast(0)
                        OverlayDropdownPreference(
                            title = stringResource(R.string.rules_label_min_hz),
                            summary = "画面静止时允许 LTPO 降频到的最低刷新率下限",
                            items = minRateLabels,
                            selectedIndex = selectedMinIndex,
                            enabled = isInstantSwitchActive,
                            onSelectedIndexChange = { idx ->
                                val hz = minRates.getOrElse(idx) { 10 }
                                targetMinHz = hz
                            }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val pkg = targetPackage
                        val hasExisting = pkg != null && appRules.any { it.packageName == pkg }

                        TextButton(
                            text = stringResource(R.string.rules_btn_save),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (!isInstantSwitchActive) {
                                    requireAccessibility()
                                    return@TextButton
                                }
                                if (pkg.isNullOrBlank()) {
                                    actions.onSelectAppForAppRule { p, l ->
                                        targetPackage = p
                                        targetLabel = l
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
                            TextButton(
                                text = stringResource(R.string.rules_btn_delete),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    actions.onDeleteAppRule(pkg)
                                    targetPackage = null
                                    targetLabel = null
                                }
                            )
                        }
                    }
                }
            }
        }

        // 已生效规则列表卡
        item(key = "card-rules-list") {
            Column {
                SmallTitle(
                    text = stringResource(R.string.rules_card_list_title, appRules.size),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
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
                            appRules.forEachIndexed { index, rule ->
                                val label = remember(rule.packageName) {
                                    runCatching {
                                        pm.getApplicationInfo(rule.packageName, 0).loadLabel(pm).toString()
                                    }.getOrDefault(rule.packageName)
                                }
                                val baseTag = if (rule.adaptive) "${rule.minHz}~${rule.maxHz}Hz" else "${rule.maxHz}Hz"
                                val ruleTag = if (isInstantSwitchActive) baseTag else "$baseTag · ${stringResource(R.string.rules_rule_status_paused)}"
                                BasicComponent(
                                    title = label,
                                    summary = rule.packageName,
                                    onClick = {
                                        if (!isInstantSwitchActive) {
                                            requireAccessibility()
                                            return@BasicComponent
                                        }
                                        targetPackage = rule.packageName
                                        targetLabel = label
                                        targetAdaptive = rule.adaptive
                                        targetMaxHz = rule.maxHz
                                        targetMinHz = rule.minHz
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
                                            fontWeight = FontWeight.Medium,
                                            color = primaryColor,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(primaryColor.copy(alpha = 0.14f))
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
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
                                                color = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
                                            )
                                        }
                                    }
                                )
                                if (index < appRules.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                TextButton(
                                    text = stringResource(R.string.rules_btn_clear_all),
                                    onClick = actions.onClearAppRules,
                                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 流媒体高刷兼容策略指南卡
        item(key = "card-stream-compat-guide") {
            Column {
                SmallTitle(
                    text = stringResource(R.string.rules_compat_policy_tip_title),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.rules_compat_policy_tip_title),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.rules_compat_policy_tip_desc),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            text = stringResource(R.string.rules_btn_open_dev_options),
                            onClick = actions.onOpenDisableAppCompatibilityPoliciesClick,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 屏幕自适应降频与功耗校准卡
        item(key = "card-calibrate-ltpo-power") {
            Column {
                SmallTitle(
                    text = stringResource(R.string.rules_calibrate_ltpo_title),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.rules_calibrate_ltpo_title),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.rules_calibrate_ltpo_desc),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            text = stringResource(R.string.rules_btn_calibrate_ltpo),
                            onClick = actions.onCalibrateLtpoAdaptiveClick,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item(key = "dockClearance-rules") {
            Spacer(Modifier.height(78.dp))
        }
    }
}
