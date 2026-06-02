package tv.onnowtv.livetv

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * App-level entry point.  Installs a global uncaught-exception
 * handler so unexpected crashes display the stack trace in a
 * full-screen "diagnostic" activity instead of the generic
 * "OnNow V2 Live keeps stopping" Android dialog.  This is
 * invaluable while we shake out the new layout on real TV boxes
 * where adb logcat is awkward.
 */
class LiveTVApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("LiveTVApp", "Uncaught exception in thread ${thread.name}", throwable)
                val intent = Intent(applicationContext, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_MESSAGE, throwable.toString())
                    putExtra(CrashActivity.EXTRA_STACK, Log.getStackTraceString(throwable))
                }
                startActivity(intent)
            } catch (chain: Throwable) {
                Log.e("LiveTVApp", "Crash handler itself failed", chain)
                defaultHandler?.uncaughtException(thread, throwable)
            }
            // Give the crash activity a moment to render, then exit
            // so the process is in a clean state.
            Thread.sleep(800)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
