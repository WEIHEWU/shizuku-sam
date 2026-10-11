package moe.shizuku.manager.maxhz

import moe.shizuku.manager.ShizukuSettings
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop

/**
 * Compose 玻璃表面：把 kyant `drawBackdrop` 缺掉的镜面高光 / 内阴影 / 投影补齐。
 *
 * ## 为什么要这层
 *
 * `glassEffects()` 只有 `vibrancy + blur + lens`，而 lens 的 AGSL 是
 *
 * ```glsl
 * if (-sd >= refractionHeight) return content.eval(coord);   // 内部原样输出
 * return content.eval(coord + d * grad);                     // 仅位移采样
 * ```
 *
 * 没有任何光照项 —— 卡片边缘那道亮线只是「背景梯度 × 位移量」的副产物，
 * 背景一平滑（极光渐变 / 纯色）就消失，也就是「玻璃反射变没了」的根因。
 *
 * 而 Apple Liquid Glass 的高光是「表面法线 · 光照方向」算出来的镜面项，
 * 与背景无关。kyant 库其实自带了这个实现（`Highlight` / `InnerShadow` /
 * `Shadow`），`drawBackdrop` 也有对应入参，之前只是没有传。
 *
 * ## 参数是怎么定的
 *
 * 实测 macOS Tahoe 控制中心穿过玻璃边缘的跨行平均亮度剖面：
 * 峰值 +96（强边）/ +44（典型边），且向内平滑衰减 **14~17 px**。
 * 改造前本 App 是峰值 +27，**2~4 px 内归零** —— 1px 白描边 vs 有厚度的光带。
 *
 * 因此这里：
 * - `highlightWidth` 只做极窄的亮核（≈ 1.2dp），
 * - `highlightBlur` 承担「向内衰减」，取 5dp ≈ 16px，对齐 Apple 的衰减跨度，
 * - `highlightAlpha` 反推自目标峰值增量（Plus 混合，实际增量 ≈ 255 × alpha × intensity）。
 *
 * 设备为 1440px 宽 / 约 3.11 密度，1dp ≈ 3.11px。
 */
data class GlassSurfaceSpec(
    /** 镜面高光颜色。Apple 用纯白，Plus 混合后相当于叠加光源 */
    val highlightColor: Color = Color.White,
    /** 亮核厚度（dp）。描边式细线，不要调大，否则变塑料描边 */
    val highlightWidth: Dp = 9.dp,
    /** 高光向内衰减半径（dp）。这是「玻璃厚度感」的主旋钮 */

    /** 高光强度。目标峰值增量 ≈ 255 × alpha × intensity（直边 intensity≈1） */
    val highlightAlpha: Float = 0.55f,
    /**
     * 光照方向（度）。
     *
     * 库内 shader 为 `intensity = pow(abs(dot(grad, normal)), falloff)` —— 注意是
     * **abs**，所以 90°（竖直）会让上下两条边同时受光、左右两条边为零，
     * 四个角各 0.707。配合下方内阴影压暗下边，净效果就是「上亮下暗」，
     * 与 Apple 一致。若改成 45° 会变成对角两角亮、另两角全黑，观感很怪。
     */
    val highlightAngle: Float = 45f,
    /** 衰减指数。1 = 线性；越大越向角落集中 */
    val highlightFalloff: Float = 1f,
    /** 内阴影颜色。亮色用蓝灰而非纯黑，避免发脏 */
    val innerShadowColor: Color = Color(0xFF4E5C77),
    /** 内阴影模糊半径（dp），撑出玻璃厚度 */
    val innerShadowRadius: Dp = 6.dp,
    /**
     * 内阴影强度。
     *
     * offset 固定为 (0,0)：内阴影的偏移方向语义无法在无设备时确认，
     * 取对称值最稳 —— 均匀压暗内侧一圈，方向错了也不明显，
     * 「上亮下暗」的方向感交给高光的 abs 项去体现。
     */
    val innerShadowAlpha: Float = 0.55f,
    /**
     * 表面底色。
     *
     * 这是「文字可读性」与「玻璃通透感」之间唯一的调节旋钮，也是 dock 是否
     * 立得起来的关键 —— Compose 通道早年在移植 View 通道时漏掉了这一层，
     * 导致 dock 内部亮度和它背后的页面一模一样（实测只差 +1.6），完全没有
     * 图底分离。
     *
     * 注意：底色加在**卡片层**而不是壁纸层。加在壁纸层会把壁纸对比度抹平，
     * `lens()` 就没素材可折射，玻璃反射会跟着消失（上一轮实测踩过）。
     */
    val surfaceTint: Color = Color(0xFFF7FAFF),
    /** 底色强度。0 = 完全通透，1 = 完全实底 */
    val tintAlpha: Float = 0.12f,
    /** 外投影高度 */
    val elevation: Dp = 3.dp,
    val ambientColor: Color = Color(0x1A204070),
    val spotColor: Color = Color(0x24204070),
    /** iOS 26 液态玻璃 .tint(Color) 色调注入：为特定卡片/按钮注入主题或强调色 */
    val tintColor: Color? = null,
    /** 按压交互时的镜面高光激发增量（默认 +30%） */
    val specularBoostOnPress: Float = 0.30f,
    /** 折射高度 (dp) —— 对应 Backdrop Catalog 的 Refraction height */
    val refractionHeight: Dp = 16.dp,
    /** 折射强度 (dp) —— 对应 Backdrop Catalog 的 Refraction amount */
    val refractionAmount: Dp = 32.dp,
    /** 3D 凸面透镜深度效果 */
    val depthEffect: Boolean = true,
    /** 色散 / 彩虹折射色差 (Chromatic Aberration) —— 对应 Backdrop Catalog 最右侧核心特效 */
    val chromaticAberration: Boolean = true
)

