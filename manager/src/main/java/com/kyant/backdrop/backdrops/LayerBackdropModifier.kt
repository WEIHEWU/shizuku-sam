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

            val radius = backdrop.blurRadius.toPx()
            if (radius > 0f || backdrop.vibrancy != null) {
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
                backdrop.graphicsLayer.renderEffect = null
                backdrop.blurredGraphicsLayer.renderEffect = null
            }

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
