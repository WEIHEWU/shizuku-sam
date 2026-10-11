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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
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

/**
 * 方案 A：以 ADB 启动与配对为主体、兼具 Root 自由选择的极简控制台中枢。
 *
 * 核心层级分明：
 * 【主功能】：ADB 核心特权中枢为主（状态指示、一键启动、无线调试免输入自动配对、手动输码、开发者选项），
 *            同时提供自由选择的特权启动通道（ADB 启动 / Root 启动 / 电脑 USB 启动 / Dhizuku）。
 * 【附加功能】：三星系统调度增强（系统默认 / 省电自适应 / 游戏满血、旁路供电、FPS浮窗、系统安全重置）。
 * 【应用规则】：按前台应用独立分配自适应或固定刷新率。
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
    val onModeChange: (Int) -> Unit = {},
    val onTogglePassThrough: (Boolean) -> Unit = {},
    val onToggleBatteryProtection: (Boolean) -> Unit = {},
    val onOpenBatteryProtectionConfigClick: () -> Unit = {},
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
        Color(0xFF111418)
    } else {
        Color(0xFFF7F9FC)
    }

    val shizukuListState = rememberLazyListState()
    val perfListState = rememberLazyListState()
    val rulesListState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(bgColor)) {
        when (currentTab) {
            0 -> {
                // ==========================================================
                // Tab 0：【特权服务】（纯粹专注 Shizuku 核心、启动配对与授权，一屏即全貌）
                // ==========================================================
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = shizukuListState,
                    contentPadding = PaddingValues(
                        top = with(density) { topInsetPx.toDp() },
                        start = 16.dp, end = 16.dp,
                        bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                // 1. 标头
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

                // 2. 服务状态总览与极速启动卡 (ADB 极速启动 / Root 启动自由选择)
                item(key = "hero", contentType = "hero") {
                    HeroCard(
                        isDark = isDark,
                        primaryColor = primaryColor,
                        status = status,
                        grantedCount = grantedCount,
                        activePort = activePort,
                        isWifiConnected = isWifiConnected,
                        isDeviceOwner = isDeviceOwner,
                        actions = actions
                    )
                }

                // 3. 未运行状态：无线调试配对中枢 (核心步骤) 与 自由选择特权启动方式
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
                    // 运行中状态：特权通道与备用连接
                    item(key = "adb-running-tools", contentType = "tools") {
                        AdbRunningToolsCard(
                            isDark = isDark,
                            isDeviceOwner = isDeviceOwner,
                            actions = actions
                        )
                    }
                }

                // dock 避让区
                item(key = "dockClearance-0", contentType = "spacer") {
                    Spacer(Modifier.height(78.dp))
                }
            }
        }
        1 -> {
                // ==========================================================
                // Tab 1：【性能硬件】（专精三星调度、旁路供电、后台休眠、FPS监控与维护）
                // ==========================================================
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = perfListState,
                    contentPadding = PaddingValues(
                        top = with(density) { topInsetPx.toDp() },
                        start = 16.dp, end = 16.dp,
                        bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. 标头
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

                    // 2. 核心调度档位 (系统默认 / 省电自适应 / 游戏满血)
                    item(key = "perf-mode", contentType = "perf") {
                        PerformanceModeCard(
                            isDark = isDark,
                            primaryColor = primaryColor,
                            currentMode = gameTileMode,
                            onModeChange = actions.onModeChange
                        )
                    }

                    // 3. 常用体验与供电保护 (旁路供电 / 充电上限保护 / 游戏自切满血 / 实时FPS / 户外高亮)
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
                            onTogglePassThrough = actions.onTogglePassThrough,
                            onToggleBatteryProtection = actions.onToggleBatteryProtection,
                            onOpenBatteryProtectionConfigClick = actions.onOpenBatteryProtectionConfigClick,
                            onToggleGameAutoSwitch = actions.onToggleGameAutoSwitch,
                            onToggleFpsMonitor = actions.onToggleFpsMonitor,
                            onToggleOutdoorMode = actions.onToggleOutdoorMode,
                            onOpenGameAutoConfigClick = actions.onOpenGameAutoConfigClick
                        )
                    }

                    // 4. 后台与深度休眠 (墓碑休眠 / 熄屏深度Doze / 限制后台自启 / 限制后台定位 / 限制扫描与同步)
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

                    // 5. 系统优化与极客拓展入口 (应用启动加速 / 强制所有应用分屏小窗 / 电池健康 / 唤醒排查 / 极客实验室 / 恢复默认)
                    item(key = "quick-actions", contentType = "actions") {
                        QuickActionsCard(
                            isDark = isDark,
                            primaryColor = primaryColor,
                            isAppLaunchBoostActive = isAppLaunchBoostActive,
                            isForceResizableActive = isForceResizableActive,
                            actions = actions
                        )
                    }

                    // dock 避让区
                    item(key = "dockClearance-1", contentType = "spacer") {
                        Spacer(Modifier.height(78.dp))
                    }
                }
            }
            2 -> {
                // ==========================================================
                // Tab 2：【应用规则】（针对独立应用设定专属自适应或固定帧率）
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
    }
}

// ==================================================================
// 0. 分类标题组件
// ==================================================================

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    isDark: Boolean,
    primaryColor: Color
) {
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 4.dp)) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark))
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            )
        }
    }
}

// ==================================================================
// 1. Hero 服务状态与启动通道卡 (提供 ADB 与 Root 自由选择)
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
    actions: HomeActions
) {
    val ok = status?.isRunning == true
    val isRoot = status?.uid == 0
    val apiVersion = status?.apiVersion ?: 0
    val patchVersion = status?.patchVersion ?: 0

    GlassCard(isDark = isDark) {
        Column(Modifier.padding(16.dp)) {
            // 顶部状态行：图标 + 标题/副标题 + 徽章
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
                        color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                    )
                    Spacer(Modifier.height(3.dp))
                    val runningModeName = if (isRoot) "Root" else "ADB"
                    Text(
                        text = if (ok) stringResource(R.string.home_status_activated_summary, runningModeName, apiVersion, patchVersion, "")
                        else if (activePort > 0) stringResource(R.string.home_status_hint_port_found, activePort)
                        else if (!isWifiConnected) stringResource(R.string.home_status_hint_no_wifi)
                        else stringResource(R.string.home_status_hint_ready),
                        fontSize = 12.5.sp,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
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
            Divider(isDark)
            Spacer(Modifier.height(14.dp))

            if (ok) {
                // 运行中：3 个核心数据磁贴
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

                // 运行中核心操作 1：管理已授权应用 (用户最常使用)
                GlassPillButton(
                    text = stringResource(R.string.home_btn_manage_authorized_apps, grantedCount),
                    isDark = isDark,
                    style = GlassButtonStyle.Prominent(primaryColor),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    onClick = actions.onAuthorizedAppsClick
                )

                Spacer(Modifier.height(10.dp))

                // 运行中副操作行：终端、重连无线调试 / 重启、停止服务
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassPillButton(
                        text = stringResource(R.string.home_btn_terminal_rish),
                        isDark = isDark,
                        style = GlassButtonStyle.Regular,
                        modifier = Modifier.weight(1f),
                        onClick = actions.onRishClick
                    )
                    if (isRoot) {
                        GlassPillButton(
                            text = stringResource(R.string.home_btn_restart_service),
                            isDark = isDark,
                            style = GlassButtonStyle.Regular,
                            modifier = Modifier.weight(1f),
                            onClick = actions.onStartRootClick
                        )
                    } else {
                        GlassPillButton(
                            text = stringResource(R.string.home_btn_reconnect_debugging),
                            isDark = isDark,
                            style = GlassButtonStyle.Regular,
                            modifier = Modifier.weight(1f),
                            onClick = actions.onWirelessStartClick
                        )
                    }
                    GlassPillButton(
                        text = stringResource(R.string.home_btn_stop_service),
                        isDark = isDark,
                        style = GlassButtonStyle.Danger,
                        onClick = actions.onStopServiceClick
                    )
                }
            } else {
                // 未运行：提供【ADB 启动】、【Root 启动】与【Dhizuku 模式】特权通道
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. 无线调试启动 (ADB)
                    GlassPillButton(
                        text = stringResource(R.string.home_btn_adb_start),
                        isDark = isDark,
                        style = GlassButtonStyle.Prominent(Color(0xFF10B981)),
                        modifier = Modifier.weight(1.4f).height(44.dp),
                        onClick = actions.onWirelessStartClick
                    )
                    // 2. Root 启动
                    GlassPillButton(
                        text = stringResource(R.string.home_btn_root_start),
                        isDark = isDark,
                        style = GlassButtonStyle.Regular,
                        modifier = Modifier.weight(1f).height(44.dp),
                        onClick = actions.onStartRootClick
                    )
                }
            }
        }
    }
}

// ==================================================================
// 2. 未运行状态：无线调试配对中枢卡 (核心步骤)
// ==================================================================

@Composable
private fun AdbPairingHubCard(
    isDark: Boolean,
    primaryColor: Color,
    isAutoPairingEnabled: Boolean,
    actions: HomeActions
) {
    Column {
        Text(
            text = stringResource(R.string.home_card_pairing_hub_title),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
        )
        GlassCard(isDark = isDark) {
            Column {
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
                Divider(isDark)
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_code_24dp),
                    iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                    title = stringResource(R.string.home_action_manual_pairing_title),
                    subtitle = stringResource(R.string.home_action_manual_pairing_subtitle),
                    onClick = actions.onWirelessPairClick
                )
                Divider(isDark)
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
}

// ==================================================================
// 3. 未运行状态：特权启动方式选择卡 (Root / 电脑 / Dhizuku 自由选择)
// ==================================================================

@Composable
private fun PrivilegeLaunchMethodsCard(
    isDark: Boolean,
    isDeviceOwner: Boolean,
    actions: HomeActions
) {
    Column {
        Text(
            text = stringResource(R.string.home_card_launch_methods_title),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
        )
        GlassCard(isDark = isDark) {
            Column {
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_root_24dp),
                    iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                    title = stringResource(R.string.home_action_root_start_title),
                    subtitle = stringResource(R.string.home_action_root_start_subtitle),
                    onClick = actions.onStartRootClick
                )
                Divider(isDark)
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_terminal_24),
                    iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                    title = stringResource(R.string.home_action_pc_start_title),
                    subtitle = stringResource(R.string.home_action_pc_start_subtitle),
                    onClick = actions.onStartAdbClick
                )
                if (isDeviceOwner) {
                    Divider(isDark)
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
        Text(
            text = stringResource(R.string.home_card_running_tools_title),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
        )
        GlassCard(isDark = isDark) {
            Column {
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_root_24dp),
                    iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                    title = stringResource(R.string.home_action_root_restart_title),
                    subtitle = stringResource(R.string.home_action_root_restart_subtitle),
                    onClick = actions.onStartRootClick
                )
                Divider(isDark)
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_terminal_24),
                    iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                    title = stringResource(R.string.home_action_pc_view_title),
                    subtitle = stringResource(R.string.home_action_pc_view_subtitle),
                    onClick = actions.onStartAdbClick
                )
                if (isDeviceOwner) {
                    Divider(isDark)
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
}

// ==================================================================
// 5. 核心性能调度分段切换卡 (附加功能)
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
        Text(
            text = stringResource(R.string.home_card_perf_mode_title),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
        )
        GlassCard(isDark = isDark) {
            Column(Modifier.padding(14.dp)) {
                // 三段式分段切换按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0xFF181C24) else Color(0xFFEAEFF6))
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
                            1 -> Color(0xFF10B981) // 绿色
                            2 -> Color(0xFF3B82F6) // 蓝色
                            else -> if (isDark) Color(0xFF333B4A) else Color(0xFFFFFFFF)
                        }
                        val activeTextColor = when (mode) {
                            1, 2 -> Color.White
                            else -> if (isDark) Color.White else Color(0xFF0F172A)
                        }
                        val inactiveTextColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

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

                // 说明文本框
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDark) Color(0xFF151921) else Color(0xFFF1F4F9))
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
                        color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
                    )
                }
            }
        }
    }
}

// ==================================================================
// 6. 常用体验核心开关卡 (附加功能)
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
    onTogglePassThrough: (Boolean) -> Unit,
    onToggleBatteryProtection: (Boolean) -> Unit = {},
    onOpenBatteryProtectionConfigClick: () -> Unit = {},
    onToggleGameAutoSwitch: (Boolean) -> Unit,
    onToggleFpsMonitor: (Boolean) -> Unit,
    onToggleOutdoorMode: (Boolean) -> Unit,
    onOpenGameAutoConfigClick: () -> Unit = {}
) {
    Column {
        Text(
            text = stringResource(R.string.home_card_quick_switches_title),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
        )
        GlassCard(isDark = isDark) {
            Column {
                SwitchItemRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_battery_24),
                    iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                    title = stringResource(R.string.home_switch_passthrough_title),
                    subtitle = stringResource(R.string.home_switch_passthrough_subtitle),
                    checked = isPassThroughActive,
                    onCheckedChange = onTogglePassThrough
                )
                Divider(isDark)
                SwitchItemRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_battery_24),
                    iconTint = if (isDark) Color(0xFF10B981) else Color(0xFF047857),
                    title = "充电上限保护",
                    subtitle = "达到设定电量上限自动停止充电，保护电池循环寿命",
                    badgeText = "${batteryProtectionLimit}% / ${batteryProtectionRecharge}% ›",
                    checked = isBatteryProtectionActive,
                    onCheckedChange = onToggleBatteryProtection,
                    onRowClick = onOpenBatteryProtectionConfigClick
                )
                Divider(isDark)
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
                Divider(isDark)
                SwitchItemRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_code_24),
                    iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                    title = stringResource(R.string.home_switch_fps_title),
                    subtitle = stringResource(R.string.home_switch_fps_subtitle),
                    checked = isFpsMonitorActive,
                    onCheckedChange = onToggleFpsMonitor
                )
                Divider(isDark)
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
        Text(
            text = "后台与深度休眠",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
        )
        GlassCard(isDark = isDark) {
            Column {
                SwitchItemRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_shield_24),
                    iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                    title = "后台进程休眠（墓碑）",
                    subtitle = "启用 Android 原生 Freezer 冻结后台缓存进程，防止偷跑 CPU",
                    checked = isTombstoneActive,
                    onCheckedChange = onToggleTombstone
                )
                Divider(isDark)
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
                Divider(isDark)
                SwitchItemRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_apps_24),
                    iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                    title = "限制后台自启",
                    subtitle = "拦截后台广播偷起常驻服务与链式关联互拉，保持后台纯净",
                    checked = isAutostartLimitActive,
                    onCheckedChange = onToggleAutostartLimit
                )
                Divider(isDark)
                SwitchItemRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_search_24),
                    iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                    title = "限制后台定位",
                    subtitle = "将后台应用定位请求间隔限制为 30 分钟，降低待机耗电",
                    checked = isLocationThrottleActive,
                    onCheckedChange = onToggleLocationThrottle
                )
                Divider(isDark)
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
}

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
    onRowClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (onRowClick != null) {
                    onRowClick()
                } else {
                    onCheckedChange(!checked)
                }
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isDark) Color(0xFF222733) else Color(0xFFEFF3F8))
                .border(1.dp, if (isDark) Color(0x1FFFFFFF) else Color(0x0C000000), RoundedCornerShape(10.dp)),
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
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                )
                if (badgeText != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isDark) Color(0x33F59E0B) else Color(0x1FF59E0B))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    if (checked) Color(0xFF10B981)
                    else if (isDark) Color(0xFF333B4A)
                    else Color(0xFFCBD5E1)
                )
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(!checked)
                },
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
            )
        }
    }
}

// ==================================================================
// 7. 系统优化与维护入口卡 (附加功能)
// ==================================================================

@Composable
private fun QuickActionsCard(
    isDark: Boolean,
    primaryColor: Color,
    isAppLaunchBoostActive: Boolean = false,
    isForceResizableActive: Boolean = false,
    actions: HomeActions
) {
    Column {
        Text(
            text = stringResource(R.string.home_card_quick_actions_title),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
        )
        GlassCard(isDark = isDark) {
            Column {
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
                Divider(isDark)
                SwitchItemRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_window_24),
                    iconTint = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777),
                    title = "强制所有应用分屏/小窗",
                    subtitle = "解除窗口限制，让所有应用均支持分屏与多任务悬浮小窗",
                    checked = isForceResizableActive,
                    onCheckedChange = actions.onToggleForceResizable
                )
                Divider(isDark)
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_battery_24),
                    iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                    title = stringResource(R.string.home_action_battery_info_title),
                    subtitle = stringResource(R.string.home_action_battery_info_subtitle),
                    badgeText = "查看 ›",
                    onClick = actions.onBatteryInfoClick
                )
                Divider(isDark)
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_shield_24),
                    iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                    title = stringResource(R.string.home_action_night_guard_title),
                    subtitle = stringResource(R.string.home_action_night_guard_subtitle),
                    onClick = actions.onNightWakeupGuardClick
                )
                Divider(isDark)
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_maxhz_restore_24),
                    iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                    title = stringResource(R.string.home_action_restore_defaults_title),
                    subtitle = stringResource(R.string.home_action_restore_defaults_subtitle),
                    onClick = actions.onRestoreDefaultsClick
                )
                Divider(isDark)
                AdbActionRow(
                    isDark = isDark,
                    icon = painterResource(R.drawable.ic_settings_outline_24dp),
                    iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                    title = stringResource(R.string.home_action_geek_lab_title),
                    subtitle = stringResource(R.string.home_action_geek_lab_subtitle),
                    onClick = actions.onOpenLaboratoryClick
                )
                Divider(isDark)
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
}

// ==================================================================
// 通用基础组件
// ==================================================================

@Composable
private fun AdbActionRow(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155),
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color? = null,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isDark) Color(0xFF222733) else Color(0xFFEFF3F8))
                .border(1.dp, if (isDark) Color(0x1FFFFFFF) else Color(0x0C000000), RoundedCornerShape(10.dp)),
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
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            )
        }
        if (badgeText != null) {
            Spacer(Modifier.width(8.dp))
            val bg = badgeColor ?: (if (isDark) Color(0xFF2C323D) else Color(0xFFEAEFF6))
            val fg = if (badgeColor != null) Color.White else (if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569))
            Text(
                text = badgeText,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .padding(horizontal = 6.dp, vertical = 2.5.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "›",
            fontSize = 17.sp,
            color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
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
    val tileBgColor = if (isDark) Color(0xFF252A34) else Color(0xFFF1F4F9)
    val tileBorderColor = if (isDark) Color(0x1FFFFFFF) else Color(0x0C000000)

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tileBgColor)
            .border(1.dp, tileBorderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                isAccent && ok -> if (isDark) Color(0xFF34D399) else Color(0xFF059669)
                isAccent -> Contrast.ensure(primaryColor, Contrast.glassSurface(isDark))
                isDark -> Color.White
                else -> Color(0xFF1E293B)
            }
        )
    }
}

@Composable
private fun Divider(isDark: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000))
    )
}

// ==================================================================
// 7. Tab 2：分应用刷新率规则组件
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
        Modifier.fillMaxSize(),
        state = rulesListState,
        contentPadding = PaddingValues(
            top = with(density) { topInsetPx.toDp() },
            start = 16.dp, end = 16.dp,
            bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 标头
        item(key = "section-rules-header") {
            SectionHeader(
                title = stringResource(R.string.home_header_rules_title),
                subtitle = stringResource(R.string.home_header_rules_subtitle),
                isDark = isDark,
                primaryColor = primaryColor
            )
        }

        // 2. 前台无障碍感知与即时切换状态卡
        item(key = "card-accessibility-status") {
            GlassCard(isDark = isDark) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDark) Color(0xFF222733) else Color(0xFFEFF3F8))
                                .border(1.dp, if (isDark) Color(0x1FFFFFFF) else Color(0x0C000000), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_maxhz_speed_24),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(if (isInstantSwitchActive) Color(0xFF10B981) else Color(0xFFEF4444)),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.rules_card_accessibility_title),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.rules_card_accessibility_subtitle),
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (isInstantSwitchActive) {
                            Text(
                                text = stringResource(R.string.rules_badge_service_running),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF10B981))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.rules_badge_service_disabled),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFEF4444))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (!isInstantSwitchActive) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDark) Color(0x28EF4444) else Color(0x1DEF4444))
                                .border(1.dp, Color(0x40EF4444), RoundedCornerShape(10.dp))
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
                                GlassPillButton(
                                    text = stringResource(R.string.rules_btn_enable_accessibility),
                                    isDark = isDark,
                                    style = GlassButtonStyle.Prominent(Color(0xFFEF4444)),
                                    onClick = actions.onOpenAccessibilityClick
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 常用场景预设卡
        item(key = "card-presets") {
            Column(modifier = if (!isInstantSwitchActive) Modifier.alpha(0.42f) else Modifier) {
                Text(
                    text = stringResource(R.string.rules_card_presets_title),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
                )
                GlassCard(isDark = isDark) {
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
                            val chipBg = if (isDark) Color(0xFF252A34) else Color(0xFFEFF3F8)
                            val chipBorder = if (isDark) Color(0x1FFFFFFF) else Color(0x0C000000)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(chipBg)
                                    .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
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
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                                    )
                                    Text(
                                        text = "${rates.second}~${rates.first}Hz",
                                        fontSize = 9.5.sp,
                                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. 目标应用与规则参数卡
        item(key = "card-rule-form") {
            Column(modifier = if (!isInstantSwitchActive) Modifier.alpha(0.42f) else Modifier) {
                Text(
                    text = stringResource(R.string.rules_card_config_title),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
                )
                GlassCard(isDark = isDark) {
                    Column(Modifier.padding(16.dp)) {
                        // 目标应用选择器
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0xFF252A34) else Color(0xFFEFF3F8))
                                .border(1.dp, if (isDark) Color(0x1FFFFFFF) else Color(0x0C000000), RoundedCornerShape(12.dp))
                                .clickable {
                                    if (!isInstantSwitchActive) {
                                        requireAccessibility()
                                        return@clickable
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isDark) Color(0xFF333B4A) else Color(0xFFE2E8F0)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_maxhz_apps_24),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(primaryColor),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = targetLabel ?: stringResource(R.string.rules_select_app_hint),
                                    fontSize = 14.sp,
                                    fontWeight = if (targetLabel != null) FontWeight.Bold else FontWeight.Normal,
                                    color = if (targetLabel != null) (if (isDark) Color.White else Color(0xFF1E293B))
                                            else (if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B))
                                )
                                if (targetPackage != null) {
                                    Text(
                                        text = targetPackage!!,
                                        fontSize = 11.sp,
                                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                }
                            }
                            Text(
                                text = "›",
                                fontSize = 18.sp,
                                color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // 自适应刷新率 Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.rules_switch_adaptive),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (targetAdaptive) stringResource(R.string.rules_switch_adaptive_summary_on)
                                           else stringResource(R.string.rules_switch_adaptive_summary_off),
                                    fontSize = 11.5.sp,
                                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                )
                            }
                            Switch(
                                checked = targetAdaptive,
                                onCheckedChange = {
                                    if (!isInstantSwitchActive) {
                                        requireAccessibility()
                                        return@Switch
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    targetAdaptive = it
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = if (isDark) Color(0xFF10B981) else Color(0xFF059669),
                                    uncheckedThumbColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    uncheckedTrackColor = if (isDark) Color(0xFF333B4A) else Color(0xFFE2E8F0)
                                )
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        // 最高刷新率选择
                        Text(
                            text = stringResource(R.string.rules_label_max_hz),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val maxRates = listOf(120, 90, 60, 48)
                            maxRates.forEach { hz ->
                                val selected = targetMaxHz == hz
                                val chipBg = if (selected) (if (isDark) Color(0xFF3B82F6) else Color(0xFF2563EB))
                                             else (if (isDark) Color(0xFF252A34) else Color(0xFFEFF3F8))
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(chipBg)
                                        .clickable {
                                            if (!isInstantSwitchActive) {
                                                requireAccessibility()
                                                return@clickable
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            targetMaxHz = hz
                                            if (targetMinHz > hz) targetMinHz = hz
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${hz}Hz",
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) Color.White else (if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B))
                                    )
                                }
                            }
                        }

                        if (targetAdaptive) {
                            Spacer(Modifier.height(12.dp))
                            // 最低刷新率选择
                            Text(
                                text = stringResource(R.string.rules_label_min_hz),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val minRates = listOf(60, 48, 30, 24, 10).filter { it <= targetMaxHz }
                                minRates.forEach { hz ->
                                    val selected = targetMinHz == hz
                                    val chipBg = if (selected) (if (isDark) Color(0xFF10B981) else Color(0xFF059669))
                                                 else (if (isDark) Color(0xFF252A34) else Color(0xFFEFF3F8))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(chipBg)
                                            .clickable {
                                                if (!isInstantSwitchActive) {
                                                    requireAccessibility()
                                                    return@clickable
                                                }
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                targetMinHz = hz
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${hz}Hz",
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) Color.White else (if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B))
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        // 保存与删除按钮行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val pkg = targetPackage
                            val hasExisting = pkg != null && appRules.any { it.packageName == pkg }

                            GlassPillButton(
                                text = stringResource(R.string.rules_btn_save),
                                isDark = isDark,
                                style = GlassButtonStyle.Prominent(if (isInstantSwitchActive) Color(0xFF2563EB) else Color(0xFF64748B)),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (!isInstantSwitchActive) {
                                        requireAccessibility()
                                        return@GlassPillButton
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
                                GlassPillButton(
                                    text = stringResource(R.string.rules_btn_delete),
                                    isDark = isDark,
                                    style = GlassButtonStyle.Danger,
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
        }

        // 5. 已生效规则列表卡
        item(key = "card-rules-list") {
            Column {
                Text(
                    text = stringResource(R.string.rules_card_list_title, appRules.size),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
                )
                GlassCard(isDark = isDark) {
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
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (!isInstantSwitchActive) {
                                                requireAccessibility()
                                                return@clickable
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            targetPackage = rule.packageName
                                            targetLabel = label
                                            targetAdaptive = rule.adaptive
                                            targetMaxHz = rule.maxHz
                                            targetMinHz = rule.minHz
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isDark) Color(0xFF222733) else Color(0xFFEFF3F8)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            painter = painterResource(R.drawable.ic_maxhz_apps_24),
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(primaryColor),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = label,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = rule.packageName,
                                            fontSize = 11.sp,
                                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    val baseTag = if (rule.adaptive) "${rule.minHz}~${rule.maxHz}Hz" else "${rule.maxHz}Hz"
                                    val ruleTag = if (isInstantSwitchActive) baseTag else "$baseTag · ${stringResource(R.string.rules_rule_status_paused)}"
                                    val tagBg = if (!isInstantSwitchActive) {
                                        if (isDark) Color(0x2864748B) else Color(0x1864748B)
                                    } else if (rule.adaptive) {
                                        if (isDark) Color(0x2810B981) else Color(0x1D10B981)
                                    } else {
                                        if (isDark) Color(0x283B82F6) else Color(0x1D3B82F6)
                                    }
                                    val tagFg = if (!isInstantSwitchActive) {
                                        if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    } else if (rule.adaptive) {
                                        if (isDark) Color(0xFF34D399) else Color(0xFF059669)
                                    } else {
                                        if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
                                    }
                                    Text(
                                        text = ruleTag,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = tagFg,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(tagBg)
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
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
                                if (index < appRules.lastIndex) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .height(0.6.dp)
                                            .background(if (isDark) Color(0x18FFFFFF) else Color(0x0E000000))
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = stringResource(R.string.rules_btn_clear_all),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            actions.onClearAppRules()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 6. 流媒体高刷兼容策略指南卡 (抖音/B站等 60Hz 限制解锁)
        item(key = "card-stream-compat-guide") {
            Column {
                Text(
                    text = stringResource(R.string.rules_compat_policy_tip_title),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
                )
                GlassCard(isDark = isDark) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isDark) Color(0xFF332B1E) else Color(0xFFFEF3C7)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "💡", fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.rules_compat_policy_tip_title),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.rules_compat_policy_tip_desc),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                        Spacer(Modifier.height(12.dp))
                        GlassPillButton(
                            text = stringResource(R.string.rules_btn_open_dev_options),
                            isDark = isDark,
                            style = GlassButtonStyle.Prominent(Color(0xFF3B82F6)),
                            onClick = actions.onOpenDisableAppCompatibilityPoliciesClick
                        )
                    }
                }
            }
        }

        // 7. 屏幕自适应降频与功耗校准卡 (S24 Ultra / Galaxy 1Hz~24Hz LTPO 降频激活)
        item(key = "card-calibrate-ltpo-power") {
            Column {
                Text(
                    text = stringResource(R.string.rules_calibrate_ltpo_title),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp)
                )
                GlassCard(isDark = isDark) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isDark) Color(0xFF1E3A2F) else Color(0xFFD1FAE5)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "⚡", fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.rules_calibrate_ltpo_title),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.rules_calibrate_ltpo_desc),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                        Spacer(Modifier.height(12.dp))
                        GlassPillButton(
                            text = stringResource(R.string.rules_btn_calibrate_ltpo),
                            isDark = isDark,
                            style = GlassButtonStyle.Prominent(Color(0xFF10B981)),
                            onClick = actions.onCalibrateLtpoAdaptiveClick
                        )
                    }
                }
            }
        }

        // dock 避让区
        item(key = "dockClearance-rules") {
            Spacer(Modifier.height(78.dp))
        }
    }
}

