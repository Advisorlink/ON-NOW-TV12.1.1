package tv.onnow.launcher.remote

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Persistent root shell with CAPTURED output for read-only queries
 * (e.g. `dumpsys input_method` for keyboard detection).  Kept separate
 * from RootInputDispatcher's write-only shell so a slow dumpsys can
 * never block key injection.
 */
object RootQueryShell {

    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val lock = Any()

    private fun ensure(): Boolean {
        synchronized(lock) {
            if (writer != null && proc?.isAlive == true) return true
            for (cmd in arrayOf(arrayOf("su"), arrayOf("sh"))) {
                try {
                    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                    val w = BufferedWriter(OutputStreamWriter(p.outputStream))
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    w.write("echo __onnow_rq_ready__\n")
                    w.flush()
                    var ok = false
                    val deadline = System.currentTimeMillis() + 4000
                    while (System.currentTimeMillis() < deadline) {
                        if (!r.ready()) {
                            if (!p.isAlive) break
                            Thread.sleep(40)
                            continue
                        }
                        val line = r.readLine() ?: break
                        if (line.contains("__onnow_rq_ready__")) { ok = true; break }
                    }
                    if (!ok) {
                        try { p.destroy() } catch (_: Throwable) {}
                        continue
                    }
                    proc = p; writer = w; reader = r
                    return true
                } catch (_: Throwable) { /* try next */ }
            }
            return false
        }
    }

    /** Run [cmd] and return its stdout, or null on timeout/failure. */
    fun query(cmd: String, timeoutMs: Long = 2500): String? {
        synchronized(lock) {
            if (!ensure()) return null
            val w = writer ?: return null
            val r = reader ?: return null
            val marker = "__onnow_rq_end_${System.nanoTime()}__"
            return try {
                w.write("$cmd 2>/dev/null; echo $marker\n")
                w.flush()
                val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (!r.ready()) { Thread.sleep(25); continue }
                    val line = r.readLine() ?: break
                    if (line.contains(marker)) return sb.toString()
                    sb.append(line).append('\n')
                }
                shutdown()
                null
            } catch (_: Throwable) {
                shutdown()
                null
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            try { writer?.write("exit\n"); writer?.flush() } catch (_: Throwable) {}
            try { writer?.close() } catch (_: Throwable) {}
            try { proc?.destroy() } catch (_: Throwable) {}
            proc = null; writer = null; reader = null
        }
    }
}
