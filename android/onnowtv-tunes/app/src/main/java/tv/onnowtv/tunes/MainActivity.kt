package tv.onnowtv.tunes

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * ON NOW TV TUNES — kiosk WebView shell with per-user YouTube sign-in.
 *
 *   v2.8.54 — Every box owns its own YouTube session.
 *
 *   Boot flow:
 *
 *     1. Inspect Android's process-wide CookieManager for the
 *        `LOGIN_INFO` / `SAPISID` cookies on `.youtube.com`.
 *
 *     2. If found → user has signed in before → jump straight to
 *        `<base>/music?box=1&yt=1`.
 *
 *     3. If missing → load Google's sign-in URL with
 *        `service=youtube&continue=…` so completion redirects to
 *        youtube.com.  Our WebViewClient watches for that redirect,
 *        verifies the cookies are now in place, and only then
 *        navigates to /music.
 *
 *   Why this is the right architecture:
 *
 *     • **No admin maintenance** — each box maintains its own
 *       session, so we never have to rotate a shared cookies file.
 *
 *     • **Per-box rate limits** — YouTube treats each session
 *       independently, so one box getting throttled doesn't take
 *       down anyone else.
 *
 *     • **Privacy** — cookies live in WebView storage on the box.
 *       Zero cookies ever touch our VPS.
 *
 *     • **YouTube Premium auto-detected** — if the user signs in
 *       with a Premium account, the IFrame player picks that up
 *       and skips ads automatically.  No code change needed.
 *
 *   Sign-out: clear app data from Android settings, or implement
 *   a `OnNowTV.signOut()` bridge method later (out of scope here).
 */
class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Make the chrome fully immersive — TV viewers should never see
        // a status bar or nav bar peeking through.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val web = WebView(this).apply {
            settings.run {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                loadWithOverviewMode = true
                useWideViewPort = true
                // v2.8.54 — Strip the "; wv" marker from the UA so
                // Google's sign-in flow doesn't refuse "this browser
                // may not be secure".  WebView UA otherwise looks
                // exactly like Chrome.
                userAgentString = userAgentString
                    .replace("; wv)", ")")
                    .replace(" wv ", " ")
            }
            webChromeClient = WebChromeClient()
        }
        // Native bridge — exposes `window.OnNowTV.resolveYouTubeAudio(...)`
        // so the React music player can resolve full-length YouTube
        // streams from the BOX's residential IP (bypassing the
        // bot detection that blocks our datacenter VPS).
        web.addJavascriptInterface(OnNowTvBridge(web), "OnNowTV")
        setContentView(web)

        // Make sure cookies persist across launches and the
        // YouTube IFrame Player can read them.
        CookieManager.getInstance().run {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        webView = web
        bootFlow()
    }

    /** Decide whether to show the sign-in flow or jump straight
     *  to /music. */
    private fun bootFlow() {
        if (isSignedInToYouTube()) {
            navigateToMusic()
        } else {
            // v2.8.58 — Reverted auto-fill.  Google's anti-automation
            // flagged the programmatic value-setter and the sign-in
            // would silently fail or get held in a "Couldn't verify
            // it's you" loop.  Manual sign-in only.  WebView cookies
            // persist across normal launches; only `uninstall →
            // reinstall` wipes them.
            Toast.makeText(
                this,
                "Sign in to YouTube to play music · use any account, fake is fine",
                Toast.LENGTH_LONG,
            ).show()
            webView.webViewClient = signInWatcherClient()
            webView.loadUrl(SIGN_IN_URL)
        }
    }

    /** Probe the process-wide cookie store for the cookies that
     *  signed-in YouTube sessions always set. */
    private fun isSignedInToYouTube(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
        return cookies.contains("LOGIN_INFO") || cookies.contains("SAPISID")
    }

    /** WebViewClient that watches for the YouTube homepage URL that
     *  Google redirects to after a successful sign-in.  Verifies
     *  cookies are present, then advances to /music. */
    private fun signInWatcherClient() = object : WebViewClient() {
        private var advanced = false

        override fun onPageFinished(view: WebView?, url: String?) {
            if (advanced || url == null) return
            val onYouTube = url.startsWith("https://www.youtube.com/")
                    || url.startsWith("https://m.youtube.com/")
            val isAuthPage = url.contains("signin")
                    || url.contains("ServiceLogin")
                    || url.contains("accounts.google.com")
            if (onYouTube && !isAuthPage && isSignedInToYouTube()) {
                advanced = true
                CookieManager.getInstance().flush()
                Toast.makeText(
                    this@MainActivity,
                    "Signed in — loading music",
                    Toast.LENGTH_SHORT,
                ).show()
                navigateToMusic()
            }
        }
    }

    private fun navigateToMusic() {
        // v2.8.70 — Load the React app from BUNDLED ASSETS instead
        // of the live VPS URL.  The build-tunes.yml workflow runs
        // `yarn build` and copies frontend/build/ into the Tunes
        // assets folder before assembling the APK, so the entire
        // SPA ships INSIDE the APK.  Every "Save to GitHub" → new
        // APK now ALSO carries the latest JS/CSS, instead of the
        // user having to also manually deploy the React bundle to
        // the VPS (which they were never doing — that's why my
        // earlier "no Vesper menu" / scroll fixes never appeared
        // on the phone).
        //
        // `REACT_APP_BACKEND_URL=https://onnowtv.duckdns.org` is
        // already baked into the JS at build time, so API calls
        // continue to hit the live backend.  Only the static
        // shell loads from `file:///`.
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/web/index.html?box=1&yt=1#/music")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private lateinit var webView: WebView

    companion object {
        private const val SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin" +
            "?service=youtube" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2F" +
            "&hl=en"
    }
}
