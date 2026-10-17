package moe.shizuku.manager.update

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.shizuku.manager.home.CenteredAppDialog
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppUpdateDialog(
    releaseInfo: AppUpdater.ReleaseInfo?,
    isDark: Boolean,
    primaryColor: Color,
    onDismiss: () -> Unit
) {
    if (releaseInfo == null) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDownloading by remember(releaseInfo) { mutableStateOf(false) }
    var downloadPercent by remember(releaseInfo) { mutableIntStateOf(0) }
    var statusMessage by remember(releaseInfo) { mutableStateOf("") }
    var errorMessage by remember(releaseInfo) { mutableStateOf<String?>(null) }

    val shizukuActive = remember(releaseInfo) {
        try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    val sizeText = remember(releaseInfo) {
        AppUpdater.formatBytes(releaseInfo.assetSize)
    }

    val summaryLine = buildString {
        append("当前版本 v${AppUpdater.getCurrentVersionName()} -> 最新版本 v${releaseInfo.versionName}")
        if (sizeText.isNotEmpty()) {
            append(" ($sizeText)")
        }
    }

    CenteredAppDialog(
        show = true,
        title = "发现新版本 v${releaseInfo.versionName}",
        summary = summaryLine,
        onDismissRequest = {
            if (!isDownloading) {
                onDismiss()
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 220.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.06f)
                        else Color.Black.copy(alpha = 0.04f)
                    )
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "更新内容",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                    Text(
                        text = releaseInfo.changelog,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            Text(
                text = if (shizukuActive) {
                    "已检测到 Shizuku 正在运行，支持特权静默安装并自动重启（免系统安装器拦截）"
                } else {
                    "下载完成后将调起系统安装器完成覆盖更新（数据完整保留）"
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (shizukuActive) primaryColor else MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            if (isDownloading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusMessage.ifEmpty { "正在准备下载..." },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "$downloadPercent%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryColor
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (downloadPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp)),
                        color = primaryColor,
                        trackColor = primaryColor.copy(alpha = 0.16f)
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFEF4444)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = if (isDownloading) "下载中..." else "稍后",
                    enabled = !isDownloading,
                    onClick = { onDismiss() },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "浏览器下载",
                    enabled = !isDownloading,
                    onClick = {
                        CustomTabsHelper.launchUrlOrCopy(context, releaseInfo.htmlUrl)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.1f)
                )
                TextButton(
                    text = if (isDownloading) "更新中..." else "立即更新",
                    enabled = !isDownloading,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        isDownloading = true
                        errorMessage = null
                        downloadPercent = 0
                        statusMessage = "正在连接更新服务器..."
                        scope.launch {
                            val result = AppUpdater.downloadAndInstall(
                                context = context,
                                release = releaseInfo,
                                onProgress = { pct, _, _, status ->
                                    downloadPercent = pct
                                    statusMessage = status
                                }
                            )
                            result.onSuccess { mode ->
                                isDownloading = false
                                if (mode == AppUpdater.InstallMode.SYSTEM_INSTALLER) {
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "更新完成，正在重新启动应用...", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                            }.onFailure { err ->
                                isDownloading = false
                                errorMessage = err.message ?: "更新失败，请尝试点击「浏览器下载」手动安装"
                            }
                        }
                    },
                    modifier = Modifier.weight(1.2f)
                )
            }
        }
    }
}
