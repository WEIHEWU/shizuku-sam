package moe.shizuku.manager.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import moe.shizuku.manager.R

class DhizukuAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DhizukuAdminReceiver"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, DhizukuAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Dhizuku Device Admin / Owner enabled")

        // Auto self-grant WRITE_SECURE_SETTINGS when activated as Device Owner
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = getComponentName(context)
            if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        context.packageName,
                        android.Manifest.permission.WRITE_SETTINGS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (_: Throwable) {}
                try {
                    dpm.setPermittedAccessibilityServices(admin, null)
                } catch (_: Throwable) {}
                Log.i(TAG, "Successfully self-granted WRITE_SECURE_SETTINGS & WRITE_SETTINGS")

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    try {
                        dpm.setPermissionGrantState(
                            admin,
                            context.packageName,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        )
                        Log.i(TAG, "Successfully self-granted POST_NOTIFICATIONS")
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to self-grant permissions", t)
        }

        // Unlock restricted settings if shell available
        Thread {
            try {
                if (moe.shizuku.manager.maxhz.MaxHzShell.isReady()) {
                    moe.shizuku.manager.maxhz.MaxHzShell.exec("appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
                }
            } catch (_: Throwable) {}
        }.start()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Dhizuku Device Admin / Owner disabled")
    }
}
