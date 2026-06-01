package tv.onnowtv.fta

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * ON NOW V2 — Free-to-Air kiosk WebView shell.
 *
 * The whole app (EPG grid, HLS preview, full-screen player, category
 * tabs, favourites, city selector) is the React SPA at
 * `${app_url}/fta`.  This Activity is a deliberately thin WebView
 * wrapper: no auth, no NewPipe, no native bridge.  We just configure
 * the WebView for hardware-accelerated HLS playback and load the
 * page.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

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

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            // Use a desktop UA so YouTube / HLS.js paths behave like
            // they do on a laptop browser.  hls.js relies on MSE which
            // some mobile UAs trigger HLS-native handoff for — the
            // desktop UA keeps us on the same code path we tested.
            settings.userAgentString = settings.userAgentString
                ?.replace(Regex("Mobile;? ?"), "")
                ?: settings.userAgentString
        }
        setContentView(webView)

        val appUrl = getString(R.string.app_url).trim().trimEnd('/')
        // Land directly on the FTA route — no redirect dance.
        webView.loadUrl("$appUrl/fta")
    }

    override fun onBackPressed() {
        // The React app has its own back-handling (Escape/BACK exits
        // full-screen player → returns to grid).  Only fall back to
        // exiting the Activity if there's nowhere to go in WebView
        // history.
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
