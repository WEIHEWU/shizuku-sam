package moe.shizuku.manager.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.maxhz.MaxHzShell
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {

    const val PREF_AUTO_CHECK_UPDATE = "auto_check_update"
    private const val PREF_LAST_AUTO_CHECK_MS = "last_auto_check_update_ms"
    private const val PREF_IGNORED_VERSION = "ignored_update_version"

    private const val GITHUB_REPO = "WEIHEWU/shizuku-sam"
    private const val API_LATEST_RELEASE = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val RAW_BUILD_GRADLE = "https://raw.githubusercontent.com/$GITHUB_REPO/main/build.gradle"
    private const val MIRROR_PREFIX = "https://ghfast.top/"
    const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_REPO/releases/latest"

    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Int,
        val title: String,
        val changelog: String,
        val downloadUrl: String,
        val assetSize: Long,
        val htmlUrl: String
    )

    enum class InstallMode {
        PRIVILEGED_SILENT,
        SYSTEM_INSTALLER
    }

    fun isAutoCheckEnabled(): Boolean {
        return try {
            ShizukuSettings.getPreferences().getBoolean(PREF_AUTO_CHECK_UPDATE, true)
        } catch (_: Throwable) {
            true
        }
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        try {
            ShizukuSettings.getPreferences().edit().putBoolean(PREF_AUTO_CHECK_UPDATE, enabled).apply()
        } catch (_: Throwable) {
        }
    }

    fun shouldPerformAutoCheck(): Boolean {
        if (!isAutoCheckEnabled()) return false
        return try {
            val prefs = ShizukuSettings.getPreferences()
            val lastCheck = prefs.getLong(PREF_LAST_AUTO_CHECK_MS, 0L)
            System.currentTimeMillis() - lastCheck >= 6 * 60 * 60 * 1000L
        } catch (_: Throwable) {
            false
        }
    }

    fun markAutoCheckPerformed() {
        try {
            ShizukuSettings.getPreferences().edit()
                .putLong(PREF_LAST_AUTO_CHECK_MS, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun getCurrentVersionName(): String {
        return BuildConfig.VERSION_NAME.substringBefore(".r").removePrefix("v")
    }

    suspend fun checkForUpdate(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val remote = fetchFromGitHubApi() ?: fetchFromRawBuildGradle()
                ?: throw IllegalStateException("无法连接到 GitHub 更新服务器，请检查网络后重试")

            val localVersionName = getCurrentVersionName()
            val localVersionCode = BuildConfig.VERSION_CODE
            if (isNewerVersion(remote.versionName, localVersionName, remote.versionCode, localVersionCode)) {
                remote
            } else {
                null
            }
        }
    }

    private fun fetchFromGitHubApi(): ReleaseInfo? {
        return try {
            val jsonText = httpGetText(API_LATEST_RELEASE, timeoutMs = 8000)
            val json = JSONObject(jsonText)
            val rawTag = json.optString("tag_name", "").trim()
            if (rawTag.isEmpty()) return null
            val versionName = rawTag.removePrefix("v").removePrefix("V")
            val title = json.optString("name", rawTag).ifBlank { "Shizuku Sam v$versionName" }
            val body = json.optString("body", "").trim().ifBlank { "性能优化与问题修复。" }
            val htmlUrl = json.optString("html_url", RELEASES_PAGE_URL).ifBlank { RELEASES_PAGE_URL }

            var downloadUrl = ""
            var assetSize = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) && url.isNotEmpty()) {
                        downloadUrl = url
                        assetSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }
            if (downloadUrl.isEmpty()) {
                downloadUrl = "https://github.com/$GITHUB_REPO/releases/download/v$versionName/Shizuku-Sam-v$versionName.apk"
            }

            ReleaseInfo(
                versionName = versionName,
                versionCode = parseVersionToCode(versionName),
                title = title,
                changelog = formatChangelog(body),
                downloadUrl = downloadUrl,
                assetSize = assetSize,
                htmlUrl = htmlUrl
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchFromRawBuildGradle(): ReleaseInfo? {
        val urls = listOf(RAW_BUILD_GRADLE, MIRROR_PREFIX + RAW_BUILD_GRADLE)
        for (url in urls) {
            try {
                val content = httpGetText(url, timeoutMs = 8000)
                val versionNameMatch = Regex("""versionName\s*=\s*"([^"]+)"""").find(content)
                val versionCodeMatch = Regex("""versionCode\s*=\s*(\d+)""").find(content)
                val versionName = versionNameMatch?.groupValues?.get(1)?.trim()?.removePrefix("v") ?: continue
                val versionCode = versionCodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: parseVersionToCode(versionName)
                val downloadUrl = "https://github.com/$GITHUB_REPO/releases/download/v$versionName/Shizuku-Sam-v$versionName.apk"
                return ReleaseInfo(
                    versionName = versionName,
                    versionCode = versionCode,
                    title = "Shizuku Sam v$versionName",
                    changelog = "发现新版本 v$versionName，建议更新以获取最新修复与改进。",
                    downloadUrl = downloadUrl,
                    assetSize = 0L,
                    htmlUrl = "https://github.com/$GITHUB_REPO/releases/tag/v$versionName"
                )
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun httpGetText(urlStr: String, timeoutMs: Int): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Shizuku-Sam/${getCurrentVersionName()}")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun formatChangelog(raw: String): String {
        return raw.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("**Full Changelog**") || trimmed.startsWith("---")
            }
            .joinToString("\n") { line ->
                line.replace(Regex("^#+\\s*"), "• ")
                    .replace("**", "")
            }
            .trim()
            .ifBlank { "性能优化与问题修复。" }
    }

    fun isNewerVersion(
        remoteVersionName: String,
        localVersionName: String,
        remoteVersionCode: Int,
        localVersionCode: Int
    ): Boolean {
        val remoteParts = remoteVersionName.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val localParts = localVersionName.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return remoteVersionCode > localVersionCode
    }

    private fun parseVersionToCode(versionName: String): Int {
        val parts = versionName.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size >= 3) {
            return parts[0] * 10000 + parts[1] * 100 + parts[2]
        }
        return 0
    }

    suspend fun downloadAndInstall(
        context: Context,
        release: ReleaseInfo,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, statusText: String) -> Unit
    ): Result<InstallMode> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("Shizuku-Sam-") && (file.name.endsWith(".apk") || file.name.endsWith(".tmp"))) {
                    file.delete()
                }
            }

            val targetFile = File(cacheDir, "Shizuku-Sam-v${release.versionName}.apk")
            val tempFile = File(cacheDir, "Shizuku-Sam-v${release.versionName}.apk.tmp")

            val candidateUrls = buildList {
                add(release.downloadUrl)
                if (!release.downloadUrl.startsWith(MIRROR_PREFIX)) {
                    add(MIRROR_PREFIX + release.downloadUrl)
                }
            }

            var downloadedOk = false
            var lastError: Throwable? = null
            for ((index, url) in candidateUrls.withIndex()) {
                try {
                    val sourceLabel = if (index == 0) "GitHub" else "镜像加速通道"
                    onProgress(0, 0L, release.assetSize, "正在连接 $sourceLabel 下载更新包...")
                    downloadFile(url, tempFile, release.assetSize) { pct, readBytes, totalBytes ->
                        onProgress(pct, readBytes, totalBytes, "正在从 $sourceLabel 下载... $pct%")
                    }
                    if (verifyApkFile(tempFile)) {
                        if (targetFile.exists()) targetFile.delete()
                        tempFile.renameTo(targetFile)
                        downloadedOk = true
                        break
                    } else {
                        tempFile.delete()
                    }
                } catch (e: Throwable) {
                    lastError = e
                    tempFile.delete()
                }
            }

            if (!downloadedOk || !targetFile.exists()) {
                throw IllegalStateException("下载安装包失败：${lastError?.localizedMessage ?: "网络超时"}")
            }

            val canUsePrivileged = try {
                Shizuku.pingBinder()
            } catch (_: Throwable) {
                false
            }

            if (canUsePrivileged) {
                onProgress(100, targetFile.length(), targetFile.length(), "正在通过 Shizuku 特权静默安装并重启...")
                val privilegedSuccess = tryPrivilegedSelfInstall(context, targetFile)
                if (privilegedSuccess) {
                    return@runCatching InstallMode.PRIVILEGED_SILENT
                }
            }

            onProgress(100, targetFile.length(), targetFile.length(), "正在调起系统安装器...")
            withContext(Dispatchers.Main) {
                installViaSystemInstaller(context, targetFile)
            }
            InstallMode.SYSTEM_INSTALLER
        }
    }

    private fun downloadFile(
        urlStr: String,
        destFile: File,
        expectedSize: Long,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        var currentUrl = urlStr
        var redirects = 0
        var conn: HttpURLConnection? = null
        try {
            while (redirects < 6) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Shizuku-Sam/${getCurrentVersionName()}")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val contentLength = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var totalRead = 0L
                        var lastReportedPct = -1
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val pct = if (contentLength > 0) {
                                ((totalRead * 100L) / contentLength).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct.coerceAtLeast(0), totalRead, contentLength)
                            }
                        }
                        output.flush()
                    }
                }
                return
            }
            throw IllegalStateException("重定向次数过多")
        } finally {
            conn?.disconnect()
        }
    }

    private fun verifyApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 500_000L) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun tryPrivilegedSelfInstall(context: Context, apkFile: File): Boolean {
        val tmpApkPath = "/data/local/tmp/shizuku_sam_update.apk"
        val tmpLogPath = "/data/local/tmp/shizuku_sam_update.log"
        val pkgName = context.packageName

        val stageCmd = "cat '${apkFile.absolutePath}' > $tmpApkPath && chmod 644 $tmpApkPath && ls -l $tmpApkPath"
        val stageRes = MaxHzShell.exec(stageCmd)
        if (stageRes.code != 0) {
            return false
        }

        val installAndRelaunchCmd =
            "nohup sh -c 'pm install -r -d $tmpApkPath > $tmpLogPath 2>&1 && rm -f $tmpApkPath && sleep 1 && am start -n $pkgName/moe.shizuku.manager.MainActivity' >/dev/null 2>&1 < /dev/null &"
        val triggerRes = MaxHzShell.exec(installAndRelaunchCmd)
        if (triggerRes.code != 0) {
            MaxHzShell.exec("rm -f $tmpApkPath")
            return false
        }

        // Wait up to 9 seconds for PackageManager to replace and restart our process.
        // If our process is still alive and the log shows Failure, fall back to system installer.
        repeat(9) {
            delay(1_000L)
            val checkRes = MaxHzShell.exec("cat $tmpLogPath 2>/dev/null")
            val logOut = checkRes.out
            if (logOut.contains("Failure", ignoreCase = true) || logOut.contains("Error", ignoreCase = true)) {
                MaxHzShell.exec("rm -f $tmpApkPath $tmpLogPath")
                return false
            }
            if (logOut.contains("Success", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun installViaSystemInstaller(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: Throwable) {
                true
            }
            if (!canInstall) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(permIntent)
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }
}
