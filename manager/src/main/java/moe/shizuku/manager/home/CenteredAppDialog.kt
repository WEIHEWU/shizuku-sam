package moe.shizuku.manager.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.shizuku.manager.app.ThemeHelper

/**
 * 全局统一居中弹窗组件
 * 
 * 彻底替换 Miuix 移动端默认底部对齐的 WindowDialog，确保在手机、平板及折叠屏上
 * 所有弹窗均强制屏幕垂直与水平居中，并保持统一的圆角、边框、投影与动态主题配色。
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
    val useSystemColor = ThemeHelper.isUsingSystemColor()

    val dialogBgColor = if (isDark) {
        if (isBlackNight) Color(0xFF000000)
        else if (useSystemColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.compose.material3.dynamicDarkColorScheme(context).surfaceContainerHigh
        } else Color(0xFF181B22)
    } else {
        if (useSystemColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.compose.material3.dynamicLightColorScheme(context).surfaceContainerHigh
        } else Color(0xFFFFFFFF)
    }

    val dialogStrokeColor = if (isDark) {
        if (isBlackNight) Color(0x26FFFFFF)
        else if (useSystemColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.compose.material3.dynamicDarkColorScheme(context).outlineVariant
        } else Color(0x224E5766)
    } else {
        if (useSystemColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.compose.material3.dynamicLightColorScheme(context).outlineVariant
        } else Color(0x12000000)
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(dialogBgColor)
                    .border(1.dp, dialogStrokeColor, RoundedCornerShape(24.dp))
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
                                color = if (isDark) Color(0xFFECEFF4) else Color(0xFF1A1C1E),
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) Color(0x26FFFFFF) else Color(0x14000000))
                                    .clickable { onDismissRequest() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✕",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFF949BA8) else Color(0xFF6B7280)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(if (isDark) Color(0x1FFFFFFF) else Color(0x12000000))
                        )
                    }
                    if (summary != null) {
                        Text(
                            text = summary,
                            fontSize = 12.5.sp,
                            color = if (isDark) Color(0xFF949BA8) else Color(0xFF6B7280),
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
