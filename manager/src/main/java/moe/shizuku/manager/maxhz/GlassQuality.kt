package moe.shizuku.manager.maxhz

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 玻璃质量档位。
 *
 * 「液态玻璃」每帧的成本几乎全在 GPU 的图像滤镜链：大半径高斯模糊 + 每像素
 * 7 次纹理采样的色散 AGSL。一屏 5~6 张玻璃面时，120Hz 的帧预算（8.3ms）必然
 * 被击穿 —— 但这些成本只在**滑动时**才真正疼：
 *
 * - [Fast] / [Flat]：任何滚动。**走无离屏的直通路径** —— 背景层本身是烘焙好的
 *   模糊版，直接把对应区域画出来就是一块磨砂玻璃，成本仅一次 drawLayer，
 *   且不分配 / 切换 render target（离屏层切换才是掉帧的主因）。
 * - [Full]：静止。离屏层 + 折射 / 色散完整滤镜。静止不追求帧率，只在失效时画。
 *
 * 注意模糊**不在档位里**：它已上移到共享背景层整屏烘焙一次（见 LayerBackdrop），
 * 静止与滚动共用，因此滚动时玻璃依然是磨砂的，只是没有边缘折射。
 *
 * 静止时参数与全质量**逐位相同** —— 用户盯着看的时候永远是完整玻璃。
 */
enum class GlassQuality { Full, Fast, Flat }

/**
 * 质量档位的**稳定持有者**。
 *
 * 关键：档位不能放进 CompositionLocal 的 value 里。value 一变，所有读取它的
 * 组合（每张卡的整个内容：文字、图标、布局）都会重组 —— 甩动时档位频繁跳变，
 * 等于边滑边疯狂重组，实测比不降级还卡。
 *
 * 这里只把**持有者**（引用恒定）放进 CompositionLocal，档位变化写进它内部的
 * State。读取发生在绘制期（`drawBackdrop` 的 effects 求值，位于节点的
 * observeReads 内），因此只会让绘制节点重建滤镜，零重组。
 */
class GlassQualityState {
    var quality: GlassQuality by mutableStateOf(GlassQuality.Full)
        internal set

    /**
     * **外部强制档位**（动画期间用），优先级高于 [quality]。
     *
     * Full 档一旦开始逐帧重绘，每帧要付约 41.6ms —— 实机实测这是「一屏 7 块玻璃
     * 各做一次离屏录制」的固定开销，与离屏分辨率无关，所以降分辨率救不了，
     * 只能**在动画期间整条走直通**。
     *
     * 典型场景：dock 的液滴位移弹簧 + 点击布丁回弹。它们是逐帧动画，
     * 期间玻璃会掉到 24fps，正是「点击 dock 一顿」的来源。
     *
     * 用 State 而不是 @Volatile：写入只在「动画开始 / 结束」这两个**翻转时刻**
     * 发生（由 `LaunchedEffect(animating)` 保证），不是每帧写，因此不会造成
     * 每帧全屏 invalidate；但翻转那一下能正确触发重绘，动画结束后玻璃能升回
     * 全质量（否则会永久停在直通，折射消失）。
     */
    var override: GlassQuality? by mutableStateOf(null)
        internal set

    /** 实际生效的档位 */
    val effective: GlassQuality get() = override ?: quality
}

/**
 * **进程内共享**的档位持有者。
 *
 * 必须共享、且必须是 [LocalGlassQualityState] 的默认值，原因是一个真实存在的
 * 架构约束：底部 dock **不是**当前页面 Compose 树的一部分 —— 它是
 * `MaxHzActivity` 里另一个独立的 `ComposeView`（`setupGlassDockOverlay`），
 * 有自己的 composition，拿不到页面通过 `CompositionLocalProvider` 注入的
 * 那个 state。
 *
 * 于是 dock 一直拿着默认值、**恒为 Full**：页面卡片滚动时已经降成直通了，
 * dock 还在每帧跑「离屏录制 + lens 色散」整条全质量管线。这正是用户 A/B 出来
 * 的结论 —— 「直通 / 无玻璃都很流畅，只有正常档不流畅」：那两个诊断档
 * （当时由 GlassDebugState 强制改写成 0f / -1f）会把 dock 的这份独有开销也一起
 * 抹掉，唯独正常档抹不掉。诊断档与 GlassDebugState 现已随浮层一起移除，
 * 改为 [GLASS_FORCE_DIRECT] 固定直通。
 *
 * 默认值指向本单例后，dock 与页面共用同一个持有者，滚动时一起降级。
 */
