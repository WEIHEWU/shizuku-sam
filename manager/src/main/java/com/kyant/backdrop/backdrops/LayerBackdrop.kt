package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

/**
 * 背景层。除原始背景外，还烘焙一份**整屏模糊版** [blurredGraphicsLayer]。
 *
 * 这是整条玻璃管线最重要的性能设计：模糊是静态背景的低通结果，背景不动 →
 * 模糊结果也不动 → **整屏只做一次**，之后每张卡片采样它即可，不必各自再跑
 * 一遍大半径高斯。原来每张卡每帧一次 63px 半径高斯（一屏 5~6 张）是卡顿的根因。
 *
 * 副作用是正面的：整屏模糊不存在「卡片边界截断」问题，边缘比逐卡模糊更自然。
 *
 * [vibrancy] 同理上移：玻璃的增艳（saturation ×1.65）也是静态背景的函数，
 * 烘焙进这一层后，**静止与滚动两档采样到的是同一份背景**，色彩基线完全一致
 * —— 修复「滑动时壁纸色彩变了」。详见 LayerBackdropModifier 的烘焙块。
 */
@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    blurredGraphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    blurRadius: Dp = 18.dp,
    vibrancy: ColorFilter? = null,
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, blurredGraphicsLayer, blurRadius, vibrancy, onDraw) {
        LayerBackdrop(graphicsLayer, blurredGraphicsLayer, blurRadius, vibrancy, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    val blurredGraphicsLayer: GraphicsLayer,
    val blurRadius: Dp,
    val vibrancy: ColorFilter?,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? = null

    /**
     * 背景内容版本号：背景层每次重新录制（壁纸 / 明暗 / 尺寸变化）时 +1。
     *
     * 下游的玻璃面据此判断「采样源变了，我的离屏内容得重录」。没有它，静止时
     * 每张卡每帧都会把**完全相同**的内容重录一遍 —— 实机实测这正是静止只有
     * 24fps（每帧恒定 41.6ms ≈ 一屏 7 块玻璃 × 6ms 的离屏录制）的根因。
     */
    @Volatile
    var version: Int = 0
        internal set

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // TODO: outer transformations lead to wrong position calculation
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            if (blurRadius > 0.dp || vibrancy != null) {
                drawLayer(blurredGraphicsLayer)
            } else {
                drawLayer(graphicsLayer)
            }
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
