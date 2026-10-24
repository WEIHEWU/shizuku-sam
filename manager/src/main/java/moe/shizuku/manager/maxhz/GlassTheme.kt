package moe.shizuku.manager.maxhz

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.shizuku.manager.app.ThemeHelper

/**
 * 所有 ComposeView 的统一主题包装。
 *
 * **为什么必须有这一层**：Compose 的 `Modifier.clickable` 按下反馈（水波纹 ripple）
 * 来自 `LocalIndication`。若 `setContent` 外面不包 `MaterialTheme`，`LocalIndication`
 * 只剩平台兜底实现 —— 表现就是**所有按钮按下时毫无动画/反馈**。
 *
 * MaterialTheme 只注入 配色 / 字体 / 形状 / Indication，**不会绘制背景**，
 * 因此不会遮挡玻璃 `drawBackdrop` 依赖的透明层级，可安全套在最外层。
 */

private val GlassLightColors = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F3EE),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF0EDE6),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0x1F0F172A)
)

private val GlassDarkColors = darkColorScheme(
    primary = Color(0xFF10B981),
    onPrimary = Color(0xFF0A0E18),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF0A0E18),
    background = Color(0xFF12131F),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E2631),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0x2BF8FAFC)
)

object GlassThemeState {
    val useSystemColor = androidx.compose.runtime.mutableStateOf(ThemeHelper.isUsingSystemColor())
    val nightMode = androidx.compose.runtime.mutableIntStateOf(moe.shizuku.manager.ShizukuSettings.getNightMode())
    val blackNightPref = androidx.compose.runtime.mutableStateOf(
        moe.shizuku.manager.ShizukuSettings.getPreferences()
            .getBoolean(ThemeHelper.KEY_BLACK_NIGHT_THEME, false)
    )
    val refreshTick = androidx.compose.runtime.mutableIntStateOf(0)

    fun sync() {
        useSystemColor.value = ThemeHelper.isUsingSystemColor()
        nightMode.intValue = moe.shizuku.manager.ShizukuSettings.getNightMode()
        blackNightPref.value = moe.shizuku.manager.ShizukuSettings.getPreferences()
            .getBoolean(ThemeHelper.KEY_BLACK_NIGHT_THEME, false)
        refreshTick.intValue++
    }

    fun resolveIsDark(context: android.content.Context? = null): Boolean {
        return when (moe.shizuku.manager.ShizukuSettings.getNightMode()) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> true
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> {
                val cfg = android.content.res.Resources.getSystem().configuration
                (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun resolveIsBlackNight(context: android.content.Context, isDark: Boolean = resolveIsDark(context)): Boolean {
        return isDark && ThemeHelper.isBlackNightTheme(context)
    }
}

@Composable
fun rememberEffectiveIsDark(): Boolean {
    val mode = GlassThemeState.nightMode.intValue
    val tick = GlassThemeState.refreshTick.intValue + MaxHzComposeRefreshBus.tick.value
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    return remember(mode, tick, config.uiMode) {
        GlassThemeState.resolveIsDark()
    }
}

@Composable
fun rememberEffectiveBlackNight(isDark: Boolean = rememberEffectiveIsDark()): Boolean {
    val context = LocalContext.current
    val blackPref = GlassThemeState.blackNightPref.value
    val tick = GlassThemeState.refreshTick.intValue + MaxHzComposeRefreshBus.tick.value
    return remember(isDark, blackPref, tick) {
        isDark && (blackPref || ThemeHelper.isBlackNightTheme(context))
    }
}

private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val GlassTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun getSystemOrAppPrimaryColor(isDark: Boolean): Color {
    val context = LocalContext.current
    val useSystem = GlassThemeState.useSystemColor.value
    val tick = GlassThemeState.refreshTick.intValue + MaxHzComposeRefreshBus.tick.value
    return remember(isDark, useSystem, tick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ThemeHelper.isUsingSystemColor()) {
            val scheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            scheme.primary
        } else {
            // SamsungRootKit 默认翡翠绿主题色（关闭系统动态取色时立即切换为 RootKit 标志性绿）
            if (isDark) Color(0xFF10B981) else Color(0xFF059669)
        }
    }
}

@Composable
fun GlassAppTheme(
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val useSystem = GlassThemeState.useSystemColor.value
    val tick = GlassThemeState.refreshTick.intValue + MaxHzComposeRefreshBus.tick.value
    val primaryColor = getSystemOrAppPrimaryColor(isDark)
    val colorScheme = remember(isDark, useSystem, tick, primaryColor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ThemeHelper.isUsingSystemColor()) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (isDark) GlassDarkColors else GlassLightColors
        }
    }

    val miuixColors = remember(isDark, useSystem, tick, primaryColor, colorScheme) {
        if (isDark) {
            if (useSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                top.yukonga.miuix.kmp.theme.darkColorScheme(
                    primary = primaryColor,
                    primaryVariant = primaryColor,
                    surface = colorScheme.surface,
                    background = colorScheme.background,
                    surfaceContainer = colorScheme.surfaceContainer,
                    surfaceVariant = colorScheme.surfaceContainerHigh
                )
            } else {
                top.yukonga.miuix.kmp.theme.darkColorScheme(
                    primary = primaryColor,
                    primaryVariant = primaryColor,
                    surface = TitaniumDark.BackgroundTop,
                    background = TitaniumDark.BackgroundMid,
                    surfaceContainer = TitaniumDark.BackgroundTop,
                    surfaceVariant = TitaniumDark.BackgroundTop
                )
            }
        } else {
            if (useSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                top.yukonga.miuix.kmp.theme.lightColorScheme(
                    primary = primaryColor,
                    primaryVariant = primaryColor,
                    surface = colorScheme.surface,
                    background = colorScheme.background,
                    surfaceContainer = colorScheme.surfaceContainer,
                    surfaceVariant = colorScheme.surfaceContainerHigh
                )
            } else {
                top.yukonga.miuix.kmp.theme.lightColorScheme(
                    primary = primaryColor,
                    primaryVariant = primaryColor,
                    surface = GlassLight.BackgroundTop,
                    background = GlassLight.BackgroundMid,
                    surfaceContainer = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFFFFFFF)
                )
            }
        }
    }

    val miuixTextStyles = remember {
        top.yukonga.miuix.kmp.theme.defaultTextStyles(
            main = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
            paragraph = TextStyle(fontSize = 13.sp, lineHeight = 17.5.sp),
            body1 = TextStyle(fontSize = 13.sp, lineHeight = 17.5.sp),
            body2 = TextStyle(fontSize = 11.5.sp, lineHeight = 15.5.sp),
            button = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            footnote1 = TextStyle(fontSize = 11.sp),
            footnote2 = TextStyle(fontSize = 10.5.sp),
            headline1 = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            headline2 = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            subtitle = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            title1 = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
            title2 = TextStyle(fontSize = 16.5.sp, fontWeight = FontWeight.Bold),
            title3 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            title4 = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        )
    }

    val existingNavOwner = androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner.current
    val fallbackNavOwner = remember {
        object : androidx.navigationevent.NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = androidx.navigationevent.NavigationEventDispatcher()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = GlassShapes,
        typography = GlassTypography
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides (existingNavOwner ?: fallbackNavOwner)
        ) {
            top.yukonga.miuix.kmp.theme.MiuixTheme(
                colors = miuixColors,
                textStyles = miuixTextStyles,
                content = content
            )
        }
    }
}
