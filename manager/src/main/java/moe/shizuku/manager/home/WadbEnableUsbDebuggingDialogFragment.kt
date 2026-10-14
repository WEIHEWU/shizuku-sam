package moe.shizuku.manager.home

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.EnvironmentUtils

class WadbEnableUsbDebuggingDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setMessage(R.string.dialog_usb_debugging_not_enabled)
                .setPositiveButton(R.string.development_settings, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    private fun onDialogShow(dialog: AlertDialog) {
        moe.shizuku.manager.utils.DialogThemeHelper.applyUnifiedDialogWindowStyle(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dismissAllowingStateLoss()
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(":settings:fragment_args_key", "enable_adb")
                if (EnvironmentUtils.isSamsung()) {
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    putExtra("android.intent.extra.WINDOW_MODE", 5) // WINDOW_MODE_FREEFORM
                    putExtra("com.samsung.android.intent.extra.LAUNCH_MODE", 4)
                }
            }
            try {
                it.context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }
}