/**
 * 卡片（圆角 26dp）。
 *
 * 所有玻璃面（卡片 / 胶囊按钮 / dock）共用同一套折射参数（18/36dp），
 * 保证「同一种材质」。色散与 3D 凸透镜深度全开，边缘有真实棱镜光谱折射。
 */
fun glassCardSpec(isDark: Boolean, tintColor: Color? = null): GlassSurfaceSpec {
    val hasWallpaper = ShizukuSettings.getWallpaperUri() != null
    val base = if (isDark) {
        GlassSurfaceSpec(
            highlightAlpha = 0.80f,
            innerShadowColor = Color.Black,
            innerShadowAlpha = 0.30f,
            surfaceTint = Color(0xFF161B26),
            // 通透优先：底色只做「极轻的一层」，让背景与折射主导观感。
            // 深色底本身很暗，浅色文字的可读性不受影响，可以压得比亮色更低。
            tintAlpha = if (hasWallpaper) 0.15f else 0.09f,
            // 与亮色一致（原为 5.dp）：elevation 越高，Compose 的阴影 pass 越贵
            elevation = 3.dp,
            ambientColor = Color(0x33000000),
            spotColor = Color(0x4D000000),
            refractionHeight = 18.dp,
            refractionAmount = 36.dp,
            depthEffect = true,
            chromaticAberration = true
        )
    } else {
        GlassSurfaceSpec(
            highlightAlpha = 0.75f,
            innerShadowColor = Color(0xFF4E5C77),
            innerShadowAlpha = 0.25f,
            surfaceTint = Color(0xFFF7FAFF),
            tintAlpha = if (hasWallpaper) 0.17f else 0.10f,
            elevation = 3.dp,
            ambientColor = Color(0x1A204070),
            spotColor = Color(0x24204070),
            refractionHeight = 18.dp,
            refractionAmount = 36.dp,
            depthEffect = true,
            chromaticAberration = true
        )
    }
    return if (tintColor != null) base.copy(tintColor = tintColor) else base
}

/**
 * 胶囊按钮（圆角 16dp）。
 *
 * 材质与卡片完全一致（同一套折射 / 色散 / 底色），仅投影更低 ——
 * 不再单独缩折射，保证全 App 玻璃是「同一种物质」。
 */
