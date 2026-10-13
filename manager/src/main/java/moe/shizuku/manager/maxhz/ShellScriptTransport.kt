package moe.shizuku.manager.maxhz

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** Runs a shell with the script delivered over stdin, never as an argv element. */
internal object ShellScriptTransport {

    const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    const val CODE_TIMEOUT = -1
    const val CODE_WAIT_FAILURE = -2
    const val CODE_STDIN_FAILURE = -4
    const val CODE_OUTPUT_FAILURE = -5

    private const val MIN_DRAIN_TIMEOUT_MS = 5_000L

    data class Result(val code: Int, val out: String, val err: String)

    fun execute(
        script: String,
        timeoutMs: Long,
        cleanupJoinMs: Long,
        startProcess: () -> Process
    ): Result {
        val process = startProcess()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdinError = AtomicReference<Throwable?>()
        val stdoutError = AtomicReference<Throwable?>()
        val stderrError = AtomicReference<Throwable?>()
        val waitError = AtomicReference<Throwable?>()
        val exitCode = IntArray(1)

        val stdoutThread = readerThread("MaxHzShell-stdout", stdout, stdoutError) {
            process.inputStream
        }
        val stderrThread = readerThread("MaxHzShell-stderr", stderr, stderrError) {
            process.errorStream
        }
        val stdinThread = daemonThread("MaxHzShell-stdin") {
            try {
                process.outputStream.use { input ->
                    val payload = if (script.endsWith("\n")) script else "$script\n"
                    input.write(payload.toByteArray(StandardCharsets.UTF_8))
                    input.flush()
                }
            } catch (t: Throwable) {
                stdinError.set(t)
            }
        }
        val waitThread = daemonThread("MaxHzShell-wait") {
            try {
                exitCode[0] = process.waitFor()
            } catch (t: Throwable) {
                waitError.set(t)
            }
        }

        stdoutThread.start()
        stderrThread.start()
        stdinThread.start()
        waitThread.start()

        try {
            waitThread.join(timeoutMs)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            cleanupProcess(
                process,
                listOf(stdinThread, waitThread, stdoutThread, stderrThread),
                cleanupJoinMs
            )
            return Result(
                CODE_WAIT_FAILURE,
                stdout.utf8(),
                combineError(stderr.utf8(), "Shell 等待失败：执行线程被中断")
            )
        }

        if (waitThread.isAlive) {
            cleanupProcess(
                process,
                listOf(stdinThread, waitThread, stdoutThread, stderrThread),
                cleanupJoinMs
            )
            return Result(
                CODE_TIMEOUT,
                stdout.utf8(),
                combineError(stderr.utf8(), "执行超时")
            )
        }

        waitError.get()?.let { error ->
            cleanupProcess(
                process,
                listOf(stdinThread, waitThread, stdoutThread, stderrThread),
                cleanupJoinMs
            )
            return Result(
                CODE_WAIT_FAILURE,
                stdout.utf8(),
                combineError(stderr.utf8(), "Shell 等待失败：${error.describe()}")
            )
        }

        val drainTimeoutMs = maxOf(cleanupJoinMs, MIN_DRAIN_TIMEOUT_MS)
        joinUninterruptibly(stdinThread, drainTimeoutMs)
        if (stdinThread.isAlive) {
            cleanupProcess(
                process,
                listOf(stdinThread, stdoutThread, stderrThread),
                cleanupJoinMs
            )
            return Result(
                CODE_STDIN_FAILURE,
                stdout.utf8(),
                combineError(
                    stderr.utf8(),
                    "脚本传输失败：stdin 写入未结束（Shell exit code=${exitCode[0]}）"
                )
            )
        }
        stdinError.get()?.let { error ->
            joinUninterruptibly(stdoutThread, drainTimeoutMs)
            joinUninterruptibly(stderrThread, drainTimeoutMs)
            if (stdoutThread.isAlive || stderrThread.isAlive) {
                cleanupProcess(process, listOf(stdoutThread, stderrThread), cleanupJoinMs)
            } else {
                closeProcessStreams(process)
            }
            return Result(
                CODE_STDIN_FAILURE,
                stdout.utf8(),
                combineError(
                    stderr.utf8(),
                    "脚本传输失败：stdin 写入失败（Shell exit code=${exitCode[0]}）：${error.describe()}"
                )
            )
        }

        joinUninterruptibly(stdoutThread, drainTimeoutMs)
        joinUninterruptibly(stderrThread, drainTimeoutMs)
        if (stdoutThread.isAlive || stderrThread.isAlive) {
            cleanupProcess(
                process,
                listOf(stdoutThread, stderrThread),
                cleanupJoinMs
            )
            return Result(
                CODE_OUTPUT_FAILURE,
                stdout.utf8(),
                combineError(
                    stderr.utf8(),
                    "Shell 输出读取失败：进程退出后输出流未结束（Shell exit code=${exitCode[0]}）"
                )
            )
        }

        closeProcessStreams(process)
        val readError = stdoutError.get() ?: stderrError.get()
        if (readError != null) {
            return Result(
                CODE_OUTPUT_FAILURE,
                stdout.utf8(),
                combineError(
                    stderr.utf8(),
                    "Shell 输出读取失败（Shell exit code=${exitCode[0]}）：${readError.describe()}"
                )
            )
        }

        return Result(exitCode[0], stdout.utf8(), stderr.utf8())
    }

    private fun readerThread(
        name: String,
        destination: ByteArrayOutputStream,
        error: AtomicReference<Throwable?>,
        input: () -> InputStream
    ): Thread {
        return daemonThread(name) {
            try {
                input().use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        synchronized(destination) {
                            if (destination.size() + count > MAX_OUTPUT_BYTES) {
                                throw IllegalStateException("Output too large")
                            }
                            destination.write(buffer, 0, count)
                        }
                    }
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
    }

    private fun daemonThread(name: String, block: () -> Unit): Thread {
        return Thread(block, name).apply { isDaemon = true }
    }

    private fun cleanupProcess(process: Process, threads: List<Thread>, joinMs: Long) {
        closeQuietly(runCatching { process.outputStream }.getOrNull())
        runCatching { process.destroy() }
        threads.forEach { joinUninterruptibly(it, 200) }
        if (runCatching { process.isAlive }.getOrDefault(false)) {
            runCatching { process.destroyForcibly() }
        }
        closeProcessStreams(process)
        threads.forEach { thread ->
            thread.interrupt()
            joinUninterruptibly(thread, joinMs)
        }
    }

    private fun closeProcessStreams(process: Process) {
        closeQuietly(runCatching { process.outputStream }.getOrNull())
        closeQuietly(runCatching { process.inputStream }.getOrNull())
        closeQuietly(runCatching { process.errorStream }.getOrNull())
    }

    private fun closeQuietly(closeable: Closeable?) {
        runCatching { closeable?.close() }
    }

    private fun joinUninterruptibly(thread: Thread, timeoutMs: Long) {
        var interrupted = false
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000
        while (thread.isAlive) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) break
            try {
                val millis = (remainingNanos / 1_000_000).coerceAtLeast(1)
                thread.join(millis)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun ByteArrayOutputStream.utf8(): String {
        val bytes = synchronized(this) { toByteArray() }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun combineError(stderr: String, diagnostic: String): String {
        return when {
            stderr.isBlank() -> diagnostic
            diagnostic.isBlank() -> stderr
            else -> stderr.trimEnd() + "\n" + diagnostic
        }
    }

    private fun Throwable.describe(): String {
        return cause?.message ?: message ?: javaClass.simpleName
    }
}
