package moe.shizuku.manager.maxhz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 轻量级 FPS / 当前前台应用悬浮窗。
 *
 * 设计约束（可读性优先）：
 * - 文字尺寸统一用 sp 并在伴生对象中命名，禁止再出现 6.5sp 这类几乎不可读的魔法值。
 * - 文字与背景之间保持足够对比度（背景半透明黑 + 文字近不透明白），
 *   避免在亮色壁纸或高光卡片上“看不清”。
 * - 折线图绘制逻辑保持不变，仅抽常量、不改算法。
 */
class MaxHzFpsOverlayView(context: Context) : View(context) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BACKGROUND_COLOR
    }

    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(FPS_TEXT_SIZE_SP)
        isFakeBoldText = true
        typeface = Typeface.MONOSPACE
        setShadowLayer(dp(1.5f), 0f, dp(0.6f), SHADOW_COLOR)
    }

    private val packagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(PACKAGE_TEXT_SIZE_SP)
        color = PACKAGE_TEXT_COLOR
        typeface = Typeface.MONOSPACE
        setShadowLayer(dp(1.2f), 0f, dp(0.5f), SHADOW_COLOR)
    }

    private val graphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(0.8f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val graphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var fpsText = "-- FPS"
    private var packageText = ""
    private val history = ArrayDeque<Double>()
    private val backgroundRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun update(fps: Double, packageName: String?) {
        val available = fps >= 0.0 && fps.isFinite()
        val safeFps = if (available) fps.coerceIn(0.0, 999.9) else 0.0
        val hasShell = rikka.shizuku.Shizuku.pingBinder() || MaxHzShell.isRootAvailable()
        val unit = if (hasShell) "FPS" else "Hz"
        fpsText = if (available) String.format(Locale.US, "%.1f %s", safeFps, unit) else "-- $unit"
        packageText = packageName.orEmpty()

        val color = when {
            !available -> FPS_COLOR_UNAVAILABLE
            safeFps >= 60 -> FPS_COLOR_GOOD
            safeFps >= 30 -> FPS_COLOR_MID
            else -> FPS_COLOR_LOW
        }
        fpsPaint.color = color
        graphStrokePaint.color = (color and 0x00ffffff) or GRAPH_STROKE_ALPHA
        graphFillPaint.color = (color and 0x00ffffff) or GRAPH_FILL_ALPHA

        if (available) {
            history.addLast(safeFps)
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        backgroundRect.set(0f, 0f, width, height)
        canvas.drawRoundRect(backgroundRect, dp(5f), dp(5f), backgroundPaint)
        drawHistory(canvas, width, height)
        canvas.drawText(fpsText, dp(4f), height * 0.62f, fpsPaint)
        compactPackageName().takeIf { it.isNotBlank() }?.let {
            canvas.drawText(it, dp(4f), height * 0.88f, packagePaint)
        }
    }

    private fun drawHistory(canvas: Canvas, width: Float, height: Float) {
        if (history.size < 2) return
        val xStep = width / (MAX_HISTORY - 1)
        val graphTop = dp(2f)
        val graphHeight = height - dp(4f)
        val stroke = Path()
        val fill = Path()

        history.forEachIndexed { index, value ->
            val x = index * xStep
            val normalized = (value / MAX_FPS_SCALE).toFloat()
            val y = graphTop + graphHeight - min(1f, normalized) * graphHeight
            val clampedY = max(graphTop + dp(1f), min(y, graphTop + graphHeight - dp(1f)))
            if (index == 0) {
                stroke.moveTo(x, clampedY)
                fill.moveTo(x, graphTop + graphHeight)
                fill.lineTo(x, clampedY)
            } else {
                stroke.lineTo(x, clampedY)
                fill.lineTo(x, clampedY)
            }
        }

        val lastX = (history.size - 1) * xStep
        fill.lineTo(lastX, graphTop + graphHeight)
        fill.close()
        canvas.drawPath(fill, graphFillPaint)
        canvas.drawPath(stroke, graphStrokePaint)
    }

    private fun compactPackageName(): String {
        if (packageText.isBlank()) return ""
        if (packagePaint.measureText(packageText) <= width - dp(8f)) return packageText
        val parts = packageText.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else packageText
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    companion object {
        /** 历史采样点数（折线图横轴分辨率）。 */
        private const val MAX_HISTORY = 60
        /** 折线图纵向归一化上限（Hz）。高于此值的 FPS 都会被压到顶部。 */
        private const val MAX_FPS_SCALE = 120f

        /** 主 FPS 数字字号（sp）。原 12.5 偏小，提到 14 更易读。 */
        private const val FPS_TEXT_SIZE_SP = 14f
        /** 包名/应用名字号（sp）。原 6.5 在手机上几乎不可读，提到 10。 */
        private const val PACKAGE_TEXT_SIZE_SP = 10f

        /** 悬浮窗背景：半透明黑，略提高不透明度以保证文字对比度。 */
        private const val BACKGROUND_COLOR = 0x800A0A0A.toInt()
        /** 文字投影颜色（近黑），用于在亮背景上兜底可读性。 */
        private const val SHADOW_COLOR = 0xCC000000.toInt()
        /** 包名文字颜色：近不透明白（原 0x99 仅 60%，改为 0xCC 约 80%）。 */
        private const val PACKAGE_TEXT_COLOR = 0xCCFFFFFF.toInt()

        /** FPS 文字配色（与折线图同色系）。 */
        private const val FPS_COLOR_UNAVAILABLE = 0xCCFFFFFF.toInt()
        private const val FPS_COLOR_GOOD = 0xDD4CAF50.toInt()
        private const val FPS_COLOR_MID = 0xDDEBD34A.toInt()
        private const val FPS_COLOR_LOW = 0xDDF44336.toInt()

        /** 折线描边/填充的透明度分量（叠加在 FPS 颜色之上）。 */
        private const val GRAPH_STROKE_ALPHA = 0x66000000.toInt()
        private const val GRAPH_FILL_ALPHA = 0x18000000.toInt()
    }
}
