package moe.shizuku.manager.maxhz

import android.net.Uri
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import moe.shizuku.manager.utils.Contrast

/**
 * 方案 A：MaxHz 三个 tab（屏幕显示 / 性能加速 / 电池省电）的高性能 Compose 渲染。
 *
 * 彻底消除 GPU 离屏模糊与背景管线开销，对齐 ShizukuX 原生极速设计，保证 120fps 流畅度。
 */

/** 行内开关动作 */
data class ToggleAction(
    val isOn: () -> Boolean,
    val onToggle: (Boolean, onComplete: (ok: Boolean) -> Unit) -> Unit,
    val isEnabled: () -> Boolean = { true }
)

/** 功能入口行：标题 + 副标题 + 状态值 + 右箭头 / 行内开关 */
data class EntryItem(
    val title: String,
    val subtitle: String,
    val stateProvider: (() -> String)? = null,
    val toggle: ToggleAction? = null,
    val onClick: () -> Unit = {}
)

/** 一个分组 = 组标签 + 入口列表 */
class MaxHzGroup(
    val label: String,
    val entries: List<EntryItem>,
    val collapsedByDefault: Boolean = false
)

/** 带按钮的特殊卡 */
data class MaxHzActionCard(
    val title: String,
    val subtitle: String,
    val buttonText: String,
    val onClick: () -> Unit
)

