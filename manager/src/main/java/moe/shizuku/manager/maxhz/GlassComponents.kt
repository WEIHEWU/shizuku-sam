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
 * Miuix 风格卡片容器：采用 Miuix `Card` 连续曲率圆角 (16dp) 与 `PressFeedbackType.Sink` 弹性按压反馈。
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
    val haptic = LocalHapticFeedback.current
    val cardBgColor = when {
        tintColor != null -> tintColor.copy(alpha = if (isDark) 0.25f else 0.15f)
        isDark -> TitaniumDark.Card
        else -> GlassLight.Card
    }
    val cardShape = RoundedCornerShape(20.dp)
    val hairline = if (isDark) TitaniumDark.Hairline else GlassLight.Hairline
    val sheenAlpha = if (isDark) TitaniumDark.SheenAlpha else GlassLight.SheenAlpha

    val cardModifier = modifier
        .clip(cardShape)
        .border(1.dp, hairline, cardShape)

    if (onClick != null) {
        top.yukonga.miuix.kmp.basic.Card(
            modifier = cardModifier,
            cornerRadius = 20.dp,
            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = cardBgColor),
            pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
            showIndication = true,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = sheenAlpha),
                            0.5f to Color.Transparent
                        )
                    )
            ) {
                content()
            }
        }
    } else {
        top.yukonga.miuix.kmp.basic.Card(
            modifier = cardModifier,
            cornerRadius = 20.dp,
            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = cardBgColor)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = sheenAlpha),
                            0.5f to Color.Transparent
                        )
                    )
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
    data class Prominent(val tint: Color) : GlassButtonStyle
    data object Danger : GlassButtonStyle
}

/**
 * Miuix 风格圆角按钮：采用 `top.yukonga.miuix.kmp.basic.Button` 与 Miuix 配色规范。
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
    val buttonColors = when (style) {
        is GlassButtonStyle.Regular -> top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors()
        is GlassButtonStyle.Prominent -> top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(
            color = style.tint,
            contentColor = Color.White
        )
        is GlassButtonStyle.Danger -> {
            val dangerBg = if (isDark) Color(0xFF3B1818) else Color(0xFFFEE2E2)
            val dangerFg = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
            top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                color = dangerBg,
                contentColor = dangerFg
            )
        }
    }

    top.yukonga.miuix.kmp.basic.Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier,
        cornerRadius = 14.dp,
        minHeight = 40.dp,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        colors = buttonColors
    ) {
        Text(
            text = text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = when (style) {
                is GlassButtonStyle.Regular -> top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSecondaryVariant
                is GlassButtonStyle.Prominent -> Color.White
                is GlassButtonStyle.Danger -> if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
            },
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
