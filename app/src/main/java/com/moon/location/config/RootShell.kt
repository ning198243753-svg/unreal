package com.moon.location.config

import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Minimal bounded root command runner (no shell-arg interpolation of user data:
 * the payload is piped on stdin).
 */
object RootShell {
    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    fun run(script: String, timeoutMs: Long = 8000): Result {
        var p: Process? = null
        return try {
            p = ProcessBuilder("su").redirectErrorStream(false).start()
            val proc = p
            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val tOut = Thread { proc.inputStream.bufferedReader().use { outBuf.append(it.readText()) } }
            val tErr = Thread { proc.errorStream.bufferedReader().use { errBuf.append(it.readText()) } }
            tOut.start(); tErr.start()
            OutputStreamWriter(proc.outputStream).use { it.write(script + "\nexit $?\n") }
            val done = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                proc.destroyForcibly()
                return Result(124, "", "timeout")
            }
            tOut.join(300); tErr.join(300)
            Result(proc.exitValue(), outBuf.toString().trim(), errBuf.toString().trim())
        } catch (t: Throwable) {
            runCatching { p?.destroyForcibly() }
            Result(-1, "", t.message ?: "error")
        }
    }

    /** Write [content] atomically to [path], world-readable, system_data_file context. */
    fun writeSharedFile(path: String, content: String): Boolean {
        val b64 = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val script = buildString {
            append("umask 022\n")
            append("printf '%s' '").append(b64).append("' | base64 -d > \"").append(path).append(".tmp\"\n")
            append("chmod 644 \"").append(path).append(".tmp\"\n")
            append("chcon u:object_r:system_data_file:s0 \"").append(path).append(".tmp\" 2>/dev/null || true\n")
            append("mv -f \"").append(path).append(".tmp\" \"").append(path).append("\"\n")
        }
        return run(script).ok
    }
}
