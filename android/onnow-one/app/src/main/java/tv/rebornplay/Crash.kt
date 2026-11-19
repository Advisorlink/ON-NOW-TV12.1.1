package tv.rebornplay

import android.app.Application
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import tv.vesper.app.BuildConfig

/** Saves the reason for a crash on the box and sends it on the next start, so faults seen on a TV can be fixed. No login details are included. */
object Crash {
    private var installed = false
    fun install(app: Application) {
        if (installed) return; installed = true
        val file = File(app.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file.writeText(android.util.Log.getStackTraceString(e).take(12_000)) }
            previous?.uncaughtException(t, e)
        }
        if (file.exists()) Thread {
            val body = JSONObject().put("version", BuildConfig.VERSION_NAME + "-" + BuildConfig.VERSION_CODE)
                .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " / Android " + android.os.Build.VERSION.RELEASE)
                .put("trace", runCatching { file.readText() }.getOrDefault("")).toString().toByteArray()
            for (host in listOf(BuildConfig.DL_BASE, "https://rebrand-app-5.emergent.host").filter { it.isNotBlank() }) {
                val ok = runCatching {
                    val c = (URL("$host/api/reborn/crash").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 15_000; setRequestProperty("Content-Type", "application/json") }
                    try { c.outputStream.use { it.write(body) }; c.responseCode in 200..299 } finally { c.disconnect() }
                }.getOrDefault(false)
                if (ok) { file.delete(); break }
            }
        }.start()
    }
}
