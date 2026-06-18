package tv.onnowtv.tunes

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

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
            webChromeClient = object : WebChromeClient() {
                // v2.8.82 — Phone-as-microphone WebRTC support.
                // The TV side of the karaoke flow opens a peer
                // connection to receive the singer's mic audio.
                // Android WebView blocks WebRTC media permissions by
                // default; we grant them automatically because the
                // bundled SPA is fully trusted (it's our own code).
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
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

    /** v2.10.42 — Boot flow reworked.
     *
     *  PREVIOUSLY: if the user wasn't signed in to YouTube we sent
     *  them STRAIGHT to `accounts.google.com/ServiceLogin?...`
     *  before React had a chance to render.  The React "Welcome to
     *  Tunes" popup (which explains the YouTube integration AND
     *  tells the user a fake/throwaway Google account is fine)
     *  therefore never showed on first launch — they jumped right
     *  into a bare Google sign-in card with zero context.
     *
     *  NOW: we ALWAYS load the React app first, no matter what
     *  cookie state we're in.  The React side renders the Welcome
     *  popup (gated by a localStorage flag → only on first launch),
     *  and when the user clicks "Got it, let's go" the React side
     *  calls `OnNowTV.startYouTubeSignIn()` which triggers the
     *  Google sign-in WebView swap.  After successful sign-in our
     *  watcher swings the WebView client back to the asset loader
     *  and reloads `/music` — by then the Welcome flag is set, so
     *  the user lands directly on Music Home with no further
     *  interruptions. */
    private fun bootFlow() {
        navigateToMusic()
    }

    /** v2.10.42 — Called from React (`OnNowTV.startYouTubeSignIn()`)
     *  when the user dismisses the Welcome popup AND is not yet
     *  signed in to YouTube.  Swaps the WebView client to the
     *  sign-in watcher and loads Google's ServiceLogin URL.  Must
     *  be invoked on the UI thread because we're touching the
     *  WebView. */
    fun startYouTubeSignInFromBridge() {
        runOnUiThread {
            if (isSignedInToYouTube()) {
                // Already signed in — nothing to do.  React will see
                // the same cookie state via `OnNowTV.isSignedInToYouTube()`.
                return@runOnUiThread
            }
            Toast.makeText(
                this,
                "Sign in to YouTube · use any account, fake is fine",
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
        // v2.8.72 — Load the React app via WebViewAssetLoader so the
        // WebView sees an HTTPS origin (`https://appassets.androidplatform.net/`)
        // instead of `file:///`.  Same physical files (bundled in
        // `assets/web/`), totally different origin behaviour:
        //
        //   • YouTube IFrame Player's postMessage works (the iframe
        //     needs a non-null parent origin to send onReady /
        //     onStateChange / time-update events).  Without this,
        //     the player can SEEM to play but audio is silently
        //     suppressed — exactly the symptom the user saw after
        //     v2.8.70 switched Tunes from a remote URL to file://.
        //   • Cross-origin <audio> playback works without the file://
        //     "secure-degraded" quirks some WebView versions apply.
        //   • localStorage / sessionStorage work normally (some
        //     WebView versions disable them on file://).
        //   • The build workflow's absolute→relative path rewrite
        //     (see v2.8.71) plus this `/assets/` handler combine so
        //     every asset reference resolves correctly.  Relative
        //     `./static/js/x.js` in index.html → `https://...net/
        //     assets/web/static/js/x.js` → handler strips `/assets/`
        //     → AssetsPathHandler looks up `assets/web/static/js/x.js`.
        //
        // `REACT_APP_BACKEND_URL=https://onnowtv.duckdns.org` is still
        // baked into the JS at build time so API calls continue to
        // hit the live backend — only the static SPA shell loads
        // from the on-device asset server.
        webView.webViewClient = AssetLoaderClient(assetLoader)
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html?box=1&yt=1#/music")
    }

    /** v2.8.72 — WebViewClient that routes every request through
     *  the asset loader so `https://appassets.androidplatform.net/...`
     *  is served from the APK's bundled `assets/web/` folder. */
    private class AssetLoaderClient(
        private val loader: WebViewAssetLoader,
    ) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
    }

    private val assetLoader by lazy {
        // Handler at `/assets/` maps the URL path AFTER the prefix
        // directly into the APK's `assets/` directory.  Because we
        // bundle the React build under `assets/web/`, the URL
        // `/assets/web/static/js/x.js` correctly resolves to the
        // file `app/src/main/assets/web/static/js/x.js`.
        WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    /** v2.10.43 — Give React first dibs on the BACK key.
     *
     *  PREVIOUSLY: this method called `webView.goBack()` unconditionally
     *  whenever WebView history was non-empty.  Two problems with that:
     *
     *    1.  On the FullScreenPlayer overlay (which is just an
     *        absolute-positioned React component, NOT a new URL),
     *        BACK should close the overlay and reveal the Album /
     *        Artist / Search page underneath.  Instead, `goBack()`
     *        navigated the WebView to the previous URL in history —
     *        often the YouTube sign-in page — and the React app got
     *        re-rendered there, dumping the user on a blank or
     *        unexpected screen.
     *
     *    2.  ANY React-level overlay (Welcome popup, queue panel,
     *        settings drawer) couldn't intercept BACK either,
     *        because the native side never asked.
     *
     *  NOW: we evaluate `window.__onnowtv_handleBack()` first.  React
     *  exposes that function from `MusicLayout`.  It returns "1" if
     *  some React component consumed the BACK (overlay closed,
     *  player collapsed, etc.) and "0" otherwise.  Only on "0" do
     *  we fall back to the old `goBack` / `super.onBackPressed`
     *  behaviour.  evaluateJavascript is async so we have to defer
     *  the fallback into the callback. */
    @Deprecated("Kept for backwards-compat with older Android SDKs")
    override fun onBackPressed() {
        if (!::webView.isInitialized) {
            super.onBackPressed()
            return
        }
        webView.evaluateJavascript(
            "(function(){try{return (typeof window.__onnowtv_handleBack==='function' && window.__onnowtv_handleBack())?'1':'0';}catch(e){return '0';}})()",
        ) { result ->
            val handled = result?.trim('"') == "1"
            if (handled) {
                // React consumed the BACK (e.g. closed the
                // FullScreenPlayer overlay).  Stay on the current URL.
                return@evaluateJavascript
            }
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                // Nothing left in WebView history AND React didn't
                // consume — exit the app cleanly.  We can't call
                // `super.onBackPressed()` from inside this callback
                // (only valid inside the override body), so use
                // finish() which has the same end result.
                finish()
            }
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
