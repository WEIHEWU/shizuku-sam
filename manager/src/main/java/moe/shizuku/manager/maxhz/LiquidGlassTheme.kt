package moe.shizuku.manager.maxhz

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import moe.shizuku.manager.utils.FillContrast
import java.lang.ref.WeakReference

/**
 * 液态玻璃引擎（Liquid Glass Engine）
 *
 * 折射数学与参数体系移植自 Kyant0/AndroidLiquidGlass（Apache-2.0）：
 *   - 圆角矩形解析 SDF：sdRoundedRect / gradSdRoundedRect
 *   - 球面折射轮廓：circleMap
 *   - 边缘折射只在距边缘 refractionHeight 内生效，内部区域原样输出
 *   - vibrancy 提饱和（原库默认 1.5）
 *
 * ------------------------------------------------------------------
 * 与上一版的关键区别：玻璃不再折射「View 自己的内容」
 * ------------------------------------------------------------------
 * 原库是 Compose，drawBackdrop 能通过 GraphicsLayer 捕获卡片「背后」的页面
 * 内容做折射；View 体系里 View.setRenderEffect() 只能拿到 View 自己绘制的
 * 内容。上一版因此把 RenderEffect 套在整张卡片上，后果有两个：
 *   1. 卡片内文字一起被偏移采样，出现 1px 横向裂缝、文字被切断；
 *   2. 亮色模式拿不到任何可折射的内容，只能直接 return，等于没有玻璃。
 *
 * 本版改为「共享背景快照」：页面背景是我们自己的 Drawable，可以离屏精确
 * 重绘成一张 Bitmap（再生成两级模糊版本）。卡片绘制时按自己在窗口中的
 * 位置去采样这张快照，依次做折射、提饱和、叠玻璃色、边缘高光、内阴影。
 * 由此：
 *   - 玻璃真正透出卡片背后的背景，亮色和深色走完全相同的管线；
 *   - 文字绘制在玻璃层之上，不经过任何 shader，永远 100% 清晰；
 *   - 一份快照供整页所有卡片复用，GPU 开销与卡片数量无关。
 *
 * 设备低于 Android 13（无 RuntimeShader/AGSL）或着色器编译失败时，
 * 自动回退到纯 Canvas 绘制的近似玻璃，保证可读性与观感不塌。
 */
object LiquidGlassTheme {

    /** 快照相对屏幕的分辨率比例：半分辨率存储，省 3/4 内存与采样带宽 */
    private const val BACKDROP_SCALE = 0.5f

    // ==================================================================
    // 一、共享背景快照
    // ==================================================================

    /**
     * 整页背景快照。由 [captureBackdrop] 生成，页面上所有玻璃组件共享。
     */
    object Backdrop {

        /** 快照对应的页面原点在窗口中的坐标，卡片据此换算自身采样位置 */
        var originX: Int = 0
            private set
        var originY: Int = 0
            private set

        private var sharp: Bitmap? = null
        private var near: Bitmap? = null
        private var far: Bitmap? = null

        /** 快照是否可用于着色器路径 */
        internal val isReady: Boolean
            get() = sharp?.takeIf { !it.isRecycled } != null &&
                near?.takeIf { !it.isRecycled } != null &&
                far?.takeIf { !it.isRecycled } != null

        internal fun near(): Bitmap? = near?.takeIf { !it.isRecycled }
        internal fun far(): Bitmap? = far?.takeIf { !it.isRecycled }

        /**
         * 所有正在使用快照的玻璃 Drawable。
         *
         * 快照换新后必须逐个 invalidateSelf()，否则已绘制过的卡片会一直停在
         * 旧快照甚至回退路径上。原因有两条，缺一不可：
         *   1. 时序：卡片首帧绘制早于 post 出去的 captureBackdrop，那一刻
         *      快照还是空的，只能走回退路径；
         *   2. 硬件加速：host.invalidate() 只重绘 host 自身，不会递归到子
         *      View，光靠 invalidate 根布局叫不醒卡片。
         * 用弱引用持有，避免 Drawable 随 View 泄漏。
         */
        private val consumers = ArrayList<WeakReference<Drawable>>()

        internal fun register(drawable: Drawable) {
            consumers.removeAll { it.get() == null }
            consumers.add(WeakReference(drawable))
        }

        private fun notifyConsumers() {
            for (i in consumers.indices) {
                consumers[i].get()?.invalidateSelf()
            }
            consumers.removeAll { it.get() == null }
        }

        internal fun update(
            base: Bitmap?,
            nearBlur: Bitmap?,
            farBlur: Bitmap?,
            originX: Int,
            originY: Int
        ) {
            sharp?.takeIf { it !== base && !it.isRecycled }?.recycle()
            sharp = base
            near = nearBlur
            far = farBlur
            this.originX = originX
            this.originY = originY
            notifyConsumers()
        }

        internal fun release() {
            sharp = null
            near = null
            far = null
            notifyConsumers()
        }
    }

    /**
     * 采集页面背景快照。
     *
     * @param host 承载页面背景的容器（通常是 rootLayout）。其尺寸决定快照
     *             尺寸，其窗口位置决定卡片采样时的坐标原点。
     * @param isDark 深色模式
     * @param isBlack 纯黑主题（仅深色下有效）
     */
    @JvmStatic
    fun captureBackdrop(host: View, isDark: Boolean, isBlack: Boolean) {
        if (GLASS_FORCE_DIRECT) return
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        try {
            val loc = IntArray(2)
            host.getLocationInWindow(loc)

            val bw = (w * BACKDROP_SCALE).toInt().coerceAtLeast(1)
            val bh = (h * BACKDROP_SCALE).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // 用一个独立实例绘制，避免 setBounds 干扰真实背景的布局
            val snapshot = createPageBackgroundDrawable(isDark, isBlack)
            snapshot.setBounds(0, 0, bw, bh)
            snapshot.draw(canvas)

            Backdrop.update(
                base = bmp,
                nearBlur = downsampleUpscale(bmp, 2),
                farBlur = downsampleUpscale(bmp, 8),
                originX = loc[0],
                originY = loc[1]
            )
        } catch (_: Throwable) {
            Backdrop.release()
        }
    }