@Composable
fun MaxHzPageScreen(
    isDark: Boolean,
    primaryColor: Color,
    topInsetPx: Int,
    bottomInsetPx: Int,
    fullHeightPx: Float,
    refreshTick: Int,
    selectedTabIndex: Int,
    flatBackground: Boolean = false,
    wallpaperUri: Uri? = null,
    wallpaperFit: String = "crop",
    isBlackNightTheme: Boolean = false,
    onTabClick: (Int) -> Unit,
    groups: List<MaxHzGroup>,
    actionCards: List<MaxHzActionCard> = emptyList(),
    extraViewFactories: List<() -> View> = emptyList()
) {
    val density = LocalDensity.current
    val bottomInsetDp = with(density) { bottomInsetPx.toDp() }

    val bgColor = if (isBlackNightTheme) {
        Color.Black
    } else if (isDark) {
        Color(0xFF111418)
    } else {
        Color(0xFFF7F9FC)
    }

    Box(Modifier.fillMaxSize().background(bgColor)) {
        val expandedStates = remember { mutableStateMapOf<String, Boolean>() }
        val listState = rememberLazyListState()

        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = with(density) { topInsetPx.toDp() },
                start = 16.dp, end = 16.dp,
                bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groups.forEach { group ->
                item(key = "group-${group.label}", contentType = "group") {
                    GroupGlassCard(
                        isDark = isDark,
                        primaryColor = primaryColor,
                        group = group,
                        refreshTick = refreshTick,
                        pageKey = selectedTabIndex,
                        expanded = expandedStates["$selectedTabIndex/${group.label}"]
                            ?: !group.collapsedByDefault,
                        onExpandedChange = {
                            expandedStates["$selectedTabIndex/${group.label}"] = it
                        }
                    )
                }
            }
            actionCards.forEachIndexed { ai, card ->
                item(key = "action-$ai", contentType = "action") {
                    ActionGlassCard(
                        isDark = isDark,
                        primaryColor = primaryColor,
                        card = card
                    )
                }
            }
            extraViewFactories.forEachIndexed { i, factory ->
                item(key = "view-$i", contentType = "view") {
                    AndroidView(
                        factory = { factory() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // dock 避让区
            item(key = "dockClearance", contentType = "spacer") {
                Spacer(Modifier.height(78.dp))
            }
        }
    }
}

/**
 * 带按钮的卡片
 */
@Composable
private fun ActionGlassCard(
    isDark: Boolean,
    primaryColor: Color,
    card: MaxHzActionCard
) {
    GlassCard(isDark = isDark) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val colors = LiquidGlassTheme.categoryColorsFor(card.title)
                IconBadge(
                    painter = painterResource(featureIconRes(card.title)),
                    colors = Pair(colors.first.toLong(), colors.second.toLong())
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = card.subtitle,
                        fontSize = 11.5.sp,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                GlassPillButton(
                    text = card.buttonText,
                    isDark = isDark,
                    style = GlassButtonStyle.Prominent(primaryColor),
                    onClick = card.onClick
                )
            }
        }
    }
}

@Composable
private fun GroupGlassCard(
    isDark: Boolean,
    primaryColor: Color,
    group: MaxHzGroup,
    refreshTick: Int,
    pageKey: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (group.collapsedByDefault) {
                        Modifier.clickable { onExpandedChange(!expanded) }
                    } else {
                        Modifier
                    }
                )
                .padding(start = 18.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = Contrast.ensure(primaryColor, Contrast.glassSurface(isDark)),
                modifier = Modifier.weight(1f, fill = false)
            )
            if (group.collapsedByDefault) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontSize = 12.sp,
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                )
            }
        }
        if (expanded) {
            GlassCard(isDark = isDark) {
                Column {
                    group.entries.forEachIndexed { index, entry ->
                        key("$pageKey/${entry.title}") {
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .padding(start = 68.dp, end = 16.dp)
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000))
                                )
                            }
                            EntryRow(entry, isDark, primaryColor, refreshTick, pageKey)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: EntryItem,
    isDark: Boolean,
    primaryColor: Color,
    refreshTick: Int,
    pageKey: Int
) {
    val cacheKey = "$pageKey/${entry.title}/$refreshTick"

    var optimistic by remember(cacheKey) { mutableStateOf<Boolean?>(null) }
    val realStatusText = remember(cacheKey) { entry.stateProvider?.invoke() }

    val statusText = when (optimistic) {
        null -> realStatusText
        true -> if (realStatusText == null) null else "开启中…"
        false -> if (realStatusText == null) null else "关闭中…"
    }

    val isOn = optimistic ?: remember(cacheKey) { entry.toggle?.isOn?.invoke() ?: false }
    var busy by remember { mutableStateOf(false) }

    val rowModifier = Modifier
        .fillMaxWidth()
        .clickable { entry.onClick() }

    Row(
        rowModifier
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val colors = LiquidGlassTheme.categoryColorsFor(entry.title)
        IconBadge(
            painter = painterResource(featureIconRes(entry.title)),
            colors = Pair(colors.first.toLong(), colors.second.toLong())
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (statusText != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        color = if (isOn) {
                            if (isDark) Color(0xFF34D399) else Color(0xFF059669)
                        } else {
                            if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        }
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.subtitle,
                fontSize = 11.5.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            )
        }
        if (entry.toggle != null) {
            key(cacheKey) {
                GlassToggle(
                    checked = isOn,
                    enabled = remember(cacheKey) { entry.toggle.isEnabled() },
                    isDark = isDark,
                    onChange = { want ->
                        if (busy) return@GlassToggle
                        busy = true
                        optimistic = want
                        entry.toggle.onToggle(want) { ok ->
                            busy = false
                            if (!ok) optimistic = null
                            MaxHzComposeRefreshBus.bump()
                        }
                    }
                )
            }
        } else {
            Text(
                text = "›",
                fontSize = 18.sp,
                color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
            )
        }
    }
}

/** 全局刷新总线：Activity 在 onDone / updateMaxHzServiceState 后 bump */
object MaxHzComposeRefreshBus {
    internal val tick = mutableStateOf(0)
    fun bump() {
        tick.value++
    }
}

/** iOS 风格开关，纯 GPU translationX 滑动，零测量与重新布局损耗 */
@Composable
private fun GlassToggle(
    checked: Boolean,
    enabled: Boolean = true,
    isDark: Boolean = false,
    onChange: (Boolean) -> Unit
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF34C759) else if (isDark) Color(0xFF333B48) else Color(0xFFE2E8F0),
        animationSpec = tween(durationMillis = 200),
        label = "trackColor"
    )
    val knobColor = Color.White

    val density = LocalDensity.current
    val targetOffsetPx = with(density) { if (checked) 18.dp.toPx() else 2.dp.toPx() }
    val knobTranslationX by animateFloatAsState(
        targetValue = targetOffsetPx,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 500f),
        label = "knobOffset"
    )

    Box(
        Modifier
            .width(46.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) trackColor else trackColor.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    translationX = knobTranslationX
                }
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(knobColor)
        )
    }
}
