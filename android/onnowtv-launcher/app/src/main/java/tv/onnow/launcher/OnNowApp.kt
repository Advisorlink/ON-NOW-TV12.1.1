package tv.onnow.launcher

import android.app.Application

/**
 * Lightweight Application class.  Kept intentionally minimal — heavy
 * lifting (config sync, push-notification polling, APK installer
 * coordination) happens later from MainActivity / dedicated workers.
 */
class OnNowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Future: WorkManager registration, crash reporter init, etc.
    }
}
