package com.kyant.backdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightElement
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.internal.recordLayer
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.InnerShadowElement
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.ShadowElement

/**
 * 玻璃离屏层的渲染分辨率比例默认值 —— 由调用方通过 `drawBackdrop(renderScale = ...)` 覆盖。
 *
 * 玻璃的 RenderEffect 链（大半径高斯模糊 + 每像素 7 次采样的色散 AGSL）是纯
 * fill-rate 开销，与离屏层像素数成正比。按 s 缩小录制、绘制时放大回屏幕，
 * 像素数变为 s²：
 *
 * | s    | 像素数 | 提速   |
 * |------|--------|--------|
 * | 1.0  | 100%   | 1.0x   |
 * | 0.75 | 56%    | 1.8x   |
 * | 0.6  | 36%    | 2.8x   |
 * | 0.5  | 25%    | **4.0x** |
 * | 0.4  | 16%    | 6.3x   |
 *
 * 取 0.5（滚动中）：模糊是低通，0.5 的采样精度在模糊之后肉眼分辨不出；
 * 0.4 开始边缘色散条纹会明显变软。
 *
 * effects 内所有长度都由 renderScale 自动换算，调用方传的仍是屏幕 dp/px。
 */
internal const val DEFAULT_BACKDROP_RENDER_SCALE = 1.0f

