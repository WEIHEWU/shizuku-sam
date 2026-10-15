package moe.shizuku.manager.starter

import moe.shizuku.manager.application
import java.io.File

object Starter {

    private val starterFile: File
        get() = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String
        get() = starterFile.absolutePath

    val adbCommand: String
        get() = "adb shell $userCommand"

    val internalCommand: String
        get() {
            val nativeLibDir = application.applicationInfo.nativeLibraryDir
            return "LD_LIBRARY_PATH=$nativeLibDir $userCommand --apk=${application.applicationInfo.sourceDir}"
        }

    suspend fun waitForBinder(timeoutMs: Long = 60_000L): Boolean {
        if (rikka.shizuku.Shizuku.pingBinder()) return true
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            kotlinx.coroutines.delay(250)
            if (rikka.shizuku.Shizuku.pingBinder()) return true
        }
        return false
    }
}
