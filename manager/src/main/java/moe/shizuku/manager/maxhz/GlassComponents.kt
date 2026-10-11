package moe.shizuku.manager.maxhz

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.kyant.backdrop.effects.colorControlsColorFilter

/**
 * 共享 Compose 界面规范组件（方案 A：ShizukuX 极致流畅规范）。
 *
 * 彻底消除 GPU 离屏模糊层、色散管线与壁纸内存开销，采用硬件加速的
 * 现代 Material 3 Surface 纯净卡片设计，单 pass 极速渲染，120fps 满帧运行。
 */

val GLASS_VIBRANCY: ColorFilter = colorControlsColorFilter(
    brightness = 0.02f,
    saturation = 1.65f
)

fun com.kyant.backdrop.BackdropEffectScope.glassEffects(
    refractionHeight: Float? = null,
    refractionAmount: Float? = null,
    depthEffect: Boolean = true,
    chromaticAberration: Boolean = true,
    quality: GlassQuality = GlassQuality.Full
) {
    // 方案 A：移除色散折射 shader pass
}

/**
 * 高性能卡片容器：统一 20dp 圆角 + 纯净 Surface 底色 + 极细微描边 + 原生按压波纹反馈。
 */
@Composable
fun GlassCard(
    backdrop: Any? = null,
    isDark: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    interactive: Boolean = false,
    tintColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cardShape = remember { RoundedCornerShape(20.dp) }
    val cardBgColor = when {
        tintColor != null -> tintColor.copy(alpha = if (isDark) 0.25f else 0.15f)
        isDark -> Color(0xFF1E222A)
        else -> Color(0xFFFFFFFF)
    }
    val cardBorderColor = if (isDark) Color(0x18FFFFFF) else Color(0x0C000000)

    val finalModifier = if (onClick != null) {
        val haptic = LocalHapticFeedback.current
        modifier
            .clip(cardShape)
            .background(cardBgColor)
            .border(1.dp, cardBorderColor, cardShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    } else {
        modifier
            .clip(cardShape)
            .background(cardBgColor)
            .border(1.dp, cardBorderColor, cardShape)
    }

    Box(finalModifier) { content() }
}

/** 图标徽章：Squircle 渐变底座 + 白色图标（分类色走 LiquidGlassTheme.categoryColorsFor） */
@Composable
fun IconBadge(
    painter: Painter,
    colors: Pair<Long, Long>,
    sizeDp: Int = 36
) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp / 4).dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(colors.first), Color(colors.second))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size((sizeDp * 0.61f).dp),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * 按钮样式规范：
 * - [Regular]：标准中性按钮
 * - [Prominent]：突出操作主色调按钮
 * - [Danger]：警示/危险操作深红按钮
 */
sealed interface GlassButtonStyle {
    data object Regular : GlassButtonStyle
    data class Prominent(val tint: Color) : GlassButtonStyle
    data object Danger : GlassButtonStyle
}

/**
 * 高性能胶囊按钮：纯净硬件加速，原生触感反馈。
 */
@Composable
fun GlassPillButton(
    backdrop: Any? = null,
    text: String,
    isDark: Boolean,
    isDanger: Boolean = false,
    style: GlassButtonStyle = if (isDanger) GlassButtonStyle.Danger else GlassButtonStyle.Regular,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val pillShape = remember { RoundedCornerShape(18.dp) }

    val (bgColor, textColor, borderColor) = when (style) {
        is GlassButtonStyle.Regular -> {
            if (isDark) {
                Triple(Color(0xFF2C323D), Color(0xFFF1F5F9), Color(0x24FFFFFF))
            } else {
                Triple(Color(0xFFF0F3F8), Color(0xFF1E293B), Color(0x14000000))
            }
        }
        is GlassButtonStyle.Prominent -> {
            Triple(style.tint, Color.White, Color.Transparent)
        }
        is GlassButtonStyle.Danger -> {
            val red = if (isDark) Color(0xFFDC2626) else Color(0xFFE11D48)
            Triple(red, Color.White, Color.Transparent)
        }
    }

    Box(
        modifier
            .height(36.dp)
            .clip(pillShape)
            .background(bgColor)
            .border(1.dp, borderColor, pillShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = if (style is GlassButtonStyle.Prominent) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1
        )
    }
}

/** 背景状态键（保留结构兼容） */
data class WallpaperContentKey(
    val uri: Uri?,
    val isDark: Boolean,
    val flat: Boolean,
    val fit: String,
    val blackNight: Boolean,
    val fullHeightPx: Float
)

internal data class Glow(val cx: Float, val cy: Float, val radius: Float, val color: Color)

internal val LIGHT_GLOWS = emptyList<Glow>()
internal val DARK_GLOWS = emptyList<Glow>()

internal val LIGHT_STOPS = listOf(
    Color(0xFFF8FBFE), Color(0xFFEFF5FC),
    Color(0xFFE4EDFA), Color(0xFFDCE8F8)
)

internal val DARK_STOPS = listOf(
    Color(0xFF070A12), Color(0xFF0A0E18),
    Color(0xFF0D121E), Color(0xFF080B14)
)

internal val FLAT_LIGHT = Color(0xFFF7F9FC)
internal val FLAT_DARK = Color(0xFF111418)

fun wallpaperTextShadow(isDark: Boolean): Shadow? = null

@Composable
fun wallpaperBodyStyle(isDark: Boolean, base: TextStyle = LocalTextStyle.current): TextStyle = base

@Composable
fun AuroraBackground(isDark: Boolean, fullHeightPx: Float = 0f, flat: Boolean = false) {
    val bgColor = if (isDark) Color(0xFF111418) else Color(0xFFF7F9FC)
    Box(Modifier.fillMaxSize().background(bgColor))
}

@Composable
fun WallpaperBackground(
    uri: Uri? = null,
    isDark: Boolean = false,
    fit: String = "crop",
    modifier: Modifier = Modifier
) {
    val bgColor = if (isDark) Color(0xFF111418) else Color(0xFFF7F9FC)
    Box(modifier.fillMaxSize().background(bgColor))
}
