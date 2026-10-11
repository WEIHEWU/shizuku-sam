package moe.shizuku.manager.starter

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.StarterActivityBinding
import android.content.Intent
import android.provider.Settings
import moe.shizuku.manager.utils.AccessibilityHelper
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.maxhz.LiquidGlassTheme
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.HotspotHelper
import moe.shizuku.manager.utils.RootBootScriptHelper
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import java.net.ConnectException
import java.net.SocketException
import javax.net.ssl.SSLProtocolException

private class NotRootedException : Exception()

class StarterActivity : AppBarActivity() {

    private val viewModel by viewModels {
        ViewModel(
            this,
            intent.getBooleanExtra(EXTRA_IS_ROOT, true),
            intent.getStringExtra(EXTRA_HOST),
            intent.getIntExtra(EXTRA_PORT, 0)
        )
    }

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

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)

        val binding = StarterActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 玻璃页面背景，与主页 / 设置页保持一致
        LiquidGlassTheme.attachGlassPageBackground(this)
        Shizuku.addBinderReceivedListener(binderListener)
        if (Shizuku.pingBinder()) {
            onServiceReady()
        }

        binding.root.setOnClickListener {
            if (Shizuku.pingBinder() && !isFinishing) {
                finish()
            }
        }

        var waitingForServiceShown = false
        viewModel.output.observe(this) {
            val output = it.data?.toString()?.trim() ?: return@observe
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
                            viewModel.appendOutput("[提示] 服务进程已在后台拉起，若此窗口未自动关闭，可轻触屏幕返回主页。")
                        }
                    }
                }
            } else if (it.status == Status.ERROR) {
                when (it.error) {
                    is ConnectException -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("本地 ADB 端口未连接")
                            .setMessage("未能连接到 127.0.0.1 端口。\n\n免 Wi-Fi 离线启动指南：\n1. 确保在系统开发者选项中已开启「USB 调试」；\n2. 若手机刚重启，建议在开发者选项中将「USB 调试」重新开关一次；\n3. 或连接 Wi-Fi / 开启个人热点后重试。")
                            .setPositiveButton("前往开发者选项") { _, _ ->
                                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                                    if (EnvironmentUtils.isSamsung()) {
                                        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                                        putExtra("android.intent.extra.WINDOW_MODE", 5)
                                        putExtra("com.samsung.android.intent.extra.LAUNCH_MODE", 4)
                                    }
                                }
                                runCatching { startActivity(intent) }
                            }
                            .setNeutralButton("重试直连 5555") { _, _ ->
                                val intent = Intent(this, StarterActivity::class.java).apply {
                                    putExtra(EXTRA_IS_ROOT, false)
                                    putExtra(EXTRA_HOST, "127.0.0.1")
                                    putExtra(EXTRA_PORT, 5555)
                                }
                                finish()
                                startActivity(intent)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    is SSLProtocolException -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("配对密钥已失效")
                            .setMessage("无线调试已被重置或配对信息已过期，需要重新配对设备。")
                            .setPositiveButton("重新配对") { _, _ ->
                                startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
                            }
                            .setNeutralButton("系统无障碍") { _, _ ->
                                AccessibilityHelper.openPairingAssistantSettings(this)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    is NotRootedException -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Root 启动失败")
                            .setMessage(R.string.start_with_root_failed)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    is AdbKeyException -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("ADB 秘钥异常")
                            .setMessage(R.string.adb_error_key_store)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    else -> {
                        val msg = it.error?.localizedMessage ?: getString(R.string.cannot_connect_port)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("启动异常")
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            binding.text1.text = output
        }
    }

    companion object {

        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_HOST = "$EXTRA.HOST"
        const val EXTRA_PORT = "$EXTRA.PORT"
    }
}

private class ViewModel(private val context: Context, root: Boolean, host: String?, port: Int) : androidx.lifecycle.ViewModel() {

    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<StringBuilder>>()

    val output = _output as LiveData<Resource<StringBuilder>>

    init {
        try {
            if (root) {
                startRoot()
            } else {
                startAdb(host!!, port)
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
                    if (ShizukuSettings.getPreferences().getBoolean(ShizukuSettings.KEEP_START_ON_BOOT, false)) {
                        RootBootScriptHelper.installBootScript()
                    }
                }
            }
        }
    }

    private fun startAdb(host: String, port: Int) {
        sb.append("Starting with wireless adb in port $port...").append('\n')
        postResult()

        viewModelScope.launch(Dispatchers.IO) {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                sb.append('\n').append(Log.getStackTraceString(e))
                postResult(AdbKeyException(e))
                return@launch
            }
            var connected = false
            var lastError: Throwable? = null
            var retryDelay = 400L
            for (attempt in 1..8) {
                try {
                    AdbClient(host, port, key).use { client ->
                        client.connect()
                        connected = true
                        client.shellCommand(Starter.internalCommand) {
                            sb.append(String(it))
                            postResult()
                        }

                        val tcpPort = ShizukuSettings.getTcpPort()
                        if (ShizukuSettings.getTcpMode() && port != tcpPort) {
                            runCatching {
                                client.command("tcpip:$tcpPort")
                            }.onFailure {
                                if (it !is java.io.EOFException && it !is java.net.SocketException) {
                                    Log.w("StarterActivity", "tcpip command error", it)
                                }
                            }
                            ShizukuSettings.setLastPort(tcpPort)
                        } else {
                            ShizukuSettings.setLastPort(port)
                        }

                        ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                    }
                    break
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt < 8 && (e is ConnectException || e is SocketException)) {
                        delay(retryDelay)
                        retryDelay = (retryDelay * 1.4).toLong().coerceAtMost(2500L)
                    } else {
                        break
                    }
                }
            }

            if (!connected && lastError != null) {
                lastError.printStackTrace()
                sb.append('\n').append(Log.getStackTraceString(lastError))
                postResult(lastError)
            }
        }
    }
}
