package moe.shizuku.manager.authorization

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Parcel
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.ShizukuSettings
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

    private val scanLock = Any()

    @Volatile
    private var lastScanTimeMs: Long = 0L

    @Volatile
    private var lastScanPackages: List<PackageInfo> = emptyList()

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

    /**
     * 毫秒级快照秒开查询（耗时 < 2ms）：
     * 划掉后台或强制重启后，直接对已知的客户端包名集合定向调用 getPackageInfo，
     * 避免等待全机 400+ 应用扫描完成，实现首页授权数与「管理已授权应用」弹窗 0 等待秒开。
     */
    fun getQuickPackagesFromSnapshot(): List<PackageInfo> {
        if (lastScanPackages.isNotEmpty()) return lastScanPackages
        val knownNames = runCatching { ShizukuSettings.getKnownClientPackages() }.getOrDefault(emptySet())
        if (knownNames.isEmpty()) return emptyList()
        val pm = runCatching { application.packageManager }.getOrNull() ?: return emptyList()
        val result = ArrayList<PackageInfo>(knownNames.size)
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        for (pkgName in knownNames) {
            if (pkgName.isBlank() || pkgName == BuildConfig.APPLICATION_ID) continue
            val pi = runCatching { pm.getPackageInfo(pkgName, flags) }.getOrNull() ?: continue
            if (isShizukuClientPackage(pi)) {
                result.add(pi)
            }
        }
        return result
    }

    fun getPackages(forceRefresh: Boolean = false): List<PackageInfo> {
        synchronized(scanLock) {
            val now = System.currentTimeMillis()
            if (!forceRefresh && now - lastScanTimeMs < 2000L && lastScanPackages.isNotEmpty()) {
                return lastScanPackages
            }

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

            // 对齐上游：仅当服务端 getApplications(-1) 返回为空（如旧版驻留内存的 v13.7 服务端尚未重启）或异常时，
            // 才触发本地 PackageManager 兜底扫描，绝不在服务端正常返回时重复执行全量扫描！
            if (packages.isEmpty()) {
                try {
                    val pm = application.packageManager
                    val localInstalled = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
                    for (pi in localInstalled) {
                        if (isShizukuClientPackage(pi)) {
                            addUnique(pi)
                        }
                    }
                } catch (e: Throwable) {
                    LOGGER.w(e, "Local PackageManager fallback scan failed")
                }
            }

            if (packages.isNotEmpty()) {
                lastScanPackages = packages
                lastScanTimeMs = System.currentTimeMillis()
                runCatching {
                    val names = HashSet<String>(packages.size)
                    for (pi in packages) {
                        names.add(pi.packageName)
                    }
                    ShizukuSettings.setKnownClientPackages(names)
                }
            }

            return packages
        }
    }

    fun granted(packageName: String, uid: Int): Boolean {
        return try {
            var binderAlive = Shizuku.pingBinder()
            if (!binderAlive && Looper.myLooper() != Looper.getMainLooper()) {
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
