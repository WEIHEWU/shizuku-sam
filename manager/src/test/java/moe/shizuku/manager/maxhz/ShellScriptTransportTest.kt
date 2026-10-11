package moe.shizuku.manager.maxhz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class ShellScriptTransportTest {

    @Test
    fun smallScriptsPreserveStdoutStderrAndExitCode() {
        val success = execute("printf 'stdout-ok\\n'; printf 'stderr-ok\\n' >&2")
        assertEquals(0, success.code)
        assertEquals("stdout-ok\n", success.out)
        assertEquals("stderr-ok\n", success.err)

        val failure = execute("printf 'before-exit\\n'; printf 'failure-detail\\n' >&2; exit 7")
        assertEquals(7, failure.code)
        assertEquals("before-exit\n", failure.out)
        assertEquals("failure-detail\n", failure.err)
    }

    @Test
    fun scriptLargerThanArgMaxStringLimitUsesStdinWithoutTruncation() {
        val script = buildString {
            repeat(30_000) { index ->
                append(": # stdin-padding-")
                append(index)
                append('\n')
            }
            append(
                """
                i=0
                while [ "${'$'}i" -lt 2000 ]; do
                  printf 'OUT-%04d\n' "${'$'}i"
                  printf 'ERR-%04d\n' "${'$'}i" >&2
                  i=${'$'}((i + 1))
                done
                exit 9
                """.trimIndent()
            )
        }
        assertTrue(script.toByteArray(StandardCharsets.UTF_8).size > 512_000)

        val result = execute(script, timeoutMs = 15_000)

        assertEquals(9, result.code)
        assertEquals(2000, result.out.lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(2000, result.err.lineSequence().filter { it.isNotEmpty() }.count())
        assertTrue(result.out.startsWith("OUT-0000\n"))
        assertTrue(result.out.endsWith("OUT-1999\n"))
        assertTrue(result.err.startsWith("ERR-0000\n"))
        assertTrue(result.err.endsWith("ERR-1999\n"))
        assertFalse(result.err.contains("Argument list too long"))
    }

    @Test
    fun timeoutReturnsExplicitErrorAndLeavesNoTransportThreads() {
        val result = execute("exec sleep 5", timeoutMs = 100)

        assertEquals(ShellScriptTransport.CODE_TIMEOUT, result.code)
        assertTrue(result.err.contains("执行超时"))
        waitForTransportThreadsToStop()
        assertEquals(0, liveTransportThreadCount())
    }

    @Test
    fun stdinFailureIsNotReportedAsAValidationFailure() {
        val result = ShellScriptTransport.execute(
            script = "printf 'must-not-run\\n'",
            timeoutMs = 2_000,
            cleanupJoinMs = 500,
            startProcess = {
                object : Process() {
                    override fun getOutputStream(): OutputStream = object : OutputStream() {
                        override fun write(value: Int) {
                            throw IOException("injected broken pipe")
                        }
                    }

                    override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
                    override fun getErrorStream() = ByteArrayInputStream("remote stderr\n".toByteArray())
                    override fun waitFor() = 23
                    override fun exitValue() = 23
                    override fun destroy() = Unit
                    override fun isAlive() = false
                }
            }
        )

        assertEquals(ShellScriptTransport.CODE_STDIN_FAILURE, result.code)
        assertTrue(result.err.contains("remote stderr"))
        assertTrue(result.err.contains("脚本传输失败：stdin 写入失败"))
        assertTrue(result.err.contains("Shell exit code=23"))
        assertFalse(result.err.contains("验证失败"))
    }

    @Test
    fun repeatedRunsDoNotLeakThreadsOrFileDescriptors() {
        val descriptorsBefore = openFileDescriptorCount()

        repeat(30) { index ->
            val result = execute("printf 'run-${index}\\n'")
            assertEquals(0, result.code)
            assertEquals("run-${index}\n", result.out)
            assertEquals("", result.err)
        }

        waitForTransportThreadsToStop()
        assertEquals(0, liveTransportThreadCount())
        val descriptorsAfter = openFileDescriptorCount()
        if (descriptorsBefore >= 0 && descriptorsAfter >= 0) {
            assertTrue(
                "open file descriptors grew from ${descriptorsBefore} to ${descriptorsAfter}",
                descriptorsAfter <= descriptorsBefore + 3
            )
        }
    }

    private fun execute(script: String, timeoutMs: Long = 5_000): ShellScriptTransport.Result {
        return ShellScriptTransport.execute(
            script = script,
            timeoutMs = timeoutMs,
            cleanupJoinMs = 500,
            startProcess = { ProcessBuilder("/bin/sh").start() }
        )
    }

    private fun waitForTransportThreadsToStop() {
        repeat(20) {
            if (liveTransportThreadCount() == 0) return
            Thread.sleep(50)
        }
    }

    private fun liveTransportThreadCount(): Int {
        return Thread.getAllStackTraces().keys.count {
            it.isAlive && it.name.startsWith("MaxHzShell-")
        }
    }

    private fun openFileDescriptorCount(): Int {
        return File("/dev/fd").list()?.size ?: -1
    }
}
