package moe.shizuku.manager.maxhz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControlsColorFilter
import com.kyant.backdrop.effects.lens

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
 * SamsungRootKit 光学玻璃卡片容器：
 * 结合 `drawBackdrop(blur(16.dp) + lens(24.dp, 24.dp))` 边缘折射、1dp 钛金发丝高光边框与顶部微光反光层。
 */
@Composable
fun GlassCard(
    backdrop: Any? = null,
    isDark: Boolean,
    isBlackNightTheme: Boolean = false,
    cornerRadius: androidx.compose.ui.unit.Dp = GlassRadii.Card,
    modifier: Modifier = Modifier.fillMaxWidth(),
    interactive: Boolean = false,
    tintColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val dark = isDark || isBlackNightTheme
    val cardBgColor = when {
        tintColor != null -> tintColor.copy(alpha = if (dark) 0.25f else 0.15f)
        isBlackNightTheme -> TitaniumBlack.Card
        dark -> TitaniumDark.Card
        else -> GlassLight.Card
    }
    val cardShape = RoundedCornerShape(cornerRadius)
    val hairline = if (dark) TitaniumDark.Hairline else GlassLight.Hairline
    val sheenAlpha = if (dark) TitaniumDark.SheenAlpha else GlassLight.SheenAlpha

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                } else {
                    Modifier
                }
            ),
        shape = cardShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, hairline),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBgColor)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = sheenAlpha),
                        0.5f to Color.Transparent
                    )
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                content()
            }
        }
    }
}

/** 图标徽章：柔和浅粉彩圆角底座 (14% alpha) + 同色系高清着色图标 */
@Composable
fun IconBadge(
    painter: Painter,
    colors: Pair<Long, Long>,
    sizeDp: Int = 34
) {
    val accent = Color(colors.first)
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp * 0.30f).dp))
            .background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(accent),
            modifier = Modifier.size((sizeDp * 0.54f).dp),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * 按钮样式规范：
 * - [Regular]：Miuix 次级灰底按钮
 * - [Prominent]：Miuix 主色调高亮按钮
 * - [Danger]：Miuix 警示红按钮
 */
sealed interface GlassButtonStyle {
    data object Regular : GlassButtonStyle
    data class Prominent(val tint: Color = Color.Unspecified) : GlassButtonStyle {
        companion object : GlassButtonStyle
    }
    data object Danger : GlassButtonStyle
}

/**
 * SamsungRootKit 风格胶囊毛玻璃按钮：
 * 采用 `GlassRadii.Pill` (999.dp) 全圆角胶囊造型、半透明玻璃填充、顶部反光高光层与 1dp 发丝边框。
 */
@Composable
fun GlassPillButton(
    backdrop: Any? = null,
    text: String,
    isDark: Boolean,
    isDanger: Boolean = false,
    style: GlassButtonStyle = if (isDanger) GlassButtonStyle.Danger else GlassButtonStyle.Regular,
    interactive: Boolean = true,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val pillShape = RoundedCornerShape(GlassRadii.Pill)
    val defaultProminentTint = getSystemOrAppPrimaryColor(isDark)
    val prominentTint = when (style) {
        is GlassButtonStyle.Prominent -> if (style.tint != Color.Unspecified) style.tint else defaultProminentTint
        GlassButtonStyle.Prominent.Companion -> defaultProminentTint
        else -> null
    }
    val isEnabled = interactive && enabled
    val bgBrush = when {
        prominentTint != null -> {
            val alphaScale = if (isEnabled) 1f else 0.45f
            if (isDark) {
                Brush.horizontalGradient(
                    listOf(
                        prominentTint.copy(alpha = 0.42f * alphaScale),
                        prominentTint.copy(alpha = 0.72f * alphaScale),
                        prominentTint.copy(alpha = 0.58f * alphaScale)
                    )
                )
            } else {
                Brush.horizontalGradient(
                    listOf(
                        prominentTint.copy(alpha = 0.82f * alphaScale),
                        prominentTint.copy(alpha = 0.96f * alphaScale),
                        prominentTint.copy(alpha = 0.86f * alphaScale)
                    )
                )
            }
        }
        style is GlassButtonStyle.Danger -> {
            val dangerFill = if (isDark) Color(0x38EF4444) else Color(0x22DC2626)
            Brush.verticalGradient(listOf(dangerFill, dangerFill))
        }
        else -> {
            val regularFill = if (isDark) Color(0x28FFFFFF) else Color(0x99FFFFFF)
            Brush.verticalGradient(listOf(regularFill, regularFill))
        }
    }
    val borderColor = when {
        prominentTint != null -> if (isDark) prominentTint.copy(alpha = if (isEnabled) 0.65f else 0.30f) else Color(0xCCFFFFFF)
        style is GlassButtonStyle.Danger -> if (isDark) Color(0x66F87171) else Color(0x55DC2626)
        else -> if (isDark) TitaniumDark.Hairline else GlassLight.Hairline
    }
    val textColor = when {
        prominentTint != null -> Color.White.copy(alpha = if (isEnabled) 1f else 0.55f)
        style is GlassButtonStyle.Danger -> if (isDark) Color(0xFFFCA5A5) else Color(0xFFDC2626)
        else -> (if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)).copy(alpha = if (isEnabled) 1f else 0.50f)
    }
    val sheenAlpha = if (isDark) 0.08f else 0.22f

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 42.dp)
            .clip(pillShape)
            .background(bgBrush)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = sheenAlpha),
                    0.6f to Color.Transparent
                )
            )
            .border(1.dp, borderColor, pillShape)
            .clickable(enabled = isEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
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
