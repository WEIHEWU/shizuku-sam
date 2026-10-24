package moe.shizuku.manager.home

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.maxhz.GlassLight
import moe.shizuku.manager.maxhz.GlassRadii
import moe.shizuku.manager.maxhz.LocalGlassEnabled
import moe.shizuku.manager.maxhz.TitaniumBlack
import moe.shizuku.manager.maxhz.TitaniumDark

/**
 * SamsungRootKit 光学玻璃全局统一居中弹窗组件
 *
 * 结合 Window 级实时高斯模糊 (FLAG_BLUR_BEHIND) + Kyant Backdrop (blur 16dp + lens 24dp)
 * 边缘光学透镜折射、1dp 钛金发丝描边与顶部微光反光层。
 */
@Composable
fun CenteredAppDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!show) return

    val context = LocalContext.current
    val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val isBlackNight = ThemeHelper.isBlackNightTheme(context) && isDark
    val glassOn = LocalGlassEnabled.current

    val dialogBgColor = when {
        isBlackNight -> TitaniumBlack.Card.copy(alpha = 0.88f)
        isDark -> Color(0xCC161B22)
        else -> Color(0xD9FFFDFB)
    }

    val dialogStrokeColor = if (isDark) TitaniumDark.Edge else GlassLight.DialogHairline
    val sheenAlpha = if (isDark) 0.06f else 0.14f
    val dialogShape = RoundedCornerShape(if (isDark) GlassRadii.Dialog else 28.dp)
    val dialogBackdrop = rememberLayerBackdrop(blurRadius = 0.dp)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { win ->
                win.setDimAmount(if (isDark) 0.48f else 0.26f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    win.attributes = win.attributes.apply {
                        blurBehindRadius = 56
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .fillMaxWidth()
                    .clip(dialogShape)
                    .shadow(
                        elevation = 24.dp,
                        shape = dialogShape,
                        ambientColor = Color.Black.copy(alpha = if (isDark) 0.55f else 0.18f),
                        spotColor = Color.Black.copy(alpha = if (isDark) 0.55f else 0.18f)
                    ),
                shape = dialogShape,
                color = dialogBgColor,
                border = BorderStroke(1.dp, dialogStrokeColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = sheenAlpha),
                                0.45f to Color.Transparent
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (title != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 17.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A),
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (isDark) Color(0x26FFFFFF) else Color(0x14000000))
                                        .border(0.8.dp, if (isDark) TitaniumDark.Hairline else GlassLight.Hairline, CircleShape)
                                        .clickable { onDismissRequest() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✕",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(if (isDark) TitaniumDark.Hairline else Color(0x18000000))
                            )
                        }
                        if (summary != null) {
                            Text(
                                text = summary,
                                fontSize = 12.5.sp,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                lineHeight = 17.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        content()
                    }
                }
            }
        }
    }
}
