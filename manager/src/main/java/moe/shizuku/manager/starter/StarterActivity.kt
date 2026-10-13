package moe.shizuku.manager.starter

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.home.GroupHeaderWithToggle
import moe.shizuku.manager.home.MiuixRowIcon
import moe.shizuku.manager.home.MiuixSubPageTopBar
import moe.shizuku.manager.home.onLongPressToggle
import moe.shizuku.manager.maxhz.GlassAppTheme
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.AccessibilityHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.RootBootScriptHelper
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import java.net.ConnectException
import java.net.SocketException
import javax.net.ssl.SSLProtocolException

private class NotRootedException : Exception()

class StarterActivity : AppActivity() {

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
    private val errorState = mutableStateOf<Throwable?>(null)
    private var serviceReadyHandled = false

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
        val current = viewModel.output.value?.data?.toString() ?: ""
        if (!current.contains("Service started, this window will be automatically closed")) {
            viewModel.appendOutput("Service started, this window will be automatically closed in 3 seconds")
        }
        window?.decorView?.postDelayed({
            if (!isFinishing) finish()
        }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        Shizuku.addBinderReceivedListener(binderListener)
        if (Shizuku.pingBinder()) {
            onServiceReady()
        }

        var waitingForServiceShown = false
        viewModel.output.observe(this) {
            val output = it.data?.toString()?.trim() ?: return@observe
            outputTextState.value = output
            if (!waitingForServiceShown && output.contains("info: shizuku_starter exit with 0")) {
                waitingForServiceShown = true
                if (!output.contains("Waiting for service...")) {
                    viewModel.appendOutput("\nWaiting for service...")
                }

                if (Shizuku.pingBinder()) {
                    onServiceReady()
                    return@observe
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    var bound = false
                    for (i in 1..60) {
                        delay(250)
                        if (Shizuku.pingBinder()) {
                            bound = true
                            withContext(Dispatchers.Main) {
                                onServiceReady()
                            }
                            break
                        }
                    }
                    if (!bound && !isFinishing) {
                        withContext(Dispatchers.Main) {
                            viewModel.appendOutput("[提示] 服务进程已在后台拉起，若此窗口未自动关闭，可轻触按钮返回主页。")
                        }
                    }
                }
            } else if (it.status == Status.ERROR) {
                errorState.value = it.error
            }
        }

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isBlackNight = isDark && ThemeHelper.isBlackNightTheme(this)
        val primaryColorInt = runCatching {
            val tv = TypedValue()
            if (theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) tv.data
            else if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
        }.getOrDefault(if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt())

        setContent {
            GlassAppTheme(isDark = isDark) {
                StarterMiuixScreen(
                    isDark = isDark,
                    isBlackNightTheme = isBlackNight,
                    primaryColor = Color(primaryColorInt),
                    outputText = outputTextState.value,
                    isServiceReady = serviceReadyState.value,
                    error = errorState.value,
                    onDismissError = { errorState.value = null },
                    onCloseClick = { finish() },
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
                        val retryIntent = Intent(this, StarterActivity::class.java).apply {
                            putExtra(EXTRA_IS_ROOT, false)
                            putExtra(EXTRA_HOST, "127.0.0.1")
                            putExtra(EXTRA_PORT, 5555)
                        }
                        finish()
                        startActivity(retryIntent)
                    },
                    onOpenPairingTutorial = {
                        startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
                    },
                    onOpenAccessibility = {
                        AccessibilityHelper.openPairingAssistantSettings(this)
                    }
                )
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
    isBlackNightTheme: Boolean,
    primaryColor: Color,
    outputText: String,
    isServiceReady: Boolean,
    error: Throwable?,
    onDismissError: () -> Unit,
    onCloseClick: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onRetryPort5555: () -> Unit,
    onOpenPairingTutorial: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var groupExpanded by remember { mutableStateOf(false) }
    var cardToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSub = groupExpanded xor cardToggled

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
                    title = stringResource(R.string.starter),
                    subtitle = "实时显示 Shizuku_starter 守护进程握手与注入日志，启动成功后窗口将自动关闭",
                    isDark = isDark,
                    primaryColor = primaryColor,
                    expanded = groupExpanded,
                    onToggleExpand = { groupExpanded = !groupExpanded },
                    backSymbol = "✕",
                    onBackClick = onCloseClick
                )
            }

