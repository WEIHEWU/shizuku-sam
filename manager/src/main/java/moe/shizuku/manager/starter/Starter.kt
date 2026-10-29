package moe.shizuku.manager.starter

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.application
import moe.shizuku.manager.utils.ShizukuStateMachine
import java.io.File
import java.util.Locale

object Starter {

    private val starterFile: File
        get() = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String
        get() = starterFile.absolutePath

    val adbCommand: String
        get() = "adb shell $userCommand"

    val internalCommand: String
        get() = "$userCommand --apk=${application.applicationInfo.sourceDir}"

    const val SERVICE_STARTED_MESSAGE = "Service started"

    suspend fun waitForBinder(
        timeoutMs: Long = 20_000L,
        log: ((String) -> Unit)? = null
    ): Boolean {
        if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
            log?.invoke(SERVICE_STARTED_MESSAGE)
            return true
        }
        log?.invoke("\nWaiting for service...")
        val t0 = System.currentTimeMillis()
        val connected = withTimeoutOrNull(timeoutMs) {
            while (ShizukuStateMachine.update() != ShizukuStateMachine.State.RUNNING) {
                delay(200)
            }
            true
        } ?: false

        if (connected) {
            val elapsed = (System.currentTimeMillis() - t0) / 1000.0
            log?.invoke(String.format(Locale.US, "Connected in %.1fs", elapsed))
            log?.invoke(SERVICE_STARTED_MESSAGE)
        }
        return connected
    }
}