val GlobalGlassQualityState: GlassQualityState = GlassQualityState()

val LocalGlassQualityState: ProvidableCompositionLocal<GlassQualityState> =
    compositionLocalOf { GlobalGlassQualityState }

private const val SAMPLE_MS = 48L

/**
 * **玻璃渲染总开关**：true = 永远走直通档。
 *
 * 直通档 = 不进离屏层、不跑 lens 折射 / 色散 AGSL，直接把「整屏预烘焙好的
 * 模糊 + 增艳背景」的对应区域画出来（一次 drawLayer，不分配 / 切换 render
 * target）。实机四档对比：直通 117~120fps、jank 0；Full 档逐帧 41.6ms
 * （一屏 7 块玻璃各一次离屏录制的固定开销，降离屏分辨率完全救不回来）。
 *
 * 代价：失去**边缘折射与彩虹色散**（液态玻璃最贵的那部分，也是最容易掉帧的
 * 那部分）。保留下来的仍是完整磨砂玻璃观感 —— 模糊、增艳、镜面高光、内阴影、
 * 投影、底色全都在，只是玻璃边缘不再有棱镜扭曲。
 *
 * 想恢复折射：把这里改成 false，档位会回到「按滚动速度分档」（静止 Full /
 * 滚动直通），静止时能拿回全质量折射。
 */
const val GLASS_FORCE_DIRECT = true

/**
 * 档位 → 离屏渲染比例。与 `GlassSurface` 里的 renderScale 提供者**必须保持一致**，
 * 否则去重判断会误判。
 *
 * Fast 与 Flat 目前都是 0f（直通），所以两者互为等价档 —— [GlassQuality] 保留
 * Flat 是为了后续接入「半分辨率离屏」这类真正不同的中间档。
 */
internal fun GlassQuality.renderScaleTier(): Float {
    if (GLASS_FORCE_DIRECT) return 0f
    // 保持全分辨率：实测把离屏降到 0.5 后帧耗时**完全没变**（41.6ms vs 41.7ms），
    // 说明 Full 档的成本不在 shader fill-rate，而在「每块玻璃一次离屏录制」的
    // 固定开销（离屏分辨率降了也省不掉）。既然零收益，就不付「色散条纹变软」
    // 这个视觉代价 —— 折射的彩虹边正是液态玻璃最标志性的特征。
    //
    // 真正的解法是 [GlassQualityState.override]：动画期间整条走直通，
    // 以及 [DrawBackdropNode] 的离屏内容缓存（静止时不重录）。
    return when (this) {
        GlassQuality.Full -> 1f
        GlassQuality.Fast -> 0f
        GlassQuality.Flat -> 0f
    }
}

/**
 * 超过此速度（dp/s）进入 Flat。
 *
 * 850 → 650：1080p 屏上一次「快速甩动」的惯性和 650dp/s 差不多，原来的阈值
 * 偏高，导致很多该进 Flat 的中高速滑动仍留在 Fast，用户反馈「快甩还是卡」。
 */
private const val FLAT_DP_PER_SEC = 650f

/** 超过此速度进入 Fast（低于它视为静止） */
private const val FAST_DP_PER_SEC = 40f

/**
 * 连续多少个采样窗静止才恢复 Full。
 *
 * 2 → 5（约 96ms → 240ms）：真机 gfxinfo 实测，2 窗时「正常」档滑动 jank 18.8%，
 * 而强制「直通」档只有 7.5% —— 差值全来自滑动中速度采样偶尔落到 0，档位弹回
 * Full 又立刻弹回 Fast，每次回弹都要让全屏卡片按全质量重渲染一遍。
 * 放宽到 5 窗后，一次连续的滑动过程不会再发生回弹，只有真正停手才升全质量。
 */
/**
 * 3 → 10（约 144ms → 480ms）。
 *
 * 一次「升回 Full」的代价远不止一帧：整屏每张卡都要重建离屏层、重跑 lens
 * 色散 shader。连续甩动时手指难免有短暂间隙，3 窗（144ms）会让每次间隙都
 * 触发一次全屏重建 —— 用户感知就是「一顿一顿」。放宽到 10 窗后，只有真正
 * 停手（半秒不动）才升全质量，而这已经是静止状态，掉一帧也看不出来。
 */
