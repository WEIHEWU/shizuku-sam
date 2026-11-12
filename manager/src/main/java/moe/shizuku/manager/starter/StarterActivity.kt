package moe.shizuku.manager.starter

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.adb.AdbPortProber
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.adb.LocalNetworkGate
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.home.MiuixRowIcon
import moe.shizuku.manager.home.MiuixSubPageTopBar
import moe.shizuku.manager.home.onLongPressToggle
import moe.shizuku.manager.maxhz.GlassAppTheme
import moe.shizuku.manager.maxhz.GlassButtonStyle
import moe.shizuku.manager.maxhz.GlassCard
import moe.shizuku.manager.maxhz.GlassLight
import moe.shizuku.manager.maxhz.GlassPillButton
import moe.shizuku.manager.maxhz.TitaniumDark
import moe.shizuku.manager.maxhz.getSystemOrAppPrimaryColor
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.AccessibilityHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.RootBootScriptHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.StartupCoordinator
import rikka.core.util.ClipboardUtils
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.ConnectException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLProtocolException

private class NotRootedException : Exception()
private class StarterBinderTimeoutException(message: String) : TimeoutException(message)

class StarterActivity : AppActivity() {

    private val localNetRequester = LocalNetworkGate.Requester(this)

    private val viewModel by viewModels {
        ViewModel(
            this,
            intent.getBooleanExtra(EXTRA_IS_ROOT, true),
            intent.getStringExtra(EXTRA_HOST),
            intent.getIntExtra(EXTRA_PORT, 0)
        )
    }

    private val outputTextState = mutableStateOf("")
    private val serviceReadyState = mutableStateOf(false)
    private val isStartingState = mutableStateOf(true)
    private val errorState = mutableStateOf<Throwable?>(null)
    private var serviceReadyHandled = false
    private var hasStarted = false

