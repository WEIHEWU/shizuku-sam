package moe.shizuku.manager

import android.app.admin.DevicePolicyManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.manager.admin.DhizukuAdminReceiver
import moe.shizuku.manager.utils.DhizukuHelper
import java.io.File
import java.util.concurrent.TimeUnit

class DhizukuProvider : ContentProvider() {

    companion object {
        private const val TAG = "DhizukuProvider"

        private const val PROVIDER_METHOD_CLIENT = "client"
        private const val PROVIDER_METHOD_GET_BINDER = "getBinder"

        private const val PARAM_DHIZUKU_BINDER = "dhizuku_binder"
        private const val PARAM_BINDER = "binder"

        private const val BINDER_DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizuku"
        private const val BINDER_DESCRIPTOR_LEGACY = "com.rosan.dhizuku.IDhizuku"
        private const val REMOTE_BINDER_DESCRIPTOR = "com.rosan.dhizuku.server"

        private const val CODE_GET_VERSION_CODE = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val CODE_GET_VERSION_NAME = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CODE_IS_PERMISSION_GRANTED = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val CODE_REMOTE_BINDER = IBinder.FIRST_CALL_TRANSACTION + 10
        private const val CODE_REMOTE_PROCESS = IBinder.FIRST_CALL_TRANSACTION + 11
        private const val CODE_BIND_USER_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 12
        private const val CODE_UNBIND_USER_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 13
        private const val CODE_UNBIND_USER_SERVICE_BY_CONN = IBinder.FIRST_CALL_TRANSACTION + 14
        private const val CODE_GET_DELEGATED_SCOPES = IBinder.FIRST_CALL_TRANSACTION + 15
        private const val CODE_SET_DELEGATED_SCOPES = IBinder.FIRST_CALL_TRANSACTION + 16

        private const val SERVICE_VERSION_CODE = 7
        private const val SERVICE_VERSION_NAME = "13.7.0"
    }