fun glassPillSpec(isDark: Boolean, tintColor: Color? = null): GlassSurfaceSpec {
    val base = glassCardSpec(isDark, tintColor)
    return if (tintColor != null) {
        base.copy(
            highlightWidth = 6.dp,
            innerShadowRadius = 4.dp,
            tintAlpha = if (isDark) 0.40f else 0.26f,
            elevation = 2.dp
        )
    } else {
        base.copy(
            highlightWidth = 6.dp,
            innerShadowRadius = 4.dp,
            elevation = 2.dp
        )
    }
}

/**
 * 底部 dock（胶囊，浮在滚动内容之上）。
 *
 * 对齐 iOS 26 液态玻璃参考视频（微信 iOS 底栏）：玻璃是**中性**的 ——
 * 奶白磨砂（亮色）/ 深灰磨砂（暗色），透出的是背景被折射后的颜色，
 * 不再注入青/薄荷绿色调（旧版那种彩色玻璃在参考视频里不存在，
 * 且与主页卡片的材质是两种东西，正是「全 App 玻璃不统一」的来源）。
 *
 * 相比卡片：底色更实（浮在滚动内容之上需要图底分离）、投影更高（悬浮感），
 * 折射参数保持 18/36dp 不变 —— 全 App 仍是同一种玻璃物质。
 */
fun glassDockSpec(isDark: Boolean): GlassSurfaceSpec {
    val base = glassCardSpec(isDark)
    return if (isDark) {
        base.copy(
            highlightWidth = 8.dp,
            highlightAlpha = 0.55f,
            surfaceTint = Color(0xFF171C26),
            tintAlpha = 0.34f,
            elevation = 10.dp,
            ambientColor = Color(0x33000000),
            spotColor = Color(0x4D000000),
            refractionHeight = 18.dp,
            refractionAmount = 36.dp,
            depthEffect = true,
            chromaticAberration = true
        )
    } else {
        base.copy(
            highlightWidth = 8.dp,
            highlightAlpha = 0.58f,
            highlightColor = Color.White,
            surfaceTint = Color.White,
            // 视频里的整条底栏几乎是「隐形玻璃」：靠边缘折射与一圈高光显形，
            // 没有奶白填充。0.42 → 0.32，白高光 0.85 → 0.58。
            tintAlpha = 0.32f,
            elevation = 10.dp,
            ambientColor = Color(0x1F204070),
            spotColor = Color(0x2E204070),
            refractionHeight = 18.dp,
            refractionAmount = 36.dp,
            depthEffect = true,
            chromaticAberration = true
        )
    }
}

/**
 * dock 内的**液态选中液滴**（参考视频里滑动的那个玻璃胶囊）。
 *
 * 与 dock 同一种玻璃，但底色更实、折射更收（10/20dp）—— 液滴只有 66x44dp，
 * 沿用 18/36dp 的大折射会让边缘采样跑到形状外，折射反而糊成一团；
 * 小形状配小折射才保得住「边缘一圈清晰扭曲」的液滴轮廓。
 * 投影降到 2dp：它贴在 dock 表面，不该有悬浮感。
 */
fun glassDockPillSpec(isDark: Boolean): GlassSurfaceSpec {
    val base = glassCardSpec(isDark)
    return if (isDark) {
        base.copy(
            highlightWidth = 5.dp,
            highlightAlpha = 0.30f,
            surfaceTint = Color(0xFF3A4454),
            // 视频里的液滴没有「白底」：它是一滴更透的玻璃，靠边缘折射与高光
            // 显形。底色 0.32 → 0.18，白高光 0.58 → 0.30（高光渐变从顶部
            // 0.95×alpha 的纯白开始，小液滴上就是一片白，必须一起压）。
            tintAlpha = 0.18f,
            innerShadowColor = Color.Black,
            innerShadowAlpha = 0.34f,
            elevation = 2.dp,
            refractionHeight = 14.dp,
            refractionAmount = 28.dp,
            depthEffect = true,
            chromaticAberration = true
        )
    } else {
        base.copy(
            highlightWidth = 5.dp,
            highlightAlpha = 0.34f,
            highlightColor = Color.White,
            // 同上：白底 0.30 → 0.14，玻璃感来自折射与高光描边，不是奶白填充。
            surfaceTint = Color(0xFFEDF1F7),
            tintAlpha = 0.14f,
            innerShadowColor = Color(0xFF4E5C77),
            innerShadowAlpha = 0.22f,
            elevation = 2.dp,
            // 折射从 8/16 提到 14/28 —— 底色变透后，折射是液滴唯一的存在感来源。
            refractionHeight = 14.dp,
            refractionAmount = 28.dp,
            depthEffect = true,
            chromaticAberration = true
        )
    }
}

