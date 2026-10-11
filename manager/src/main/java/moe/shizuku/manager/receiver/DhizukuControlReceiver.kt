package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.util.Log
import android.widget.Toast
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.DhizukuHelper

class DhizukuControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DhizukuControlReceiver"
        const val ACTION_REMOVE_DEVICE_OWNER = "moe.shizuku.manager.action.REMOVE_DEVICE_OWNER"
        const val ACTION_REMOVE_DEVICE_ADMIN = "moe.shizuku.manager.action.REMOVE_DEVICE_ADMIN"
        const val ACTION_DEACTIVATE_OWNER = "moe.shizuku.manager.action.DEACTIVATE_OWNER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val callingUid = Binder.getCallingUid()
        // Allow ADB shell (UID 2000), Root (UID 0), or self app process
        if (callingUid != 0 && callingUid != 2000 && callingUid != Process.myUid()) {
            Log.w(TAG, "Rejected unauthorized broadcast from UID: $callingUid")
            return
        }

        when (intent.action) {
            ACTION_REMOVE_DEVICE_OWNER, ACTION_REMOVE_DEVICE_ADMIN, ACTION_DEACTIVATE_OWNER -> {
                Log.i(TAG, "Removing Device Owner / Admin via broadcast request from UID $callingUid")
                val success = DhizukuHelper.removeDeviceOwner(context)
                if (success) {
                    Toast.makeText(context, "设备所有者与管理员已彻底停用 / Deactivated successfully", Toast.LENGTH_LONG).show()
                    Log.i(TAG, "Device Owner and Admin successfully removed via broadcast")
                } else {
                    Toast.makeText(context, "停用已处理 / Processed deactivation", Toast.LENGTH_LONG).show()
                    Log.i(TAG, "Device Owner deactivation processed")
                }
            }
        }
    }
}
