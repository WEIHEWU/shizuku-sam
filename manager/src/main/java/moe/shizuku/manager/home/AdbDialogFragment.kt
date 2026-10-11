package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPortProber
import moe.shizuku.manager.databinding.AdbDialogBinding
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.HotspotHelper

@RequiresApi(Build.VERSION_CODES.R)
class AdbDialogFragment : DialogFragment() {

    private lateinit var binding: AdbDialogBinding
    private lateinit var adbMdns: AdbMdns
    private val endpoint = MutableLiveData<Pair<String, Int>>()
    private var loopbackJob: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = AdbDialogBinding.inflate(layoutInflater)
        adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) {
            endpoint.postValue(it)
        }

        val builder = MaterialAlertDialogBuilder(context).apply {
            setTitle(R.string.dialog_adb_discovery)
            setView(binding.root)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(R.string.development_settings, null)

            // Samsung Specific: Launch in Pop-up mode
            if (EnvironmentUtils.isSamsung()) {
                setNeutralButton(R.string.adb_dialog_popup_settings) { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                        putExtra("android.intent.extra.WINDOW_MODE", 5) // WINDOW_MODE_FREEFORM
                        putExtra("com.samsung.android.intent.extra.LAUNCH_MODE", 4)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        loopbackJob?.cancel()
        adbMdns.stop()
    }

    private fun onDialogShow(dialog: AlertDialog) {
        adbMdns.start()
        val context = dialog.context

        val isWifi = HotspotHelper.isWifiConnected(context)
        if (!isWifi) {
            binding.tvSearchStatus.text = "正在搜索无线调试服务...\n请在系统开发者选项中手动开启「无线调试」或连接外部 Wi-Fi。"
        }

        // 持续轮询本地回环接口（127.0.0.1）候选端口（5555、lastPort、tlsPort）
        // 对齐 ShizukuPlus：不设提前超时，只要发现端口立即秒连
        loopbackJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val candidatePort = AdbPortProber.findActiveLoopbackPort(context, timeoutMs = 150)
                if (candidatePort > 0) {
                    withContext(Dispatchers.Main) {
                        if (isAdded && !isStateSaved) {
                            startAndDismiss("127.0.0.1", candidatePort)
                        }
                    }
                    break
                }
                delay(1000)
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            }
            try {
                it.context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
            }
        }

        endpoint.observe(this) { (host, port) ->
            if (host.isBlank() || port > 65535 || port < 1) return@observe
            endpoint.removeObservers(this)
            loopbackJob?.cancel()
            startAndDismiss(host, port)
        }
    }

    private fun startAndDismiss(host: String, port: Int) {
        loopbackJob?.cancel()
        adbMdns.stop()
        ShizukuSettings.setLastPort(port)
        val intent = Intent(context, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
            putExtra(StarterActivity.EXTRA_HOST, host)
            putExtra(StarterActivity.EXTRA_PORT, port)
        }
        requireContext().startActivity(intent)

        dismissAllowingStateLoss()
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }
}
