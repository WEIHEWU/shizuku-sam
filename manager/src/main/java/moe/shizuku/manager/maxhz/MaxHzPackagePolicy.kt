package moe.shizuku.manager.maxhz

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.Build

object MaxHzPackagePolicy {

    enum class AppTrustClass {
        USER_APP,
        SYSTEM_APP,
        SUSPECTED_PRELOAD,
        CORE_SERVICE
    }

    fun isUserInstalledApp(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            return false
        }
        return isUserInstalledApp(context, info)
    }

    fun trustClass(context: Context, packageName: String): AppTrustClass {
        if (packageName == context.packageName) return AppTrustClass.CORE_SERVICE
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            return if (isSystemLikePackageName(packageName)) AppTrustClass.SUSPECTED_PRELOAD else AppTrustClass.USER_APP
        }
        return trustClass(context, info)
    }

    fun trustClass(context: Context, info: ApplicationInfo): AppTrustClass {
        val packageName = info.packageName ?: return AppTrustClass.SUSPECTED_PRELOAD
        if (packageName == context.packageName) return AppTrustClass.CORE_SERVICE
        if (isCoreServicePackage(packageName)) return AppTrustClass.CORE_SERVICE
        if (isSystemPath(info.sourceDir) || isSystemPath(info.publicSourceDir)) return AppTrustClass.SYSTEM_APP
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if (info.flags and systemFlags != 0) return AppTrustClass.SYSTEM_APP
        if (info.uid < Process.FIRST_APPLICATION_UID) return AppTrustClass.CORE_SERVICE
        if (isSystemLikePackageName(packageName)) return AppTrustClass.SUSPECTED_PRELOAD
        return AppTrustClass.USER_APP
    }

    fun isRefreshRateTargetApp(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            return false
        }
        return isRefreshRateTargetApp(context, info)
    }

    fun isRefreshRateTargetApp(context: Context, info: ApplicationInfo): Boolean {
        val packageName = info.packageName ?: return false
        if (isGameApp(context, info)) return false
        if (isUserInstalledApp(context, info)) return true
        if (packageName == context.packageName) return false
        if (info.uid < Process.FIRST_APPLICATION_UID) return false
        if (isPlatformOrVendorPackage(packageName)) return false
        return isCuratedUserFacingPreload(packageName)
    }

    fun isGameApp(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            return false
        }
        return isGameApp(context, info)
    }

    fun isGameApp(context: Context, info: ApplicationInfo): Boolean {
        if (info.packageName == context.packageName) return false
        if (info.category == ApplicationInfo.CATEGORY_GAME) return true
        if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
        val pkg = info.packageName ?: return false
        if (pkg.startsWith("com.tencent.tmgp.") ||
            pkg.startsWith("com.tencent.ig") ||
            pkg.startsWith("com.miHoYo.") ||
            pkg.startsWith("com.hypergryph.") ||
            pkg.startsWith("com.epicgames.") ||
            pkg.startsWith("com.ea.gp.") ||
            pkg.startsWith("com.activision.") ||
            pkg.startsWith("com.riotgames.") ||
            pkg.startsWith("com.netease.") ||
            pkg.startsWith("com.gameloft.") ||
            pkg.contains(".game.") ||
            pkg.endsWith(".game") ||
            pkg.contains(".kurogame")) {
            return true
        }
        return false
    }

    fun isUserInstalledApp(context: Context, info: ApplicationInfo): Boolean {
        return trustClass(context, info) == AppTrustClass.USER_APP
    }

    fun isSystemRelated(context: Context, packageName: String): Boolean {
        return trustClass(context, packageName) != AppTrustClass.USER_APP
    }

    private fun isPlatformOrVendorPackage(packageName: String): Boolean {
        if (packageName == "android") return true
        if (packageName.startsWith("com.android.")) return true
        if (packageName.startsWith("com.samsung.")) return true
        if (packageName.startsWith("com.sec.")) return true
        if (packageName.startsWith("com.sem.")) return true
        if (packageName.startsWith("com.osp.")) return true
        if (packageName.startsWith("com.skms.")) return true
        if (packageName.startsWith("com.wssyncmldm")) return true

        return packageName in setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.google.android.configupdater",
            "com.google.android.feedback",
            "com.google.android.syncadapters.calendar",
            "com.google.android.tts",
            "com.google.android.webview",
            "com.android.vending"
        )
    }

    private fun isSystemPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return path.startsWith("/system/") ||
            path.startsWith("/system_ext/") ||
            path.startsWith("/product/") ||
            path.startsWith("/vendor/") ||
            path.startsWith("/odm/") ||
            path.startsWith("/oem/") ||
            path.startsWith("/apex/")
    }

    private fun isSystemLikePackageName(packageName: String): Boolean {
        return packageName == "android" ||
            packageName.startsWith("com.android.") ||
            packageName.startsWith("com.samsung.") ||
            packageName.startsWith("com.sec.") ||
            packageName.startsWith("com.sem.") ||
            packageName.startsWith("com.osp.") ||
            packageName.startsWith("com.skms.") ||
            packageName.startsWith("com.wssyncmldm") ||
            packageName.startsWith("com.google.android.")
    }

    private fun isCoreServicePackage(packageName: String): Boolean {
        return packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.android.phone" ||
            packageName == "com.android.server.telecom" ||
            packageName == "com.android.providers.settings" ||
            packageName == "com.android.providers.telephony" ||
            packageName == "com.android.providers.media" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName == "com.android.packageinstaller" ||
            packageName == "com.android.settings" ||
            packageName == "com.android.vending" ||
            packageName == "com.google.android.gms" ||
            packageName == "com.google.android.gsf" ||
            packageName == "com.google.android.gsf.login" ||
            packageName == "com.samsung.android.app.telephonyui" ||
            packageName == "com.samsung.android.incallui" ||
            packageName == "com.samsung.android.messaging" ||
            packageName == "com.samsung.android.dialer" ||
            packageName == "com.samsung.android.sm" ||
            packageName == "com.samsung.android.samsungaccount" ||
            packageName == "com.samsung.android.oneconnect" ||
            packageName == "com.samsung.android.sm.devicesecurity" ||
            packageName == "com.sec.phone" ||
            packageName == "com.sec.android.app.launcher" ||
            packageName == "com.sec.android.app.shealth"
    }

    private fun isCuratedUserFacingPreload(packageName: String): Boolean {
        return packageName in setOf(
            // Video / streaming
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.netflix.mediaclient",
            "com.disney.disneyplus",
            "com.amazon.avod.thirdpartyclient",
            "com.hulu.plus",
            "tv.twitch.android.app",
            "com.bilibili.app.in",
            "tv.danmaku.bili",

            // Music / audio
            "com.spotify.music",
            "com.soundcloud.android",
            "com.pandora.android",
            "com.google.android.apps.podcasts",

            // Reading / documents
            "com.amazon.kindle",
            "com.google.android.apps.books",
            "com.adobe.reader",
            "com.google.android.apps.docs",

            // Browser / maps / visible daily apps
            "com.google.android.apps.maps",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.photos",
            "com.microsoft.office.outlook",
            "com.google.android.gm"
        )
    }
}
