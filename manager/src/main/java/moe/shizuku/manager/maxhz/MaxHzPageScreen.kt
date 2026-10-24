package moe.shizuku.manager.maxhz

import android.net.Uri
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 方案 A：MaxHz 三个 tab（屏幕显示 / 性能加速 / 电池省电）的 Miuix 原生渲染。
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
    val bgBrush = glassBackgroundBrush(isDark = isDark, isBlackNightTheme = isBlackNightTheme)

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        val expandedStates = remember { mutableStateMapOf<String, Boolean>() }
        val listState = rememberLazyListState()

        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            state = listState,
            contentPadding = PaddingValues(
                top = with(density) { topInsetPx.toDp() },
                start = 16.dp, end = 16.dp,
                bottom = GLASS_DOCK_CLEARANCE_DP + bottomInsetDp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            groups.forEach { group ->
                item(key = "group-${group.label}", contentType = "group") {
                    GroupGlassCard(
                        isDark = isDark,
                        isBlackNightTheme = isBlackNightTheme,
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
                        isBlackNightTheme = isBlackNightTheme,
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

@Composable
private fun Modifier.onLongPressTogglePage(onLongPress: () -> Unit): Modifier {
    return this.pointerInput(onLongPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val longPress = awaitLongPressOrCancellation(down.id)
            if (longPress != null) {
                longPress.consume()
                onLongPress()
                var event = awaitPointerEvent(PointerEventPass.Initial)
                while (event.changes.any { it.pressed }) {
                    event.changes.forEach { it.consume() }
                    event = awaitPointerEvent(PointerEventPass.Initial)
                }
                event.changes.forEach { it.consume() }
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
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    card: MaxHzActionCard
) {
    val haptic = LocalHapticFeedback.current
    var showSubtitle by remember { mutableStateOf(false) }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressTogglePage {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showSubtitle = !showSubtitle
            },
        isDark = isDark,
        isBlackNightTheme = isBlackNightTheme,
        cornerRadius = 16.dp
    ) {
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
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (showSubtitle) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = card.subtitle,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
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
    isBlackNightTheme: Boolean = false,
    primaryColor: Color,
    group: MaxHzGroup,
    refreshTick: Int,
    pageKey: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showGroupSubtitles by remember(pageKey, group.label) { mutableStateOf(false) }
    val rowExpandedMap = remember(pageKey, group.label) { mutableStateMapOf<String, Boolean>() }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(end = 12.dp)
                .then(
                    if (group.collapsedByDefault) {
                        Modifier.clickable { onExpandedChange(!expanded) }
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(Modifier.weight(1f)) {
                SmallTitle(
                    text = if (group.collapsedByDefault) {
                        "${group.label} ${if (expanded) "▾" else "▸"}"
                    } else {
                        group.label
                    },
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            if (expanded) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (showGroupSubtitles) {
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            }
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val next = !showGroupSubtitles
                            showGroupSubtitles = next
                            rowExpandedMap.clear()
                        }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (showGroupSubtitles) "隐藏说明" else "显示说明",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (showGroupSubtitles) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        }
                    )
                }
            }
        }
        if (expanded) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                isDark = isDark,
                isBlackNightTheme = isBlackNightTheme,
                cornerRadius = 20.dp
            ) {
                Column {
                    group.entries.forEach { entry ->
                        key("$pageKey/${entry.title}") {
                            val showSub = rowExpandedMap[entry.title] ?: showGroupSubtitles
                            EntryRow(
                                entry = entry,
                                isDark = isDark,
                                primaryColor = primaryColor,
                                refreshTick = refreshTick,
                                pageKey = pageKey,
                                showSubtitle = showSub,
                                onToggleSubtitle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    rowExpandedMap[entry.title] = !showSub
                                }
                            )
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
    pageKey: Int,
    showSubtitle: Boolean,
    onToggleSubtitle: () -> Unit
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

    val colors = LiquidGlassTheme.categoryColorsFor(entry.title)

    Box(Modifier.onLongPressTogglePage(onToggleSubtitle)) {
        if (entry.toggle != null) {
            val enabled = remember(cacheKey) { entry.toggle.isEnabled() }
            SwitchPreference(
                title = entry.title,
                summary = if (showSubtitle) entry.subtitle else null,
                checked = isOn,
                enabled = enabled,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                onCheckedChange = { want ->
                    if (busy) return@SwitchPreference
                    busy = true
                    optimistic = want
                    entry.toggle.onToggle(want) { ok ->
                        busy = false
                        if (!ok) optimistic = null
                        MaxHzComposeRefreshBus.bump()
                    }
                },
                startAction = {
                    Box(Modifier.padding(end = 12.dp)) {
                        IconBadge(
                            painter = painterResource(featureIconRes(entry.title)),
                            colors = Pair(colors.first.toLong(), colors.second.toLong())
                        )
                    }
                }
            )
        } else {
            val isShortStatus = !statusText.isNullOrBlank() && statusText.length <= 8
            val displaySummary = if (!statusText.isNullOrBlank() && !isShortStatus) {
                "${entry.subtitle}\n当前状态：$statusText"
            } else {
                entry.subtitle
            }
            ArrowPreference(
                title = entry.title,
                summary = if (showSubtitle) displaySummary else null,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                onClick = entry.onClick,
                startAction = {
                    Box(Modifier.padding(end = 12.dp)) {
                        IconBadge(
                            painter = painterResource(featureIconRes(entry.title)),
                            colors = Pair(colors.first.toLong(), colors.second.toLong())
                        )
                    }
                },
                endActions = {
                    if (isShortStatus && statusText != null) {
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
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

