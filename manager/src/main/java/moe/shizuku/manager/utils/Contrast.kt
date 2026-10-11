package moe.shizuku.manager.utils

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG 对比度工具。
 *
 * 背景：项目多处把「主题强调色」直接当作文字色使用（例如屏幕页的分组小标题
 * `color = primaryColor`）。强调色是给按钮底色/图标设计的，压在浅色玻璃卡片上
 * 实测只有 3.39:1，低于 AA 的 4.5:1。而强调色是动态取色（themeColors.primary），
 * 硬编码一个「更深的蓝」在换主题后会立刻失效，所以这里按 WCAG 相对亮度做
 * 运行时收敛，而不是写死颜色。
 */
object Contrast {

    /** sRGB 通道线性化（WCAG 2.1 定义） */
    private fun channel(v: Float): Float {
        val c = v / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** WCAG 相对亮度 */
    fun luminance(color: Color): Float =
        0.2126f * channel(color.red * 255f) +
            0.7152f * channel(color.green * 255f) +
            0.0722f * channel(color.blue * 255f)

    /** WCAG 对比度，返回 1.0 ~ 21.0 */
    fun ratio(fg: Color, bg: Color): Float {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * WCAG 里「与黑白对比度相等」的亮度分界点：背景亮于此值就该压暗文字，
     * 暗于此值就该提亮文字。用于自动选择收敛方向。
     */
    private const val MID_LUMINANCE = 0.1791f

    /**
     * 把前景色朝 [toward] 逐步混合，直到与 [bg] 的对比度达到 [target]。
     *
     * [toward] 缺省时按背景亮度自动选方向——浅底压暗、深底提亮。
     * 这一点很关键：深色模式下把强调色继续压黑只会让对比度更差。
     *
     * 二分 8 次（1/256 精度），只找「刚好够用」的最小改动量，尽量保留原本色相，
     * 避免过度加深把强调色压成死黑、丢掉视觉层级。
     *
     * [target] 默认 **4.8 而不是 AA 线的 4.5**，这是实测倒推出来的余量：
     * 按 4.5 收敛后，真机实测仍会掉到 4.34~4.47（浅色无壁纸最严苛），差 0.03~0.16
     * 就是过不了线。原因是 11sp 的状态文字几乎没有「纯色」像素，抗锯齿会把
     * 实测字色往背景方向拖；而玻璃表面色又随壁纸与 backdrop 采样点浮动，
     * [glassSurface] 只是代表性取值，不是最坏取值。留 0.3 余量后实测稳定在 4.7+。
     */
    fun ensure(
        fg: Color,
        bg: Color,
        target: Float = 4.8f,
        toward: Color? = null
    ): Color {
        if (ratio(fg, bg) >= target) return fg
        val dest = toward ?: if (luminance(bg) > MID_LUMINANCE) Color.Black else Color.White
        var lo = 0f   // 完全不改（不达标）
        var hi = 1f   // 完全变成 dest（必定达标）
        repeat(8) {
            val mid = (lo + hi) / 2f
            if (ratio(lerp(fg, dest, mid), bg) >= target) hi = mid else lo = mid
        }
        return lerp(fg, dest, hi)
    }

    /**
     * 玻璃卡片的代表性表面色，作为对比度收敛的参考背景。
     *
     * 取的是实测中最不利（对文字最不友好）的取值，并额外留了余量：
     * 浅色玻璃卡片实测最暗约 (208,232,248)，这里取更暗的 #C8E4F4；
     * 深色约 #1A2430。
     *
     * 为什么必须比实测值更暗：第一版直接拿实测最暗值 #D8E8F0 当基准，
     * 结果浅色无壁纸下仍测出 4.37:1（差 0.13 就达标）—— 因为玻璃表面
     * 随壁纸和 backdrop 采样点浮动，用实测值做基准等于零余量。
     */
    fun glassSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF1A2430) else Color(0xFFC8E4F4)

    private fun lerp(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
        colorSpace = a.colorSpace
    )
}
