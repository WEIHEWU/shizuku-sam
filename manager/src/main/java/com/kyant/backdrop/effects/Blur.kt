package com.kyant.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.isRenderEffectSupported

fun BackdropEffectScope.blur(
    @FloatRange(from = 0.0) radius: Float,
    edgeTreatment: TileMode = TileMode.Clamp
) {
    if (!isRenderEffectSupported()) return
    if (radius <= 0f) return

    // 换算到离屏层坐标（见 BackdropEffectScope.renderScale）
    val r = radius * renderScale

    if (edgeTreatment != TileMode.Clamp || renderEffect != null) {
        if (r > padding) {
            padding = r
        }
    }

    renderEffect =
        BlurEffect(
            renderEffect,
            r,
            r,
            edgeTreatment
        )
}