/**
 * 玻璃表面修饰器：投影 → drawBackdrop（折射背景）→ 自绘高光 / 内阴影。
 *
 * ## 为什么自己画，而不用库自带的 `highlight` 入参
 *
 * 实测：给 `drawBackdrop` 传 `highlight` 后，原本 `lens()` 折射产生的边缘亮边
 * **从 +16.6 掉到 +4** —— 高光需要 padding 腾出绘制空间，连带改变了 backdrop
 * 的捕获区域，把折射亮边抹平了。而且实测高光幅度对 `width` 几乎不敏感
 * （w=1.2dp → +8.5，w=3dp → +10.4），调不动。
 *
 * 所以还是自己画。
 *
 * ## 为什么高光要栅格化缓存（[rememberGraphicsLayer] + `record`）
 *
 * 高光只由「卡片尺寸 + spec」决定，而这两者在滚动时都不变 —— 但原实现是在
 * `onDrawSurface` 里**每帧重画**：建 Path、建渐变 Brush、再叠 3 条宽描边
 * （最宽那条 56px）。首页滚一屏有 8~10 张卡，等于每帧几百次描边 + 几百个
 * Shader 分配。
 *
 * A/B 实测（首页滑动 12 次）：
 *
 * | 变体 | Janky | p90 | p99 | Slow UI thread |
 * |---|---|---|---|---|
 * | 原实现（每帧重画） | 8.50% | 38ms | 69ms | 63 |
 * | 精简为 2 层描边 | 8.70% | 34ms | 53ms | 56 |
 * | 完全关掉高光 | 5.56% | 23ms | 38ms | 42 |
 * | **栅格化缓存（本实现）** | **7.3%** | **27ms** | **46ms** | **57** |
 *
 * 精简层数只救回 4ms，说明瓶颈不是层数而是「每帧重复描边」本身。改成把高光
 * 录进 [GraphicsLayer]（尺寸不变就不重录）后，每帧只剩一次纹理贴图，性能落在
 * 「原实现」与「关掉高光」之间靠后者一侧（p90 38→27ms），同时完整保留玻璃材质
 * —— 与改前截图逐像素对比 mean|Δ| 仅 0.06~0.15，肉眼无差别。
 *
 * ## 层叠顺序
 *
 * 链上修饰符按「先写的在下、后写的在上」绘制，内容最靠上。所以
 * `.drawBackdrop().drawWithCache{}` 落点是：backdrop → 高光 → 内容，
 * 与原 `onDrawSurface` 的 z 序完全一致（底色在文字之下，文字不会被冲淡）。
 *
 * `shape` 是 Compose 的 `androidx.compose.ui.graphics.Shape` —— kyant 的
 * `RoundedRectangle` / `Capsule` 都实现了它，投影、折射、裁剪共用同一形状。
 */
