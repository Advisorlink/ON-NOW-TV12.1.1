package tv.vesper.app

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Custom Application class with two responsibilities:
 *
 *  1. **Crash capture** — registers an uncaught-exception handler
 *     in `attachBaseContext` (the earliest possible Android
 *     lifecycle entry point) so even crashes that happen in
 *     `Application.onCreate` or in `Activity.onCreate` *before*
 *     our own try/catch blocks get written to a log file the user
 *     can retrieve.
 *
 *     Log location: `getFilesDir()/onnowtv-crash.txt`, accessible
 *     via Samsung "My Files" at
 *     `Internal storage / Android / data / tv.onnowtv.app / files /`
 *     (with "Show hidden files" enabled).  We *also* mirror to
 *     external Documents/ when permission allows so the user can
 *     find it without spelunking through hidden folders.
 *
 *  2. **Last-launch crash detection** — when `MainActivity.onCreate`
 *     starts it reads `lastCrash` and, if non-null, renders the
 *     stack trace on a black emergency-info screen with a "Share"
 *     button.  This way the user gets actionable info instead of
 *     a silent close-on-tap.
 */
class OnNowApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // v2.7.80 SECURITY — Tamper-detection runs once, BEFORE any
        // WebView / ExoPlayer / network setup.  On debug builds this
        // is a no-op (see IntegrityGuard); on release it kills the
        // process immediately if the APK has been re-signed,
        // re-packaged, or is being run under Frida / Xposed /
        // jdb.  See `security/IntegrityGuard.kt` for the full
        // policy.
        try {
            tv.vesper.app.security.IntegrityGuard.runChecks(this)
        } catch (_: Throwable) {
            // Defence in depth — never let the guard itself crash
            // the legitimate app.  Soft failures are tolerated;
            // hard failures already exited via exitProcess().
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        /* Load any crash captured during the previous run BEFORE
           any other component (Activity, Service, libVLC, etc.)
           is loaded.  The bytes are tiny so reading them
           synchronously here is fine. */
        try {
            val f = File(filesDir, CRASH_LOG_NAME)
            if (f.exists() && f.length() > 0) {
                lastCrash = f.readText(Charsets.UTF_8)
                /* Don't delete — let MainActivity decide whether
                   to clear it after the user dismisses the report.
                   That way a crash loop still surfaces the latest
                   message, not an empty file. */
            }
        } catch (_: Throwable) { /* never let the logger crash the app */ }

        /* Install the uncaught-exception handler.  The previous
           handler is chained so the system's "App keeps stopping"
           dialog still works after we've logged. */
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("ON NOW TV V2 crash — ${java.util.Date()}")
                pw.println("Thread: ${thread.name}")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                pw.println("Build ID: ${Build.DISPLAY}")
                pw.println("--------------------------------")
                throwable.printStackTrace(pw)
                pw.flush()
                val txt = sw.toString()
                /* Primary log: internal app storage, no permissions
                   required, always writable. */
                try {
                    File(filesDir, CRASH_LOG_NAME).writeText(txt, Charsets.UTF_8)
                } catch (_: Throwable) { /* swallow */ }
                /* Secondary log: external app-scoped Downloads
                   directory.  Visible via My Files on Samsung
                   without enabling hidden files.  Available on
                   every API >= 19 without permissions when using
                   getExternalFilesDir. */
                try {
                    val ext = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (ext != null) {
                        if (!ext.exists()) ext.mkdirs()
                        File(ext, CRASH_LOG_NAME).writeText(txt, Charsets.UTF_8)
                    }
                } catch (_: Throwable) { /* swallow */ }
            } catch (_: Throwable) { /* never let the handler hide the real crash */ }
            /* Re-throw via the previous handler so the OS still
               kills the process and shows the system dialog.  If
               we returned cleanly here, the JVM would be in an
               undefined state. */
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_LOG_NAME = "onnowtv-crash.txt"

        /** Crash text captured in the previous run, if any.  Read
         *  by MainActivity.onCreate to surface a diagnostic
         *  screen.  Null when the app started cleanly. */
        var lastCrash: String? = null
    }
}