    /** 释放快照（Activity 销毁或主题切换前调用） */
    @JvmStatic
    fun releaseBackdrop() {
        Backdrop.release()
    }

    /** 深色模式判定（按系统 uiMode，供非 MaxHzActivity 的页面复用） */
    @JvmStatic
    fun isNightMode(config: android.content.res.Configuration): Boolean =
        config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * 给任意 Activity 挂上玻璃页面背景 —— 一行接入。
     *
     * MaxHzActivity 体系之外的页面（设置、应用管理、关于等）原本完全没有玻璃，
     * 视觉与主页面脱节。这个入口把「背景 Drawable + 快照采集 + 尺寸变化重采」
     * 打包好，任何 Activity 调一次即可与主页面保持一致。
     *
     * 快照是全局共享的，后进入的页面会覆盖前一个页面的快照；返回时前一个
     * 页面会在 onResume 重新采集，不会串。
     */
    @JvmStatic
    fun attachGlassPageBackground(activity: android.app.Activity) {
        val host = activity.findViewById<View>(android.R.id.content) ?: return
        var isDark = isNightMode(activity.resources.configuration)
        var isBlack = isDark && runCatching {
            moe.shizuku.manager.app.ThemeHelper.isBlackNightTheme(activity)
        }.getOrDefault(false)

        LiquidGlassTheme.setDensity(activity.resources.displayMetrics.density)
        // 非 MaxHzActivity 的页面（设置、教程等）拿不到 themeColors，这里直接从
        // 主题解析 colorPrimary 灌进去 —— 动态取色开启时同样会跟随系统主题色。
        runCatching {
            val tv = android.util.TypedValue()
            if (activity.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorPrimary, tv, true
                )
            ) {
                LiquidGlassTheme.setAccent(tv.data)
            }
        }
        host.background = createPageBackgroundDrawable(isDark, isBlack)

