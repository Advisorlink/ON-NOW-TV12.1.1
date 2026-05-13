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
    private var pendingVoiceCallbackId: String? = null

    /** Launches the system speech recognizer and routes the result
     *  back to the React side via window.__voiceSearchResult(id,...). */
    fun startVoiceRecognition(callbackId: String) {
        pendingVoiceCallbackId = callbackId
        try {
            val intent = android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_PROMPT,
                    "Say a movie or show name"
                )
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_MAX_RESULTS,
                    1
                )
            }
            startActivityForResult(intent, REQ_VOICE_SEARCH)
        } catch (e: Exception) {
            dispatchVoiceResult(callbackId, null, "no-recognizer")
            pendingVoiceCallbackId = null
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_SEARCH) {
            val cbId = pendingVoiceCallbackId ?: return
            pendingVoiceCallbackId = null
            if (resultCode != RESULT_OK || data == null) {
                dispatchVoiceResult(cbId, null, "cancelled")
                return
            }
            val results = data.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS
            )
            val text = results?.firstOrNull()
            if (text.isNullOrBlank()) {
                dispatchVoiceResult(cbId, null, "empty")
            } else {
                dispatchVoiceResult(cbId, text, null)
            }
        }
    }

    private fun dispatchVoiceResult(
        callbackId: String,
        text: String?,
        error: String?
    ) {
        val esc = { s: String? -> (s ?: "").replace("\\", "\\\\").replace("'", "\\'") }
        val js = "window.__voiceSearchResult && window.__voiceSearchResult(" +
            "'${esc(callbackId)}', '${esc(text)}', '${esc(error)}')"
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    companion object {
        private const val REQ_VOICE_SEARCH = 9201
    }

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

            // Force the WebView onto a dedicated hardware layer so
            // every paint (and especially shelf scroll transforms)
            // is GPU-composited.  On the HK1's old Mali GPU this is
            // the difference between 30 fps stuttery scroll and a
            // buttery 60 fps LeanBack-style glide.
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            // Bonus on Android 9+: lets the WebView's compositor
            // render off the UI thread (huge win for D-pad nav).
            android.webkit.WebView.setWebContentsDebuggingEnabled(false)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = true
                allowContentAccess = false
                @Suppress("DEPRECATION")
                setEnableSmoothTransition(true)
                // Boost render priority so the WebView's compositor
                // gets first dibs on each frame.  Deprecated on
                // Chrome WebView ≥ 56 but still honoured on older
                // Chinese AOSP WebViews (Chrome 49-55 territory).
                @Suppress("DEPRECATION")
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                // Force-disable text autosizing — these heuristics
                // run on every layout pass.  We control font sizing
                // explicitly via clamp() so the autosizer is wasted
                // CPU.
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                @Suppress("DEPRECATION")
                setDefaultZoom(WebSettings.ZoomDensity.FAR)
                userAgentString = userAgentString + " OnNowTV/" + BuildConfig.VERSION_NAME
            }

            // Smooth-scroll the inner WebView content frame.  Both
            // these knobs matter for D-pad-driven horizontal scrolls.
            isScrollbarFadingEnabled = true
            scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            isHorizontalFadingEdgeEnabled = false
            isVerticalFadingEdgeEnabled = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            webViewClient = VesperWebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(WebAppInterface(this@MainActivity), "OnNowTV")

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

        // Dev-mode URL.  When set (via the splash diagnostic
        // "Try loading from network" button, or via Settings →
        // Developer), the WebView loads this URL instead of the
        // bundled `file:///android_asset/web/index.html`.  Lets us
        // iterate on the React side without rebuilding the APK.
        val devPrefs = getSharedPreferences("onnowtv-dev", MODE_PRIVATE)
        val devUrl = devPrefs.getString("dev_url", null)?.takeIf { it.startsWith("http") }
        val bootUrl = devUrl ?: "file:///android_asset/web/index.html"

        setContentView(webView)
        webView.loadUrl(bootUrl)
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
     * The HK1 remote sends BACK as KEYCODE_BACK.  Behaviour:
     *
     *   • If the WebView's current page sets `window.__vesperOnHome
     *     === 'home-root'` → pop the "Close ON NOW TV?" confirm
     *     dialog instead of unwinding history all the way back to
     *     the launcher.
     *   • Otherwise fall back to the normal goBack / finish flow,
     *     which keeps Detail / Sources / Settings working as before.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript("(window.__vesperOnHome||'')") { raw ->
                val flag = raw?.trim('"') ?: ""
                runOnUiThread {
                    when (flag) {
                        "home-root" -> showExitConfirm()
                        else -> {
                            if (webView.canGoBack()) webView.goBack()
                            else finish()
                        }
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showExitConfirm() {
        // Build a fully custom Vesper-themed exit sheet instead of
        // the stock AlertDialog.  Uses our inflated dialog layout
        // with neon-blue accent buttons and a glass-card background.
        val view = layoutInflater.inflate(
            R.layout.dialog_exit_confirm, null
        )
        val dialog = androidx.appcompat.app.AlertDialog.Builder(
            this,
            androidx.appcompat.R.style.Theme_AppCompat_Dialog
        )
            .setView(view)
            .setCancelable(true)
            .create()

        // Transparent decor window so our drawable corner radius
        // shows through (default would put a white rectangle behind).
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT
            )
        )
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        val btnCancel = view.findViewById<android.widget.Button>(
            R.id.exit_btn_cancel
        )
        val btnClose = view.findViewById<android.widget.Button>(
            R.id.exit_btn_close
        )

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnClose.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.setOnShowListener {
            // Land focus on Cancel ("Stay") so the safer action is
            // the default — the user has to explicitly press Right
            // to land on "Close app".
            btnCancel.requestFocus()
        }

        dialog.show()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