private const val IDLE_WINDOWS = 10

/** 两次档位切换之间的最小间隔，进一步抑制边界抖动 */
private const val MIN_SWITCH_MS = 150L

/**
 * 按**实际滚动速度**分档（不是只看 `isScrollInProgress`）：慢速拖动时玻璃保持
 * 完整观感，只有真正甩起来才切 Flat。结果写进 [GlassQualityState]，不触发重组。
 *
 * 速度用首个可见条目的滚动位置估算：`index * 平均条高 - offset`（px），
 * 每 [SAMPLE_MS] 采样一次求差分。
 */
@Composable
fun rememberGlassQualityState(listState: LazyListState): GlassQualityState {
    val density = LocalDensity.current
    // 用共享单例而不是 remember { 新建 }：dock 在独立的 ComposeView 里，
    // 只有共享同一个持有者，它才可能跟着页面一起降级（详见 GlobalGlassQualityState）。
    val state = GlobalGlassQualityState

    // 固定直通档：renderScaleTier() 恒为 0f，档位再怎么变都不会改变渲染结果，
    // 所以采样协程整个不开 —— 省掉每 48ms 一次的 LazyListState 轮询，以及
    // 「档位翻转 → 所有玻璃节点 invalidateDrawCache + invalidateDraw」
    // 那份纯抖动开销（直通档与正常档 jank 差的那 11 个点就来自这里）。
    if (GLASS_FORCE_DIRECT) return state

    LaunchedEffect(listState, density) {
        var prevPos = 0f
        var hasPrev = false
        var idleWindows = 0
        var lastSwitchAt = 0L

        while (true) {
            delay(SAMPLE_MS)

            val items = listState.layoutInfo.visibleItemsInfo
            val first = items.firstOrNull()
            val avgSize = if (items.isEmpty()) 0f else items.map { it.size }.average().toFloat()
            val pos = if (first == null) 0f else first.index * avgSize - first.offset.toFloat()
            val dpPerSec = if (hasPrev) {
                abs(pos - prevPos) / density.density * (1000f / SAMPLE_MS)
            } else {
                0f
            }
            prevPos = pos
            hasPrev = true

            // 硬门禁：只要列表还在滚动（含惯性惯性滑行），就绝不升 Full。
            // 只靠速度采样不够 —— 采样窗落在两次触摸事件之间时会读到 0，
            // 档位弹回 Full 让全屏卡片按全质量重渲染，下一窗又弹回 Fast，
            // 来回抖动正是「正常档 18.8% vs 直通档 7.5%」那 11 个点的来源。
            val scrolling = listState.isScrollInProgress
            val target = when {
                scrolling -> if (dpPerSec > FLAT_DP_PER_SEC) GlassQuality.Flat else GlassQuality.Fast
                else -> {
                    idleWindows++
                    if (idleWindows >= IDLE_WINDOWS) GlassQuality.Full else state.quality
                }
            }
            if (scrolling) idleWindows = 0

            val now = System.currentTimeMillis()
            // 只在**渲染结果真的会变**时才写 State。
            //
            // 写一次 State，所有玻璃节点都会 onObservedReadsChanged →
            // invalidateDrawCache()（重建 observeReads + 重跑 effects）
            // + invalidateDraw()（丢弃缓存的绘制命令，下一帧重新录制整张卡）。
            // 一屏 6 张卡，甩动时 Fast/Flat 在 650dp/s 阈值附近来回抖，
            // 哪怕有 150ms 限流也是每秒 6 次 × 6 张卡的全屏重绘请求 ——
            // 这才是「强制直通 7.5% vs 正常档」多出来的那部分 jank：
            // 直通档的 renderScale 直接返回常量 0f、根本不读 qualityState，
            // 所以档位怎么抖都不会触发任何重绘。
            //
            // Fast 与 Flat 的离屏比例完全相同（都是 0f 直通），它们之间的
            // 切换对画面没有任何影响，却要付一次全屏重绘 —— 直接吞掉。
            if (target != state.quality &&
                target.renderScaleTier() != state.quality.renderScaleTier() &&
                now - lastSwitchAt >= MIN_SWITCH_MS
            ) {
                state.quality = target
                lastSwitchAt = now
            }
        }
    }

    return state
}
