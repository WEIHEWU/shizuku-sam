package moe.shizuku.manager.maxhz

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
                    PackageManager.ApplicationInfoFlags.of(0)
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
                    PackageManager.ApplicationInfoFlags.of(0)
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
        if (info.uid < Process.FIRST_APPLICATION_UID) return AppTrustClass.CORE_SERVICE

        val isSysFlag = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isSysDir = isSystemPath(info.sourceDir) || isSystemPath(info.publicSourceDir)

        // 凡是没有 FLAG_SYSTEM 且未安装在系统只读分区下的应用，均为用户自行安装的应用
        // （包括用户自装的 Chrome、三星浏览器、Good Lock 插件、Google 应用与全部游戏）
        if (!isSysFlag && !isSysDir) {
            return AppTrustClass.USER_APP
        }

        if (isCoreServicePackage(packageName)) return AppTrustClass.CORE_SERVICE
        return AppTrustClass.SYSTEM_APP
    }

    fun isRefreshRateTargetApp(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        if (packageName == "android" || packageName == "com.android.systemui") return false
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
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
        if (packageName == context.packageName) return false
        if (packageName == "android" || packageName == "com.android.systemui") return false
        // 用户安装的全部应用（含游戏、Google/三星系列用户应用）100% 允许配置与展示
        if (isUserInstalledApp(context, info)) return true
        // 系统应用：排除无可执行代码的纯资源 Overlay 主题包及底层空壳包
        if ((info.flags and ApplicationInfo.FLAG_HAS_CODE) == 0) return false
        if (isHeadlessOverlayPackage(packageName)) return false
        return true
    }

    fun isTransientSystemWindow(context: Context, packageName: String): Boolean {
        if (packageName == "android" || packageName == "com.android.systemui") return true
        if (isUserInstalledApp(context, packageName)) return false
        if (isInteractiveSystemApp(packageName) || isCuratedUserFacingPreload(packageName)) return false
        val launchIntent = runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        return launchIntent == null
    }

    fun getLaunchablePackages(context: Context): Set<String> {
        val result = HashSet<String>()
        val pm = context.packageManager
        val categories = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_INFO)
        for (cat in categories) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val resolved = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            } catch (_: Throwable) {
                emptyList()
            }
            for (ri in resolved) {
                ri.activityInfo?.packageName?.takeIf { it.isNotBlank() }?.let { result.add(it) }
            }
        }
        result.addAll(INTERACTIVE_SYSTEM_PACKAGES)
        return result
    }

    fun isGameApp(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
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
        @Suppress("DEPRECATION")
        if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
        val pkg = info.packageName ?: return false
        if (pkg.startsWith("com.tencent.tmgp.") ||
            pkg.startsWith("com.tencent.ig") ||
            pkg.startsWith("com.tencent.lolm") ||
            pkg.startsWith("com.tencent.jkchess") ||
            pkg.startsWith("com.tencent.KiHan") ||
            pkg.startsWith("com.tencent.mf.uam") ||
            pkg.startsWith("com.miHoYo.") ||
            pkg.startsWith("com.HoYoverse.") ||
            pkg.startsWith("com.hypergryph.") ||
            pkg.startsWith("com.kurogame.") ||
            pkg.startsWith("com.netease.") ||
            pkg.startsWith("com.papegames.") ||
            pkg.startsWith("com.lilithgames.") ||
            pkg.startsWith("com.supercell.") ||
            pkg.startsWith("com.mojang.") ||
            pkg.startsWith("com.roblox.") ||
            pkg.startsWith("com.epicgames.") ||
            pkg.startsWith("com.ea.gp.") ||
            pkg.startsWith("com.activision.") ||
            pkg.startsWith("com.riotgames.") ||
            pkg.startsWith("com.gameloft.") ||
            pkg.startsWith("com.blizzard.") ||
            pkg.startsWith("com.garena.") ||
            pkg.startsWith("com.pubg.") ||
            pkg.startsWith("com.levelinfinite.") ||
            pkg.startsWith("com.bilibili.azurlane") ||
            pkg.startsWith("com.bilibili.fgo") ||
            pkg.contains(".game.") ||
            pkg.contains(".games.") ||
            pkg.endsWith(".game") ||
            pkg.endsWith(".games") ||
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

    private fun isHeadlessOverlayPackage(packageName: String): Boolean {
        return packageName.startsWith("com.android.internal.") ||
            packageName.startsWith("com.samsung.internal.") ||
            packageName.contains(".overlay.") ||
            packageName.endsWith(".overlay") ||
            packageName.contains(".auto_generated_rro_")
    }

    private fun isInteractiveSystemApp(packageName: String): Boolean {
        return packageName in INTERACTIVE_SYSTEM_PACKAGES
    }

    private val INTERACTIVE_SYSTEM_PACKAGES = setOf(
        "com.android.settings",
        "com.android.vending",
        "com.android.chrome",
        "com.sec.android.gallery3d",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.myfiles",
        "com.sec.android.app.camera",
        "com.sec.android.app.clockpackage",
        "com.sec.android.app.popupcalculator",
        "com.sec.android.app.voicenote",
        "com.sec.android.app.samsungapps",
        "com.sec.android.app.launcher",
        "com.sec.android.app.shealth",
        "com.samsung.android.calendar",
        "com.samsung.android.app.notes",
        "com.samsung.android.messaging",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.app.contacts",
        "com.samsung.android.email.provider",
        "com.samsung.android.video",
        "com.samsung.android.game.gamehome",
        "com.samsung.android.oneconnect",
        "com.samsung.android.voc",
        "com.samsung.android.themestore"
    )

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
            packageName == "com.samsung.android.sm.devicesecurity" ||
            packageName == "com.sec.phone" ||
            packageName == "com.sec.android.app.launcher"
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
