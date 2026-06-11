package tv.onnowtv.fta

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * ON NOW V2 — Free-to-Air kiosk WebView shell.
 *
 * Loads the React SPA at `${app_url}/fta`.  The actual EPG, HLS
 * preview, full-screen player, category side menu, favourites and
 * city selector all live in the React tree.  This Activity is a
 * deliberately thin WebView wrapper plus a branded splash that
 * sits on top of the page until React reports it's done loading.
 *
 * Splash strategy:
 *     1. `Theme.OnNowFta.Splash` sets the windowBackground to the
 *        red→orange gradient so the user sees the FTA brand the
 *        instant the launcher hands control over.  No white flash.
 *     2. onCreate inflates activity_main.xml — a FrameLayout that
 *        stacks the WebView and the `fta_splash` overlay (also a
 *        gradient + ON NOW V2 FREE TO AIR wordmark).
 *     3. WebViewClient.onPageFinished fades the overlay out across
 *        450 ms once the React app has hydrated.  A safety timer
 *        also dismisses the splash after 4 s in case the page
 *        finishes silently (mWebView is offline, etc.).
 *
 * Bridge:  exposes `window.OnNowFTA.openExoPlayer(...)` so the
 * React full-screen player can hand the HLS URL off to native
 * ExoPlayer (see ExoPlayerActivity).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var splashView: View? = null
    private var splashDismissed = false
    private val splashSafetyHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fully immersive — no status / nav bar.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.fta_webview)
        splashView = findViewById(R.id.fta_splash)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            // Desktop UA so HLS.js / MSE behaves as on a laptop browser.
            userAgentString = userAgentString
                ?.replace(Regex("Mobile;? ?"), "")
                ?: userAgentString
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Give React a beat to hydrate, then fade out the splash.
                Handler(Looper.getMainLooper()).postDelayed({ dismissSplash() }, 350)
            }
        }

        // Bridge for native ExoPlayer handoff.
        webView.addJavascriptInterface(FtaBridge(this), "OnNowFTA")

        val appUrl = getString(R.string.app_url).trim().trimEnd('/')
        webView.loadUrl("$appUrl/fta")

        // Safety net: if onPageFinished never fires (offline / slow
        // network), drop the splash anyway after 4 s.
        splashSafetyHandler.postDelayed({ dismissSplash() }, 4000)
    }

    /**
     * Animate the splash overlay out.  Idempotent — the safety
     * timer and `onPageFinished` race to call it; only the first
     * caller does any work.
     */
    private fun dismissSplash() {
        val sv = splashView ?: return
        if (splashDismissed) return
        splashDismissed = true
        splashSafetyHandler.removeCallbacksAndMessages(null)

        sv.animate()
            .alpha(0f)
            .setDuration(450)
            .withEndAction {
                (sv.parent as? FrameLayout)?.removeView(sv)
                splashView = null
                // After the splash leaves, swap the window
                // background back to plain black so we don't see
                // gradient bleed if the WebView ever clears.
                window.setBackgroundDrawableResource(R.color.fta_bg)
            }
            .start()
    }

    /**
     * Launch native ExoPlayer with the given HLS stream.  Called
     * from the React `FullScreenPlayer` component when running
     * inside this APK (`window.OnNowFTA.openExoPlayer(...)`).
     */
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

    override fun onBackPressed() {
        // The React app handles its own BACK chain (close side menu,
        // exit full-screen, etc.).  Only fall back to popping the
        // WebView history when the page has somewhere to go.
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

/**
 * JS-facing bridge object exposed as `window.OnNowFTA` to the React
 * SPA.  Every method MUST be annotated with `@JavascriptInterface`
 * or the WebView won't expose it.  Methods run on a Binder thread —
 * hop back to the UI thread before touching the Activity.
 */
class FtaBridge(private val activity: MainActivity) {

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

    /** Feature-detect hook for the React layer. */
    @JavascriptInterface
    fun isNativePlayerAvailable(): Boolean = true
}
