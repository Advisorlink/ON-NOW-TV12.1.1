package tv.onnowtv.trivia

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

/**
 * ON NOW TRIVIA — kiosk WebView shell.
 *
 *   Boot flow:
 *
 *     1. Immersive full-screen setup (KEEP_SCREEN_ON — a trivia
 *        lobby can idle for a while before the host starts).
 *     2. Load the bundled React SPA at `#/trivia` via
 *        WebViewAssetLoader — no login, no profile gate.
 *
 *   The TV screen creates a room over the production backend
 *   (REACT_APP_BACKEND_URL baked into the bundle) and shows a QR
 *   code; guests scan it with their phones which open the public
 *   `/trivia/play?room=CODE` web route and become the buzzers.
 *   All game state flows over the `/api/trivia/ws/{code}` WebSocket.
 *
 *   `mediaPlaybackRequiresUserGesture = false` lets the lobby
 *   jingle / buzzer / fanfare sound pack autoplay on the box.
 */
class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore the normal NoActionBar theme AFTER the manifest-declared
        // Splash theme has painted its windowBackground.
        setTheme(R.style.Theme_OnNowTrivia_NoActionBar)
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
            webChromeClient = WebChromeClient()
        }
        setContentView(web)

        webView = web
        webView.webViewClient = AssetLoaderClient(assetLoader)
        webView.loadUrl(
            "https://appassets.androidplatform.net/assets/web/index.html?box=1#/trivia",
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

    // Silence the lobby jingle when the app leaves the foreground —
    // without this the WebView keeps looping audio behind the
    // launcher home screen.
    override fun onStop() {
        super.onStop()
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "(function(){try{" +
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
            webView.resumeTimers()
            webView.onResume()
        }
    }

    private lateinit var webView: WebView
}
