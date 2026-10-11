package moe.shizuku.manager.settings

import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.maxhz.GlassCard
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.utils.Contrast
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.RootBootScriptHelper
import rikka.material.app.LocaleDelegate
import rikka.shizuku.manager.ShizukuLocales
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGlassScreen(
    isDark: Boolean,
    isBlackNightTheme: Boolean,
    primaryColor: Color = if (isDark) Color(0xFF90CAF9) else Color(0xFF1976D2),
    onBackClick: () -> Unit,
    onRecreate: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val bgColor = if (isBlackNightTheme) {
        Color.Black
    } else if (isDark) {
        Color(0xFF111418)
    } else {
        Color(0xFFF7F9FC)
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

    Box(
        Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 56.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 48.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶栏标头
            item(key = "header-top") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF222733) else Color(0xFFEFF3F8))
                            .border(1.dp, if (isDark) Color(0x1FFFFFFF) else Color(0x0C000000), RoundedCornerShape(12.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBackClick()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "‹",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            color = if (isDark) Color.White else Color(0xFF1E293B)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_title),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else Color(0xFF1E293B)
                        )
                    }
                }
            }

            // 1. 启动与常驻
            item(key = "section-startup") {
                SettingsSectionTitle(stringResource(R.string.settings_section_startup), isDark, primaryColor)
            }
            item(key = "card-startup") {
                GlassCard(isDark = isDark) {
                    Column {
                        SettingsSwitchRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_root_24dp),
                            iconTint = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                            title = stringResource(R.string.settings_start_on_boot),
                            subtitle = stringResource(R.string.settings_start_on_boot_summary),
                            checked = startOnBoot,
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
                        SettingsDivider(isDark)
                        SettingsSwitchRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_maxhz_shield_24),
                            iconTint = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                            title = stringResource(R.string.settings_watchdog),
                            subtitle = stringResource(R.string.settings_watchdog_summary),
                            checked = watchdog,
                            onCheckedChange = { checked ->
                                ShizukuSettings.setWatchdog(context, checked)
                                watchdog = ShizukuSettings.getWatchdog()
                            }
                        )
                        SettingsDivider(isDark)
                        SettingsSwitchRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_wadb_24),
                            iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                            title = stringResource(R.string.settings_tcp_mode),
                            subtitle = stringResource(R.string.settings_tcp_mode_summary),
                            checked = tcpMode,
                            onCheckedChange = { checked ->
                                ShizukuSettings.setTcpMode(checked)
                                tcpMode = ShizukuSettings.getTcpMode()
                            }
                        )
                        SettingsDivider(isDark)
                        SettingsActionRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_terminal_24),
                            iconTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                            title = stringResource(R.string.settings_tcp_port),
                            subtitle = "$tcpPort",
                            onClick = { showPortDialog = true }
                        )
                    }
                }
            }

            // 2. 界面外观与主题
            item(key = "section-appearance") {
                SettingsSectionTitle(stringResource(R.string.settings_section_appearance), isDark, primaryColor)
            }
            item(key = "card-appearance") {
                GlassCard(isDark = isDark) {
                    Column {
                        val nightModeSummary = when (nightMode) {
                            AppCompatDelegate.MODE_NIGHT_NO -> stringResource(R.string.settings_night_mode_off)
                            AppCompatDelegate.MODE_NIGHT_YES -> stringResource(R.string.settings_night_mode_on)
                            else -> stringResource(R.string.settings_night_mode_follow_system)
                        }
                        SettingsActionRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_outline_dark_mode_24),
                            iconTint = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                            title = stringResource(R.string.dark_theme),
                            subtitle = nightModeSummary,
                            onClick = { showNightModeDialog = true }
                        )
                        if (nightMode != AppCompatDelegate.MODE_NIGHT_NO) {
                            SettingsDivider(isDark)
                            SettingsSwitchRow(
                                isDark = isDark,
                                icon = painterResource(R.drawable.ic_maxhz_sun_24),
                                iconTint = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5),
                                title = stringResource(R.string.settings_black_night_theme),
                                subtitle = stringResource(R.string.settings_black_night_theme_summary),
                                checked = blackNightTheme,
                                onCheckedChange = { checked ->
                                    ShizukuSettings.getPreferences().edit().putBoolean(ThemeHelper.KEY_BLACK_NIGHT_THEME, checked).apply()
                                    blackNightTheme = checked
                                    onRecreate()
                                }
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SettingsDivider(isDark)
                            SettingsSwitchRow(
                                isDark = isDark,
                                icon = painterResource(R.drawable.ic_system_icon),
                                iconTint = primaryColor,
                                title = stringResource(R.string.settings_use_system_color),
                                subtitle = stringResource(R.string.settings_use_system_color),
                                checked = useSystemColor,
                                activeColor = primaryColor,
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
                SettingsSectionTitle(stringResource(R.string.settings_section_language), isDark, primaryColor)
            }
            item(key = "card-language") {
                GlassCard(isDark = isDark) {
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
                    SettingsActionRow(
                        isDark = isDark,
                        icon = painterResource(R.drawable.ic_outline_translate_24),
                        iconTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                        title = stringResource(R.string.settings_language),
                        subtitle = currentLangName,
                        onClick = { showLanguageDialog = true }
                    )
                }
            }

            // 4. 关于与翻译
            item(key = "section-about") {
                SettingsSectionTitle(stringResource(R.string.settings_section_about), isDark, primaryColor)
            }
            item(key = "card-about") {
                GlassCard(isDark = isDark) {
                    Column {
                        SettingsActionRow(
                            isDark = isDark,
                            icon = painterResource(R.drawable.ic_baseline_link_24),
                            iconTint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                            title = stringResource(R.string.settings_translation),
                            subtitle = stringResource(R.string.settings_translation_summary, stringResource(R.string.app_name)),
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
                        text = "Shizuku v13.7.0",
                        fontSize = 11.5.sp,
                        color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                    )
                }
            }
        }
    }

    // 端口修改弹窗
    if (showPortDialog) {
        var inputPort by remember { mutableStateOf(tcpPort.toString()) }
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text(stringResource(R.string.settings_port_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = inputPort,
                    onValueChange = { inputPort = it.filter { ch -> ch.isDigit() }.take(5) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    label = { Text("1 - 65535") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val port = inputPort.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        ShizukuSettings.setTcpPort(port)
                        tcpPort = port
                    }
                    showPortDialog = false
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPortDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // 深色模式选择弹窗
    if (showNightModeDialog) {
        val options = listOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to stringResource(R.string.settings_night_mode_follow_system),
            AppCompatDelegate.MODE_NIGHT_YES to stringResource(R.string.settings_night_mode_on),
            AppCompatDelegate.MODE_NIGHT_NO to stringResource(R.string.settings_night_mode_off)
        )
        AlertDialog(
            onDismissRequest = { showNightModeDialog = false },
            title = { Text(stringResource(R.string.settings_night_mode_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    options.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
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
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (nightMode == mode),
                                onClick = null
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(text = label, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // 语言选择弹窗
    if (showLanguageDialog) {
        val locales = listOf(
            "SYSTEM" to stringResource(R.string.follow_system),
            "zh-CN" to "简体中文",
            "zh-TW" to "繁體中文",
            "en" to "English"
        )
        val prefTag = ShizukuSettings.getPreferences().getString(ShizukuSettings.LANGUAGE, "SYSTEM")
        val currentConfiguredLang = if (prefTag.isNullOrEmpty()) "SYSTEM" else prefTag
        var selectedTag by remember { mutableStateOf(currentConfiguredLang) }

        val onSelectLang: (String) -> Unit = { tag ->
            selectedTag = tag
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

        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_section_language), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    locales.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectLang(tag) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedTag == tag),
                                onClick = { onSelectLang(tag) }
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(text = label, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String,
    isDark: Boolean,
    primaryColor: Color
) {
    Text(
        text = title,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
        modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    activeColor: Color? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val switchActiveColor = activeColor ?: (if (isDark) Color(0xFF10B981) else Color(0xFF059669))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
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
                modifier = Modifier.size(20.dp)
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
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = switchActiveColor,
                uncheckedThumbColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                uncheckedTrackColor = if (isDark) Color(0xFF333B4A) else Color(0xFFE2E8F0)
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    isDark: Boolean,
    icon: Painter,
    iconTint: Color,
    title: String,
    subtitle: String,
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
                modifier = Modifier.size(20.dp)
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
        Spacer(Modifier.width(4.dp))
        Text(
            text = "›",
            fontSize = 17.sp,
            color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
        )
    }
}

@Composable
private fun SettingsDivider(isDark: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.6.dp)
            .background(if (isDark) Color(0x18FFFFFF) else Color(0x0E000000))
    )
}
