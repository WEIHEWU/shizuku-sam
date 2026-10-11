package moe.shizuku.manager.maxhz

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.shizuku.manager.R

/**
 * 全应用统一的高性能底栏 Dock 导航（方案 A：ShizukuX 极致流畅规范）：
 *
 * 1. 采用原生硬件加速的 Material 3 纯净胶囊底座，彻底去除跨 ComposeView 的离屏模糊与背景采样；
 * 2. 交互指示器采用极速临界阻尼弹簧，零 GC 开销与零延迟；
 * 3. 每一个页签带有原生触感反馈（HapticFeedback）。
 */

val GLASS_DOCK_TABS = listOf("Shizuku", "性能硬件", "应用规则")
val GLASS_DOCK_HEIGHT_DP = 64.dp
val GLASS_DOCK_CLEARANCE_DP = 88.dp

private const val DOCK_PADDING_START_DP = 6f
private const val DOCK_SLOT_STEP_DP = 90.66f
private const val DOCK_SLOT_WIDTH_DP = 90.66f
private val DOCK_PILL_WIDTH = 86.dp
private val DOCK_PILL_HEIGHT = 46.dp

@Composable
fun GlassDockNav(
    backdrop: Any? = null,
    isDark: Boolean,
    selectedIndex: Int,
    bottomInsetDp: Dp,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<String> = listOf(
        stringResource(R.string.dock_tab_shizuku),
        stringResource(R.string.dock_tab_hardware),
        stringResource(R.string.dock_tab_rules)
    )
) {
    val haptic = LocalHapticFeedback.current
    val clampedIndex = selectedIndex.coerceIn(0, 2)
    val dockShape = remember { RoundedCornerShape(30.dp) }

    val density = LocalDensity.current
    val pillTargetXPx = with(density) {
        (DOCK_PADDING_START_DP + clampedIndex * DOCK_SLOT_STEP_DP +
            (DOCK_SLOT_WIDTH_DP - 86f) / 2f).dp.toPx()
    }

    // 高性能位移：临界阻尼的高刚度弹簧，响应迅速、零拖泥带水，向 ShizukuX 的敏捷靠拢
    val animatedPillX by animateFloatAsState(
        targetValue = pillTargetXPx,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 650f),
        label = "dockPillX"
    )

    val pillShape = remember { RoundedCornerShape(24.dp) }
    val pillBgColor = if (isDark) Color(0xFF2C323E) else Color(0xFFE2E8F0)
    val pillBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x18000000)

    val dockBgColor = if (isDark) Color(0xF2181C24) else Color(0xF8FFFFFF)
    val dockBorderColor = if (isDark) Color(0x28FFFFFF) else Color(0x14000000)

    Box(
        Modifier
            .then(modifier)
            .padding(bottom = 12.dp + bottomInsetDp)
            .width(284.dp)
            .height(58.dp)
            .clip(dockShape)
            .background(dockBgColor)
            .border(1.dp, dockBorderColor, dockShape),
        contentAlignment = Alignment.CenterStart
    ) {
        // 1. 高性能指示器胶囊（纯 GPU 硬件变换层，免去内部二次离屏烘焙与重布局）
        Box(
            Modifier
                .graphicsLayer {
                    translationX = animatedPillX
                }
                .width(DOCK_PILL_WIDTH)
                .height(DOCK_PILL_HEIGHT)
                .clip(pillShape)
                .background(pillBgColor)
                .border(1.dp, pillBorderColor, pillShape)
        )

        // 2. 3 个页签交互行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, label ->
                DockTabButton(
                    label = label,
                    selected = index == clampedIndex,
                    isDark = isDark,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabClick(index)
                    }
                )
            }
        }
    }
}

/**
 * dock 内的单个胶囊按钮：
 * 文字颜色平滑过渡，零额外 RenderNode 开销。
 */
@Composable
private fun RowScope.DockTabButton(
    label: String,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    val textColor by animateColorAsState(
        targetValue = when {
            selected -> if (isDark) Color(0xFFEAF2FF) else Color(0xFF10151F)
            isDark -> Color(0xFF8E99A8)
            else -> Color(0xFF6B7280)
        },
        animationSpec = tween(durationMillis = 150),
        label = "dockTabText"
    )

    Box(
        Modifier
            .weight(1f)
            .height(DOCK_PILL_HEIGHT)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )
    }
}
