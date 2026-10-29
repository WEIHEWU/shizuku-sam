package moe.shizuku.manager.authorization

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Parcel
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.application
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ServerConstants
import java.util.*

object AuthorizationManager {

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int): List<PackageInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(userId)
            val binder = Shizuku.getBinder()
                ?: throw IllegalStateException("Shizuku binder not available")
            try {
                binder.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                throw RuntimeException(e)
            }
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as? ParcelableListSlice<PackageInfo>)?.list
                ?: emptyList()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun isShizukuClientPackage(pi: PackageInfo): Boolean {
        if (BuildConfig.APPLICATION_ID == pi.packageName) return false
        val ai = pi.applicationInfo ?: return false
        val requestsPermission = pi.requestedPermissions?.contains(Manifest.permission.API_V23) == true
        val isV3Support = ai.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") == true
        return requestsPermission || isV3Support
    }

    fun getPackages(): List<PackageInfo> {
        val seenKeys = HashSet<String>()
        val packages: MutableList<PackageInfo> = ArrayList()

        fun addUnique(pi: PackageInfo) {
            val uid = pi.applicationInfo?.uid ?: return
            if (seenKeys.add("${pi.packageName}_$uid")) {
                packages.add(pi)
            }
        }

        try {
            if (Shizuku.isPreV11() || (Shizuku.getVersion() == 11 && Shizuku.getServerPatchVersion() < 3)) {
                for (user in ShizukuSystemApis.getUsers(useCache = false)) {
                    try {
                        for (pi in ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id)) {
                            if (isShizukuClientPackage(pi)) addUnique(pi)
                        }
                    } catch (e: Throwable) {
                        LOGGER.w(e, "getInstalledPackages")
                    }
                }
            } else {
                for (pi in getApplications(-1)) {
                    if (BuildConfig.APPLICATION_ID != pi.packageName && pi.applicationInfo != null) {
                        addUnique(pi)
                    }
                }
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "getPackages from server failed, falling back to local PackageManager")
        }

        // 纯内存级本地 PackageManager 兜底合并（不发起任何循环内 Binder IPC，耗时 <5ms）：
        // 兼容驻留内存的旧版 v13.7 服务端进程及 One UI 8/9 (Android 16/17) 隐藏 API 限制
        try {
            val pm = application.packageManager
            val localInstalled = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
            for (pi in localInstalled) {
                if (isShizukuClientPackage(pi)) {
                    addUnique(pi)
                }
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "Local PackageManager scan failed")
        }

        return packages
    }

    fun granted(packageName: String, uid: Int): Boolean {
        return try {
            // 对齐上游 Shizuku Plus (#398)：进程隔夜从休眠/墓碑唤醒瞬间 Binder 可能短暂未就绪，
            // 最多重试 3 次，防止 granted() 误返 false 导致已授权数量被清零
            var binderAlive = Shizuku.pingBinder()
            if (!binderAlive) {
                repeat(3) {
                    if (!binderAlive) {
                        Thread.sleep(150)
                        binderAlive = Shizuku.pingBinder()
                    }
                }
            }
            if (!binderAlive) return false
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, packageName, uid / 100000) == PackageManager.PERMISSION_GRANTED
            } else {
                (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun grant(packageName: String, uid: Int) {
        try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } else {
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    fun revoke(packageName: String, uid: Int) {
        try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } else {
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Throwable) {
        }
    }
}
