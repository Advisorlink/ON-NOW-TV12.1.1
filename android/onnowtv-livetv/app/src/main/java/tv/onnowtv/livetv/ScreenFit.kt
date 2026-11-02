package tv.onnowtv.livetv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View

/** Overscan safe-area: shrinks every screen by a few % for TVs that crop the edges. */
object ScreenFit {
    private const val FILE = "screen_fit"
    private const val KEY = "pct"
    val options = intArrayOf(100, 97, 95, 92, 90)

    fun get(ctx: Context): Int = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY, 100)

    fun set(ctx: Context, pct: Int) {
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY, pct).apply()
    }

    fun apply(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        val pct = get(activity)
        val dm = activity.resources.displayMetrics
        val px = dm.widthPixels * (100 - pct) / 200
        val py = dm.heightPixels * (100 - pct) / 200
        if (content.paddingLeft == px && content.paddingTop == py) return
        content.setBackgroundColor(Color.BLACK)
        content.setPadding(px, py, px, py)
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { if (a !is CrashActivity) a.window.decorView.post { apply(a) } }
            override fun onActivityResumed(a: Activity) { if (a !is CrashActivity) apply(a) }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
