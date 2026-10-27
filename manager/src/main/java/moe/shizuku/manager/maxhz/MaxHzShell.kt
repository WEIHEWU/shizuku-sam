package moe.shizuku.manager.maxhz

import moe.shizuku.manager.maxhz.shell.ShizukuShellBridge
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * MaxHz 内部 Shell 执行入口（Root + Shizuku 双通道特权引擎 + 常驻预热连接池）。
 *
 * 优先采用 Root (KernelSU/Magisk/APatch su) 执行底层硬件直通控制；
 * 若未检测到直接 Root，则无缝切换至 Shizuku 特权 API 运行。
 *
 * 架构升级：
 * - 引入常驻 WarmShellSession 复用特权 Shell 进程，常规指令免去每次 fork/Binder 建连开销（延迟降至 ~3ms）；
 * - 遇到长耗时任务（>15s）或并发争用、进程异常时，自动回退至独立进程 [ShellScriptTransport] 执行。
 */
object MaxHzShell {

    data class Result(val code: Int, val out: String, val err: String)

    // Fix CVE-M01: @Volatile 确保跨线程可见性，避免指令重排导致读到未初始化值。
    @Volatile
    private var directRootAvailable: Boolean? = null

    private val warmLock = ReentrantLock()

    @Volatile
    private var warmSession: WarmShellSession? = null

    private class ActiveCommand(val token: String) {
        val outMarkerPrefix = "__MAXHZ_OUT_EOF_${token}__:"
        val errMarker = "__MAXHZ_ERR_EOF_${token}__"
        val outLines = ArrayList<String>()
        val errLines = ArrayList<String>()
        @Volatile
        var exitCode: Int = 0
        val latch = CountDownLatch(2)
    }

    private class WarmShellSession(val process: Process) {
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        @Volatile
        var currentCommand: ActiveCommand? = null
        @Volatile
        var closed: Boolean = false

        init {
            thread(start = true, isDaemon = true, name = "maxhz-warm-stdout") {
                try {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            val cmd = currentCommand ?: return@forEach
                            if (line.startsWith(cmd.outMarkerPrefix)) {
                                cmd.exitCode = line.removePrefix(cmd.outMarkerPrefix).trim().toIntOrNull() ?: 0
                                cmd.latch.countDown()
                            } else {
                                synchronized(cmd.outLines) {
                                    cmd.outLines.add(line)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    closed = true
                    currentCommand?.latch?.let {
                        it.countDown()
                        it.countDown()
                    }
                }
            }

            thread(start = true, isDaemon = true, name = "maxhz-warm-stderr") {
                try {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            val cmd = currentCommand ?: return@forEach
                            if (line == cmd.errMarker) {
                                cmd.latch.countDown()
                            } else {
                                synchronized(cmd.errLines) {
                                    cmd.errLines.add(line)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    closed = true
                    currentCommand?.latch?.let {
                        it.countDown()
                        it.countDown()
                    }
                }
            }
        }

        fun isAlive(): Boolean = !closed && process.isAlive

        fun destroy() {
            closed = true
            runCatching { writer.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.destroyForcibly() }
        }
    }

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
     * 获取当前生效的特权通道描述（用于诊断中心展示）。
     */
    fun activePrivilegeChannelLabel(): String {
        return when {
            isRootAvailable() -> "Root 直通 (su) + 常驻预热池"
            ShizukuShellBridge.isReady() -> "Shizuku 特权服务 + 常驻预热池"
            isDeviceOwnerReady() -> "Device Owner / WRITE_SECURE_SETTINGS"
            else -> "未就绪（请先启动 Shizuku 或授予 Root）"
        }
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

    private fun tryExecWarm(script: String, timeoutMs: Long): Result? {
        if (timeoutMs > 15_000L) return null
        if (!warmLock.tryLock(250, TimeUnit.MILLISECONDS)) return null
        try {
            var session = warmSession
            if (session == null || !session.isAlive()) {
                session?.destroy()
                session = WarmShellSession(createProcess())
                warmSession = session
            }
            val token = UUID.randomUUID().toString().replace("-", "")
            val cmd = ActiveCommand(token)
            session.currentCommand = cmd
            val wrappedScript = buildString(script.length + 160) {
                append("(\n")
                append(script)
                append("\n)\n")
                append("__maxhz_rc=$?\n")
                append("printf '\\n").append(cmd.outMarkerPrefix).append("%d\\n' \"\$__maxhz_rc\"\n")
                append("printf '\\n").append(cmd.errMarker).append("\\n' >&2\n")
            }
            session.writer.write(wrappedScript)
            session.writer.flush()

            val finished = cmd.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished || session.closed) {
                session.destroy()
                warmSession = null
                return null
            }
            val outText = synchronized(cmd.outLines) {
                if (cmd.outLines.isNotEmpty() && cmd.outLines.last().isEmpty()) {
                    cmd.outLines.removeAt(cmd.outLines.lastIndex)
                }
                cmd.outLines.joinToString("\n").trimEnd()
            }
            val errText = synchronized(cmd.errLines) {
                if (cmd.errLines.isNotEmpty() && cmd.errLines.last().isEmpty()) {
                    cmd.errLines.removeAt(cmd.errLines.lastIndex)
                }
                cmd.errLines.joinToString("\n").trimEnd()
            }
            return Result(cmd.exitCode, outText, errText)
        } catch (_: Throwable) {
            warmSession?.destroy()
            warmSession = null
            return null
        } finally {
            warmSession?.currentCommand = null
            warmLock.unlock()
        }
    }

    private fun runShell(script: String, timeoutMs: Long, threadJoinMs: Long): Result {
        if (!isReady()) {
            return Result(-3, "", "Shell 启动失败：未检测到已授权的 Root (su) 权限或 Shizuku 服务，请先启动服务")
        }

        tryExecWarm(script, timeoutMs)?.let { return it }

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
