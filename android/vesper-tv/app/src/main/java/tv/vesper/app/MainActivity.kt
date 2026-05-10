package tv.vesper.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Vesper TV — thin WebView wrapper that hosts the Vesper React web app.
 *
 *  - Forces landscape + immersive fullscreen (no system bars, no nav)
 *  - Keeps the screen on (TV remote may go idle)
 *  - Forwards remote BACK to in-app history when possible
 *  - JS, DOM-storage, hardware acceleration enabled (required for HLS.js)
 *  - Only loads the configured app URL — no arbitrary navigation
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lock landscape early so the WebView lays out correctly first time.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        applyImmersiveMode()

        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#06080F"))

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // Local-asset bundle needs file:// access; we still
                // disable arbitrary content:// access for safety.
                allowFileAccess = true
                allowContentAccess = false
                userAgentString = userAgentString + " OnNowTV/" + BuildConfig.VERSION_NAME
            }

            // Keep all navigation inside the WebView.
            webViewClient = VesperWebViewClient()
            webChromeClient = WebChromeClient()

            // Bridge so the web app can hand video off to VLC/MX Player.
            addJavascriptInterface(WebAppInterface(this@MainActivity), "OnNowTV")

            // Make the WebView itself focusable so the D-pad has somewhere
            // to land when the page first loads.
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        // System dark mode so the loading flash matches the page.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                webView.settings,
                WebSettingsCompat.FORCE_DARK_ON
            )
        }

        // On every new APK install, clear the WebView cache once so users
        // never see a stale React bundle from a previous version.
        val prefs = getSharedPreferences("onnowtv", MODE_PRIVATE)
        val lastVersion = prefs.getInt("last_version", 0)
        if (lastVersion != BuildConfig.VERSION_CODE) {
            webView.clearCache(true)
            webView.clearHistory()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            prefs.edit().putInt("last_version", BuildConfig.VERSION_CODE).apply()
        }

        setContentView(webView)

        webView.loadUrl("file:///android_asset/web/index.html")
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            controller?.hide(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
            controller?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    /**
     * The HK1 remote sends BACK as KEYCODE_BACK. Translate it to
     * web history navigation when possible so the user doesn't get
     * kicked out of the app on every detail-page back-press.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
