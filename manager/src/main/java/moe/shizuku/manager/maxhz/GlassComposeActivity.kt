package moe.shizuku.manager.maxhz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.min

/**
 * Compose 液态玻璃验证页（迁移第一步，第二版：贴近主页结构）。
 *
 * 结构对齐主页：Hero 状态卡 + 两张功能卡 + 底部 dock。
 * 卡片 2 与 dock 下方压着彩色内容，验证「折射真实内容」（View 做不到）。
 * 布局按本机逻辑尺寸 384x832dp 设计（density 3.75）。
 */
class GlassComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var dark by remember { mutableStateOf(false) }
            GlassPreviewScreen(dark = dark, onToggle = { dark = !dark })
        }
    }
}

private val SWATCH_COLORS = listOf(
    Color(0xFFE8503A), Color(0xFFF2B705),
    Color(0xFF2FBF71), Color(0xFF2D9CDB),
    Color(0xFF9B59B6)
)

// ---- 布局常量（本机 384x832dp 逻辑尺寸）----
private val TOP_SPACER = 48.dp
private val CARD_H1 = 170.dp
private val CARD_H2 = 120.dp
private val CARD_H3 = 120.dp
private val CARD_GAP = 18.dp
private val H_PAD = 20.dp

@Composable
private fun GlassPreviewScreen(dark: Boolean, onToggle: () -> Unit) {
    val backdrop = rememberLayerBackdrop(vibrancy = GLASS_VIBRANCY)

    Box(Modifier.fillMaxSize()) {
        // ---- 被记录的背景与内容（玻璃折射的素材）----
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            AuroraBackground(dark)
            // 彩色内容带 A：压在卡片 2 下方（y ≈ 236..356dp）
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 252.dp, start = 44.dp, end = 44.dp)
                    .fillMaxWidth()
                    .height(88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SWATCH_COLORS.forEach { c ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(c, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    )
                }
            }
            // 彩色内容带 B：压在 dock 下方（y ≈ 728..816dp）
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .height(88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SWATCH_COLORS.forEach { c ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(c, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    )
                }
            }
        }

        // ---- 玻璃卡片（不参与 backdrop 记录）----
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = H_PAD),
            verticalArrangement = Arrangement.spacedBy(CARD_GAP)
        ) {
            Spacer(Modifier.height(TOP_SPACER))
            GlassCard(backdrop, dark, CARD_H1) { HeroContent(dark) }
            GlassCard(backdrop, dark, CARD_H2) { RowContent(dark, "ADB 配对", "无线调试 · 端口与配对码") }
            GlassCard(backdrop, dark, CARD_H3) { RowContent(dark, "Shell 教程", "通过 ADB 授权终端访问") }
        }

        // ---- 底部 dock（胶囊形玻璃，压在内容带 B 上）----
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .width(300.dp)
                .height(64.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { glassEffects() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("主页", "权限", "终端").forEach { label ->
                    Text(
                        text = label,
                        color = if (dark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // ---- 深浅色开关（足够大的点击区域）----
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(CircleShape)
                .clickable { onToggle() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .background(
                    if (dark) Color(0x33FFFFFF) else Color(0x22000000),
                    CircleShape
                )
        ) {
            Text(
                text = if (dark) "深色" else "浅色",
                color = if (dark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                fontSize = 12.sp
            )
        }
    }
}


/** 复刻 LiquidGlassTheme 的极光背景（同色值体系），Compose 绘制 */
@Composable
private fun AuroraBackground(dark: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (dark) {
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF070A12), Color(0xFF0A0E18),
                        Color(0xFF0D121E), Color(0xFF080B14)
                    )
                )
            )
            DARK_GLOWS.forEach { g -> drawGlow(g, w, h) }
        } else {
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF8FBFE), Color(0xFFEFF5FC),
                        Color(0xFFE4EDFA), Color(0xFFDCE8F8)
                    )
                )
            )
            LIGHT_GLOWS.forEach { g -> drawGlow(g, w, h) }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlow(g: Glow, w: Float, h: Float) {
    val radius = g.radius * min(w, h)
    val center = Offset(g.cx * w, g.cy * h)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(g.color, Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

@Composable
private fun GlassCard(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    dark: Boolean,
    height: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedRectangle(26.dp) },
                effects = { glassEffects() }
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        content()
    }
}

@Composable
private fun HeroContent(dark: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(Color(0xFFFF3B30), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Shizuku 服务状态",
                color = if (dark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
                fontSize = 18.sp
            )
        }
        Text(
            text = "未运行 · 通过无线调试启动",
            color = if (dark) Color(0xFFCBD5E1) else Color(0xFF475569),
            fontSize = 12.sp
        )
        Text(
            text = "drawBackdrop · vibrancy + blur + lens",
            color = if (dark) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun RowContent(dark: Boolean, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            color = if (dark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
            fontSize = 15.sp
        )
        Text(
            text = subtitle,
            color = if (dark) Color(0xFFCBD5E1) else Color(0xFF475569),
            fontSize = 11.sp
        )
    }
}


