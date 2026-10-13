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

    suspend fun waitForBinder(timeoutMs: Long = 20_000L, log: ((String) -> Unit)? = null): Boolean {
        if (rikka.shizuku.Shizuku.pingBinder()) {
            log?.invoke("\nService started, this window will be automatically closed in 3 seconds\n")
            return true
        }
        log?.invoke("\nWaiting for service...\n")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            kotlinx.coroutines.delay(250)
            if (rikka.shizuku.Shizuku.pingBinder()) {
                val elapsed = (System.currentTimeMillis() - start) / 1000.0
                log?.invoke("Connected in ${String.format("%.1f", elapsed)}s\n")
                log?.invoke("Service started, this window will be automatically closed in 3 seconds\n")
                return true
            }
        }
        return false
    }
}
