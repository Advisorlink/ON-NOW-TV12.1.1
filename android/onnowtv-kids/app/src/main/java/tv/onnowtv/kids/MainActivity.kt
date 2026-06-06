package tv.onnowtv.kids

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * ON NOW V2 KIDS — standalone Kids kiosk WebView wrapper.
 *
 * Loads `${app_url}/kids` (same React route used by the old Vesper
 * Kids profile) and wraps it with a native security shell:
 *
 *   • Registered as `android.intent.category.HOME` so pressing the
 *     HOME button on the remote routes back here.  The activity is
 *     `singleTask` + `stateNotNeeded`, so we never lose state.
 *
 *   • Pressing BACK / HOME / the on-screen Settings gear opens a
 *     native PIN overlay — the only way to leave Kids mode or open
 *     the parent settings rail.
 *
 *   • The Settings rail (left edge) gates the existing React
 *     `/kids/settings` page (content types + max ratings + change
 *     PIN).
 *
 * PIN storage: 4-digit code in `SharedPreferences("kids_kiosk", …)`.
 * Default = "0000" (on first run a Toast prompts the parent to set
 * one in Settings).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var splashView: View? = null
    private var splashDismissed = false
    private val splashSafetyHandler = Handler(Looper.getMainLooper())

    private lateinit var pinOverlay: View
    private lateinit var pinDigits: List<TextView>
    private lateinit var pinTitle: TextView
    private lateinit var pinHint: TextView
    private var pinBuffer = StringBuilder()

    /** What to do once the user enters the correct PIN. */
    private enum class PinIntent { EXIT_KIDS, OPEN_SETTINGS }
    private var pendingIntent: PinIntent = PinIntent.EXIT_KIDS

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.kids_webview)
        splashView = findViewById(R.id.kids_splash)
        pinOverlay = findViewById(R.id.kids_pin_overlay)
        pinTitle = findViewById(R.id.kids_pin_title)
        pinHint = findViewById(R.id.kids_pin_hint)
        pinDigits = listOf(
            findViewById(R.id.kids_pin_digit_1),
            findViewById(R.id.kids_pin_digit_2),
            findViewById(R.id.kids_pin_digit_3),
            findViewById(R.id.kids_pin_digit_4),
        )

        findViewById<View>(R.id.kids_settings_btn).setOnClickListener {
            requestPin(PinIntent.OPEN_SETTINGS)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgentString
                ?.replace(Regex("Mobile;? ?"), "")
                ?: userAgentString
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Handler(Looper.getMainLooper()).postDelayed({ dismissSplash() }, 350)
            }
        }

        webView.addJavascriptInterface(KidsBridge(this), "OnNowKids")

        val appUrl = getString(R.string.app_url).trim().trimEnd('/')
        webView.loadUrl("$appUrl/kids")

        splashSafetyHandler.postDelayed({ dismissSplash() }, 4000)

        maybePromptFirstRun()
    }

    /**
     * Whenever the system re-dispatches the HOME intent to us (the
     * user pressed the HOME button on the remote OR returned from
     * a transient external activity), require the PIN before doing
     * anything else.  This is the central trap that satisfies the
     * user's "can't exit by pressing HOME" rule.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            requestPin(PinIntent.EXIT_KIDS)
        }
    }

    override fun onResume() {
        super.onResume()
        // Defensive: if the activity comes back to the foreground
        // because of a HOME press (and onNewIntent wasn't called
        // because we were already foreground), still show the PIN.
        // The launching Intent's HOME category is the marker.
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true && pinOverlay.visibility != View.VISIBLE) {
            // No-op on first launch — we want the kid to see the
            // home page, not the PIN overlay on cold start.  Use a
            // SharedPreferences flag so subsequent HOME presses
            // (after the first foreground render) DO trigger the
            // gate.
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getBoolean("seen_home", false)) {
                requestPin(PinIntent.EXIT_KIDS)
            } else {
                prefs.edit().putBoolean("seen_home", true).apply()
            }
        }
    }

    /** Animate the splash overlay out. */
    private fun dismissSplash() {
        val sv = splashView ?: return
        if (splashDismissed) return
        splashDismissed = true
        splashSafetyHandler.removeCallbacksAndMessages(null)
        sv.animate().alpha(0f).setDuration(450).withEndAction {
            (sv.parent as? FrameLayout)?.removeView(sv)
            splashView = null
            window.setBackgroundDrawableResource(R.color.kids_bg)
        }.start()
    }

    private fun maybePromptFirstRun() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.contains(KEY_PIN)) return
        // Seed a default PIN and surface a one-time tip so the
        // parent knows to change it in Settings.
        prefs.edit().putString(KEY_PIN, DEFAULT_PIN).apply()
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(
                this,
                "Default Kids PIN is $DEFAULT_PIN — change it in Settings.",
                Toast.LENGTH_LONG,
            ).show()
        }, 1500)
    }

    /* ─────────────────── PIN overlay ─────────────────── */

    private fun requestPin(intent: PinIntent) {
        pendingIntent = intent
        pinBuffer.setLength(0)
        repaintPinDigits()
        pinTitle.text = when (intent) {
            PinIntent.EXIT_KIDS    -> "Enter parent PIN to exit"
            PinIntent.OPEN_SETTINGS -> "Enter parent PIN to open settings"
        }
        pinHint.text = "Press the number keys on your remote"
        pinOverlay.alpha = 0f
        pinOverlay.visibility = View.VISIBLE
        pinOverlay.animate().alpha(1f).setDuration(160).start()
        pinOverlay.requestFocus()
    }

    private fun dismissPinOverlay() {
        pinOverlay.animate().alpha(0f).setDuration(160).withEndAction {
            pinOverlay.visibility = View.GONE
        }.start()
    }

    private fun repaintPinDigits() {
        for (i in pinDigits.indices) {
            pinDigits[i].text = if (i < pinBuffer.length) "•" else ""
            pinDigits[i].setBackgroundResource(
                if (i == pinBuffer.length) R.drawable.kids_pin_box_focused
                else R.drawable.kids_pin_box_idle
            )
        }
    }

    private fun pushPinDigit(c: Char) {
        if (pinBuffer.length >= 4) return
        pinBuffer.append(c)
        repaintPinDigits()
        if (pinBuffer.length == 4) verifyPin()
    }

    private fun popPinDigit() {
        if (pinBuffer.isEmpty()) return
        pinBuffer.deleteCharAt(pinBuffer.length - 1)
        repaintPinDigits()
    }

    private fun verifyPin() {
        val expected = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        if (pinBuffer.toString() == expected) {
            dismissPinOverlay()
            when (pendingIntent) {
                PinIntent.OPEN_SETTINGS -> openKidsSettingsRoute()
                PinIntent.EXIT_KIDS     -> reallyExitToSystemHome()
            }
            return
        }
        pinHint.text = "Wrong PIN — try again"
        pinBuffer.setLength(0)
        // Brief shake animation on the row before the parent retries.
        pinOverlay.findViewById<View>(R.id.kids_pin_row)
            ?.animate()
            ?.translationXBy(24f)
            ?.setDuration(60L)
            ?.withEndAction {
                pinOverlay.findViewById<View>(R.id.kids_pin_row)
                    ?.animate()?.translationX(0f)?.setDuration(120L)?.start()
                repaintPinDigits()
            }?.start()
    }

    /** Route the WebView into the existing React `/kids/settings`
     *  page (PIN already passed — the React-side gate is bypassed
     *  via a URL flag the page recognises). */
    private fun openKidsSettingsRoute() {
        val appUrl = getString(R.string.app_url).trim().trimEnd('/')
        webView.loadUrl("$appUrl/kids/settings?gate=passed")
    }

    /** Launch the system "Select Home app" chooser so the parent
     *  can hand control back to the regular launcher, then move our
     *  task to background.  If they cancel the chooser they stay
     *  inside Kids — exactly the desired escape-hatch UX. */
    private fun reallyExitToSystemHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            // Show the chooser explicitly so the parent picks ANOTHER
            // home app (otherwise the system would just re-launch us).
            val chooser = Intent.createChooser(home, "Choose home app").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (_: Throwable) {
            // Last resort — drop the task so the user sees their
            // previous app.  They'll still hit our HOME intent next
            // time they press the button unless they choose another
            // launcher.
            moveTaskToBack(true)
        }
    }

    /* ─────────────────── Key handling ─────────────────── */

    /**
     * Pre-input filter: when the PIN overlay is up, intercept digit
     * keys + BACK so the WebView never sees them.  Otherwise pass
     * through to default handling.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && pinOverlay.visibility == View.VISIBLE) {
            val code = event.keyCode
            if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                pushPinDigit(('0' + (code - KeyEvent.KEYCODE_0)))
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_DEL -> { popPinDigit(); return true }
                KeyEvent.KEYCODE_BACK -> { dismissPinOverlay(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (pinOverlay.visibility == View.VISIBLE) {
            dismissPinOverlay()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        // We're at the root of the React /kids tree — the parent is
        // trying to leave Kids.  Show the PIN gate.
        requestPin(PinIntent.EXIT_KIDS)
    }

    fun launchExoPlayer(
        url: String,
        title: String?,
        subtitle: String?,
        posterUrl: String?,
    ) {
        if (url.isBlank()) return
        val intent = Intent(this, ExoPlayerActivity::class.java).apply {
            putExtra(ExoPlayerActivity.EXTRA_URL, url)
            putExtra(ExoPlayerActivity.EXTRA_TITLE, title.orEmpty())
            putExtra(ExoPlayerActivity.EXTRA_SUBTITLE, subtitle.orEmpty())
            putExtra(ExoPlayerActivity.EXTRA_POSTER_URL, posterUrl.orEmpty())
        }
        startActivity(intent)
    }

    /** Called from the React Settings page after the parent saves a
     *  new PIN — keeps the native gate in sync with React's local
     *  storage so they share a single source of truth. */
    fun savePinFromBridge(newPin: String) {
        if (newPin.length != 4 || newPin.any { !it.isDigit() }) return
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putString(KEY_PIN, newPin).apply()
    }

    companion object {
        private const val PREFS = "kids_kiosk"
        private const val KEY_PIN = "parent_pin"
        private const val DEFAULT_PIN = "0000"
    }
}

/**
 * JS-facing bridge exposed as `window.OnNowKids` to the React app.
 * Same contract as Vesper's `OnNowTV` / FTA's `OnNowFTA`, plus a
 * `savePin` hook so the React Settings page can update the native
 * PIN store atomically.
 */
class KidsBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun openExoPlayer(
        url: String?,
        title: String?,
        subtitle: String?,
        posterUrl: String?,
    ) {
        val u = url ?: return
        activity.runOnUiThread {
            activity.launchExoPlayer(u, title, subtitle, posterUrl)
        }
    }

    @JavascriptInterface
    fun isNativePlayerAvailable(): Boolean = true

    @JavascriptInterface
    fun savePin(newPin: String?) {
        val p = newPin ?: return
        activity.runOnUiThread { activity.savePinFromBridge(p) }
    }
}