    private var serviceBinder: DhizukuServiceBinder? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        serviceBinder = DhizukuServiceBinder(ctx)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val binder = serviceBinder ?: DhizukuServiceBinder(context ?: return null).also { serviceBinder = it }
        val bundle = Bundle()
        bundle.putBinder(PARAM_DHIZUKU_BINDER, binder)
        bundle.putBinder(PARAM_BINDER, binder)
        return bundle
    }

    private class DhizukuServiceBinder(private val context: Context) : Binder() {

        init {
            attachInterface(object : android.os.IInterface {
                override fun asBinder(): IBinder = this@DhizukuServiceBinder
            }, BINDER_DESCRIPTOR)
        }

        override fun getInterfaceDescriptor(): String = BINDER_DESCRIPTOR

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                CODE_REMOTE_BINDER -> {
                    // Transact to target IBinder with host privileges
                    try {
                        data.enforceInterface(REMOTE_BINDER_DESCRIPTOR)
                    } catch (_: Throwable) {
                        // Tolerate interface mismatch
                    }
                    val targetBinder = data.readStrongBinder() ?: return false
                    val targetCode = data.readInt()
                    val targetFlags = data.readInt()
                    val targetData = Parcel.obtain()
                    try {
                        targetData.appendFrom(data, data.dataPosition(), data.dataAvail())
                        val identity = clearCallingIdentity()
                        try {
                            targetBinder.transact(targetCode, targetData, reply, targetFlags)
                        } finally {
                            restoreCallingIdentity(identity)
                        }
                    } finally {
                        targetData.recycle()
                    }
                    true
                }

                CODE_GET_VERSION_CODE -> {
                    reply?.writeNoException()
                    reply?.writeInt(SERVICE_VERSION_CODE)
                    true
                }

                CODE_GET_VERSION_NAME -> {
                    reply?.writeNoException()
                    reply?.writeString(SERVICE_VERSION_NAME)
                    true
                }

                CODE_IS_PERMISSION_GRANTED -> {
                    reply?.writeNoException()
                    val isPrivileged = DhizukuHelper.isPrivileged(context)
                    reply?.writeInt(if (isPrivileged) 1 else 0)
                    true
                }

                CODE_GET_DELEGATED_SCOPES -> {
                    val pkg = data.readString() ?: ""
                    reply?.writeNoException()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        val admin = DhizukuAdminReceiver.getComponentName(context)
                        val scopes = dpm?.getDelegatedScopes(admin, pkg) ?: emptyList()
                        reply?.writeStringArray(scopes.toTypedArray())
                    } else {
                        reply?.writeStringArray(emptyArray())
                    }
                    true
                }

                CODE_SET_DELEGATED_SCOPES -> {
                    val pkg = data.readString() ?: ""
                    val scopes = data.createStringArray() ?: emptyArray()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        val admin = DhizukuAdminReceiver.getComponentName(context)
                        val identity = clearCallingIdentity()
                        try {
                            dpm?.setDelegatedScopes(admin, pkg, scopes.toList())
                        } catch (t: Throwable) {
                            Log.w(TAG, "setDelegatedScopes failed", t)
                        } finally {
                            restoreCallingIdentity(identity)
                        }
                    }
                    reply?.writeNoException()
                    true
                }

                CODE_REMOTE_PROCESS -> {
                    try {
                        val cmd = data.createStringArray() ?: emptyArray()
                        val env = data.createStringArray()
                        val dir = data.readString()
                        val pb = ProcessBuilder(*cmd)
                        if (dir != null) pb.directory(File(dir))
                        if (env != null) {
                            val map = pb.environment()
                            for (entry in env) {
                                val eq = entry.indexOf('=')
                                if (eq != -1) map[entry.substring(0, eq)] = entry.substring(eq + 1)
                            }
                        }
                        val proc = pb.start()
                        reply?.writeNoException()
                        reply?.writeStrongBinder(DhizukuRemoteProcessBinder(proc))
                    } catch (t: Exception) {
                        Log.e(TAG, "remoteProcess failed", t)
                        reply?.writeException(t)
                    }
                    true
                }

                CODE_BIND_USER_SERVICE, CODE_UNBIND_USER_SERVICE, CODE_UNBIND_USER_SERVICE_BY_CONN -> {
                    reply?.writeNoException()
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    private class DhizukuRemoteProcessBinder(private val process: Process) : Binder() {

        companion object {
            private const val CODE_GET_OUTPUT_STREAM = IBinder.FIRST_CALL_TRANSACTION + 0
            private const val CODE_GET_INPUT_STREAM = IBinder.FIRST_CALL_TRANSACTION + 1
            private const val CODE_GET_ERROR_STREAM = IBinder.FIRST_CALL_TRANSACTION + 2
            private const val CODE_EXIT_VALUE = IBinder.FIRST_CALL_TRANSACTION + 3
            private const val CODE_DESTROY = IBinder.FIRST_CALL_TRANSACTION + 4
            private const val CODE_ALIVE = IBinder.FIRST_CALL_TRANSACTION + 5
            private const val CODE_WAIT_FOR = IBinder.FIRST_CALL_TRANSACTION + 6
            private const val CODE_WAIT_FOR_TIMEOUT = IBinder.FIRST_CALL_TRANSACTION + 7
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                CODE_EXIT_VALUE -> {
                    reply?.writeNoException()
                    reply?.writeInt(try { process.exitValue() } catch (_: IllegalThreadStateException) { -1 })
                    true
                }
                CODE_DESTROY -> {
                    process.destroy()
                    reply?.writeNoException()
                    true
                }
                CODE_ALIVE -> {
                    reply?.writeNoException()
                    val alive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.isAlive else (try { process.exitValue(); false } catch (_: Exception) { true })
                    reply?.writeInt(if (alive) 1 else 0)
                    true
                }
                CODE_WAIT_FOR -> {
                    val res = process.waitFor()
                    reply?.writeNoException()
                    reply?.writeInt(res)
                    true
                }
                CODE_WAIT_FOR_TIMEOUT -> {
                    val timeout = data.readLong()
                    val unitStr = data.readString() ?: "SECONDS"
                    val unit = try { TimeUnit.valueOf(unitStr) } catch (_: Throwable) { TimeUnit.SECONDS }
                    reply?.writeNoException()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        reply?.writeInt(if (process.waitFor(timeout, unit)) 1 else 0)
                    } else {
                        reply?.writeInt(1)
                    }
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
}
