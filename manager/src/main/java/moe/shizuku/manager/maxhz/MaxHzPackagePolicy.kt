package moe.shizuku.manager.maxhz

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.Build
import java.nio.charset.Charset
import java.util.Locale

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

    private val NON_GAME_MANAGEMENT_PACKAGES = setOf(
        "com.samsung.android.game.gamehome",
        "com.samsung.android.game.gametools",
        "com.samsung.android.game.gos",
        "com.enhance.gameservice",
        "com.xiaomi.gamecenter",
        "com.xiaomi.gamecenter.sdk.service",
        "com.heytap.gamecenter",
        "com.nearme.gamecenter",
        "com.vivo.game",
        "com.huawei.gamebox",
        "com.bilibili.app.in",
        "tv.danmaku.bili",
        "com.taptap"
    )

    private val KNOWN_GAME_PACKAGE_PREFIXES = listOf(
        "com.tencent.tmgp.",
        "com.tencent.ig",
        "com.tencent.lolm",
        "com.tencent.jkchess",
        "com.tencent.kihan",
        "com.tencent.mf.",
        "com.tencent.af",
        "com.tencent.shootgame",
        "com.tencent.nfsonline",
        "com.tencent.fifamobile",
        "com.tencent.YiRen",
        "com.mihoyo.",
        "com.hoyoverse.",
        "com.cognosphere.",
        "com.hypergryph.",
        "com.gryphline.",
        "com.kurogame.",
        "com.netease.",
        "com.papegames.",
        "com.infoldgames.",
        "com.lilithgames.",
        "com.supercell.",
        "com.mojang.",
        "com.roblox.",
        "com.epicgames.",
        "com.ea.gp.",
        "com.ea.game.",
        "com.activision.",
        "com.riotgames.",
        "com.gameloft.",
        "com.blizzard.",
        "com.garena.",
        "com.pubg.",
        "com.krafton.",
        "com.levelinfinite.",
        "com.proximabeta.",
        "com.rayark.",
        "com.yostar.",
        "com.yoostar.",
        "jp.co.craftegg.",
        "com.aniplex.",
        "com.bilibili.azurlane",
        "com.bilibili.fgo",
        "com.bilibili.priconne",
        "com.bilibili.star",
        "com.bilibili.deadcells",
        "com.leiting.",
        "com.xd.",
        "com.yingxiong.",
        "com.lingxigames.",
        "com.aligames.",
        "com.zlongame.",
        "com.perfectworld.",
        "com.seasun.",
        "com.sanguosha.",
        "com.habby.",
        "com.chillyroom.",
        "com.bandainamcoent.",
        "com.square_enix.",
        "jp.konami.",
        "com.konami.",
        "com.sega.",
        "com.capcom.",
        "com.ubisoft.",
        "com.rockstargames.",
        "com.devsisters.",
        "com.nexon.",
        "com.netmarble.",
        "com.pearlabyss.",
        "com.com2us.",
        "com.shiftup.",
        // 常见模拟器
        "org.ppsspp.",
        "xyz.aethersx2.",
        "org.dolphinemu.",
        "org.citra.",
        "org.yuzu.",
        "io.github.lime3ds.",
        "com.retroarch",
        "com.dsemu.drastic",
        "org.vita3k.",
        "emu.skyline",
        "com.winlator",
        "com.swordfish.lemuroid",
        "com.github.stenzek.duckstation"
    )

    private val KNOWN_GAME_EXACT_PACKAGES = setOf(
        "com.riotgames.league.wildrift",
        "com.riotgames.league.teamfighttactics",
        "com.dts.freefireth",
        "com.dts.freefiremax",
        "com.mobile.legends",
        "com.vng.pubgmobile",
        "com.rekoo.pubgm",
        "kr.txwy.and.arknights",
        "tw.txwy.and.arknights"
    )

    private val GAME_LABEL_KEYWORDS = listOf(
        "原神", "崩坏", "星穹铁道", "绝区零", "鸣潮", "明日方舟", "王者荣耀",
        "和平精英", "英雄联盟", "金铲铲", "云顶之弈", "火影忍者", "暗区突围",
        "三角洲行动", "无畏契约", "穿越火线", "逆水寒", "永劫无间", "燕云十六声",
        "第五人格", "阴阳师", "蛋仔派对", "光·遇", "光遇", "恋与深空", "无限暖暖",
        "闪耀暖暖", "剑与远征", "部落冲突", "皇室战争", "荒野乱斗", "我的世界",
        "三国杀", "元气骑士", "尘白禁区", "重返未来", "蔚蓝档案", "碧蓝航线",
        "战双帕弥什", "深空之眼", "少女前线", "率土之滨", "三国志·战略版",
        "实况足球", "巅峰极速", "QQ飞车", "跑跑卡丁车", "球球大作战", "贪吃蛇大作战",
        "开心消消乐", "保卫萝卜", "植物大战僵尸", "泰拉瑞亚", "星露谷物语", "死亡细胞",
        "genshin", "honkai", "wuthering waves", "arknights", "azur lane", "blue archive",
        "minecraft", "roblox", "pubg", "call of duty", "brawl stars", "clash of clans",
        "clash royale", "diablo", "fortnite", "asphalt", "terraria", "balatro"
    )

    fun isGameApp(context: Context, packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || pkg == context.packageName) return false
        if (MaxHzPrefs.isUserMarkedGame(context, pkg)) return true
        if (pkg in MaxHzPrefs.userUnmarkedGamePackages(context)) return false
        if (pkg in MaxHzPrefs.gameAutoPerformancePackages(context)) return true
        if (pkg in NON_GAME_MANAGEMENT_PACKAGES) return false

        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    pkg,
                    PackageManager.ApplicationInfoFlags.of(
                        (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(
                    pkg,
                    PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
                )
            }
        } catch (_: Throwable) {
            return matchesGamePackageHeuristics(pkg)
        }
        return isGameApp(context, info, labelOverride = null)
    }

    fun isGameApp(context: Context, info: ApplicationInfo, labelOverride: String? = null): Boolean {
        val pkg = info.packageName ?: return false
        if (pkg == context.packageName) return false
        if (MaxHzPrefs.isUserMarkedGame(context, pkg)) return true
        if (pkg in MaxHzPrefs.userUnmarkedGamePackages(context)) return false
        if (pkg in MaxHzPrefs.gameAutoPerformancePackages(context)) return true
        if (pkg in NON_GAME_MANAGEMENT_PACKAGES) return false

        if (info.category == ApplicationInfo.CATEGORY_GAME) return true
        @Suppress("DEPRECATION")
        if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
        if (matchesGamePackageHeuristics(pkg)) return true

        // 针对用户安装应用，结合展示名称关键词辅助识别国产渠道服、双开分身或未声明 category 的游戏
        if (isUserInstalledApp(context, info)) {
            val label = (labelOverride ?: runCatching { info.loadLabel(context.packageManager).toString() }.getOrNull())
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            if (label.isNotBlank() &&
                !label.contains("助手") &&
                !label.contains("社区") &&
                !label.contains("论坛") &&
                !label.contains("壁纸") &&
                !label.contains("百科") &&
                !label.contains("攻略")
            ) {
                if (GAME_LABEL_KEYWORDS.any { kw -> label.contains(kw) }) {
                    return true
                }
            }
        }
        return false
    }

    private fun matchesGamePackageHeuristics(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        if (lower in KNOWN_GAME_EXACT_PACKAGES) return true
        if (KNOWN_GAME_PACKAGE_PREFIXES.any { lower.startsWith(it.lowercase(Locale.ROOT)) }) return true
        if (lower.contains(".tmgp.") ||
            lower.contains(".game.") ||
            lower.contains(".games.") ||
            lower.endsWith(".game") ||
            lower.endsWith(".games") ||
            lower.contains(".minigame") ||
            lower.contains(".kurogame") ||
            lower.contains(".mihoyo") ||
            lower.contains(".hoyoverse") ||
            lower.contains(".hypergryph")
        ) {
            return true
        }
        // 国产渠道服常见后缀组合（如 com.xxx.aligames / .bilibili / .bili / .m4399 / .taptap）
        if ((lower.endsWith(".aligames") || lower.endsWith(".m4399") || lower.contains(".m4399.")) &&
            !lower.contains("gamecenter")
        ) {
            return true
        }
        return false
    }

    /**
     * 同时匹配应用名、包名、以及中文应用名的拼音首字母（忽略大小写与空格）。
     */
    fun matchesAppQuery(label: String, packageName: String, rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        val lowerLabel = label.lowercase(Locale.ROOT)
        val lowerPkg = packageName.lowercase(Locale.ROOT)
        if (lowerLabel.contains(q) || lowerPkg.contains(q)) return true
        val compactQ = q.replace(" ", "")
        if (compactQ.all { it in 'a'..'z' || it in '0'..'9' }) {
            val initials = chineseInitials(label)
            if (initials.isNotEmpty() && initials.contains(compactQ)) return true
        }
        return false
    }

    private val GBK_CHARSET: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    // GB2312 一级汉字拼音首字母区位阈值表（无第三方依赖）
    private val PINYIN_BOUNDARIES = intArrayOf(
        0xB0A1, 0xB0C5, 0xB2C1, 0xB4EE, 0xB6EA, 0xB7A2, 0xB8C1, 0xB9FE,
        0xBBF7, 0xBFA6, 0xC0AC, 0xC2E8, 0xC4C3, 0xC5B6, 0xC5BE, 0xC6DA,
        0xC8BB, 0xC8F6, 0xCBFA, 0xCDDA, 0xCEF4, 0xD1B9, 0xD4D1, 0xD7FA
    )
    private val PINYIN_LETTERS = charArrayOf(
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
        'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q',
        'r', 's', 't', 'w', 'x', 'y', 'z'
    )
    private val POLYPHONE_OVERRIDES = mapOf(
        '重' to 'c',
        '率' to 's',
        '鸣' to 'm',
        '穹' to 'q',
        '铁' to 't',
        '绝' to 'j',
        '铲' to 'c',
        '弈' to 'y',
        '帕' to 'p',
        '弥' to 'm',
        '蔚' to 'w',
        '碧' to 'b'
    )

    fun chineseInitials(text: String): String {
        if (text.isBlank()) return ""
        val charset = GBK_CHARSET
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                ch in 'A'..'Z' -> sb.append(ch.lowercaseChar())
                POLYPHONE_OVERRIDES.containsKey(ch) -> sb.append(POLYPHONE_OVERRIDES.getValue(ch))
                ch.code in 0x4E00..0x9FFF && charset != null -> {
                    val bytes = ch.toString().toByteArray(charset)
                    if (bytes.size == 2) {
                        val code = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                        if (code in 0xB0A1..0xD7F9) {
                            for (i in 0 until PINYIN_LETTERS.size) {
                                if (code >= PINYIN_BOUNDARIES[i] && code < PINYIN_BOUNDARIES[i + 1]) {
                                    sb.append(PINYIN_LETTERS[i])
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }
        return sb.toString()
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
