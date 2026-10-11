package com.kyant.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    val size: Size

    val layoutDirection: LayoutDirection

    val shape: Shape

    var padding: Float

    var renderEffect: RenderEffect?

    /**
     * 离屏层相对屏幕的分辨率比例（0 < s <= 1）。
     *
     * 玻璃的 RenderEffect 链（大半径模糊 + 逐像素多次采样的折射 AGSL）是
     * 纯 fill-rate 开销，成本与离屏层像素数成正比。把离屏层按 s 缩小录制、
     * 绘制时再放大回屏幕尺寸，像素数变为 s² —— s = 0.6 即 **2.8 倍**提速。
     *
     * 视觉代价只落在折射采样的高频细节上：模糊本身是低通，半径 40px 以上时
     * 采样精度降到 0.6 肉眼分辨不出。
     *
     * effects 内的长度（blur 半径、折射高度/强度、圆角半径）都按此比例换算到
     * 离屏层坐标，因此**调用方传的仍是屏幕 dp/px，语义不变**。
     */
    val renderScale: Float
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    override var renderEffect: RenderEffect? = null
    override var renderScale: Float = 1f

    /** 未经 [renderScale] 缩放的原始（屏幕）尺寸，只用于变更判定 */
    private var rawSize: Size = Size.Unspecified

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    fun update(scope: DrawScope, renderScale: Float = 1f): Boolean {
        val newDensity = scope.density
        val newFontScale = scope.fontScale
        val newSize = scope.size
        val newLayoutDirection = scope.layoutDirection

        // 注意用 rawSize 而非 size 比较：size 是缩放后的，直接比较会永远不等，
        // 导致每帧都重建 renderEffect。
        val changed = newDensity != density ||
                newFontScale != fontScale ||
                newSize != rawSize ||
                newLayoutDirection != layoutDirection ||
                renderScale != this.renderScale

        if (changed) {
            density = newDensity
            fontScale = newFontScale
            rawSize = newSize
            size = Size(newSize.width * renderScale, newSize.height * renderScale)
            layoutDirection = newLayoutDirection
            this.renderScale = renderScale
        }

        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        padding = 0f
        renderEffect = null
        effects()
    }

    fun reset() {
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        runtimeShaderCache.clear()
    }
}