fun Modifier.glassSurface(
    backdrop: LayerBackdrop,
    spec: GlassSurfaceSpec,
    shape: () -> Shape,
    interactive: Boolean = false,
    interactionSource: MutableInteractionSource? = null
): Modifier = composed {
    val highlightLayer = rememberGraphicsLayer()
    val outline = remember(shape) { shape() }

    /**
     * shape lambda 引用稳定化：DrawBackdropElement 判等需要 ShapeProvider
     * （内部包着 shapeBlock）相等才跳过 update。调用方每次重组传新 lambda 时，
     * rememberUpdatedState 保证 lambda 内容最新、而引用包装稳定，配合
     * ShapeProvider.equals（按 shapeBlock 实例判等）命中缓存。
     */
    val stableShape = rememberUpdatedState(shape)
    // 包装 lambda 也必须 remember：捕获的是 State 对象（跨重组稳定），
    // 这样 currentShape 引用在重组间保持不变，ShapeProvider.equals 才能命中。
    val currentShape: () -> Shape = remember { { stableShape.value.invoke() } }

    /**
     * effects lambda 同样需要引用稳定，否则每次重组都让
     * ModifierNodeElement 判等失败 → 整屏卡片重建 RenderEffect。
     * 按「spec + quality」缓存：spec 是 data class（壁纸开关/明暗才变），
     * quality 只在滚动起停时翻转 —— 因此整条链的重建只发生在质量真正切换
     * 的那一刻（每次滑动开始/结束各一次），而不是每次重组。
     */
    /**
     * 只取**持有者**（引用恒定），不取档位值。
     *
     * 档位若作为 CompositionLocal 的 value，它一变就会让所有读取方的组合失效
     * —— 甩动时档位频繁跳变 = 边滑边疯狂重组，实测比不降级还卡。
     * 现在档位变化只发生在绘制期（renderScale 提供者在节点的 observeReads 内
     * 被调用，effects 在 updateEffects 内求值），零重组。
     */
    val qualityState = LocalGlassQualityState.current

    /**
     * 离屏层渲染比例（每帧在绘制期求值，引用恒定以免 Modifier 判等失败）。
     *
     * 只取 1.0 / 0 两个值，故意**不做 0.5 这类中间降采样**：
     * 三星 hwui 上 scale<1 这条路径历史上与 RenderThread 崩溃强相关，且录制/绘制
     * 两侧的 padding 换算有一堆隐式约定，收益（像素数 1/4）不值得这个风险。
     * 需要省的地方改用「降效果强度」和「彻底停机」两种更干净的手段。
     *
     * - Full 1.0：与屏幕同分辨率；
     * - Fast 1.0：分辨率不变，靠 blur 减半 + 关色散省（见 glassEffects）；
     * - Flat 0：整条背景管线停机（连离屏录制都不做），成本归零。
     */
    val renderScale: () -> Float = remember(qualityState) {
        {
            // 档位 → 离屏比例沿用 GlassQuality.renderScaleTier()，两处必须一致，
            // 否则「只有渲染结果真变化才写 State」的去重判断会失效。
            // override（动画期间强制直通）优先于滚动分档。
            qualityState.effective.renderScaleTier()
        }
    }

    val effects: com.kyant.backdrop.BackdropEffectScope.() -> Unit = remember(spec, qualityState) {
        {
            // 与 renderScale 提供者保持一致：读**生效**档位（override 优先），
            // 否则动画期间 renderScale 已是直通、effects 却还按 Full 构建滤镜链，
            // 两边对不上，档位去重判断也会失效。
            val q = qualityState.effective
            // 只有离屏档才构建滤镜链；直通档走 drawBackdropDirect，effects 根本
            // 不参与绘制 —— 按「档位对应的离屏比例」判断，而不是写死 Full，
            // 这样固定直通（GLASS_FORCE_DIRECT）时一次滤镜都不会建。
            if (q.renderScaleTier() > 0f) {
                glassEffects(
                    refractionHeight = spec.refractionHeight.toPx(),
                    refractionAmount = spec.refractionAmount.toPx(),
                    depthEffect = spec.depthEffect,
                    chromaticAberration = spec.chromaticAberration,
                    quality = q
                )
            }
        }
    }

    val baseModifier = this
        .shadow(
            elevation = spec.elevation,
            shape = outline,
            clip = false,
            ambientColor = spec.ambientColor,
            spotColor = spec.spotColor
        )
        .drawBackdrop(
            backdrop = backdrop,
            shape = currentShape,
            effects = effects,
            renderScale = renderScale
        )
        .drawWithCache {
            highlightLayer.record(
                density = this,
                layoutDirection = layoutDirection,
                size = IntSize(
                    size.width.toInt().coerceAtLeast(1),
                    size.height.toInt().coerceAtLeast(1)
                )
            ) {
                drawGlassHighlight(
                    spec = spec,
                    outline = outline.createOutline(size, layoutDirection, this)
                )
            }
            onDrawWithContent {
                drawLayer(highlightLayer)
                drawContent()
            }
        }

    if (interactive) {
        val source = interactionSource ?: remember { MutableInteractionSource() }
        val isPressed by source.collectIsPressedAsState()
        val pressedScale by animateFloatAsState(
            targetValue = if (isPressed) 0.965f else 1f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 450f),
            label = "glassPressScale"
        )
        baseModifier.graphicsLayer {
            scaleX = pressedScale
            scaleY = pressedScale
        }
    } else {
        baseModifier
    }
}

