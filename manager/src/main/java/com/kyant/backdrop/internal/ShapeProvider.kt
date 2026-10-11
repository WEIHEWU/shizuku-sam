package com.kyant.backdrop.internal

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

@Immutable
internal class ShapeProvider(val shapeBlock: () -> Shape) {

    private var _shape: Shape? = null
    private var _outline: Outline? = null
    private var _size: Size = Size.Unspecified
    private var _layoutDirection: LayoutDirection? = null
    private var _density: Float? = null

    val innerShape
        get() = shapeBlock()

    val shape = object : Shape {

        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            val shape = shapeBlock()
            if (_shape != shape) {
                _shape = shape
                _outline = null
            }
            if (_outline == null || _size != size || _layoutDirection != layoutDirection || _density != density.density) {
                _size = size
                _layoutDirection = layoutDirection
                _density = density.density
                _outline = shape.createOutline(size, layoutDirection, density)
            }

            return _outline!!
        }
    }

    /**
     * 相等性：两个 ShapeProvider 只要持有同一个 shapeBlock 实例就视为相等。
     *
     * 没有这两个方法时，ModifierNodeElement 的判等永远失败，任何一次无关重组
     * 都会让所有玻璃卡片重建一遍 RenderEffect（模糊 + 折射 + 色散整条链）。
     * 调用方（GlassSurface 等）用 remember 稳定 shapeBlock 后即可命中。
     */
    override fun equals(other: Any?): Boolean =
        this === other || (other is ShapeProvider && shapeBlock === other.shapeBlock)

    override fun hashCode(): Int = System.identityHashCode(shapeBlock)
}
