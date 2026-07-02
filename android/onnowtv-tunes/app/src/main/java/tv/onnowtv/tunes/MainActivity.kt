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
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

/**
 * ON NOW TV TUNES — kiosk WebView shell (NewPipeExtractor-only).
 *
 *   v2.12.0 — YouTube sign-in flow removed entirely.
 *
 *   Boot flow:
 *
 *     1. Immersive full-screen setup.
 *     2. Load the bundled React SPA directly at `/music` via
 *        WebViewAssetLoader — no login, no cookies, no gate.
 *
 *   Track resolution is handled 100 % on-device by
 *   `NewPipeExtractor` via the `OnNowTV.resolveYouTubeAudio(...)`
 *   JS bridge.  NewPipe scrapes YouTube's public web pages
 *   anonymously from this box's residential IP, returning direct
 *   googlevideo.com CDN URLs the HTML5 `<audio>` element streams
 *   with zero backend involvement.
 *
 *   Why this is the right architecture:
 *
 *     • **Zero admin maintenance** — no cookies to rotate, no
 *       YouTube accounts to babysit.
 *     • **Zero sign-in friction** — user opens Tunes and plays
 *       music in one tap.
 *     • **Ad-free audio bytes** — the CDN stream itself has no
 *       ad inserts; ads are injected only by YouTube's player UI
 *       which we never use.
 *     • **Per-box egress** — every request originates from the
 *       user's home Wi-Fi, so datacenter bot-detection doesn't
 *       apply.
 *     • **Privacy** — no cookies ever touch our VPS.
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
            }
            webChromeClient = object : WebChromeClient() {
                // Phone-as-microphone WebRTC support for the karaoke
                // flow.  Bundled SPA is fully trusted (our own code).
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
        }
        // Native bridge — exposes `window.OnNowTV.resolveYouTubeAudio(...)`
        // so the React music player can resolve full-length YouTube
        // streams from the BOX's residential IP via NewPipeExtractor.
        web.addJavascriptInterface(OnNowTvBridge(web), "OnNowTV")
        setContentView(web)

        // v2.12.0 — Cookie store still enabled because the IFrame
        // Player fallback (rare case where NewPipe fails) benefits
        // from persistent visitor cookies.  No sign-in required.
        CookieManager.getInstance().run {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        webView = web
        webView.webViewClient = AssetLoaderClient(assetLoader)
        // Load the bundled React SPA directly — no sign-in gate.
        webView.loadUrl(
            "https://appassets.androidplatform.net/assets/web/index.html?box=1&yt=1#/music",
        )
    }

    /** WebViewClient that routes every request through the asset
     *  loader so `https://appassets.androidplatform.net/...` is
     *  served from the APK's bundled `assets/web/` folder. */
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

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // v2.12.9 — Kill the audio when the app leaves the foreground
    // (HOME button, or the final BACK that finishes the activity).
    // Without this the WebView keeps playing music behind the
    // launcher's home screen.
    override fun onStop() {
        super.onStop()
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "(function(){try{" +
                    "var e=window.__musicEngine;" +
                    "if(e){try{e.pause()}catch(x){}" +
                    "try{e.audio&&e.audio.pause()}catch(x){}" +
                    "try{e.yt&&e.yt.pauseVideo&&e.yt.pauseVideo()}catch(x){}}" +
                    "var m=document.querySelectorAll('audio,video');" +
                    "for(var i=0;i<m.length;i++){try{m[i].pause()}catch(x){}}" +
                    "}catch(x){}})();",
                null,
            )
            webView.onPause()
            webView.pauseTimers()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::webView.isInitialized) {
            // Resume the WebView itself but do NOT auto-resume
            // playback — the user presses play again if they want it.
            webView.resumeTimers()
            webView.onResume()
        }
    }

    private lateinit var webView: WebView
}