    private val binderListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            Shizuku.removeBinderReceivedListener(this)
            runOnUiThread { onServiceReady() }
        }
    }

    private fun onServiceReady() {
        if (serviceReadyHandled) return
        serviceReadyHandled = true
        serviceReadyState.value = true
        isStartingState.value = false
        errorState.value = null
        val current = viewModel.output.value?.data?.toString() ?: ""
        if (!current.contains(Starter.SERVICE_STARTED_MESSAGE)) {
            viewModel.appendOutput("\n${Starter.SERVICE_STARTED_MESSAGE}")
        }
        window?.decorView?.postDelayed({
            if (!isFinishing) finish()
        }, 350)
    }

    private fun triggerStart(forceRetry: Boolean = false, overridePort: Int? = null) {
        if (serviceReadyState.value) return
        val isRoot = viewModel.isRootMode
        if (!isRoot && !LocalNetworkGate.hasPermission(this)) {
            errorState.value = null
            isStartingState.value = true
            localNetRequester.requestIfNeeded(
                caller = "StarterActivity",
                onGranted = {
                    viewModel.start(forceRetry = forceRetry, overridePort = overridePort)
                },
                onDenied = {
                    viewModel.failWithMissingLocalNetworkPermission()
                }
            )
            return
        }
        viewModel.start(forceRetry = forceRetry, overridePort = overridePort)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasStarted) {
            hasStarted = true
            triggerStart(forceRetry = false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDialogWindowGeometry()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
    }

    private fun applyDialogWindowGeometry() {
        val dm = resources.displayMetrics
        val dialogWidth = minOf((dm.widthPixels * 0.92f).toInt(), (520 * dm.density).toInt())
        window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            win.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setDimAmount(0.55f)
            win.setGravity(Gravity.CENTER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    win.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    win.attributes = win.attributes.apply {
                        blurBehindRadius = 56
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyDialogWindowGeometry()

        Shizuku.addBinderReceivedListener(binderListener)

        viewModel.output.observe(this) {
            val output = it.data?.toString()?.trim() ?: return@observe
            outputTextState.value = output
            isStartingState.value = viewModel.isRunningNow()
            if (output.endsWith(Starter.SERVICE_STARTED_MESSAGE)) {
                onServiceReady()
            } else if (it.status == Status.ERROR) {
                isStartingState.value = false
                errorState.value = it.error
            }
        }

        // 兜底：若窗口在 300ms 内仍未收到焦点回调，自动触发启动
        window?.decorView?.postDelayed({
            if (!hasStarted && !isFinishing) {
                hasStarted = true
                triggerStart(forceRetry = false)
            }
        }, 300)

        val isDark = moe.shizuku.manager.maxhz.GlassThemeState.resolveIsDark(this)
        val isBlackNight = moe.shizuku.manager.maxhz.GlassThemeState.resolveIsBlackNight(this, isDark)
        val dialogBgColor = when {
            isBlackNight -> Color(0xFF0A0C10)
            isDark -> Color(0xFA161B22)
            else -> Color(0xFAFFFDFB)
        }
        val dialogStrokeColor = if (isDark) {
            TitaniumDark.Edge
        } else {
            GlassLight.DialogHairline
        }

        setContent {
            GlassAppTheme(isDark = isDark) {
                val activePrimaryColor = getSystemOrAppPrimaryColor(isDark)
                val config = LocalConfiguration.current
                val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
                val windowMaxHeightDp = (config.screenHeightDp * if (isLandscape) 0.82f else 0.62f).dp
                val terminalMaxHeightDp = (config.screenHeightDp * if (isLandscape) 0.30f else 0.32f).dp
                    .coerceIn(112.dp, 240.dp)

                // 注意：此处严禁包裹 BackdropProvider（其内部在无自定义背景层时会附带 .fillMaxSize()，
                // 在有限最大高度约束下会把弹窗强行撑满至上限而产生大片空白）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .heightIn(max = windowMaxHeightDp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(dialogBgColor)
                        .border(1.dp, dialogStrokeColor, RoundedCornerShape(24.dp))
                ) {
                    StarterMiuixScreen(
                        isDark = isDark,
                        primaryColor = activePrimaryColor,
                        isRootMode = viewModel.isRootMode,
                        targetEndpointText = viewModel.currentEndpointLabel(),
                        outputText = outputTextState.value,
                        isServiceReady = serviceReadyState.value,
                        isStarting = isStartingState.value,
                        error = errorState.value,
                        terminalMaxHeightDp = terminalMaxHeightDp,
                        onRetryClick = {
                            triggerStart(forceRetry = true)
                        },
                        onCopyLogClick = {
                            val logReport = viewModel.buildCopyableLog()
                            if (ClipboardUtils.put(this@StarterActivity, logReport)) {
                                Toast.makeText(this@StarterActivity, "已复制完整启动与诊断日志", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onCloseClick = { finish() },
                        onOpenLocalNetSettings = {
                            LocalNetworkGate.openAppPermissionSettings(this@StarterActivity)
                        },
                        onOpenDevOptions = {
                            val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                                if (EnvironmentUtils.isSamsung()) {
                                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                                    putExtra("android.intent.extra.WINDOW_MODE", 5)
                                    putExtra("com.samsung.android.intent.extra.LAUNCH_MODE", 4)
                                }
                            }
                            runCatching { startActivity(devIntent) }
                        },
                        onRetryPort5555 = {
                            triggerStart(forceRetry = true, overridePort = 5555)
                        },
                        onOpenPairingTutorial = {
                            startActivity(Intent(this@StarterActivity, AdbPairingTutorialActivity::class.java))
                        },
                        onOpenAccessibility = {
                            AccessibilityHelper.openPairingAssistantSettings(this@StarterActivity)
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_HOST = "$EXTRA.HOST"
        const val EXTRA_PORT = "$EXTRA.PORT"
    }
}

@Composable
private fun StarterMiuixScreen(
    isDark: Boolean,
    primaryColor: Color,
    isRootMode: Boolean,
    targetEndpointText: String,
    outputText: String,
    isServiceReady: Boolean,
    isStarting: Boolean,
    error: Throwable?,
    terminalMaxHeightDp: androidx.compose.ui.unit.Dp,
    onRetryClick: () -> Unit,
    onCopyLogClick: () -> Unit,
    onCloseClick: () -> Unit,
    onOpenLocalNetSettings: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onRetryPort5555: () -> Unit,
    onOpenPairingTutorial: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var groupExpanded by remember { mutableStateOf(false) }
    var cardToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSub = groupExpanded xor cardToggled
    val hasError = error != null && !isServiceReady

    val errorAccentColor = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    val readyAccentColor = Color(0xFF10B981)

    val statusTitle = when {
        isServiceReady -> "Shizuku 服务已成功拉起"
        hasError -> when (error) {
            is LocalNetworkGate.LocalNetworkPermissionException -> "等待授予本地网络权限"
            is StarterBinderTimeoutException -> "等待服务响应超时"
            is ConnectException -> "无线调试端口连接失败"
            is SSLProtocolException -> "无线调试配对已失效"
            is NotRootedException -> "Root 授权获取失败"
            is AdbKeyException -> "ADB 密钥异常"
            else -> "Shizuku 启动失败"
        }
        else -> "正在拉起 Shizuku 底层服务..."
    }

    val errorReasonText = when (error) {
        null -> null
        is LocalNetworkGate.LocalNetworkPermissionException ->
            "Android 17 强制启用本地网络保护（LNP）：未运行时授予「本地网络」权限时，系统会对每次连接弹出「选择要连接的设备」；已跳过本次连接，请开启权限后重试。"
        is StarterBinderTimeoutException ->
            error.message ?: "等待 Shizuku Binder 超过 18 秒未响应，请确认无线调试/USB 调试连接有效或重试。"
        is ConnectException ->
            "未能连接无线调试地址（$targetEndpointText）。请确认已在开发者选项中开启「无线调试」，或重开关一次调试开关后点「重试 5555」。"
        is SSLProtocolException ->
            "无线调试密钥已重置或过期，请点击「重新配对」完成一次性配对。"
        is NotRootedException ->
            stringResource(R.string.start_with_root_failed)
        is AdbKeyException ->
            stringResource(R.string.adb_error_key_store)
        else ->
            error.localizedMessage?.takeIf { it.isNotBlank() } ?: stringResource(R.string.cannot_connect_port)
    }

    // 根容器不挂 verticalScroll，避免在有界高度上限下撑高弹窗或形成双重滚动；
    // 滚动仅保留在终端日志区域内部，整窗高度完全随内容紧凑自适应。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        MiuixSubPageTopBar(
            title = stringResource(R.string.starter),
            subtitle = "实时显示 shizuku_starter 握手日志，启动成功后自动关闭",
            isDark = isDark,
            primaryColor = primaryColor,
            expanded = groupExpanded,
            onToggleExpand = { groupExpanded = !groupExpanded },
            backSymbol = "✕",
            onBackClick = onCloseClick
        )

        Spacer(Modifier.height(8.dp))

        GlassCard(
            isDark = isDark,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .onLongPressToggle(haptic) { cardToggled = !cardToggled }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiuixRowIcon(
                        isDark = isDark,
                        icon = painterResource(R.drawable.ic_terminal_24),
                        iconTint = when {
                            isServiceReady -> readyAccentColor
                            hasError -> errorAccentColor
                            else -> primaryColor
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = statusTitle,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasError) errorAccentColor else MiuixTheme.colorScheme.onSurface
                        )
                        AnimatedVisibility(
                            visible = showSub,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Text(
                                text = "通过 mDNS 解析的无线调试地址（回环仅作回退）或 Root 注入启动 shizuku_starter，绑定成功后自动关闭窗口。",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { cardToggled = !cardToggled }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                val terminalScrollState = rememberScrollState()
                LaunchedEffect(outputText) {
                    terminalScrollState.animateScrollTo(terminalScrollState.maxValue)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .heightIn(min = 96.dp, max = terminalMaxHeightDp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0x38384850) else Color(0x73FFFFFF))
                        .border(1.dp, if (isDark) Color(0x26FFFFFF) else Color(0xCCFFFFFF), RoundedCornerShape(14.dp))
                        .verticalScroll(terminalScrollState)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = outputText.ifBlank { "正在初始化启动环境..." },
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }

                if (hasError && !errorReasonText.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    val errorBoxScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 76.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(errorAccentColor.copy(alpha = if (isDark) 0.14f else 0.09f))
                            .border(1.dp, errorAccentColor.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                            .verticalScroll(errorBoxScroll)
                            .padding(horizontal = 11.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = errorReasonText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    // 针对具体失败原因提供快捷修复入口
                    when (error) {
                        is LocalNetworkGate.LocalNetworkPermissionException -> {
                            Spacer(Modifier.height(8.dp))
                            GlassPillButton(
                                text = "去系统设置开启本地网络权限",
                                isDark = isDark,
                                style = GlassButtonStyle.Prominent,
                                onClick = onOpenLocalNetSettings,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        is ConnectException, is StarterBinderTimeoutException -> {
                            if (!isRootMode) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassPillButton(
                                        text = "重试 5555",
                                        isDark = isDark,
                                        style = GlassButtonStyle.Regular,
                                        onClick = onRetryPort5555,
                                        modifier = Modifier.weight(1f)
                                    )
                                    GlassPillButton(
                                        text = "开发者选项",
                                        isDark = isDark,
                                        style = GlassButtonStyle.Regular,
                                        onClick = onOpenDevOptions,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        is SSLProtocolException -> {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlassPillButton(
                                    text = "系统无障碍",
                                    isDark = isDark,
                                    style = GlassButtonStyle.Regular,
                                    onClick = onOpenAccessibility,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassPillButton(
                                    text = "重新配对",
                                    isDark = isDark,
                                    style = GlassButtonStyle.Prominent,
                                    onClick = onOpenPairingTutorial,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        else -> Unit
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassPillButton(
                            text = "重试",
                            isDark = isDark,
                            enabled = !isStarting,
                            style = GlassButtonStyle.Prominent,
                            onClick = onRetryClick,
                            modifier = Modifier.weight(1f)
                        )
                        GlassPillButton(
                            text = "复制日志",
                            isDark = isDark,
                            style = GlassButtonStyle.Regular,
                            onClick = onCopyLogClick,
                            modifier = Modifier.weight(1f)
                        )
                        GlassPillButton(
                            text = "关闭",
                            isDark = isDark,
                            style = GlassButtonStyle.Regular,
                            onClick = onCloseClick,
                            modifier = Modifier.weight(0.85f)
                        )
                    }
                } else if (isServiceReady) {
                    Spacer(Modifier.height(10.dp))
                    GlassPillButton(
                        text = "完成并返回主页",
                        isDark = isDark,
                        style = GlassButtonStyle.Prominent,
                        onClick = onCloseClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private class ViewModel(
    context: Context,
    private val root: Boolean,
    private val host: String?,
    private val initialPort: Int
) : androidx.lifecycle.ViewModel() {

    private val appContext = context.applicationContext
    private val sb = StringBuilder()
    private var lastStackTrace: String = ""
    private val _output = MutableLiveData<Resource<StringBuilder>>()

    val isRootMode: Boolean get() = root
    val output = _output as LiveData<Resource<StringBuilder>>

    private var hasAttempted = false
    private var activeJob: Job? = null
    private var currentHost: String? = host
    private var currentPort: Int = initialPort

    companion object {
        private const val BINDER_WAIT_TIMEOUT_MS = 18_000L
    }

    fun isRunningNow(): Boolean = activeJob?.isActive == true

    fun currentEndpointLabel(): String {
        val resolved = AdbPortProber.resolveConnectHost(currentHost)
        return if (currentPort > 0) "$resolved:$currentPort" else resolved
    }

    fun failWithMissingLocalNetworkPermission() {
        if (activeJob?.isActive == true) return
        val endpoint = currentEndpointLabel()
        val err = LocalNetworkGate.LocalNetworkPermissionException(
            "本地网络权限未授予，已跳过本次连接（请授予权限后点击重试）"
        )
        sb.clear()
        sb.appendLine("[拦截] 本地网络权限 (ACCESS_LOCAL_NETWORK) 未授予，已跳过无线调试连接 ($endpoint) 以避免系统弹出设备选择器。")
        lastStackTrace = ""
        StartupCoordinator.markWaitingForPermission("StarterActivity.permissionDenied ($endpoint)")
        postResult(err)
    }

    fun start(forceRetry: Boolean = false, overridePort: Int? = null, overrideHost: String? = null) {
        // 幂等防重：已有任务执行中时严禁叠加启动任务
        if (activeJob?.isActive == true) return
        if (!forceRetry && hasAttempted) return
        hasAttempted = true
        if (overridePort != null) {
            currentPort = overridePort
            // 重试指定端口（如 5555）时默认走 127.0.0.1 回环地址
            currentHost = overrideHost ?: "127.0.0.1"
        } else if (overrideHost != null) {
            currentHost = overrideHost
        }

        val modeLabel = if (root) "Root" else "ADB"
        StartupCoordinator.tryBeginStart(
            mode = modeLabel,
            userInitiated = true,
            caller = "StarterActivity"
        )

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            sb.clear()
            lastStackTrace = ""
            if (forceRetry) {
                appendOutput("Retrying starter ($modeLabel)...")
            }
            try {
                if (root) {
                    startRoot()
                } else {
                    val targetHost = AdbPortProber.resolveConnectHost(currentHost)
                    AdbStarter.startAdb(appContext, targetHost, currentPort) {
                        appendOutput(it)
                    }
                }
                val binderConnected = Starter.waitForBinder(timeoutMs = BINDER_WAIT_TIMEOUT_MS) {
                    appendOutput(it)
                }
                if (!binderConnected) {
                    throw StarterBinderTimeoutException(
                        "等待服务响应超时（${BINDER_WAIT_TIMEOUT_MS / 1000}s 内未收到 Shizuku Binder）。"
                    )
                }
                StartupCoordinator.onStartSucceeded(modeLabel)
                if (root) {
                    ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ROOT)
                    ShizukuSettings.setLastSettledStateRunning(true)
                    Shell.cmd(AdbStarter.buildSelfHardeningCommand(appContext)).submit()
                    if (ShizukuSettings.getWatchdog()) {
                        runCatching { WatchdogService.start(appContext) }
                    }
                    if (ShizukuSettings.getPreferences().getBoolean(ShizukuSettings.KEEP_START_ON_BOOT, false)) {
                        RootBootScriptHelper.installBootScript()
                    }
                }
            } catch (e: Throwable) {
                if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
                }
                ShizukuStateMachine.update()
                val reason = when (e) {
                    is NotRootedException -> StartupCoordinator.FailureReason.ROOT_UNAVAILABLE
                    is StarterBinderTimeoutException -> StartupCoordinator.FailureReason.BINDER_TIMEOUT
                    else -> StartupCoordinator.classifyThrowable(e)
                }
                StartupCoordinator.onStartFailed(
                    reason = reason,
                    detail = e.localizedMessage ?: reason.label,
                    userInitiated = true,
                    caller = "StarterActivity"
                )
                lastStackTrace = Log.getStackTraceString(e)
                sb.appendLine()
                sb.appendLine("[失败] ${e.localizedMessage ?: e.javaClass.simpleName}")
                postResult(e)
            }
        }
    }

    fun buildCopyableLog(): String {
        return buildString {
            appendLine("--- Starter 终端输出 ---")
            appendLine(sb.toString().trim().ifBlank { "(空)" })
            if (lastStackTrace.isNotBlank()) {
                appendLine()
                appendLine("--- 异常堆栈 ---")
                appendLine(lastStackTrace.trim())
            }
            appendLine()
            append(StartupCoordinator.buildDiagnosticReport(appContext))
        }
    }

    fun appendOutput(line: String) {
        synchronized(sb) {
            sb.appendLine(line)
        }
        postResult()
    }

    private fun postResult(throwable: Throwable? = null) {
        if (throwable == null)
            _output.postValue(Resource.success(sb))
        else
            _output.postValue(Resource.error(throwable, sb))
    }

    private suspend fun startRoot() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        appendOutput("Starting with root...")

        return withContext(Dispatchers.IO) {
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                appendOutput("Can't open root shell, try again...")

                if (!Shell.getShell().isRoot) {
                    appendOutput("Still not :(")
                    throw NotRootedException()
                }
            }

            suspendCancellableCoroutine { cont ->
                Shell.cmd(Starter.internalCommand).to(object : CallbackList<String?>() {
                    override fun onAddElement(s: String?) {
                        s?.let { appendOutput(it) }
                    }
                }).submit {
                    if (it.isSuccess) {
                        ShizukuStateMachine.update()
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(Exception("Command exited with non-zero value"))
                    }
                }
            }
        }
    }
}
