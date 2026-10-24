package moe.shizuku.manager.maxhz

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass / "Titanium" 设计规范 Token — 移植自 SamsungRootKit / OdinBox 玻璃架构。
 *
 * 1. 动静分离：底栏使用独立的 Backdrop 渲染，列表卡片使用 0 GPU 开销的静态高透钛金玻璃；
 * 2. 颜色编码：Compose [Color] 长整型字面量为 0xAARRGGBB；
 * 3. 性能熔断：提供 [LocalGlassEnabled] 全局 CompositionLocal 开关。
 */

// ── 圆角规范 (dp) ──────────────────────────────────────────────────────────────
object GlassRadii {
    val Card: Dp = 20.dp           // 卡片大圆角
    val Dialog: Dp = 24.dp         // 暗色对话框
    val GlassDialog: Dp = 32.dp    // 亮色玻璃对话框
    val Pill: Dp = 999.dp          // 胶囊形状 (Dock / Input / Tag)
    val Chip: Dp = 28.dp           // 历史记录 / 标签
    val Button: Dp = 14.dp         // 操作按钮
}

// ── 暗色模式 ("Titanium Dark") ───────────────────────────────────────────────────────
object TitaniumDark {
    val BackgroundTop = Color(0xFF161B22)     // 钛金顶部背景
    val BackgroundMid = Color(0xFF12131F)     // 钛金中部过渡
    val BackgroundBottom = Color(0xFF0D0E18)  // 钛金底部深沉
    val Card = Color(0x2A384850)             // 钛金卡片填充 (AA=2A ≈ 42% 冷灰蓝，绝非死黑)
    val CardInnerShadow = Color(0x0A000000)   // 卡片顶部极浅内阴影
    val Hairline = Color(0x26FFFFFF)         // 发丝描边 (AA=26 ≈ 15% 纯白 @ 1dp)
    val Edge = Color(0x33FFFFFF)             // 对话框边缘 (AA=33 ≈ 20% 纯白)
    val Accent = Color(0xFF00E5FF)           // 钛金高亮青色
    val AccentPressed = Color(0x4D00E5FF)    // 按下高光
    val DialogBg = Color(0xF0161B22)         // 对话框打底
    val SheenAlpha = 0.04f                   // 顶部微弱反光强度 (4%)
}

// ── 亮色模式 ("Glass Light") ─────────────────────────────────────────────────────────
object GlassLight {
    val BackgroundTop = Color(0xFFF8FBFE)     // 亮色顶部微蓝
    val BackgroundMid = Color(0xFFEFF5FC)     // 亮色中部
    val BackgroundBottom = Color(0xFFE4EDFA)  // 亮色底部
    val Card = Color(0x59FFFFFF)             // 玻璃卡片主填充 (AA=59 ≈ 35% 暖白)
    val Hairline = Color(0xCCFFFFFF)         // 亮色发丝描边 (AA=CC ≈ 80% 高透白 @ 1dp)
    val DialogBg = Color(0xE6FFFDFB)         // 对话框打底 (AA=E6 ≈ 90% 暖白)
    val DialogHairline = Color(0xFFFFFFFF)   // 亮色对话框高亮边
    val Accent = Color(0xFF10B981)           // 玻璃翡翠绿
    val SheenAlpha = 0.10f                   // 顶部微弱反光强度 (10%)
}

/**
 * 玻璃特效全局性能熔断开关。
 * 当处于性能诊断、AOT 编译或重负载任务时，设为 false 可完全禁用 GPU 离屏模糊，
 * 降级为 0 开销的纯色微光卡片，锁定 120Hz 极低功耗。
 */
val LocalGlassEnabled = compositionLocalOf { true }
