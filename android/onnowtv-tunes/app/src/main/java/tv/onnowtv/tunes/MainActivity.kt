package tv.onnowtv.tunes

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
 * ON NOW TV TUNES — kiosk WebView shell.
 *
 *   Hosts the same React frontend bundle that Vesper uses but boots
 *   directly into the `/music` route.  The shell exists so the Music
 *   app is a **separate Android application** (its own package id,
 *   its own launcher tile, its own admin slot, its own update
 *   cadence) — exactly as the user asked.  Compared to a "single
 *   APK with multiple modes" the operational story is much cleaner:
 *
 *     • Different package id → won't conflict with Vesper or the
 *       Launcher on the HK1.
 *     • Different signing key allowed → ON NOW TV staff can ship a
 *       Music-only patch without touching the Vesper signing chain.
 *     • Different update channel → the GitHub Releases tag this
 *       app reads is `tunes-latest`, NOT Vesper's `apk-latest`.
 *
 *   Implementation detail: hardware-accelerated WebView + HTML5
 *   `<audio>` from the React side gives us full playback of Deezer
 *   30-s previews, Radio Browser live streams, and podcast RSS
 *   enclosures with zero native plumbing required.
 *
 *   The base URL is read from `res/values/strings.xml` (`app_url`)
 *   so a single string change re-targets the entire app at a new
 *   backend without re-engineering.
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
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        setContentView(web)

        // Boot directly into /music — never the Vesper profile picker.
        val base = getString(R.string.app_url).trim().trimEnd('/')
        web.loadUrl("$base/music")
        webView = web
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private lateinit var webView: WebView
}
