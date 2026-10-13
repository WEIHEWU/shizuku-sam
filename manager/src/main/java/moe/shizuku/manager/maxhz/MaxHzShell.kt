package moe.shizuku.manager.maxhz

import moe.shizuku.manager.maxhz.shell.ShizukuShellBridge
import java.util.concurrent.TimeUnit

/**
 * MaxHz 内部 Shell 执行入口（Root + Shizuku 双通道特权引擎）。
 *
 * 优先采用 Root (KernelSU/Magisk/APatch su) 执行底层硬件直通控制；
 * 若未检测到直接 Root，则无缝切换至 Shizuku 特权 API 运行。
 *
 * Fix CVE-S03: 移除本文件中对 Shizuku.newProcess 的重复反射实现，
 *              统一委托给 ShizukuShellBridge，单点维护 Hidden-API 兼容性。
 * Fix CVE-M01: directRootAvailable 添加 @Volatile + synchronized 双重检查，
 *              su 检测加 3 秒超时，root 判断改为精确正则。
 */
object MaxHzShell {

    data class Result(val code: Int, val out: String, val err: String)

    // Fix CVE-M01: @Volatile 确保跨线程可见性，避免指令重排导致读到未初始化值。
    @Volatile
    private var directRootAvailable: Boolean? = null

    /**
     * 检查当前系统是否存在可用并已授权的直接 Root (su) 环境。
     * 线程安全：使用双重检查锁定（double-checked locking）。
     * 带 3 秒超时，防止等待用户授权的 su 永久阻塞调用线程。
     */
    fun isRootAvailable(): Boolean {
        // 快速路径：已缓存结果直接返回（@Volatile 保证可见性）
        directRootAvailable?.let { return it }

        // 慢速路径：首次检测，加锁保证只执行一次
        return synchronized(this) {
            // 二次检查：防止多个线程同时通过第一个 null 检查后重复检测
            directRootAvailable ?: detectRoot().also { directRootAvailable = it }
        }
    }

    /**
     * 实际执行 su 检测，带超时保护。
     * Fix CVE-M01: 使用 uid=0(root) 精确匹配，避免 "rootdir" 等字符串误判。
     */
    private fun detectRoot(): Boolean {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            p.outputStream.close()
            val exited = p.waitFor(2, TimeUnit.SECONDS)
            if (!exited) {
                p.destroyForcibly()
                return false
            }
            if (p.exitValue() != 0) {
                return false
            }
            val output = p.inputStream.bufferedReader().use { it.readText() }
            output.contains(Regex("""uid=0\(root\)"""))
        } catch (t: Throwable) {
            runCatching { p?.destroyForcibly() }
            false
        }
    }

    /**
     * 检查设备所有者 (Dhizuku / Device Owner) 特权通道是否可用。
     */
    fun isDeviceOwnerReady(): Boolean {
        return try {
            val app = moe.shizuku.manager.application
            moe.shizuku.manager.utils.DhizukuHelper.isPrivileged(app) ||
                app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 检查特权通信通道（Root 或 Shizuku）是否已就绪。
     */
    fun isReady(): Boolean {
        return isRootAvailable() || ShizukuShellBridge.isReady()
    }

    /**
     * 执行一段包含多行逻辑的 Shell 脚本（默认超时 15 秒）。
     */
    fun exec(script: String, timeoutSeconds: Long = 15): Result {
        return runShell(script, timeoutSeconds * 1000, 500)
    }

    /**
     * 快速执行极轻量 Shell 脚本（用于高频读取或简单状态查询，默认超时 1000ms）。
     */
    fun execRaw(script: String, timeoutMs: Long = 1000): Result {
        return runShell(script, timeoutMs, 100)
    }

    private fun createProcess(): Process {
        // Priority 1: Direct root process via su
        if (isRootAvailable()) {
            runCatching {
                return Runtime.getRuntime().exec(arrayOf("su"))
            }
            // su spawn 失败时降级到 Shizuku
        }

        // Priority 2: Shizuku privileged process
        // Fix CVE-S03: 不再在此处重复反射 Shizuku.newProcess；
        //              统一委托给 ShizukuShellBridge，它是唯一的反射维护点。
        return ShizukuShellBridge.newShellProcess()
    }

    private fun runShell(script: String, timeoutMs: Long, threadJoinMs: Long): Result {
        if (!isReady()) {
            return Result(-3, "", "Shell 启动失败：未检测到已授权的 Root (su) 权限或 Shizuku 服务，请先启动服务")
        }

        return try {
            val transport = ShellScriptTransport.execute(
                script = script,
                timeoutMs = timeoutMs,
                cleanupJoinMs = threadJoinMs,
                startProcess = { createProcess() }
            )
            Result(transport.code, transport.out, transport.err)
        } catch (t: Throwable) {
            Result(-3, "", ShizukuShellBridge.getReadableErrorMessage(t))
        }
    }
}
