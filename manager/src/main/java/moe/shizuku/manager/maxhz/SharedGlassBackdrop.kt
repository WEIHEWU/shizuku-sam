package moe.shizuku.manager.maxhz

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 进程内共享的玻璃背景层。
 *
 * 为什么需要它：底部 dock 是 `MaxHzActivity` 里**另一个独立的 ComposeView**
 * （`glassDockOverlay`，不在页面组合树内）。它自己 `rememberLayerBackdrop()`
 * 出来的层**从未被 `Modifier.layerBackdrop` 录制过 —— 是空的**，于是 dock
 * 的 `glassSurface` 采样到一张空图，只剩「底色 + 高光」，没有背景折射。
 * 这正是「白底还在、玻璃感/折射差太多」的根因。
 *
 * dock 不能自己录制整屏背景：它只是底部一条 ~76dp 的覆盖层，画整屏背景会把
 * 页面内容整个盖住。所以只能**复用页面已经烘焙好的那一份**：页面把自己的
 * [LayerBackdrop] 发布到这里，dock 直接采样。
 *
 * 跨 ComposeView 采样之所以可行：两个 View 在同一个 window 内，
 * `LayerBackdrop.drawBackdrop` 的 `localPositionOf` 在不同组合树之间会落到
 * `positionInWindow()` 差值分支 —— 拿到的仍是**窗口坐标差**，偏移正确。
 *
 * 生命周期：页面用 [PublishBackdrop] 发布、组合销毁时自动撤回；
 * dock 读到 null 时退化成自己那张空层（= 修复前的行为），不会崩。
 */
object SharedGlassBackdrop {

    private var holder by mutableStateOf<LayerBackdrop?>(null)

    /**
     * 内容版本号：背景内容（壁纸 / 明暗 / 尺寸）变化时 +1。
     * 背景层是「静态才重录」的，重录后 dock 必须重画一帧才能采样到新内容，
     * 而 [holder] 仍是同一个实例（State 不会变），所以这里单独记一个版本号。
     */
    private var revision by mutableIntStateOf(0)

    /** 读取即订阅（holder / revision 任一变化都会让 dock 重组重绘） */
    fun snapshot(): Snapshot = Snapshot(holder, revision)

    fun publish(backdrop: LayerBackdrop) {
        holder = backdrop
        revision++
    }

    fun release(backdrop: LayerBackdrop) {
        if (holder === backdrop) {
            holder = null
            revision++
        }
    }

    /** 一次性读出两个 State，避免在调用方写两处 `by` 委托 */
    class Snapshot internal constructor(
        val backdrop: LayerBackdrop?,
        @Suppress("unused") val revision: Int
    )
}

/**
 * 把本页的玻璃背景层发布到 [SharedGlassBackdrop]，供独立 ComposeView 的
 * dock 采样。
 *
 * @param contentKey 背景内容标识；变化时重新发布（会 bump revision，
 *                   触发 dock 重绘，采样到重录后的新背景）。
 */
@Composable
fun PublishBackdrop(backdrop: LayerBackdrop, contentKey: Any? = Unit) {
    LaunchedEffect(backdrop, contentKey) {
        SharedGlassBackdrop.publish(backdrop)
    }
    DisposableEffect(backdrop) {
        onDispose { SharedGlassBackdrop.release(backdrop) }
    }
}
