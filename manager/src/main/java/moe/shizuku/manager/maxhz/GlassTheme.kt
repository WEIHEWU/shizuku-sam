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
    primary = Color(0xFF3F51B5),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3F51B5),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FBFE),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE4EDFA),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0x1F0F172A)
)

private val GlassDarkColors = darkColorScheme(
    primary = Color(0xFF8C9EFF),
    onPrimary = Color(0xFF0A0E18),
    secondary = Color(0xFF8C9EFF),
    onSecondary = Color(0xFF0A0E18),
    background = Color(0xFF070A12),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0D121E),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1A2233),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0x2BF8FAFC)
)

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
    return remember(isDark, ThemeHelper.isUsingSystemColor()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ThemeHelper.isUsingSystemColor()) {
            val scheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            scheme.primary
        } else {
            if (isDark) Color(0xFF277AF7) else Color(0xFF3482FF)
        }
    }
}

@Composable
fun GlassAppTheme(
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val primaryColor = getSystemOrAppPrimaryColor(isDark)
    val colorScheme = remember(isDark, ThemeHelper.isUsingSystemColor()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ThemeHelper.isUsingSystemColor()) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (isDark) GlassDarkColors else GlassLightColors
        }
    }

    val useSystem = ThemeHelper.isUsingSystemColor()
    val miuixColors = remember(isDark, useSystem, primaryColor, colorScheme) {
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
                    surface = Color(0xFF0D0F12),
                    background = Color(0xFF0D0F12),
                    surfaceContainer = Color(0xFF181A20),
                    surfaceVariant = Color(0xFF181A20)
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
                    surface = Color(0xFFF5F6F9),
                    background = Color(0xFFF5F6F9),
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
