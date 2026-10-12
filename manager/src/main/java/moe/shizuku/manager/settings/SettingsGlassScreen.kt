package moe.shizuku.manager.settings

import android.content.ComponentName
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.home.GroupHeaderWithToggle
import moe.shizuku.manager.home.MiuixRowIcon
import moe.shizuku.manager.home.MiuixSubPageTopBar
import moe.shizuku.manager.home.onLongPressToggle
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.RootBootScriptHelper
import rikka.material.app.LocaleDelegate
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Locale

@Composable
fun SettingsGlassScreen(
    isDark: Boolean,
    isBlackNightTheme: Boolean,
    primaryColor: Color = if (isDark) Color(0xFF277AF7) else Color(0xFF3482FF),
    onBackClick: () -> Unit,
    onRecreate: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val bgColor = if (isBlackNightTheme) {
        Color.Black
    } else {
        MiuixTheme.colorScheme.surface
    }

    val bootComponent = remember { ComponentName(context.packageName, BootCompleteReceiver::class.java.name) }
    var startOnBoot by remember { mutableStateOf(context.packageManager.isComponentEnabled(bootComponent)) }
    var watchdog by remember { mutableStateOf(ShizukuSettings.getWatchdog()) }
    var tcpMode by remember { mutableStateOf(ShizukuSettings.getTcpMode()) }
    var tcpPort by remember { mutableIntStateOf(ShizukuSettings.getTcpPort()) }
    var nightMode by remember { mutableIntStateOf(ShizukuSettings.getNightMode()) }
    var blackNightTheme by remember { mutableStateOf(ThemeHelper.isBlackNightTheme(context)) }
    var useSystemColor by remember { mutableStateOf(ThemeHelper.isUsingSystemColor()) }

    var showPortDialog by remember { mutableStateOf(false) }
    var showNightModeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    var startupExpanded by remember { mutableStateOf(false) }
    var appearanceExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = bgColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                top = 54.dp,
                start = 14.dp,
                end = 14.dp,
                bottom = 48.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Miuix 顶栏标头（支持长按展开说明）
            item(key = "header-top") {
                MiuixSubPageTopBar(
                    title = stringResource(R.string.settings_title),
                    subtitle = "管理开机自启、服务进程守护、界面深浅色外观与多语言偏好",
                    isDark = isDark,
                    primaryColor = primaryColor,
                    onBackClick = onBackClick
                )
            }

            // 1. 启动与常驻
            item(key = "section-startup") {
                Column {
                    GroupHeaderWithToggle(
                        text = stringResource(R.string.settings_section_startup),
                        isDark = isDark,
                        primaryColor = primaryColor,
                        expanded = startupExpanded,
                        onToggle = { startupExpanded = !startupExpanded }
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        SettingsMiuixSwitchRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_root_24dp),
                            iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                            title = stringResource(R.string.settings_start_on_boot),
                            subtitle = stringResource(R.string.settings_start_on_boot_summary),
                            checked = startOnBoot,
                            groupExpanded = startupExpanded,
                            onCheckedChange = { checked ->
                                context.packageManager.setComponentEnabled(bootComponent, checked)
                                startOnBoot = context.packageManager.isComponentEnabled(bootComponent)
                                ShizukuSettings.getPreferences().edit().putBoolean(ShizukuSettings.KEEP_START_ON_BOOT, checked).apply()
                                if (checked) {
                                    ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ROOT)
                                    RootBootScriptHelper.installBootScript()
                                } else {
                                    RootBootScriptHelper.removeBootScript()
                                }
                            }
                        )
                        SettingsMiuixSwitchRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_maxhz_shield_24),
                            iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF10B981),
                            title = stringResource(R.string.settings_watchdog),
                            subtitle = stringResource(R.string.settings_watchdog_summary),
                            checked = watchdog,
                            groupExpanded = startupExpanded,
                            onCheckedChange = { checked ->
                                ShizukuSettings.setWatchdog(context, checked)
                                watchdog = ShizukuSettings.getWatchdog()
                            }
                        )
                        SettingsMiuixSwitchRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_wadb_24),
                            iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                            title = stringResource(R.string.settings_tcp_mode),
                            subtitle = stringResource(R.string.settings_tcp_mode_summary),
                            checked = tcpMode,
                            groupExpanded = startupExpanded,
                            onCheckedChange = { checked ->
                                ShizukuSettings.setTcpMode(checked)
                                tcpMode = ShizukuSettings.getTcpMode()
                            }
                        )
                        SettingsMiuixActionRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_terminal_24),
                            iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                            title = stringResource(R.string.settings_tcp_port),
                            subtitle = "设置在启用 TCP 端口监听时使用的固定本地端口号（默认 5555）",
                            valueText = "$tcpPort",
                            groupExpanded = startupExpanded,
                            onClick = { showPortDialog = true }
                        )
                    }
                }
            }

            // 2. 界面外观与主题
            item(key = "section-appearance") {
                Column {
                    GroupHeaderWithToggle(
                        text = stringResource(R.string.settings_section_appearance),
                        isDark = isDark,
                        primaryColor = primaryColor,
                        expanded = appearanceExpanded,
                        onToggle = { appearanceExpanded = !appearanceExpanded }
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        val nightModeSummary = when (nightMode) {
                            AppCompatDelegate.MODE_NIGHT_NO -> stringResource(R.string.settings_night_mode_off)
                            AppCompatDelegate.MODE_NIGHT_YES -> stringResource(R.string.settings_night_mode_on)
                            else -> stringResource(R.string.settings_night_mode_follow_system)
                        }
                        SettingsMiuixActionRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_outline_dark_mode_24),
                            iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                            title = stringResource(R.string.dark_theme),
                            subtitle = "切换应用界面的浅色、深色或跟随系统深色模式策略",
                            valueText = nightModeSummary,
                            groupExpanded = appearanceExpanded,
                            onClick = { showNightModeDialog = true }
                        )
                        if (nightMode != AppCompatDelegate.MODE_NIGHT_NO) {
                            SettingsMiuixSwitchRow(
                                isDark = isDark,
                                icon = painterResource(R.drawable.ic_maxhz_sun_24),
                                iconTint = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5),
                                title = stringResource(R.string.settings_black_night_theme),
                                subtitle = stringResource(R.string.settings_black_night_theme_summary),
                                checked = blackNightTheme,
                                groupExpanded = appearanceExpanded,
                                onCheckedChange = { checked ->
                                    ShizukuSettings.getPreferences().edit().putBoolean(ThemeHelper.KEY_BLACK_NIGHT_THEME, checked).apply()
                                    blackNightTheme = checked
                                    onRecreate()
                                }
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SettingsMiuixSwitchRow(
                                isDark = isDark,
                                icon = painterResource(R.drawable.ic_system_icon),
                                iconTint = primaryColor,
                                title = stringResource(R.string.settings_use_system_color),
                                subtitle = "提取系统壁纸主色调（Material You）作为界面动态强调色",
                                checked = useSystemColor,
                                groupExpanded = appearanceExpanded,
                                onCheckedChange = { checked ->
                                    ShizukuSettings.getPreferences().edit().putBoolean(ThemeHelper.KEY_USE_SYSTEM_COLOR, checked).apply()
                                    useSystemColor = checked
                                    onRecreate()
                                }
                            )
                        }
                    }
                }
            }

            // 3. 语言与国际化
            item(key = "section-language") {
                Column {
                    GroupHeaderWithToggle(
                        text = stringResource(R.string.settings_section_language),
                        isDark = isDark,
                        primaryColor = primaryColor,
                        expanded = languageExpanded,
                        onToggle = { languageExpanded = !languageExpanded }
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        val currentConfiguredLang = ShizukuSettings.getPreferences().getString(ShizukuSettings.LANGUAGE, "SYSTEM") ?: "SYSTEM"
                        val currentLangName = if (currentConfiguredLang == "SYSTEM" || currentConfiguredLang.isEmpty()) {
                            stringResource(R.string.follow_system)
                        } else {
                            when (currentConfiguredLang) {
                                "zh-CN" -> "简体中文"
                                "zh-TW" -> "繁體中文"
                                "en" -> "English"
                                else -> {
                                    val currentLocale = ShizukuSettings.getLocale()
                                    currentLocale?.getDisplayName(currentLocale)?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.follow_system)
                                }
                            }
                        }
                        SettingsMiuixActionRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_outline_translate_24),
                            iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                            title = stringResource(R.string.settings_language),
                            subtitle = "单独指定本应用界面的显示语言，无需更改系统全局语言",
                            valueText = currentLangName,
                            groupExpanded = languageExpanded,
                            onClick = { showLanguageDialog = true }
                        )
                    }
                }
            }

            // 4. 关于与翻译
            item(key = "section-about") {
                Column {
                    GroupHeaderWithToggle(
                        text = stringResource(R.string.settings_section_about),
                        isDark = isDark,
                        primaryColor = primaryColor,
                        expanded = aboutExpanded,
                        onToggle = { aboutExpanded = !aboutExpanded }
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        SettingsMiuixActionRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_baseline_link_24),
                            iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                            title = stringResource(R.string.settings_translation),
                            subtitle = stringResource(R.string.settings_translation_summary, stringResource(R.string.app_name)),
                            groupExpanded = aboutExpanded,
                            onClick = {
                                CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.translation_url))
                            }
                        )
                    }
                }
            }

            // 底部版本提示
            item(key = "footer-version") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Shizuku Sam v13.8.4",
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }

    // Miuix 窗口级弹窗：端口修改
    var inputPort by remember(showPortDialog, tcpPort) { mutableStateOf(tcpPort.toString()) }
    var portDialogShowDesc by remember(showPortDialog) { mutableStateOf(false) }
    WindowDialog(
        show = showPortDialog,
        title = stringResource(R.string.settings_port_dialog_title),
        summary = if (portDialogShowDesc) "请输入 1 ~ 65535 之间的合法本地 TCP 端口号（建议保持默认 5555）。" else null,
        onDismissRequest = { showPortDialog = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onLongPressToggle(haptic) { portDialogShowDesc = !portDialogShowDesc },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextField(
                value = inputPort,
                onValueChange = { inputPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = "端口号 (1 - 65535)",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { showPortDialog = false },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = stringResource(android.R.string.ok),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val port = inputPort.toIntOrNull()
                        if (port != null && port in 1..65535) {
                            ShizukuSettings.setTcpPort(port)
                            tcpPort = port
                        }
                        showPortDialog = false
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // Miuix 窗口级弹窗：深色模式选择
    val nightOptions = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to stringResource(R.string.settings_night_mode_follow_system),
        AppCompatDelegate.MODE_NIGHT_YES to stringResource(R.string.settings_night_mode_on),
        AppCompatDelegate.MODE_NIGHT_NO to stringResource(R.string.settings_night_mode_off)
    )
    WindowDialog(
        show = showNightModeDialog,
        title = stringResource(R.string.settings_night_mode_title),
        onDismissRequest = { showNightModeDialog = false }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            nightOptions.forEach { (mode, label) ->
                val selected = nightMode == mode
                val itemBg = when {
                    selected && isDark -> Color(0xFF162946)
                    selected && !isDark -> Color(0xFFEBF3FF)
                    isDark -> Color(0xFF28282D)
                    else -> Color(0xFFF0F2F6)
                }
                val itemFg = if (selected) {
                    if (isDark) Color(0xFF60A5FA) else primaryColor
                } else {
                    MiuixTheme.colorScheme.onSurface
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(itemBg)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (nightMode != mode) {
                                ShizukuSettings.getPreferences().edit().putInt(ShizukuSettings.NIGHT_MODE, mode).apply()
                                AppCompatDelegate.setDefaultNightMode(mode)
                                nightMode = mode
                                showNightModeDialog = false
                                onRecreate()
                            } else {
                                showNightModeDialog = false
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = itemFg
                    )
                    if (selected) {
                        Text(
                            text = "✓",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = itemFg
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = { showNightModeDialog = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Miuix 窗口级弹窗：语言选择
    val locales = listOf(
        "SYSTEM" to stringResource(R.string.follow_system),
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en" to "English"
    )
    val prefTag = ShizukuSettings.getPreferences().getString(ShizukuSettings.LANGUAGE, "SYSTEM")
    val currentConfiguredLang = if (prefTag.isNullOrEmpty()) "SYSTEM" else prefTag

    WindowDialog(
        show = showLanguageDialog,
        title = stringResource(R.string.settings_section_language),
        onDismissRequest = { showLanguageDialog = false }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            locales.forEach { (tag, label) ->
                val selected = currentConfiguredLang == tag
                val itemBg = when {
                    selected && isDark -> Color(0xFF162946)
                    selected && !isDark -> Color(0xFFEBF3FF)
                    isDark -> Color(0xFF28282D)
                    else -> Color(0xFFF0F2F6)
                }
                val itemFg = if (selected) {
                    if (isDark) Color(0xFF60A5FA) else primaryColor
                } else {
                    MiuixTheme.colorScheme.onSurface
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(itemBg)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val locale = if ("SYSTEM" == tag) {
                                LocaleDelegate.systemLocale
                            } else {
                                Locale.forLanguageTag(tag)
                            }
                            LocaleDelegate.defaultLocale = locale
                            ShizukuSettings.getPreferences().edit().putString(ShizukuSettings.LANGUAGE, tag).apply()
                            showLanguageDialog = false
                            onRecreate()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = itemFg
                    )
                    if (selected) {
                        Text(
                            text = "✓",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = itemFg
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = { showLanguageDialog = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SettingsMiuixSwitchRow(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    groupExpanded: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var rowToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSubtitle = groupExpanded xor rowToggled

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressToggle(haptic) { rowToggled = !rowToggled }
    ) {
        SwitchPreference(
            title = title,
            summary = if (showSubtitle && subtitle.isNotBlank()) subtitle else null,
            checked = checked,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            startAction = {
                MiuixRowIcon(isDark = isDark, icon = icon, iconTint = iconTint)
            }
        )
    }
}

@Composable
private fun SettingsMiuixActionRow(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color,
    title: String,
    subtitle: String,
    valueText: String? = null,
    groupExpanded: Boolean = false,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var rowToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSubtitle = groupExpanded xor rowToggled

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressToggle(haptic) { rowToggled = !rowToggled }
    ) {
        ArrowPreference(
            title = title,
            summary = if (showSubtitle && subtitle.isNotBlank()) subtitle else null,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            startAction = {
                MiuixRowIcon(isDark = isDark, icon = icon, iconTint = iconTint)
            },
            endActions = {
                if (!valueText.isNullOrBlank()) {
                    Text(
                        text = valueText,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }
        )
    }
}
