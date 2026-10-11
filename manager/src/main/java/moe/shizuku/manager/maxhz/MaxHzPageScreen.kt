package moe.shizuku.manager.maxhz

import android.net.Uri
import android.view.View
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
    val bgColor = if (isBlackNightTheme) Color.Black else MiuixTheme.colorScheme.surface

    Box(Modifier.fillMaxSize().background(bgColor)) {
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
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
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = card.subtitle,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
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
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
            ) {
                Column {
                    group.entries.forEach { entry ->
                        key("$pageKey/${entry.title}") {
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

    val colors = LiquidGlassTheme.categoryColorsFor(entry.title)
    val summaryText = if (!statusText.isNullOrBlank()) {
        "${entry.subtitle} · $statusText"
    } else {
        entry.subtitle
    }

    if (entry.toggle != null) {
        val enabled = remember(cacheKey) { entry.toggle.isEnabled() }
        SwitchPreference(
            title = entry.title,
            summary = summaryText,
            checked = isOn,
            enabled = enabled,
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
                IconBadge(
                    painter = painterResource(featureIconRes(entry.title)),
                    colors = Pair(colors.first.toLong(), colors.second.toLong())
                )
            }
        )
    } else {
        ArrowPreference(
            title = entry.title,
            summary = entry.subtitle,
            onClick = entry.onClick,
            startAction = {
                IconBadge(
                    painter = painterResource(featureIconRes(entry.title)),
                    colors = Pair(colors.first.toLong(), colors.second.toLong())
                )
            },
            endActions = {
                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }
        )
    }
}

/** 全局刷新总线：Activity 在 onDone / updateMaxHzServiceState 后 bump */
object MaxHzComposeRefreshBus {
    internal val tick = mutableStateOf(0)
    fun bump() {
        tick.value++
    }
}

