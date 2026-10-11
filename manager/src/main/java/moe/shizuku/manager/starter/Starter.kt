package moe.shizuku.manager.starter

import moe.shizuku.manager.application
import java.io.File

object Starter {

    private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String = starterFile.absolutePath

    val adbCommand = "adb shell $userCommand"

    val internalCommand = "$userCommand --apk=${application.applicationInfo.sourceDir}"

    suspend fun waitForBinder(timeoutMs: Long = 20_000L): Boolean {
        if (rikka.shizuku.Shizuku.pingBinder()) return true
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            kotlinx.coroutines.delay(250)
            if (rikka.shizuku.Shizuku.pingBinder()) return true
        }
        return false
    }
}