/**
 * 自绘高光：三层描边叠加出「亮核 + 向内衰减」的剖面。
 *
 * 单层描边只能得到一条等宽的线；三层（宽而淡 → 窄而亮）叠加后，靠近边缘处
 * 三层重叠最亮、向内逐层消失，形成连续衰减 —— 这正是 Apple 剖面
 * （峰值 +44~96、向内衰减 14~17px）的形状。
 *
 * 描边以轮廓为中心，外侧一半被 `clipPath` 裁掉，所以 `width × 2` 才是实际
 * 可见的内侧带宽。
 */
private fun DrawScope.drawGlassHighlight(
    spec: GlassSurfaceSpec,
    outline: Outline
) {
    val h = size.height
    // 高光 / 内阴影的作用范围用固定 dp，不能按卡片高度取比例 ——
    // 否则矮卡片（dock 64dp）与高卡片（列表卡 400dp）的带宽会差好几倍。
    val hl = (24.dp.toPx()).coerceAtMost(h * 0.35f)
    val sh = (20.dp.toPx()).coerceAtMost(h * 0.25f)

    // 纵向渐变：顶部受光透亮，底部微压暗（透光晶体感，让色散彩虹折射完整透出）
    val brush = Brush.verticalGradient(
        0f to spec.highlightColor.copy(alpha = 0.95f * spec.highlightAlpha),
        (hl * 0.35f / h) to spec.highlightColor.copy(alpha = 0.55f * spec.highlightAlpha),
        (hl / h) to Color.Transparent,
        (1f - sh / h) to Color.Transparent,
        (1f - sh * 0.30f / h) to spec.innerShadowColor.copy(alpha = 0.10f * spec.innerShadowAlpha),
        1f to spec.innerShadowColor.copy(alpha = 0.30f * spec.innerShadowAlpha),
        startY = 0f,
        endY = h
    )
    val band = spec.highlightWidth.toPx()
    val path = Path().apply { addOutline(outline) }
    clipPath(path) {
        // 1) 表面底色与 .tint(Color) 色调注入：
        //    先铺一层微白/微深底色作为毛玻璃打底，保证无论背景明暗文字均清晰可读；
        //    若有 tintColor，再在底色上叠一层主题强调色（如 Dock 浅青薄荷绿）。
        if (spec.tintAlpha > 0f) {
            drawPath(path, color = spec.surfaceTint, alpha = spec.tintAlpha)
        }
        if (spec.tintColor != null) {
            drawPath(path, color = spec.tintColor)
        }
        // 2) 高光 / 内阴影：三层描边叠出「亮核 + 向内衰减」
        drawPath(path, brush = brush, alpha = 0.14f, style = Stroke(width = band * 2f))
        drawPath(path, brush = brush, alpha = 0.28f, style = Stroke(width = band * 0.9f))
        drawPath(path, brush = brush, alpha = 0.85f, style = Stroke(width = max(1.5f, band * 0.12f)))
    }
}