        // 用显式 Runnable：末句 host.post{} 返回 Boolean，
        // 若写成 lambda 会被推断成 () -> Boolean，post() 不接受。
        val recapture = Runnable {
            isDark = isNightMode(activity.resources.configuration)
            isBlack = isDark && runCatching {
                moe.shizuku.manager.app.ThemeHelper.isBlackNightTheme(activity)
            }.getOrDefault(false)
            host.background = createPageBackgroundDrawable(isDark, isBlack)
            host.post {
                captureBackdrop(host, isDark, isBlack)
                host.invalidate()
            }
        }
        host.post(recapture)
        host.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) host.post(recapture)
        }
    }

    /**
     * 降采样再升采样，用双线性插值近似高斯模糊。
     *
     * 相比在着色器里做多次采样，这种方式一次性完成、成本固定，且所有卡片
     * 共享同一张结果，是本方案能保持高性能的关键。
     */
    private fun downsampleUpscale(src: Bitmap, scale: Int): Bitmap {
        val sw = (src.width / scale).coerceAtLeast(1)
        val sh = (src.height / scale).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        if (small.width == src.width && small.height == src.height) return small
        val out = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (!small.isRecycled) small.recycle()
        return out
    }

    // ==================================================================
    // 二、AGSL 玻璃着色器
    // ==================================================================

    /**
     * 一次 draw 内完成：SDF 圆角裁剪（抗锯齿）-> 边缘折射 -> 背景快照采样
     * （近/远模糊混合）-> vibrancy 提饱和 -> 玻璃底色 -> 边缘高光 -> 内阴影
     * -> 描边。
     *
     * 与原库 RoundedRectRefractionShaderString 的差异：
     *   * 新增背景快照采样、模糊混合、vibrancy、高光、内阴影、描边（原库这些
     *     是独立 Compose Modifier，这里合并进一个着色器以减少绘制趟数）
     *   * radiusAt 用居中坐标（原库用未居中的 coord，四角等半径时等价，
     *     居中版本在四角不等半径时才正确）
     *   * 返回非预乘颜色，因为这里作为 Paint 的 Shader 使用
     */
    private const val GLASS_SHADER = """
uniform shader backdropNear;
uniform shader backdropFar;

uniform float2 size;
uniform float2 origin;
uniform float backdropScale;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
uniform float blurMix;
uniform float vibrancy;

uniform half4 glassTint;
uniform float tintStrength;

uniform half4 highlightColor;
uniform float highlightAngle;
uniform float highlightWidth;
uniform float highlightFalloff;
uniform float highlightStrength;

uniform half4 shadowColor;
uniform float shadowAngle;
uniform float shadowWidth;
uniform float shadowFalloff;
uniform float shadowStrength;

uniform half4 strokeColor;
uniform float strokeWidth;
uniform float strokeStrength;

uniform float overallAlpha;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
}

half3 sampleBackdrop(float2 coord) {
    float2 c = coord * backdropScale;
    return mix(backdropNear.eval(c).rgb, backdropFar.eval(c).rgb, blurMix);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centered = coord - halfSize;
    float radius = radiusAt(centered, cornerRadii);
    float sd = sdRoundedRect(centered, halfSize, radius);

    float mask = 1.0 - smoothstep(-0.75, 0.75, sd);
    if (mask <= 0.0) {
        return half4(0.0);
    }

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centered, halfSize, gradRadius);

    float2 sampleCoord = coord + origin;
    float2 offset = float2(0.0);
    bool refracting = refractionHeight > 0.0 && (-sd) < refractionHeight;

    if (refracting) {
        float clamped = min(sd, 0.0);
        float d = circleMap(1.0 - (-clamped) / refractionHeight) * refractionAmount;
        float2 dir = normalize(grad + depthEffect * normalize(centered + float2(0.0001, 0.0001)));
        offset = d * dir;
    }

    float3 color;
    if (chromaticAberration > 0.0 && refracting) {
        float2 base = sampleCoord + offset;
        float disp = chromaticAberration
            * ((centered.x * centered.y) / max(0.0001, halfSize.x * halfSize.y));
        float2 dc = offset * disp;
        float3 c = float3(0.0);
        c.r += float3(sampleBackdrop(base + dc)).r / 3.5;
        c.r += float3(sampleBackdrop(base + dc * (2.0 / 3.0))).r / 3.5;
        c.g += float3(sampleBackdrop(base + dc * (2.0 / 3.0))).g / 7.0;
        c.r += float3(sampleBackdrop(base + dc / 3.0)).r / 3.5;
        c.g += float3(sampleBackdrop(base + dc / 3.0)).g / 3.5;
        c.g += float3(sampleBackdrop(base)).g / 3.5;
        c.g += float3(sampleBackdrop(base - dc / 3.0)).g / 3.5;
        c.b += float3(sampleBackdrop(base - dc / 3.0)).b / 3.0;
        c.b += float3(sampleBackdrop(base - dc * (2.0 / 3.0))).b / 3.0;
        c.r += float3(sampleBackdrop(base - dc)).r / 7.0;
        c.b += float3(sampleBackdrop(base - dc)).b / 3.0;
        color = c;
    } else {
        color = float3(sampleBackdrop(sampleCoord + offset));
    }

    float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
    color = mix(float3(luma, luma, luma), color, vibrancy);

    color = mix(color, float3(glassTint.rgb), tintStrength);

    if (highlightStrength > 0.0) {
        float edge = clamp(1.0 + sd / max(1.0, highlightWidth), 0.0, 1.0);
        float dir = dot(normalize(grad + float2(0.0001, 0.0001)),
            float2(cos(highlightAngle), sin(highlightAngle)));
        float amount = pow(max(dir, 0.0), highlightFalloff) * edge;
        color = mix(color, float3(highlightColor.rgb), amount * highlightStrength);
    }

    if (shadowStrength > 0.0) {
        float edge = clamp(1.0 + sd / max(1.0, shadowWidth), 0.0, 1.0);
        float dir = dot(normalize(grad + float2(0.0001, 0.0001)),
            float2(cos(shadowAngle), sin(shadowAngle)));
        float amount = pow(max(-dir, 0.0), shadowFalloff) * edge;
        color = mix(color, float3(shadowColor.rgb), amount * shadowStrength);
    }

    if (strokeStrength > 0.0 && strokeWidth > 0.0) {
        float ring = 1.0 - smoothstep(0.0, strokeWidth, abs(sd + strokeWidth * 0.5));
        color = mix(color, float3(strokeColor.rgb), ring * strokeStrength);
    }

    return half4(color, mask * overallAlpha);
}
"""

    // ==================================================================
    // 三、亮色 / 深色参数体系
    // ==================================================================

    /**
     * 一套完整的玻璃参数。亮色与深色的差异全部收敛在这里，渲染流程完全
     * 一致，从结构上避免「改了深色漏掉亮色」。
     */
    private data class GlassSpec(
        val glassTint: Int,
        val tintStrength: Float,
        val blurMix: Float,
        val vibrancy: Float,
        val refractionHeightPx: Float,
        val refractionAmountPx: Float,
        val depthEffect: Boolean,
        val chromaticAberration: Float,
        val highlightColor: Int,
        val highlightAngle: Float,
        val highlightWidthPx: Float,
        val highlightFalloff: Float,
        val highlightStrength: Float,
        val shadowColor: Int,
        val shadowAngle: Float,
        val shadowWidthPx: Float,
        val shadowFalloff: Float,
        val shadowStrength: Float,
        val strokeColor: Int,
        val strokeWidthPx: Float,
        val strokeStrength: Float,
        val ambientShadow: Int,
        val spotShadow: Int,
        val elevationPx: Float
    )

    private val density: Float
        get() = densityOverride?.takeIf { it > 0f }
            ?: android.content.res.Resources.getSystem().displayMetrics.density

    private var densityOverride: Float? = null

    /** 可选：由宿主 Activity 注入实际屏幕密度（多窗口场景更准） */
    @JvmStatic
    fun setDensity(value: Float) {
        densityOverride = value.takeIf { it > 0f }
    }

    /**
     * 强调色注入口。
     *
     * 原先按钮、徽章、进度条的强调色全部写死成 `0xFF007AFF` 之类的固定值，
     * 用户在系统里换主题色（Material You 动态取色）后界面毫无变化。
     * 现在统一由宿主把 `themeColors.primary`（读自 `?attr/colorPrimary`，
     * 动态取色开启时自动跟随系统）灌进来，所有强调元素共用同一来源。
     */
    private var accentOverride: Int? = null

    @JvmStatic
    fun setAccent(color: Int) {
        accentOverride = color
    }

    /** 强调色，未注入时退回 iOS 蓝 */
    private val accent: Int get() = accentOverride ?: 0xFF007AFF.toInt()

    /** 按比例压暗，用于按压态 / 渐变末端，免去再写死一组色值 */
    private fun shade(color: Int, amount: Float): Int {
        val k = 1f - amount.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(color) * k).toInt().coerceIn(0, 255),
            (Color.green(color) * k).toInt().coerceIn(0, 255),
            (Color.blue(color) * k).toInt().coerceIn(0, 255)
        )
    }

    /** 向白色混合提亮，用于描边 / 高光，同样由主色派生 */
    private fun lighten(color: Int, amount: Float): Int {
        val k = amount.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(color) + (255 - Color.red(color)) * k).toInt().coerceIn(0, 255),
            (Color.green(color) + (255 - Color.green(color)) * k).toInt().coerceIn(0, 255),
            (Color.blue(color) + (255 - Color.blue(color)) * k).toInt().coerceIn(0, 255)
        )
    }

    /**
     * 功能分类色：色相固定（保留"电池=绿 / Root=紫"这类辨识度），
     * 但饱和度与明度取自当前强调色 —— 原先整组色值写死在代码里，系统主题色
     * 怎么换都不动。现在换主题色时，图标组会整体跟着变浓或变淡。
     *
     * @param hue 0f~1f 的分类色相
     */
    private fun categoryHue(hue: Float): Int {
        val r = Color.red(accent) / 255f
        val g = Color.green(accent) / 255f
        val b = Color.blue(accent) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val s = when {
            max == min -> 0.55f
            l > 0.5f -> (max - min) / (2f - max - min)
            else -> (max - min) / (max + min)
        }
        return hsl(hue, (s * 1.05f).coerceIn(0.42f, 0.82f), l.coerceIn(0.44f, 0.60f))
    }

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 1f) + 1f) % 1f
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((hh * 6f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((hh * 6f).toInt() % 6) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return Color.rgb(
            ((r + m) * 255f).toInt().coerceIn(0, 255),
            ((g + m) * 255f).toInt().coerceIn(0, 255),
            ((b + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    private fun cardSpec(isDark: Boolean, w: Float, h: Float): GlassSpec {
        // 折射高度随卡片短边收敛，避免矮卡片被折射撕裂
        val shortSide = minOf(w, h).coerceAtLeast(1f)
        // 折射带高度对齐原库基准：LiquidButton 用的是 lens(12f.dp, 24f.dp)，
        // 即折射带 12dp、位移 24dp。我先前为了"加强效果"加到 26dp，结果折射区
        // 过宽、边缘发散成一团糊，反而不如原库 12dp 锐利。
        // 位移 24dp 与原库第二个参数一致，保持不变。
        val refractDp = 12f
        val refractHeight = minOf(refractDp * density, shortSide * 0.35f)
        return if (isDark) {
            GlassSpec(
                // 深色：原库 containerColor = 0xFF121212 @ 40%
                glassTint = 0xFF121212.toInt(),
                // 对齐 Compose 通道 glassCardSpec(dark) 的 tintAlpha=0.34：
                // View 通道 tintStrength ≈ Compose tintAlpha × 0.4（不同缩放系）
                tintStrength = 0.14f,
                blurMix = 0.62f,
                // 夜间：过饱和会把暗部仅有的那点颜色推成刺眼的荧光色，
                // 1.52 → 1.30，色彩仍在但不再跳。
                vibrancy = 1.30f,
                refractionHeightPx = refractHeight,
                refractionAmountPx = 12f * density,
                depthEffect = false,
                // 对齐原库默认（lens 的 chromaticAberration 默认 false），
                // 深色同样只留极小的 0.10。
                chromaticAberration = 0.10f,
                highlightColor = 0xFFFFFFFF.toInt(),
                highlightAngle = (-Math.PI / 4.0).toFloat(),
                highlightWidthPx = 2.5f * density,
                highlightFalloff = 1.6f,
                // 全白高光在暗背景上是一道刺眼的亮边，夜间降到 0.40
                highlightStrength = 0.40f,
                shadowColor = 0xFF000000.toInt(),
                shadowAngle = (3.0 * Math.PI / 4.0).toFloat(),
                shadowWidthPx = 4f * density,
                shadowFalloff = 1.4f,
                shadowStrength = 0.30f,
                // 描边同理：纯白描边在夜间过于醒目，压到 0.22
                strokeColor = 0xFFFFFFFF.toInt(),
                strokeWidthPx = 1.2f * density,
                strokeStrength = 0.22f,
                ambientShadow = 0x33000000.toInt(),
                spotShadow = 0x4D000000.toInt(),
                elevationPx = 6f * density
            )
        } else {
            GlassSpec(
                // 亮色：原库 containerColor = 0xFFFAFAFA @ 40%。
                //
                // 早期版本取 tintStrength=0.52 的近纯白，实测会把背景光晕的
                // 色彩整片洗掉（卡片环彩度 14 vs 卡外背景 32），玻璃退化成
                // 一块白板 —— 亮色"看不出玻璃感"的根因就在这里。
                // 现在压到 0.34，把通透度还给折射层，对比度改由 vibrancy
                // 提升色彩纯度、以及内阴影撑厚度来补，不再靠加白。
                glassTint = 0xFFF7FAFF.toInt(),
                // 原库 LiquidButton 默认不加 tint，需要实一点才给 surfaceColor
                // = White(0.3f)。我先前 0.32 是常驻 tint，等于始终隔着一层白纱，
                // 通透感全被吃掉。压到 0.16 后背景色彩才真正透得过来。
                tintStrength = 0.16f,
                blurMix = 0.50f,
                vibrancy = 1.45f,
                refractionHeightPx = refractHeight,
                refractionAmountPx = 24f * density,
                depthEffect = false,
                // 原库 lens() 的 chromaticAberration 默认是 false —— 默认根本不
                // 开色散。我之前为了"加强"开到 0.80，边缘泛彩显脏，正是差距来源
                // 之一。按原库默认关掉，只保留极小的 0.10 让边缘不至于死板。
                chromaticAberration = 0.10f,
                // 高光 / 内阴影的"带宽"才是亮色玻璃感强弱的关键。
                // 原先 highlightWidth 只有 3dp、shadow 6dp，在 1440px 宽的
                // 屏幕上近乎一条细线，肉眼根本注意不到 —— 这才是"玻璃感不强"
                // 的主因，而不是背景不够花。这里把高光带放到 9dp、阴影带到
                // 12dp，并把 falloff 从 1.4/1.3 降到 1.0，让过渡更宽更柔和。
                highlightColor = 0xFFFFFFFF.toInt(),
                highlightAngle = (-Math.PI / 4.0).toFloat(),
                highlightWidthPx = 9f * density,
                highlightFalloff = 1.0f,
                highlightStrength = 0.95f,
                // 亮色纯白高光本身不显眼，厚度主要靠这圈蓝灰内阴影撑
                shadowColor = 0xFF4E5C77.toInt(),
                shadowAngle = (3.0 * Math.PI / 4.0).toFloat(),
                shadowWidthPx = 12f * density,
                shadowFalloff = 1.0f,
                shadowStrength = 0.65f,
                strokeColor = 0xFFFFFFFF.toInt(),
                strokeWidthPx = 1.5f * density,
                strokeStrength = 0.85f,
                ambientShadow = 0x1A204070.toInt(),
                spotShadow = 0x24204070.toInt(),
                elevationPx = 2f * density
            )
        }
    }

    private fun dockSpec(isDark: Boolean, h: Float): GlassSpec {
        val base = cardSpec(isDark, 1000f, h)
        val refractHeight = minOf(20f * density, h * 0.42f)
        return base.copy(
            // dock 浮在滚动内容之上，要比卡片更实才压得住，但仍需留出通透度：
            // 亮色此前 0.58 会连同顶部导航栏一起洗成白块。
            tintStrength = if (isDark) 0.48f else 0.44f,
            refractionHeightPx = refractHeight,
            refractionAmountPx = 16f * density,
            depthEffect = true,
            highlightWidthPx = 3f * density,
            highlightFalloff = 1.5f,
            highlightStrength = if (isDark) 0.60f else 0.78f,
            shadowWidthPx = 5f * density,
            shadowStrength = if (isDark) 0.32f else 0.40f,
            strokeWidthPx = 1.2f * density,
            strokeStrength = if (isDark) 0.34f else 0.62f,
            elevationPx = 6f * density
        )
    }

    // ==================================================================
    // 四、公开 API
    // ==================================================================

    /**
     * 页面背景统一 Miuix 纯净底色（亮色 0xFFF5F6F9，深色 0xFF0D0F12，纯黑 0x000000）。
     * 与 Miuix Compose 页面完全对齐，彻底消除不同界面之间的色差与割裂。
     */
    @JvmStatic
    fun createPageBackgroundDrawable(isDark: Boolean, isBlack: Boolean): Drawable {
        if (isBlack) {
            return ColorDrawable(Color.BLACK)
        }
        val color = if (isDark) 0xFF0D0F12.toInt() else 0xFFF5F6F9.toInt()
        return ColorDrawable(color)
    }

    /**
     * 卡片 Miuix 底板（无描边纯色大圆角卡片，对齐 Miuix Card）。
     */
    @JvmStatic
    fun createCardDrawable(
        cornerRadiusPx: Float,
        isDark: Boolean,
        host: View? = null,
        hasAccentTop: Boolean = false
    ): Drawable = GradientDrawable().apply {
        cornerRadius = cornerRadiusPx
        val cardBgColor = if (isDark) 0xFF181A20.toInt() else 0xFFFFFFFF.toInt()
        setColor(cardBgColor)
    }

    /**
     * 底部 dock 玻璃底板。折射更强、玻璃更实，浮在滚动内容之上。
     */
    @JvmStatic
    fun createDockDrawable(
        cornerRadiusPx: Float,
        isDark: Boolean,
        host: View? = null
    ): Drawable = LiquidGlassDrawable(
        cornerRadiusPx = cornerRadiusPx,
        isDark = isDark,
        host = host,
        hasAccentTop = false,
        isDock = true
    )

    /**
     * 应用玻璃材质（MaterialCardView 版）：统一 20dp 圆角、0dp 海拔、无阴影、对齐 Compose Miuix Card。
     */
    @JvmStatic
    fun applyGlassMaterial(card: MaterialCardView, radiusPx: Float, isDark: Boolean) {
        card.radius = radiusPx
        card.cardElevation = 0f
        card.maxCardElevation = 0f
        card.useCompatPadding = false
        card.preventCornerOverlap = false
        card.strokeWidth = 0
        card.setCardBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * 应用玻璃材质（普通 View 版，用于 dock）。
     *
     * 除了海拔与阴影颜色，还会设置圆角 Outline —— dock 是 LinearLayout，
     * 仅靠背景 Drawable 裁剪的话，elevation 阴影仍是方角，会露馅。
     */
    @JvmStatic
    fun applyDockMaterial(view: View, radiusPx: Float, isDark: Boolean) {
        view.elevation = 6f * density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.outlineAmbientShadowColor =
                if (isDark) 0x33000000.toInt() else 0x1A204070.toInt()
            view.outlineSpotShadowColor =
                if (isDark) 0x4D000000.toInt() else 0x24204070.toInt()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.clipToOutline = true
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
        }
    }

    /** Miuix 风格开关：开启态采用 Miuix 标志性蓝，关闭态采用 Miuix 柔和浅灰/深灰轨道 */
    @JvmStatic
    fun styleIosSwitch(switch: View, isDark: Boolean) {
        if (switch !is MaterialSwitch) return
        val checkedTrackColor = if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
        val uncheckedTrackColor = if (isDark) 0xFF2E323D.toInt() else 0xFFE2E6EE.toInt()

        switch.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(checkedTrackColor, uncheckedTrackColor)
        )
        switch.thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        switch.trackDecorationTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    }

    /** 图标 Squircle，亮暗统一加深渐变避免出现「飘」的白块 */
    @JvmStatic
    fun createIosIconDrawable(title: String, cornerRadiusPx: Float): Drawable {
        val (c1, c2) = categoryColorsFor(title)
        return IosIconSquircleDrawable(c1, c2, cornerRadiusPx)
    }

    /**
     * 由标题派生分类色（c1 主色 / c2 渐变末色）。色相保留分类语义
     * （绿=电池 / 紫=Root / 红=恢复…），饱和度与明度由 categoryHue()
     * 从当前强调色取。Compose 入口卡片与 View 图标共用同一映射。
     */
    @JvmStatic
    fun categoryColorsFor(title: String): Pair<Int, Int> {
        return when {
            title.contains("电池") || title.contains("充电") || title.contains("省电") || title.contains("直供") || title.contains("待机") -> {
                categoryHue(0.40f) to shade(categoryHue(0.40f), 0.26f)
            }
            title.contains("刷新") || title.contains("屏幕") || title.contains("自适应") || title.contains("帧率") -> {
                categoryHue(0.59f) to shade(categoryHue(0.59f), 0.26f)
            }
            title.contains("无线") || title.contains("调试") || title.contains("系统") -> {
                categoryHue(0.52f) to shade(categoryHue(0.52f), 0.26f)
            }
            title.contains("电脑") || title.contains("ADB") || title.contains("加速") || title.contains("性能") -> {
                categoryHue(0.09f) to shade(categoryHue(0.09f), 0.26f)
            }
            title.contains("Root") || title.contains("终端") || title.contains("命令") -> {
                categoryHue(0.75f) to shade(categoryHue(0.75f), 0.26f)
            }
            title.contains("应用") || title.contains("授权") -> {
                categoryHue(0.59f) to shade(categoryHue(0.59f), 0.26f)
            }
            title.contains("恢复") || title.contains("重置") || title.contains("残留") -> {
                categoryHue(0.01f) to shade(categoryHue(0.01f), 0.26f)
            }
            else -> {
                accent to shade(accent, 0.26f)
            }
        }
    }

    /**
     * 胶囊按钮。按钮位于卡片内部，背后是已经很淡的玻璃，因此走轻量玻璃：
     * 半透明填充 + 顶部高光 + 底部内阴影，无需再采样背景快照。
     */
    @JvmStatic
    fun createButtonDrawable(
        cornerRadiusPx: Float,
        isDark: Boolean,
        isPrimary: Boolean = false,
        isDanger: Boolean = false,
        isEmphasis: Boolean = false
    ): Drawable {
        val normal =
            LiquidGlassButtonDrawable(cornerRadiusPx, isDark, isPrimary, isDanger, false, isEmphasis)
        val pressed =
            LiquidGlassButtonDrawable(cornerRadiusPx, isDark, isPrimary, isDanger, true, isEmphasis)
        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
        val rippleColor = when {
            isPrimary -> Color.argb(110, 255, 255, 255)
            isDark -> Color.argb(80, 255, 255, 255)
            else -> Color.argb(54, 20, 40, 80)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), stateList, null)
    }

    /** 状态徽章，亮暗双模式重新配色 */
    @JvmStatic
    fun createBadgeDrawable(statusText: String, isDark: Boolean): Drawable {
        return LiquidGlassBadgeDrawable(badgeKind(statusText), isDark)
    }

    /**
     * 徽章语义判定。原先这段匹配在 createBadgeDrawable 与
     * MaxHzActivity.updateStatusText 里各写了一遍，两边一旦不同步就会出现
     * 「底色按成功画、文字按信息画」的错配。收口到这里，两处共用。
     */
    @JvmStatic
    fun badgeKind(statusText: String): BadgeKind = when {
        statusText.contains("已启用") || statusText.contains("正常") || statusText.contains("运行中") ||
            statusText.contains("满血") || statusText.contains("已锁定") || statusText.contains("极速") -> BadgeKind.SUCCESS
        statusText.contains("异常") || statusText.contains("关闭") || statusText.contains("失败") ||
            statusText.contains("需") -> BadgeKind.WARNING
        else -> BadgeKind.INFO
    }

    /** 徽章填充色（唯一来源：Drawable 与文字色计算都从这里取） */
    @JvmStatic
    fun badgeFill(kind: BadgeKind, isDark: Boolean): Int = when (kind) {
        BadgeKind.SUCCESS -> if (isDark) 0xFF34C759.toInt() else 0xFF1E9E48.toInt()
        BadgeKind.WARNING -> if (isDark) 0xFFFF9500.toInt() else 0xFFD97A05.toInt()
        // 由主题强调色派生，不再写死 0xFF007AFF / 0xFF93C5FD
        BadgeKind.INFO -> if (isDark) accent else shade(accent, 0.12f)
    }

    /**
     * 徽章文字色。
     *
     * 原先文字色是「跟填充同色相的另一个值」，浅色警告填充 #D97A05 配文字
     * #D97706 —— 实测只有 1.03:1，字完全糊进底里。现在按 WCAG 相对亮度收敛：
     * 填充保持不动以留住颜色语义，只动文字。强调色是动态取色，只能运行时算。
     */
    @JvmStatic
    fun badgeText(kind: BadgeKind, isDark: Boolean, preferred: Int): Int {
        return FillContrast.readableOn(badgeFill(kind, isDark), preferred)
    }

    enum class BadgeKind {
        SUCCESS, WARNING, INFO
    }

    // ==================================================================
    // 五、Drawable 实现
    // ==================================================================

    /** 一束径向光晕：cx/cy 为相对比例，radius 为相对短边比例 */
    private class Aurora(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val color: Int
    )


    /**
     * 极光壁纸背景。深色按夜间标准压暗，亮色用低强度冷色光晕。
     * 不再叠加任何花纹图案（用户要求界面保持干净）。
     */
    private class AuroraDrawable(
        private val baseColor: Int,
        private val auras: List<Aurora>,
        private val verticalStops: IntArray? = null,
        private val verticalPositions: FloatArray? = null
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val auraPaints = auras.map { Paint(Paint.ANTI_ALIAS_FLAG) }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            val minSide = minOf(w, h)

            if (verticalStops != null && verticalStops.size >= 2) {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, h, verticalStops, verticalPositions, Shader.TileMode.CLAMP
                )
            } else {
                paint.shader = null
                paint.color = baseColor
            }

            auras.forEachIndexed { index, aura ->
                auraPaints[index].shader = RadialGradient(
                    w * aura.cx,
                    h * aura.cy,
                    minSide * aura.radius,
                    aura.color,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRect(bounds, paint)
            for (p in auraPaints) {
                canvas.drawRect(bounds, p)
            }
        }


        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            auraPaints.forEach { it.alpha = alpha }
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            auraPaints.forEach { it.colorFilter = colorFilter }
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /**
     * 液态玻璃底板。
     *
     * 两条绘制路径：
     *  - Android 13+ 且快照就绪：AGSL 着色器采样背景快照，完整玻璃效果；
     *  - 否则：纯 Canvas 近似（渐变 + 高光描边 + 内阴影），观感接近，
     *    且保证文字可读，不会出现「透明卡片看不清字」。
     */
    class LiquidGlassDrawable(
        private val cornerRadiusPx: Float,
        private val isDark: Boolean,
        host: View?,
        private val hasAccentTop: Boolean = false,
        private val isDock: Boolean = false
    ) : Drawable() {

        /** shader 路径专用，与回退路径的 paint 分开，避免互相污染 */
        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fallbackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        private val rectF = RectF()
        private val strokeRectF = RectF()
        private val location = IntArray(2)

        private val hostRef = host?.let { WeakReference(it) }

        private var shader: android.graphics.RuntimeShader? = null
        private var shaderUsable = false
        private var nearShader: BitmapShader? = null
        private var farShader: BitmapShader? = null
        private var boundNear: Bitmap? = null
        private var boundFar: Bitmap? = null

        private var lastOriginX = Int.MIN_VALUE
        private var lastOriginY = Int.MIN_VALUE
        private var currentRadius = cornerRadiusPx

        init {
            // 登记到共享快照：快照生成 / 主题切换后会被主动通知重绘。
            // 不登记的话，首帧早于 captureBackdrop 的卡片会永远停在回退路径
            // 上（实测首页 8 张卡片全部如此），玻璃等于没生效。
            Backdrop.register(this)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            rectF.set(bounds)
            strokeRectF.set(
                bounds.left + 1f,
                bounds.top + 1f,
                bounds.right - 1f,
                bounds.bottom - 1f
            )

            val radius = cornerRadiusPx.coerceAtMost(minOf(w, h) / 2f)
            currentRadius = radius
            val spec = if (isDock) dockSpec(isDark, h) else cardSpec(isDark, w, h)

            buildShader(w, h, radius, spec)
            buildFallback(w, h, spec)
            lastOriginX = Int.MIN_VALUE
            lastOriginY = Int.MIN_VALUE
        }

        private fun buildShader(w: Float, h: Float, radius: Float, spec: GlassSpec) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                shader = null
                shaderUsable = false
                return
            }
            try {
                val runtime = shader ?: android.graphics.RuntimeShader(GLASS_SHADER).also {
                    shader = it
                }
                runtime.setFloatUniform("size", w, h)
                runtime.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                runtime.setFloatUniform("backdropScale", BACKDROP_SCALE)
                runtime.setFloatUniform("refractionHeight", spec.refractionHeightPx)
                // 负值折射：与原库 lens() 中
                // setFloatUniform("refractionAmount", -refractionAmount) 一致。
                // 正值是「放大镜」式外扩，负值才是玻璃边缘把内侧内容向外推、
                // 形成透镜环的那种折射感。
                runtime.setFloatUniform("refractionAmount", -spec.refractionAmountPx)
                runtime.setFloatUniform("depthEffect", if (spec.depthEffect) 1f else 0f)
                runtime.setFloatUniform("chromaticAberration", spec.chromaticAberration)
                runtime.setFloatUniform("blurMix", spec.blurMix)
                runtime.setFloatUniform("vibrancy", spec.vibrancy)

                writeColor(runtime, "glassTint", spec.glassTint)
                runtime.setFloatUniform("tintStrength", spec.tintStrength)

                writeColor(runtime, "highlightColor", spec.highlightColor)
                runtime.setFloatUniform("highlightAngle", spec.highlightAngle)
                runtime.setFloatUniform("highlightWidth", spec.highlightWidthPx)
                runtime.setFloatUniform("highlightFalloff", spec.highlightFalloff)
                runtime.setFloatUniform("highlightStrength", spec.highlightStrength)

                writeColor(runtime, "shadowColor", spec.shadowColor)
                runtime.setFloatUniform("shadowAngle", spec.shadowAngle)
                runtime.setFloatUniform("shadowWidth", spec.shadowWidthPx)
                runtime.setFloatUniform("shadowFalloff", spec.shadowFalloff)
                runtime.setFloatUniform("shadowStrength", spec.shadowStrength)

                writeColor(runtime, "strokeColor", spec.strokeColor)
                runtime.setFloatUniform("strokeWidth", spec.strokeWidthPx)
                runtime.setFloatUniform("strokeStrength", spec.strokeStrength)

                runtime.setFloatUniform("overallAlpha", 1f)
                runtime.setFloatUniform("origin", 0f, 0f)
                shaderUsable = true
            } catch (_: Throwable) {
                shader = null
                shaderUsable = false
            }
            hasAccentTop
        }

        private fun writeColor(runtime: android.graphics.RuntimeShader, name: String, argb: Int) {
            runtime.setFloatUniform(
                name,
                Color.red(argb) / 255f,
                Color.green(argb) / 255f,
                Color.blue(argb) / 255f,
                Color.alpha(argb) / 255f
            )
        }

        /** 回退路径：渐变玻璃 + 顶部高光 / 底部内阴影描边 */
        private fun buildFallback(w: Float, h: Float, spec: GlassSpec) {
            if (isDark) {
                fallbackPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(
                        withAlpha(spec.glassTint, 0.40f),
                        withAlpha(spec.glassTint, 0.32f),
                        withAlpha(spec.glassTint, 0.26f)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                fallbackPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(
                        0xF2FFFFFF.toInt(),
                        0xEAFFFFFF.toInt(),
                        0xE1F4F6FB.toInt()
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            fallbackStrokePaint.strokeWidth = maxOf(1f, spec.strokeWidthPx)
            fallbackStrokePaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    withAlpha(spec.highlightColor, spec.highlightStrength),
                    withAlpha(spec.highlightColor, spec.highlightStrength * 0.3f),
                    withAlpha(spec.shadowColor, spec.shadowStrength)
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        private fun withAlpha(argb: Int, alpha: Float): Int = Color.argb(
            (alpha * 255f).toInt().coerceIn(0, 255),
            Color.red(argb),
            Color.green(argb),
            Color.blue(argb)
        )

        override fun draw(canvas: Canvas) {
            if (rectF.isEmpty) return
            if (GLASS_FORCE_DIRECT) {
                drawFallback(canvas)
                return
            }

            val runtime = shader
            val near = Backdrop.near()
            val far = Backdrop.far()

            if (shaderUsable && runtime != null && near != null && far != null) {
                if (bindBackdrop(runtime, near, far)) {
                    updateOrigin(runtime)
                    glassPaint.shader = runtime
                    canvas.drawRoundRect(rectF, currentRadius, currentRadius, glassPaint)
                    return
                }
            }
            drawFallback(canvas)
        }

        /** 绑定背景快照；快照变化时重建 BitmapShader，其余情况复用 */
        private fun bindBackdrop(
            runtime: android.graphics.RuntimeShader,
            near: Bitmap,
            far: Bitmap
        ): Boolean {
            return try {
                if (boundNear !== near) {
                    nearShader = BitmapShader(near, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    boundNear = near
                }
                if (boundFar !== far) {
                    farShader = BitmapShader(far, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    boundFar = far
                }
                val ns = nearShader ?: return false
                val fs = farShader ?: return false
                runtime.setInputShader("backdropNear", ns)
                runtime.setInputShader("backdropFar", fs)
                true
            } catch (_: Throwable) {
                shaderUsable = false
                false
            }
        }

        /**
         * 把卡片在背景快照中的位置写进着色器。
         *
         * 每次 draw 时实时取位置：滚动会触发重绘，位置随之更新，玻璃里透出
         * 的背景始终与真实背景对齐。getLocationInWindow 在已 attach 的 View
         * 上开销很小，且只在位置变化时写 uniform。
         */
        private fun updateOrigin(runtime: android.graphics.RuntimeShader) {
            val view = hostRef?.get()
            val ox: Int
            val oy: Int
            if (view != null && view.isAttachedToWindow) {
                view.getLocationInWindow(location)
                ox = location[0] - Backdrop.originX + bounds.left
                oy = location[1] - Backdrop.originY + bounds.top
            } else {
                ox = 0
                oy = 0
            }
            if (ox != lastOriginX || oy != lastOriginY) {
                lastOriginX = ox
                lastOriginY = oy
                runtime.setFloatUniform("origin", ox.toFloat(), oy.toFloat())
            }
        }

        private fun drawFallback(canvas: Canvas) {
            canvas.drawRoundRect(rectF, currentRadius, currentRadius, fallbackPaint)
            canvas.drawRoundRect(strokeRectF, currentRadius, currentRadius, fallbackStrokePaint)
        }

        override fun setAlpha(alpha: Int) {
            glassPaint.alpha = alpha
            fallbackPaint.alpha = alpha
            fallbackStrokePaint.alpha = alpha
            val runtime = shader
            if (shaderUsable && runtime != null) {
                runCatching { runtime.setFloatUniform("overallAlpha", alpha / 255f) }
            }
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            glassPaint.colorFilter = colorFilter
            fallbackPaint.colorFilter = colorFilter
            fallbackStrokePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** iOS 图标 Squircle，亮暗统一 */
    class IosIconSquircleDrawable(
        private val color1: Int,
        private val color2: Int,
        private val cornerRadiusPx: Float
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
        }
        private val rectF = RectF()
        private val strokeRectF = RectF()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0 || h <= 0) return
            rectF.set(bounds)
            strokeRectF.set(bounds.left + 1f, bounds.top + 1f, bounds.right - 1f, bounds.bottom - 1f)
            fillPaint.shader = LinearGradient(0f, 0f, 0f, h, color1, color2, Shader.TileMode.CLAMP)
            strokePaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xB3FFFFFF.toInt(), 0x14000000.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            if (rectF.isEmpty) return
            val r = cornerRadiusPx.coerceAtMost(minOf(rectF.width(), rectF.height()) / 2f)
            canvas.drawRoundRect(rectF, r, r, fillPaint)
            canvas.drawRoundRect(strokeRectF, r, r, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Miuix 按钮底板：无描边、圆润色块，与 Miuix TextButton 保持同一套语言。
     */
    class LiquidGlassButtonDrawable(
        private val cornerRadiusPx: Float,
        private val isDark: Boolean,
        private val isPrimary: Boolean,
        private val isDanger: Boolean,
        private val isPressed: Boolean,
        private val isEmphasis: Boolean = false
    ) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rectF = RectF()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0 || h <= 0) return

            rectF.set(bounds)
            fillPaint.shader = null
            fillPaint.color = when {
                isPrimary -> {
                    val base = if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
                    if (isPressed) shade(base, 0.16f) else base
                }
                isDanger -> {
                    if (isDark) {
                        if (isPressed) 0x44F87171 else 0x2EF87171
                    } else {
                        if (isPressed) 0xFFFAD8D2.toInt() else 0xFFFFECE8.toInt()
                    }
                }
                !isDark -> {
                    when {
                        isPressed -> 0xFFE2E6EE.toInt()
                        isEmphasis -> 0xFFE8EBF2.toInt()
                        else -> 0xFFF0F2F6.toInt()
                    }
                }
                else -> {
                    when {
                        isPressed -> 0xFF2E323D.toInt()
                        isEmphasis -> 0xFF272B35.toInt()
                        else -> 0xFF21242C.toInt()
                    }
                }
            }
        }

        override fun draw(canvas: Canvas) {
            if (rectF.isEmpty) return
            val r = cornerRadiusPx.coerceAtMost(minOf(rectF.width(), rectF.height()) / 2f)
            canvas.drawRoundRect(rectF, r, r, fillPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** 状态徽章，亮色改用加深的实心底色以维持对比度 */
    class LiquidGlassBadgeDrawable(
        private val kind: BadgeKind,
        private val isDark: Boolean
    ) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        private val rectF = RectF()
        private val strokeRectF = RectF()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0 || h <= 0) return

            rectF.set(bounds)
            strokeRectF.set(
                bounds.left + 0.75f,
                bounds.top + 0.75f,
                bounds.right - 0.75f,
                bounds.bottom - 0.75f
            )

            fillPaint.color = badgeFill(kind, isDark)
            strokePaint.color = when (kind) {
                BadgeKind.SUCCESS -> if (isDark) 0xFF86EFAC.toInt() else 0xFFB7F0C8.toInt()
                BadgeKind.WARNING -> if (isDark) 0xFFFDE68A.toInt() else 0xFFFBD9A0.toInt()
                // 由主题强调色派生，不再写死 0xFF007AFF / 0xFF93C5FD
                BadgeKind.INFO -> if (isDark) lighten(accent, 0.55f) else lighten(accent, 0.62f)
            }
        }

        override fun draw(canvas: Canvas) {
            if (rectF.isEmpty) return
            val radius = rectF.height() * 0.5f
            canvas.drawRoundRect(rectF, radius, radius, fillPaint)
            canvas.drawRoundRect(strokeRectF, radius, radius, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
