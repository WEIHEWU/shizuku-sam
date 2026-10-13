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
            try {
                Shizuku.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                LOGGER.w(e, "transact getApplications failed")
                return emptyList()
            }
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as? ParcelableListSlice<PackageInfo>)?.list ?: emptyList()
        } catch (e: Throwable) {
            LOGGER.w(e, "getApplications parse failed")
            emptyList()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getPackages(): List<PackageInfo> {
        val packages: MutableList<PackageInfo> = ArrayList()
        if (Shizuku.isPreV11() || (Shizuku.getVersion() == 11 && Shizuku.getServerPatchVersion() < 3)) {
            val allPackages: MutableList<PackageInfo> = ArrayList()
            for (user in ShizukuSystemApis.getUsers(useCache = false)) {
                try {
                    allPackages.addAll(ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id))
                } catch (e: Throwable) {
                    LOGGER.w(e, "getInstalledPackages")
                }
            }
            for (pi in allPackages) {
                if (BuildConfig.APPLICATION_ID == pi.packageName) continue
                if (pi.applicationInfo?.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") != true) continue
                if (pi.requestedPermissions?.contains(Manifest.permission.API_V23) != true) continue

                packages.add(pi)
            }
        } else {
            // 优先通过上游标准 Binder IPC 查询服务端已缓存应用
            try {
                packages.addAll(getApplications(-1))
            } catch (e: Throwable) {
                LOGGER.w(e, "getApplications from server failed")
            }
        }

        // 针对 Android 15/16/17 (One UI 9) 极端大数量应用环境下的本地兜底容灾：
        // 在三四百个应用的现代旗舰机上，服务端进程直接走底层的 GET_META_DATA | GET_PERMISSIONS 反射
        // 极易因底层接口重构或 Binder 1MB 溢出返回空。此时利用管理端自身具备的 QUERY_ALL_PACKAGES 权限直接通过本地 PMS 补全
        if (packages.isEmpty() && Shizuku.pingBinder()) {
            try {
                val pm = application.packageManager
                val allInstalled = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
                for (pi in allInstalled) {
                    if (BuildConfig.APPLICATION_ID == pi.packageName) continue
                    val isV3Support = pi.applicationInfo?.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") == true
                    val requestsPermission = pi.requestedPermissions?.contains(Manifest.permission.API_V23) == true
                    val isGranted = runCatching { granted(pi.packageName, pi.applicationInfo?.uid ?: 0) }.getOrDefault(false)
                    if (isGranted || (isV3Support && requestsPermission)) {
                        packages.add(pi)
                    }
                }
            } catch (e: Throwable) {
                LOGGER.w(e, "Local PMS scan failed, trying ShizukuSystemApis fallback")
                try {
                    for (user in ShizukuSystemApis.getUsers(useCache = false)) {
                        val installed = ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id)
                        for (pi in installed) {
                            if (BuildConfig.APPLICATION_ID == pi.packageName) continue
                            val isV3 = pi.applicationInfo?.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") == true
                            val reqPerm = pi.requestedPermissions?.contains(Manifest.permission.API_V23) == true
                            val isGranted = runCatching { granted(pi.packageName, pi.applicationInfo?.uid ?: 0) }.getOrDefault(false)
                            if (isGranted || (isV3 && reqPerm)) {
                                packages.add(pi)
                            }
                        }
                    }
                } catch (tr: Throwable) {
                    LOGGER.w(tr, "ShizukuSystemApis fallback failed")
                }
            }
        }
        return packages
    }

    fun granted(packageName: String, uid: Int): Boolean {
        return if (Shizuku.isPreV11()) {
            ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, packageName, uid / 100000) == PackageManager.PERMISSION_GRANTED
        } else {
            val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
            if ((flags and FLAG_ALLOWED) == FLAG_ALLOWED) {
                true
            } else {
                // 双向校验：若服务端内存位因重启短暂断开，直接回退系统标准运行时权限校验
                try {
                    val pm = application.packageManager
                    pm.checkPermission(Manifest.permission.API_V23, packageName) == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    fun grant(packageName: String, uid: Int) {
        if (Shizuku.isPreV11()) {
            ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
        } else {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
            try {
                ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } catch (_: Throwable) {}
        }
    }

    fun revoke(packageName: String, uid: Int) {
        if (Shizuku.isPreV11()) {
            ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
        } else {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
            try {
                ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } catch (_: Throwable) {}
        }
    }
}
