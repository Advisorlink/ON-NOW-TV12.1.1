package tv.onnowtv.livetv

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * "Boost box": warns when the box is short on free RAM and frees what an app is allowed to —
 * cached/background processes of other launchable apps (killBackgroundProcesses) plus this
 * app's own in-memory picture caches. Android doesn't let a normal app wipe all RAM.
 */
object BoxBoost {
    private const val TAG = "BoxBoost"
    private const val PREFS = "box_boost"
    private const val KEY_LAST_PROMPT = "last_prompt"
    private const val CHECK_EVERY_MS = 45_000L
    private const val PROMPT_COOLDOWN_MS = 30 * 60_000L
    private const val MB = 1024L * 1024L

    /** App-specific extra trimming (e.g. Coil memory cache, WebView RAM cache). */
    var trimOwnCaches: (() -> Unit)? = null
    /** Activities that must never be interrupted (video playback). */
    var isPlayback: (Activity) -> Boolean = { it.javaClass.simpleName.contains("Player") }

    private val main = Handler(Looper.getMainLooper())
    private var resumed: WeakReference<Activity>? = null
    private var showing: Dialog? = null

    data class Memory(val availMb: Long, val totalMb: Long, val low: Boolean)

    fun memory(ctx: Context): Memory {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val low = mi.lowMemory || mi.availMem < maxOf((mi.threshold * 1.3).toLong(), mi.totalMem / 8)
        return Memory(mi.availMem / MB, mi.totalMem / MB, low)
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { resumed = WeakReference(a); schedule(0L) }
            override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null; main.removeCallbacks(tick) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (showing?.ownerActivity === a) showing = null }
        })
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) schedule(0L)
            }
            override fun onConfigurationChanged(c: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() { schedule(0L) }
        })
    }

    private val tick = Runnable { check(); schedule(CHECK_EVERY_MS) }

    private fun schedule(delay: Long) { main.removeCallbacks(tick); main.postDelayed(tick, if (delay == 0L) 4_000L else delay) }

    private fun check() {
        val a = resumed?.get() ?: return
        if (a.isFinishing || isPlayback(a) || showing?.isShowing == true) return
        val m = runCatching { memory(a) }.getOrNull() ?: return
        if (!m.low) return
        val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_PROMPT, 0L) < PROMPT_COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST_PROMPT, System.currentTimeMillis()).apply()
        show(a, auto = true)
    }

    /** Frees memory; calls [done] on the main thread with MB freed. */
    fun boost(ctx: Context, done: (Long) -> Unit) {
        val app = ctx.applicationContext
        val before = memory(app).availMb
        runCatching { trimOwnCaches?.invoke() }
        Thread {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            var closed = 0
            for (pkg in otherApps(app)) {
                runCatching { am.killBackgroundProcesses(pkg); closed++ }
            }
            Runtime.getRuntime().gc()
            Thread.sleep(1_500L)
            val freed = maxOf(0L, memory(app).availMb - before)
            Log.i(TAG, "boost: asked $closed apps to close, freed ${freed}MB")
            main.post { done(freed) }
        }.start()
    }

    private fun otherApps(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val out = HashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) }.getOrDefault(emptyList())
                .forEach { out.add(it.activityInfo.packageName) }
        }
        out.remove(ctx.packageName)
        home?.let { out.remove(it) }
        return out
    }

    /** The pop-up. [auto] = shown because memory ran low; otherwise opened from Settings. */
    fun show(activity: Activity, auto: Boolean) {
        if (activity.isFinishing || showing?.isShowing == true) return
        val m = memory(activity)
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt()
        fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            this.text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(3f).toFloat(), 1f)
        }
        val gold = 0xFFF5C451.toInt()
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30f), dp(26f), dp(30f), dp(24f))
            background = GradientDrawable().apply { setColor(0xF2121722.toInt()); cornerRadius = dp(22f).toFloat(); setStroke(dp(1f), 0x33FFFFFF) }
        }
        card.addView(text(if (auto) "RUNNING LOW ON MEMORY" else "BOOST BOX", 12f, gold, bold = true).apply { letterSpacing = 0.18f; tag = "boost-eyebrow" })
        card.addView(text(if (auto) "Your box is slowing down" else "Free up memory", 24f, Color.WHITE, bold = true).apply { setPadding(0, dp(6f), 0, dp(10f)) })
        val pct = if (m.totalMb > 0) (m.availMb * 100 / m.totalMb).toInt() else 0
        card.addView(text("${m.availMb} MB free of ${m.totalMb} MB ($pct%)", 15f, if (m.low) 0xFFFF8E8E.toInt() else 0xFFB8C2D6.toInt(), bold = true).apply { tag = "boost-memory" })
        card.addView(FrameLayout(activity).apply {
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(4f).toFloat() }
            addView(View(activity).apply { background = GradientDrawable().apply { setColor(if (m.low) 0xFFFF7373.toInt() else gold); cornerRadius = dp(4f).toFloat() } },
                FrameLayout.LayoutParams(0, -1))
            post { getChildAt(0).layoutParams = FrameLayout.LayoutParams(width * pct.coerceIn(2, 100) / 100, -1) }
        }, LinearLayout.LayoutParams(-1, dp(8f)).apply { topMargin = dp(8f); bottomMargin = dp(14f) })
        val why = if (auto) "When free memory runs low, menus lag, channels take longer to open and video can stutter. " else "Keeps menus quick and channels opening fast. "
        val body = text(why + "Boost box closes other apps left running in the background and clears temporary picture memory. Your sign-in, favourites and settings are not touched.", 15f, 0xFFD6DCE8.toInt())
        card.addView(body, LinearLayout.LayoutParams(dp(520f), -2))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(20f), 0, 0) }
        fun button(label: String, primary: Boolean) = TextView(activity).apply {
            text = label; textSize = 16f; isFocusable = true; isClickable = true; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(26f), dp(12f), dp(26f), dp(12f))
            fun paint(f: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(30f).toFloat()
                    setColor(if (primary) (if (f) gold else 0xFFC99A2E.toInt()) else (if (f) 0x44FFFFFF else 0x18FFFFFF))
                    if (f) setStroke(dp(2f), Color.WHITE)
                }
                setTextColor(if (primary) 0xFF1A1405.toInt() else Color.WHITE)
                scaleX = if (f) 1.05f else 1f; scaleY = scaleX
            }
            paint(false); setOnFocusChangeListener { _, f -> paint(f) }
        }
        val later = button("Not now", false).apply { tag = "boost-not-now" }
        val go = button("Boost box", true).apply { tag = "boost-go" }
        row.addView(later); row.addView(go, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14f) })
        card.addView(row)

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(card)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOwnerActivity(activity)
            setOnDismissListener { if (showing === this) showing = null }
        }
        later.setOnClickListener { dialog.dismiss() }
        go.setOnClickListener {
            go.isEnabled = false; later.isEnabled = false; go.text = "Boosting…"
            boost(activity) { freed ->
                if (!dialog.isShowing) return@boost
                val after = memory(activity)
                go.text = "Done"
                body.text = if (freed >= 20) "Freed $freed MB — now ${after.availMb} MB free. Your box should feel quicker."
                else "Your box is already running about as lean as Android allows (${after.availMb} MB free). If it still feels slow, restart the box."
                main.postDelayed({ runCatching { dialog.dismiss() } }, 3_500L)
            }
        }
        showing = dialog
        dialog.show()
        go.requestFocus()
    }
}
