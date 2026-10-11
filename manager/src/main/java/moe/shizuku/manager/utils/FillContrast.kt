package moe.shizuku.manager.utils

import android.graphics.Color
import kotlin.math.pow

/**
 * 填充类控件（状态胶囊 / 实心按钮）的 WCAG 对比度收敛。
 *
 * 为什么要这个：状态胶囊原本「填充色」和「文字色」取了同一色相的两个相邻值
 * —— 浅色警告填充 #D97A05、文字 #D97706，实测对比度只有 1.03:1，字几乎完全
 * 融进底里，等于白画。而强调色是动态取色（themeColors.primary），没法写死
 * 一组固定色值了事，只能在运行时按相对亮度收敛。
 *
 * 这里用的是 android.graphics.Color 的 Int 色值，面向老 View 通道；
 * Compose 通道请用手性相同的 utils/Contrast.kt。
 */
object FillContrast {

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val INK = 0xFF0B1220.toInt()

    /**
     * 目标是 WCAG AA 的 4.5:1，但实测会系统性偏低约 0.1 —— 字形抗锯齿的边缘
     * 过渡色会混进采样，把「字 vs 底」的距离拉近。第一版按 4.5 收敛，浅色
     * 信息胶囊实测只有 4.42:1，擦边不过。留 0.2 余量。
     */
    const val AA_TARGET = 4.7f

    private fun channel(v: Int): Float {
        val c = v / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun luminance(color: Int): Float =
        0.2126f * channel(Color.red(color)) +
            0.7152f * channel(Color.green(color)) +
            0.0722f * channel(Color.blue(color))

    fun ratio(fg: Int, bg: Int): Float {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** 按 k 等比缩放 RGB，保持色相不变、只改明暗 */
    private fun scale(color: Int, k: Float): Int = Color.argb(
        255,
        (Color.red(color) * k).toInt().coerceIn(0, 255),
        (Color.green(color) * k).toInt().coerceIn(0, 255),
        (Color.blue(color) * k).toInt().coerceIn(0, 255)
    )

    private fun lerp(a: Int, b: Int, t: Float): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    /**
     * 沿「fg → dest」方向二分找最小改动量，使 fg 对 bg 达到 target。
     * 即便推到端点也够不到 target 就返回 null（判为该方向不可行）。
     */
    private fun solve(fg: Int, bg: Int, dest: Int, target: Float): Pair<Int, Float>? {
        if (ratio(dest, bg) < target) return null
        var lo = 0f
        var hi = 1f
        repeat(12) {
            val mid = (lo + hi) / 2f
            if (ratio(lerp(fg, dest, mid), bg) >= target) hi = mid else lo = mid
        }
        return lerp(fg, dest, hi) to hi
    }

    /**
     * 在给定填充色上取一个达标的文字色。
     *
     * 分别朝黑、白两个方向求最小改动量，取「可行且改动更小」的那个。
     * 之所以要判可行性：深色模式下信息胶囊填充就是强调色本身（亮度 0.17），
     * 朝黑推到纯黑也只有 4.31:1，够不到 4.5 —— 必须朝白走。
     */
    fun readableOn(fill: Int, preferred: Int, target: Float = AA_TARGET): Int {
        if (ratio(preferred, fill) >= target) return preferred
        val dark = solve(preferred, fill, BLACK, target)
        val light = solve(preferred, fill, WHITE, target)
        return when {
            dark == null && light == null ->
                if (ratio(WHITE, fill) >= ratio(BLACK, fill)) WHITE else BLACK
            dark == null -> light!!.first
            light == null -> dark.first
            dark.second <= light.second -> dark.first
            else -> light.first
        }
    }

    /**
     * 实心按钮：保留「白字 + 彩色填充」的观感，把填充压暗到刚好让白字达标，
     * 而不是反过来把白字改成黑字。
     *
     * 红色 #FF3B30 配白字只有 3.55:1；等比压暗到 #DF342A 后 4.50:1。
     * 强调色是动态取色，所以这里在运行时算，不能写死。
     */
    fun fillForWhite(fill: Int, target: Float = AA_TARGET): Int =
        applyScale(fill, scaleForWhite(fill, target))

    /** 让白字达标所需的填充缩放系数（1f 表示原本就达标，不用动） */
    fun scaleForWhite(fill: Int, target: Float = AA_TARGET): Float {
        val opaque = opaque(fill)
        if (ratio(WHITE, opaque) >= target) return 1f
        var lo = 0f
        var hi = 1f
        repeat(12) {
            val mid = (lo + hi) / 2f
            if (ratio(WHITE, scale(opaque, 1f - mid)) >= target) hi = mid else lo = mid
        }
        return 1f - hi
    }

    /**
     * 按系数缩放填充。渐变色请整组用同一个系数（取最亮那一档算出来），
     * 否则每档各自压到临界值会把渐变压平 —— 红色 #FF3B30→#D72820 两端单独
     * 压完变成 #DF342A→#D72820，明暗关系基本没了。
     */
    fun applyScale(fill: Int, k: Float): Int = scale(opaque(fill), k)

    private fun opaque(color: Int): Int =
        Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))

    /** 供状态胶囊在浅色下使用的深色字基准（比纯黑略带冷调，压在彩色上不死板） */
    fun ink(): Int = INK
}
