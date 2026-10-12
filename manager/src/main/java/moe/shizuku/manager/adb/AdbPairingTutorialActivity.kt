package moe.shizuku.manager.adb

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.home.GroupHeaderWithToggle
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.home.MiuixRowIcon
import moe.shizuku.manager.home.MiuixSubPageTopBar
import moe.shizuku.manager.home.onLongPressToggle
import moe.shizuku.manager.maxhz.GlassAppTheme
import moe.shizuku.manager.utils.AccessibilityHelper
import rikka.compatibility.DeviceCompatibility
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import java.net.ConnectException

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingTutorialActivity : AppActivity() {

    private val notificationEnabledState = mutableStateOf(false)
    private var launchedDeveloperOptions: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = this

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val notifEnabled = isNotificationEnabled()
        notificationEnabledState.value = notifEnabled
        if (notifEnabled) {
            startPairingService()
        }

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isBlackNight = isDark && ThemeHelper.isBlackNightTheme(this)
        val primaryColorInt = runCatching {
            val tv = TypedValue()
            if (theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) tv.data
            else if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
        }.getOrDefault(if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt())

        val isMiui = DeviceCompatibility.isMiui()
        val isOppo = isColorOS()

        setContent {
            GlassAppTheme(isDark = isDark) {
                AdbPairingTutorialMiuixScreen(
                    isDark = isDark,
                    isBlackNightTheme = isBlackNight,
                    primaryColor = Color(primaryColorInt),
                    notificationEnabled = notificationEnabledState.value,
                    isMiui = isMiui,
                    isColorOs = isOppo,
                    onBackClick = { finish() },
                    onOpenDeveloperOptions = {
                        launchedDeveloperOptions = true
                        AdbPairingDetector.request()
                        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            launchedDeveloperOptions = false
                            AdbPairingDetector.cancel()
                        }
                    },
                    onOpenNotificationOptions = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                        }
                    },
                    onPairingSuccess = {
                        val intent = Intent(context, HomeActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("EXTRA_AUTO_START_AFTER_PAIRING", true)
                        }
                        startActivity(intent)
                        finish()
                    }
                )
            }
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.notificationChannel)
        return nm.areNotificationsEnabled() &&
            (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    override fun onResume() {
        super.onResume()
        if (launchedDeveloperOptions) {
            launchedDeveloperOptions = false
            AdbPairingDetector.cancelIfReturnedToApp()
        }

        val newNotificationEnabled = isNotificationEnabled()
        notificationEnabledState.value = newNotificationEnabled
        if (newNotificationEnabled) {
            startPairingService()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            if (!launchedDeveloperOptions) {
                AdbPairingDetector.cancel()
            }
            runCatching { startService(AdbPairingService.stopIntent(this)) }
        }
        super.onDestroy()
    }

    private fun startPairingService() {
        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException
            ) {
                val mode = getSystemService(AppOpsManager::class.java)
                    .noteOpNoThrow("android:start_foreground", android.os.Process.myUid(), packageName, null, null)
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(this, "OP_START_FOREGROUND is denied. What are you doing?", Toast.LENGTH_LONG).show()
                }
                startService(intent)
            }
        }
    }

    private fun isColorOS(): Boolean {
        return try {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ||
                brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ||
                !getSystemProperty("ro.build.version.opporom").isNullOrBlank() ||
                !getSystemProperty("ro.oplus.image.my_manifest").isNullOrBlank()
        } catch (_: Throwable) {
            false
        }
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            getMethod.invoke(null, key) as? String
        } catch (_: Throwable) {
            null
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun AdbPairingTutorialMiuixScreen(
    isDark: Boolean,
    isBlackNightTheme: Boolean,
    primaryColor: Color,
    notificationEnabled: Boolean,
    isMiui: Boolean,
    isColorOs: Boolean,
    onBackClick: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenNotificationOptions: () -> Unit,
    onPairingSuccess: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var groupExpanded by remember { mutableStateOf(false) }
    var showManualPairDialog by remember { mutableStateOf(false) }

    val bgColor = if (isBlackNightTheme) {
        Color.Black
    } else {
        MiuixTheme.colorScheme.surface
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = bgColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                top = 54.dp,
                start = 14.dp,
                end = 14.dp,
                bottom = 48.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header-top") {
                MiuixSubPageTopBar(
                    title = stringResource(R.string.adb_pairing),
                    subtitle = "通过通知栏搜索或手动输入配对码完成无线调试授权，长按卡片可展开详细说明",
                    isDark = isDark,
                    primaryColor = primaryColor,
                    expanded = groupExpanded,
                    onToggleExpand = { groupExpanded = !groupExpanded },
                    onBackClick = onBackClick
                )
            }

            if (!notificationEnabled) {
                item(key = "card-notif-disabled") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiuixRowIcon(
                                    isDark = isDark,
                                    icon = painterResource(R.drawable.ic_warning_24),
                                    iconTint = Color(0xFFF54A45)
                                )
                                Text(
                                    text = "通知权限未开启",
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFFF87171) else Color(0xFFD9383A)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.adb_pairing_tutorial_content_notification_blocked),
                                fontSize = 13.sp,
                                lineHeight = 18.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(14.dp))
                            TextButton(
                                text = stringResource(R.string.notification_settings),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = onOpenNotificationOptions,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                // 配对状态与网络提示卡
                item(key = "card-notif-ready") {
                    var cardToggled by remember(groupExpanded) { mutableStateOf(false) }
                    val showSub = groupExpanded xor cardToggled
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onLongPressToggle(haptic) { cardToggled = !cardToggled },
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiuixRowIcon(
                                    isDark = isDark,
                                    icon = painterResource(R.drawable.ic_outline_notifications_active_24),
                                    iconTint = Color(0xFF10B981)
                                )
                                Text(
                                    text = stringResource(R.string.adb_pairing_tutorial_content_notification),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            AnimatedVisibility(
                                visible = showSub,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .clickable { cardToggled = !cardToggled }
                                ) {
                                    Text(
                                        text = stringResource(R.string.adb_pairing_tutorial_content_network),
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.adb_pairing_tutorial_content_network_limation_not_foreground),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }

                if (isMiui) {
                    item(key = "card-miui-tip") {
                        var miuiToggled by remember(groupExpanded) { mutableStateOf(false) }
                        val showSub = groupExpanded xor miuiToggled
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onLongPressToggle(haptic) { miuiToggled = !miuiToggled },
                            cornerRadius = 20.dp,
                            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixRowIcon(
                                        isDark = isDark,
                                        icon = painterResource(R.drawable.ic_warning_24),
                                        iconTint = Color(0xFFF59E0B)
                                    )
                                    Text(
                                        text = stringResource(R.string.adb_pairing_tutorial_content_miui),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                                AnimatedVisibility(
                                    visible = showSub,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Text(
                                        text = stringResource(R.string.adb_pairing_tutorial_content_miui_2),
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (isColorOs) {
                    item(key = "card-oppo-tip") {
                        var oppoToggled by remember(groupExpanded) { mutableStateOf(false) }
                        val showSub = groupExpanded xor oppoToggled
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onLongPressToggle(haptic) { oppoToggled = !oppoToggled },
                            cornerRadius = 20.dp,
                            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixRowIcon(
                                        isDark = isDark,
                                        icon = painterResource(R.drawable.ic_warning_24),
                                        iconTint = Color(0xFFF59E0B)
                                    )
                                    Text(
                                        text = stringResource(R.string.adb_pairing_tutorial_content_oppo),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                                AnimatedVisibility(
                                    visible = showSub,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Text(
                                        text = stringResource(R.string.adb_pairing_tutorial_content_oppo_2),
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 步骤引导卡片组
                item(key = "section-steps") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GroupHeaderWithToggle(
                            text = "配对操作步骤",
                            isDark = isDark,
                            primaryColor = primaryColor,
                            expanded = groupExpanded,
                            onToggle = { groupExpanded = !groupExpanded }
                        )

                        // 步骤 1
                        var step1Toggled by remember(groupExpanded) { mutableStateOf(false) }
                        val showStep1Sub = groupExpanded xor step1Toggled
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onLongPressToggle(haptic) { step1Toggled = !step1Toggled },
                            cornerRadius = 20.dp,
                            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixRowIcon(
                                        isDark = isDark,
                                        icon = painterResource(R.drawable.ic_numeric_1_circle_outline_24),
                                        iconTint = primaryColor
                                    )
                                    Text(
                                        text = "进入「开发者选项」→「无线调试」",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                                AnimatedVisibility(
                                    visible = showStep1Sub,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .clickable { step1Toggled = !step1Toggled }
                                    ) {
                                        Text(
                                            text = stringResource(R.string.adb_pairing_tutorial_content_steps),
                                            fontSize = 12.5.sp,
                                            lineHeight = 18.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(R.string.adb_pairing_tutorial_content_left_is_clickable),
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            color = if (isDark) Color(0xFFF87171) else Color(0xFFD9383A)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    text = stringResource(R.string.development_settings),
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    onClick = onOpenDeveloperOptions,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // 步骤 2
                        var step2Toggled by remember(groupExpanded) { mutableStateOf(false) }
                        val showStep2Sub = groupExpanded xor step2Toggled
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onLongPressToggle(haptic) { step2Toggled = !step2Toggled },
                            cornerRadius = 20.dp,
                            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixRowIcon(
                                        isDark = isDark,
                                        icon = painterResource(R.drawable.ic_numeric_2_circle_outline_24),
                                        iconTint = primaryColor
                                    )
                                    Text(
                                        text = "点击「使用配对码配对设备」并输入配对码",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                                AnimatedVisibility(
                                    visible = showStep2Sub,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Text(
                                        text = stringResource(R.string.adb_pairing_tutorial_content_enter_pairing_code),
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .clickable { step2Toggled = !step2Toggled }
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    text = stringResource(R.string.adb_pairing_tutorial_manual_pairing),
                                    onClick = { showManualPairDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // 步骤 3
                        var step3Toggled by remember(groupExpanded) { mutableStateOf(false) }
                        val showStep3Sub = groupExpanded xor step3Toggled
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onLongPressToggle(haptic) { step3Toggled = !step3Toggled },
                            cornerRadius = 20.dp,
                            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixRowIcon(
                                        isDark = isDark,
                                        icon = painterResource(R.drawable.ic_numeric_3_circle_outline_24),
                                        iconTint = Color(0xFF10B981)
                                    )
                                    Text(
                                        text = "完成配对并自动连接",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                                AnimatedVisibility(
                                    visible = showStep3Sub,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Text(
                                        text = stringResource(R.string.adb_pairing_tutorial_content_finish),
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .clickable { step3Toggled = !step3Toggled }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Miuix 窗口级弹窗：手动配对
    MiuixManualPairingDialog(
        show = showManualPairDialog,
        isDark = isDark,
        primaryColor = primaryColor,
        onDismiss = { showManualPairDialog = false },
        onOpenDeveloperOptions = onOpenDeveloperOptions,
        onPairingSuccess = {
            showManualPairDialog = false
            onPairingSuccess()
        }
    )
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun MiuixManualPairingDialog(
    show: Boolean,
    isDark: Boolean,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onPairingSuccess: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var portText by remember(show) { mutableStateOf("") }
    var codeText by remember(show) { mutableStateOf("") }
    var detectedHost by remember(show) { mutableStateOf("127.0.0.1") }
    var statusMsg by remember(show) { mutableStateOf("正在通过本地 mDNS 自动搜索无线调试配对端口...") }
    var isError by remember(show) { mutableStateOf(false) }
    var isPairing by remember(show) { mutableStateOf(false) }
    var showDesc by remember(show) { mutableStateOf(false) }

    DisposableEffect(show) {
        if (!show) return@DisposableEffect onDispose {}
        val mdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { (serviceHost, servicePort) ->
            if (serviceHost.isNotBlank()) {
                detectedHost = serviceHost
            }
            if (servicePort > 0) {
                portText = servicePort.toString()
                statusMsg = "已自动发现配对端口：$servicePort，请输入 6 位配对码"
                isError = false
            }
        }
        mdns.start()
        onDispose {
            runCatching { mdns.stop() }
        }
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.dialog_adb_pairing_title),
        summary = if (showDesc) "可在分屏/小窗模式下打开系统「无线调试 → 使用配对码配对设备」，系统将自动填入端口或由您手动输入。" else null,
        onDismissRequest = { if (!isPairing) onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onLongPressToggle(haptic) { showDesc = !showDesc },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isError) {
                            if (isDark) Color(0x2EF87171) else Color(0xFFFFECE8)
                        } else {
                            if (isDark) Color(0xFF28282D) else Color(0xFFF0F2F6)
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = statusMsg,
                    fontSize = 12.5.sp,
                    color = if (isError) {
                        if (isDark) Color(0xFFF87171) else Color(0xFFD9383A)
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    }
                )
            }

            TextButton(
                text = "启用无障碍自动秒配对助手",
                onClick = {
                    onDismiss()
                    AdbPairingDetector.request()
                    if (AdbPairingAccessibilityService.isEnabled(context)) {
                        onOpenDeveloperOptions()
                        Toast.makeText(context, "配对助手已就绪！点击「使用配对码配对设备」即可自动秒配对", Toast.LENGTH_LONG).show()
                    } else {
                        AccessibilityHelper.openPairingAssistantSettings(context)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextField(
                    value = portText,
                    onValueChange = { input ->
                        portText = input.filter { it.isDigit() }.take(5)
                        isError = false
                    },
                    label = "配对端口",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                TextField(
                    value = codeText,
                    onValueChange = { input ->
                        codeText = input.filter { it.isDigit() }.take(6)
                        isError = false
                    },
                    label = "6 位配对码",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    enabled = !isPairing,
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.9f)
                )
                TextButton(
                    text = "开发者选项",
                    enabled = !isPairing,
                    onClick = onOpenDeveloperOptions,
                    modifier = Modifier.weight(1.1f)
                )
                TextButton(
                    text = if (isPairing) "配对中..." else stringResource(android.R.string.ok),
                    enabled = !isPairing,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val port = portText.toIntOrNull() ?: -1
                        if (port !in 1..65535) {
                            isError = true
                            statusMsg = context.getString(R.string.dialog_adb_invalid_port)
                            return@TextButton
                        }
                        if (codeText.isBlank()) {
                            isError = true
                            statusMsg = context.getString(R.string.dialog_adb_pairing_paring_code)
                            return@TextButton
                        }
                        isPairing = true
                        isError = false
                        statusMsg = "正在向 $detectedHost:$port 发起 TLS 配对..."
                        scope.launch(Dispatchers.IO) {
                            val key = try {
                                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                            } catch (e: Throwable) {
                                withContext(Dispatchers.Main) {
                                    isPairing = false
                                    isError = true
                                    statusMsg = context.getString(R.string.adb_error_key_store)
                                }
                                return@launch
                            }
                            val result = runCatching {
                                AdbPairingClient(detectedHost, port, codeText, key).start()
                            }
                            withContext(Dispatchers.Main) {
                                isPairing = false
                                result.onSuccess { ok ->
                                    if (ok) {
                                        onPairingSuccess()
                                    } else {
                                        isError = true
                                        statusMsg = context.getString(R.string.paring_code_is_wrong)
                                    }
                                }.onFailure { err ->
                                    isError = true
                                    statusMsg = when (err) {
                                        is ConnectException -> context.getString(R.string.cannot_connect_port)
                                        is AdbInvalidPairingCodeException -> context.getString(R.string.paring_code_is_wrong)
                                        else -> err.localizedMessage ?: context.getString(R.string.cannot_connect_port)
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