/** 默认渲染比例提供者（引用恒定，避免 Modifier 判等失败） */
private val DefaultRenderScale: () -> Float = { DEFAULT_BACKDROP_RENDER_SCALE }

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    renderScale: () -> Float = DefaultRenderScale
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                renderScale = renderScale
            )
        )
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    renderScale: () -> Float = DefaultRenderScale
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            if (innerShadow != null) {
                InnerShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = innerShadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (shadow != null) {
                ShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = shadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (highlight != null) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = highlight
                )
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                renderScale = renderScale
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val effects: BackdropEffectScope.() -> Unit,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?,
    val renderScale: () -> Float
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            effects = effects,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront,
            renderScale = renderScale
        )
    }

    override fun update(node: DrawBackdropNode) {
        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.effects = effects
        node.layerBlock = layerBlock
        if (node.exportedBackdrop != exportedBackdrop) {
            node.exportedBackdrop?.layerCoordinates = null
            node.exportedBackdrop = exportedBackdrop
        }
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        if (node.renderScale != renderScale) {
            node.renderScale = renderScale
            node.invalidateDraw()
        }
        // 重组可能换了 effects / shape / backdrop → 离屏内容失效
        node.dirty = true
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false
        if (renderScale != other.renderScale) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (exportedBackdrop?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        result = 31 * result + renderScale.hashCode()
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var effects: BackdropEffectScope.() -> Unit,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?,
    var renderScale: () -> Float
) : LayoutModifierNode, DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, Modifier.Node() {

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null

    private val layoutLayerBlock: GraphicsLayerScope.() -> Unit = {
        clip = true
        shape = shapeProvider.shape
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Auto
    }

    private var layoutCoordinates: LayoutCoordinates? = null

    private var padding by mutableFloatStateOf(0f)

    /**
     * 注意 translate / scale 的调用顺序：先 translate(padding) 再 scale(s)，
     * 变换累积为 `s * p + padding` —— 即「内容缩小 s 倍后，整体落在离屏层的
     * padding 处」。反过来写会得到 `s * (p + padding)`，边缘留白不足，
     * 模糊会在卡片边界外截断。
     *
     * [BackdropRenderScale] 为 1 时与原始实现逐位等价。
     */
    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val padding = padding
        val scale = renderScale()

        canvas.save()
        if (padding != 0f) {
            canvas.translate(padding, padding)
        }
        if (scale != 1f) {
            canvas.scale(scale, scale)
        }
        try {
            onDrawBackdrop {
                with(backdrop) {
                    drawBackdrop(
                        density = effectScope,
                        coordinates = layoutCoordinates,
                        layerBlock = layerBlock
                    )
                }
            }
        } finally {
            canvas.restore()
        }
    }

    /**
     * 直通绘制：把背景的对应区域直接画进当前画布，完全不经过离屏层。
     *
     * 与 [drawBackdropLayer] 的区别只有「没有离屏、没有 RenderEffect」，
     * 取区域的坐标换算完全一致（同一个 [Backdrop.drawBackdrop]），
     * 因此不会有错位。形状裁剪由节点的 layoutLayerBlock 负责。
     */
    private val drawBackdropDirect: DrawScope.() -> Unit = {
        with(backdrop) {
            drawBackdrop(
                density = effectScope,
                coordinates = layoutCoordinates,
                layerBlock = layerBlock
            )
        }
    }

    /**
     * 离屏内容是否**需要重新录制**。
     *
     * 原先每帧都 `recordLayer` —— 但玻璃的离屏内容只在下列条件变化时才变：
     * 卡片位置/尺寸、渲染档位、effects 参数、背景内容本身。静止时这些**全都不变**，
     * 每帧重录等于把完全相同的内容反复画进离屏层。
     *
     * 实机数据（S25 Ultra，静止 5s）：正常档 **24fps / 每帧恒定 41.6ms**，
     * 而直通档 120fps / 8.3ms。41.6ms 不随离屏分辨率变化（0.5x 与 1x 完全相同），
     * 说明它根本不是 fill-rate，而是「每块玻璃一次离屏录制」的固定开销 ——
     * 一屏 6 张卡 + dock ≈ 7 次 × 约 6ms。缓存后静止时几乎归零。
     */
    internal var dirty = true

    private var recordedSize: IntSize? = null
    private var recordedScale = 0f
    private var recordedPadding = 0f
    private var recordedVersion = -1

    private val drawBackdropLayer: DrawScope.() -> Unit = {
        val layer = graphicsLayer
        val scale = renderScale()
        // 停机守卫：scale <= 0 时连离屏录制都不做，必须在这里挡住。
        // draw() 主路径有 `if (scale > 0f)` 保护，但末尾 exportedBackdrop 的
        // 录制块会无条件调用本函数 —— 少了这道守卫，下面的 canvas.scale(1f/scale)
        // 会拿到 1f/0f = Infinity，变换矩阵整个崩坏，实机表现为卡片背后拖出
        // 一片红色脏块（用户反馈的「滑动时出现红色阴影」）。
        if (layer != null && scale > 0f) {
            val padding = padding
            val layerSize = IntSize(
                (size.width * scale).toInt().coerceAtLeast(1)
                    + padding.toInt() * 2,
                (size.height * scale).toInt().coerceAtLeast(1)
                    + padding.toInt() * 2
            )
            val version = (backdrop as? LayerBackdrop)?.version ?: 0

            if (dirty || layerSize != recordedSize || scale != recordedScale ||
                padding != recordedPadding || version != recordedVersion
            ) {
                dirty = false
                recordedSize = layerSize
                recordedScale = scale
                recordedPadding = padding
                recordedVersion = version

                recordLayer(
                    layer,
                    this@DrawBackdropNode,
                    size = layerSize,
                    block = recordBackdropBlock
                )
            }

            // topLeft 是离屏层坐标（padding 已由 effects 按 renderScale 换算过）
            layer.topLeft =
                if (padding != 0f) IntOffset(-padding.toInt(), -padding.toInt())
                else IntOffset.Zero

            if (scale != 1f) {
                // 把缩小录制的离屏层放大回屏幕尺寸
                val canvas = drawContext.canvas
                canvas.save()
                canvas.scale(1f / scale, 1f / scale)
                drawLayer(layer)
                canvas.restore()
            } else {
                drawLayer(layer)
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(IntOffset.Zero, layerBlock = layoutLayerBlock)
        }
    }

    override fun ContentDrawScope.draw() {
        // renderScale <= 0 是调用方的「整条背景管线停机」信号（如快速甩动）：
        // 连离屏录制与绘制都跳过，成本归零；玻璃只剩底色 + 高光 + 投影。
        val scale = renderScale()
        if (scale > 0f) {
            if (effectScope.update(this, scale)) {
                updateEffects()
                // 尺寸 / 密度 / 档位比例变了 → 离屏内容失效
                dirty = true
            }
        }

        val versionBefore = (backdrop as? LayerBackdrop)?.version ?: 0
        onDrawBehind?.invoke(this)
        if (scale > 0f) {
            drawBackdropLayer()
        } else if (scale == 0f) {
            // 停机 / 直通档：不进离屏层，直接把背景那一块画到当前画布。
            // 背景层本身已经是预烘焙的模糊版，所以这里出来的仍是一块磨砂玻璃 ——
            // 只是没有边缘折射。成本 = 一次 drawLayer，且不分配/切换 render target。
            drawBackdropDirect()
        }
        // scale < 0：诊断用的「无玻璃」档，连背景都不画。
        onDrawSurface?.invoke(this)
        drawContent()
        onDrawFront?.invoke(this)
        val versionAfter = (backdrop as? LayerBackdrop)?.version ?: 0
        if (versionAfter != versionBefore) {
            dirty = true
            invalidateDraw()
        }

        // 停机时整块跳过：保留上一帧的背景层内容，而不是把一张没有玻璃的空图
        // 录进去（否则依赖它的下游表面会突然失去背景）。
        if (scale > 0f) {
            exportedBackdrop?.graphicsLayer?.let { layer ->
                recordLayer(layer, this@DrawBackdropNode) {
                    onDrawBehind?.invoke(this)
                    drawBackdropLayer()
                    onDrawSurface?.invoke(this)
                    onDrawFront?.invoke(this)
                }
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            // 卡片位置/尺寸变了 → 它相对背景的位置变了，离屏内容必须重录。
            // （滚动结束后卡片停在新位置，会重录一次；之后静止就不再重录了。）
            dirty = true
            if (backdrop.isCoordinatesDependent) {
                layoutCoordinates = coordinates
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
            exportedBackdrop?.layerCoordinates = coordinates
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDrawCache()
        // 档位 → renderScale / effects 变了，离屏内容要重录；必须重画一帧才生效
        dirty = true
        invalidateDraw()
    }

    fun invalidateDrawCache() {
        observeEffects()
    }

    private fun observeEffects() {
        observeReads {
            // 先跑一次 renderScale 提供者：调用方会在里面读取决定档位的 State。
            // 必须在这里读 —— updateEffects() 在 size 未知时会提前返回，
            // 档位 State 的观察就注册不上，档位变化也就不会触发重绘了。
            renderScale()
            updateEffects()
        }
    }

    private fun updateEffects() {
        if (!isRenderEffectSupported()) return

        // 首帧绘制前的 observeReads 触发（onAttach / 失效重算）时，
        // effectScope.size 仍是 Size.Unspecified；此时执行 effects 会在
        // shape.corners() 的 size.minDimension 上抛 IllegalStateException。
        // 跳过即可：draw() 每帧都会用真实尺寸重跑 updateEffects。
        if (effectScope.size.isUnspecified) return

        effectScope.apply(effects)
        graphicsLayer?.renderEffect = effectScope.renderEffect
        padding = effectScope.padding
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()

        observeEffects()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }

        effectScope.reset()
        layoutCoordinates = null
        exportedBackdrop?.layerCoordinates = null
    }
}
