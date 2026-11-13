package tv.rebornplay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView

/** Every screen inside this app is Reborn Play: any older brand wording left in shared player / Live TV screens is rewritten as it appears. */
object Rebrand {
    private val brand = Regex("\\bon\\s+now\\s+tv(\\s*·?\\s*v2)?(\\s*·?\\s*live(\\s+tv)?)?|\\bon\\s+now\\s*·?\\s*v2(\\s+live\\s+tv)?|\\binside\\s+on\\s+now\\b|\\bvesper\\b", RegexOption.IGNORE_CASE)
    fun fix(s: CharSequence): String? {
        if (!brand.containsMatchIn(s)) return null
        return brand.replace(s) { m -> val t = m.value; if (t.startsWith("inside", true)) "inside Reborn Play" else if (t == t.uppercase() && s.toString() == s.toString().uppercase()) "REBORN PLAY" else "Reborn Play" }
    }
    private fun walk(v: View) {
        if (v is TextView) { fix(v.text ?: "")?.let { v.text = it } }
        else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    private var installed = false
    fun install(app: Application) {
        if (installed) return; installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                val root = a.window?.decorView ?: return
                var last = 0L
                root.viewTreeObserver.addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - last < 250) return@OnGlobalLayoutListener
                    last = now; runCatching { walk(root) }
                })
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) { runCatching { a.window?.decorView?.let { walk(it) } } }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