            item(key = "section-terminal") {
                Column {
                    GroupHeaderWithToggle(
                        text = if (isServiceReady) "启动状态：已就绪" else "终端启动输出",
                        isDark = isDark,
                        primaryColor = primaryColor,
                        expanded = groupExpanded,
                        onToggle = { groupExpanded = !groupExpanded }
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onLongPressToggle(haptic) { cardToggled = !cardToggled },
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MiuixRowIcon(
                                    isDark = isDark,
                                    icon = painterResource(R.drawable.ic_terminal_24),
                                    iconTint = if (isServiceReady) Color(0xFF10B981) else primaryColor
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = if (isServiceReady) "Shizuku 服务已成功拉起" else "正在拉起 Shizuku 底层服务...",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    AnimatedVisibility(
                                        visible = showSub,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        Text(
                                            text = "通过本地回环 ADB 或 Root 注入启动 shizuku_starter，绑定成功后自动在 3 秒内关闭窗口。",
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier
                                                .padding(top = 3.dp)
                                                .clickable { cardToggled = !cardToggled }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isDark) Color(0xFF21242C) else Color(0xFFF0F2F6))
                                    .padding(14.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = outputText.ifBlank { "正在初始化启动环境..." },
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            if (isServiceReady) {
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    text = "完成并返回主页",
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    onClick = onCloseClick,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Miuix 窗口级弹窗：启动异常处理
    val showErrorDialog = error != null
    val dialogTitle = when (error) {
        is ConnectException -> "本地 ADB 端口未连接"
        is SSLProtocolException -> "配对密钥已失效"
        is NotRootedException -> "Root 启动失败"
        is AdbKeyException -> "ADB 秘钥异常"
        else -> "启动异常"
    }
    val dialogMessage = when (error) {
        is ConnectException -> "未能连接到 127.0.0.1 端口。\n\n免 Wi-Fi 离线启动指南：\n1. 确保在系统开发者选项中已开启「USB 调试」；\n2. 若手机刚重启，建议在开发者选项中将「USB 调试」重新开关一次；\n3. 或连接 Wi-Fi / 开启个人热点后重试。"
        is SSLProtocolException -> "无线调试已被重置或配对信息已过期，需要重新配对设备。"
        is NotRootedException -> stringResource(R.string.start_with_root_failed)
        is AdbKeyException -> stringResource(R.string.adb_error_key_store)
        else -> error?.localizedMessage ?: stringResource(R.string.cannot_connect_port)
    }

    WindowDialog(
        show = showErrorDialog,
        title = dialogTitle,
        onDismissRequest = onDismissError
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = dialogMessage,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            when (error) {
                is ConnectException -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = stringResource(android.R.string.cancel),
                            onClick = onDismissError,
                            modifier = Modifier.weight(0.8f)
                        )
                        TextButton(
                            text = "重试 5555",
                            onClick = {
                                onDismissError()
                                onRetryPort5555()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "开发者选项",
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = {
                                onDismissError()
                                onOpenDevOptions()
                            },
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                }
                is SSLProtocolException -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = stringResource(android.R.string.cancel),
                            onClick = onDismissError,
                            modifier = Modifier.weight(0.8f)
                        )
                        TextButton(
                            text = "系统无障碍",
                            onClick = {
                                onDismissError()
                                onOpenAccessibility()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "重新配对",
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = {
                                onDismissError()
                                onOpenPairingTutorial()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                else -> {
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = onDismissError,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private class ViewModel(context: Context, root: Boolean, host: String?, port: Int) : androidx.lifecycle.ViewModel() {

    private val appContext = context.applicationContext
    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<StringBuilder>>()

    val output = _output as LiveData<Resource<StringBuilder>>

    init {
        try {
            if (root) {
                startRoot()
            } else {
                startAdb(host ?: "127.0.0.1", port)
            }
        } catch (e: Throwable) {
            postResult(e)
        }
    }

    fun appendOutput(line: String) {
        sb.appendLine(line)
        postResult()
    }

    private fun postResult(throwable: Throwable? = null) {
        if (throwable == null)
            _output.postValue(Resource.success(sb))
        else
            _output.postValue(Resource.error(throwable, sb))
    }

    private fun startRoot() {
        sb.append("Starting with root...").append('\n').append('\n')
        postResult()

        viewModelScope.launch(Dispatchers.IO) {
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                sb.append('\n').append("Can't open root shell, try again...").append('\n')

                postResult()
                if (!Shell.getShell().isRoot) {
                    sb.append('\n').append("Still not :(").append('\n')
                    postResult(NotRootedException())
                    return@launch
                }
            }

            Shell.cmd(Starter.internalCommand).to(object : CallbackList<String?>() {
                override fun onAddElement(s: String?) {
                    sb.append(s).append('\n')
                    postResult()
                }
            }).submit {
                if (it.code != 0) {
                    sb.append('\n').append("Send this to developer may help solve the problem.")
                    postResult()
                } else {
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
            }
        }
    }

    private fun startAdb(host: String, port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AdbStarter.startAdb(appContext, port) {
                    sb.append(it)
                    postResult()
                }
                Starter.waitForBinder {
                    sb.append(it)
                    postResult()
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                sb.append('\n').append(Log.getStackTraceString(e))
                postResult(e)
            }
        }
    }
}
