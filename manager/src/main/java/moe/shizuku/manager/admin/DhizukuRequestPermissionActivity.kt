package moe.shizuku.manager.admin

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log

class DhizukuRequestPermissionActivity : Activity() {

    companion object {
        private const val TAG = "DhizukuReqPerm"
        private const val EXTRA_CLIENT_UID = "uid"
        private const val EXTRA_CLIENT_BINDER = "request_permission_binder"
        private const val LISTENER_DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val bundle = intent.extras
            val listenerBinder: IBinder? = bundle?.getBinder(EXTRA_CLIENT_BINDER)

            if (listenerBinder != null && listenerBinder.isBinderAlive) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(LISTENER_DESCRIPTOR)
                    data.writeInt(PackageManager.PERMISSION_GRANTED)
                    listenerBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, IBinder.FLAG_ONEWAY)
                    Log.i(TAG, "Granted Dhizuku permission to calling client")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to dispatch permission grant to client", t)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in DhizukuRequestPermissionActivity", t)
        }

        finish()
    }
}
