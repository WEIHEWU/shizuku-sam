package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.internal.ColorFilterEffect
import com.kyant.backdrop.internal.recordLayer
import com.kyant.backdrop.isRenderEffectSupported

/**
 * @param contentKey 背景内容的标识。变化时背景层会重新录制。
 *
 * 背景（壁纸 / 极光）是静态的，不需要每帧重录；但库无法自己知道「内容变了」，
 * 由调用方把决定内容的状态打包传进来（壁纸 URI / 明暗 / 极光开关 / 尺寸）。
 */
fun Modifier.layerBackdrop(backdrop: LayerBackdrop, contentKey: Any? = Unit): Modifier =
    this then LayerBackdropElement(backdrop, contentKey)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val contentKey: Any?
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, contentKey)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        if (node.contentKey != contentKey) {
            node.contentKey = contentKey
            node.dirty = true
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (contentKey != other.contentKey) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + (contentKey?.hashCode() ?: 0)
        return result
    }
}

/**
 * 背景层（壁纸 / 极光）是**静态**的 —— 没有任何动画驱动它逐帧变化。
 * 但 `draw()` 每帧都会被调用，原实现也每帧都 [recordLayer] 一次：
 * 等于每帧把整屏（1440×3200）的纵向渐变 + 数个大半径径向渐变重新画一遍
 * 并送进离屏层。这在深色极光下尤其贵，是「深色模式明显更卡」的主因之一。
 *
 * 这里改成：只在**内容变了**（[contentKey]）或**尺寸变了**时才重录。
 *
 * 注意 [drawContent] 不能省：它负责把背景画到屏幕上；录制是额外的一遍，
 * 进的是离屏层。省掉的是后者。
 *
 * 未使用 `observeReads`：在 draw 阶段注册快照观察会与绘制期的 snapshot
 * 读观察冲突，实测有崩溃风险，改为由调用方显式传内容标识，更可控。
 */
private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var contentKey: Any?
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    internal var dirty = true
    private var lastSize: Size = Size.Unspecified

    override fun ContentDrawScope.draw() {
        val sizeChanged = lastSize != size
        drawContent()

        if (dirty || sizeChanged) {
            dirty = false
            lastSize = size
            recordLayer(
                backdrop.graphicsLayer,
                this@LayerBackdropNode
            ) { backdrop.onDraw(this@draw) }

            // 烘焙模糊版（整屏一次）。背景静态 → 只在内容/尺寸变化时重做，
            // 之后每张卡片采样它，省掉「每卡每帧一次大半径高斯」这个最大开销。
            //
            // 关键：模糊必须挂在**源层**上、录完立刻摘掉。
            // Compose 的 GraphicsLayer.renderEffect 是在 drawLayer() 时应用的，
            // 不是 record 时。写过一版把 effect 挂在 blurredGraphicsLayer 上 ——
            // 结果 record 时没模糊，反而每次 drawLayer(blurred) 都重跑一遍
            // 全屏高斯（每卡每帧一次，比逐卡小区域模糊还贵）。真机实测：
            // 直通档与全质量档 jank 都是 ~30%，优化完全没生效。
            // 烘焙链：**先 vibrancy 再模糊**（与原逐卡滤镜链的执行顺序一致 ——
            // BackdropEffectScope 的 chain 是「后加的后执行」，原链为
            // colorControls → blur → lens，故 vibrancy 在 blur 之前）。
            //
            // vibrancy 之所以必须在这里烘焙：滚动降级档走的是 renderScale=0 的
            // 直通路径，**完全不跑 effects** —— 若增艳仍挂在每张卡的滤镜链上，
            // 静止时玻璃里的壁纸是 saturation ×1.65、一滑就掉回 ×1.0，
            // 用户看到的就是「滑动时壁纸色彩变了」。背景是静态的，增艳结果也
            // 是静态的，整屏做一次即可，两档共用同一份背景 → 色彩基线一致，
            // 而且 Full 档还少了一个每帧 ColorFilter pass，纯赚。
            val radius = backdrop.blurRadius.toPx()
            val vibrancyInput = backdrop.vibrancy?.let {
                if (isRenderEffectSupported()) ColorFilterEffect(null, it) else null
            }
            backdrop.graphicsLayer.renderEffect =
                if (radius > 0f && isRenderEffectSupported()) {
                    BlurEffect(vibrancyInput, radius, radius, TileMode.Clamp)
                } else {
                    vibrancyInput
                }
            recordLayer(
                backdrop.blurredGraphicsLayer,
                this@LayerBackdropNode
            ) { drawLayer(backdrop.graphicsLayer) }
            // 摘掉：源层保持无 effect，烘焙层也不能带 effect ——
            // 否则下游每张卡 drawLayer(blurred) 又会各跑一次高斯。
            backdrop.graphicsLayer.renderEffect = null
            backdrop.blurredGraphicsLayer.renderEffect = null

            // 通知下游玻璃：采样源换了新的，离屏内容得重录
            backdrop.version++
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
        dirty = true
    }
}
