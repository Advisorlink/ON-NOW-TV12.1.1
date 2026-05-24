package tv.vesper.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
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
    /* Public accessor used by [WebAppInterface] when it needs to
     * post update-progress events back into the page via
     * `evaluateJavascript`.  Returns null until the WebView has
     * been instantiated by `onCreate`. */
    internal fun webViewOrNull(): WebView? =
        if (this::webView.isInitialized) webView else null
    /** Set to true the moment WebView creation succeeds — prevents
     *  lifecycle methods from crashing if WebView init failed
     *  (e.g. a phone without Android System WebView installed). */
    private var webViewReady: Boolean = false
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
        if (!webViewReady) return
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

        // v2.7.82 SECURITY — FLAG_SECURE blocks the OS from screen-
        // shotting the activity (including from the recents
        // task-switcher, screen-recording APIs, and Chromecast
        // mirroring), and forces the surface contents to be
        // unreadable to screen-share daemons.  Combined with R8
        // obfuscation + integrity guards, this means a determined
        // attacker can't even capture the UI's pixels to extract
        // brand assets / UI screenshots for re-skin.
        //
        // v2.7.89 — Temporarily disabled so the user can screenshot
        // the on-screen debug overlay we just added to diagnose the
        // mobile touch / player bugs.  Re-enable in a future build
        // once the bugs are fixed.  The single-line toggle below
        // is the ONLY place to flip this.
        val secureFlagEnabled = false
        if (secureFlagEnabled) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        /* If the previous run crashed, the global handler in
           OnNowApplication captured the stack trace to disk.  Show
           it on screen with a Share / Copy / Dismiss row so the user
           can send us actionable diagnostic info instead of seeing
           a silent close.  We render the screen first and skip the
           rest of onCreate — the user dismisses it to retry boot. */
        val prevCrash = OnNowApplication.lastCrash
        if (prevCrash != null && savedInstanceState == null) {
            showCrashReport(prevCrash)
            return
        }

        /* v2.7.86 — One-time migration: force ExoPlayer as the
           player backend on first launch of this build for users
           whose phone has somehow ended up on LibVLC despite the
           default being ExoPlayer (most likely because a previous
           Settings tap stuck in SharedPreferences).
           Watch Together stream-sync only works on ExoPlayer, so
           an accidental LibVLC pref breaks the party-sync
           feature. Marker pref ensures this only runs once — the
           user can still opt back to LibVLC via Settings.        */
        run {
            val mig = getSharedPreferences("onnowtv-migrations", MODE_PRIVATE)
            val key = "force_exo_v2_7_86"
            if (!mig.getBoolean(key, false)) {
                getSharedPreferences("vesper_player", MODE_PRIVATE)
                    .edit()
                    .putBoolean(ExoPlayerActivity.PREF_KEY_USE_EXO, true)
                    .apply()
                mig.edit().putBoolean(key, true).apply()
                android.util.Log.i(
                    "VesperMain",
                    "v2.7.86 migration: forced player backend → ExoPlayer (once)"
                )
            }
        }

        // Detect Android TV vs phone via the LEANBACK system feature.
        // ALSO falls back to UI_MODE_TYPE_TELEVISION for cheap Chinese
        // AOSP boxes that don't always declare leanback but DO ship
        // the TV UI mode.
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION

        // Only lock orientation on TV.  On phones we let the OS pick
        // — forcing landscape on a phone with portrait rotation-lock
        // throws `IllegalStateException: Only fullscreen opaque
        // activities can request orientation` (combined with the
        // translucent system bars in Theme.Vesper.Fullscreen).
        // The React UI auto-adapts via useIsMobile() / data-platform
        // so phone users get the bottom-nav mobile UI in portrait.
        if (isTv) {
            try {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } catch (_: IllegalStateException) {
                /* swallow — manifest already provides a fallback */
            }
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // NOTE: applyImmersiveMode() is intentionally NOT called
        // here.  On Android 16 (Pixel 8, Samsung Fold 7) the
        // window.insetsController is null until the DecorView is
        // created — which happens when setContentView() runs.
        // Calling immersive mode here threw a NullPointerException
        // and crashed the app on launch.  We rely on the call from
        // onWindowFocusChanged() below to apply immersive mode once
        // the WebView has been attached.

        webView = try {
            WebView(this)
        } catch (e: Throwable) {
            // Some phones (especially Huawei without GMS, certain
            // custom ROMs) can throw `MissingWebViewPackageException`
            // or `RuntimeException` here if the system WebView
            // provider is disabled / corrupted.  Show a friendly
            // error screen instead of crashing the process — gives
            // the user something actionable rather than an instant
            // close.
            val msg = "WebView not available on this device.\n\n" +
                "Please install or enable Android System WebView " +
                "from the Play Store, then re-launch ON NOW TV.\n\n" +
                "Details: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            val tv = android.widget.TextView(this).apply {
                text = msg
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#06080F"))
                setPadding(48, 48, 48, 48)
                textSize = 16f
            }
            setContentView(tv)
            return
        }
        webView.apply {
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
                // v2.7.80 SECURITY — Lock down file:// access.
                // We never load file URLs from anywhere except our
                // bundled assets, and we MUST forbid them entirely
                // from chaining into universal-origin reads
                // (CVE-class XSS-to-disk-read pattern).
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
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

            // v2.7.80 SECURITY — Refuse all WebView downloads.  The
            // app uses its own Update Gate / Premiumize HTTP fetches
            // for legitimate downloads; an unprompted WebView
            // download triggered by a malicious page (or an injected
            // redirect) is always a bug, not a feature.
            setDownloadListener { _, _, _, _, _ ->
                android.util.Log.w("MainActivity", "WebView download blocked")
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
            // v2.7.55 — Custom WebChromeClient that grants the WebView
            // microphone access (needed for the Watch Together voice
            // reactions feature).  RECORD_AUDIO is already declared
            // in the manifest, so this only forwards the WebView's
            // request to the Android system mic.
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    if (request == null) return
                    val wanted = request.resources
                    val allowed = wanted.filter {
                        it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }.toTypedArray()
                    if (allowed.isNotEmpty()) {
                        request.grant(allowed)
                    } else {
                        request.deny()
                    }
                }
            }
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

        // v2.7.46 — Restore the WebView's last URL if MainActivity
        // was killed by Android during ExoPlayer playback (common on
        // the HK1 box's limited RAM during HEVC 1080p decode).
        // Without this, the player closes → user lands on the boot
        // URL (home) instead of the detail page they came from.
        //
        // We only restore if:
        //   1. The saved entry exists and is < 30 minutes old.
        //   2. There's no `dev_url` override.
        //   3. The saved URL is a same-origin hash route (starts with
        //      the bundled `file:///android_asset/web/index.html#…`
        //      or the dev URL) — never trust a random saved URL.
        val savedPrefs = getSharedPreferences("onnowtv_route", MODE_PRIVATE)
        val savedUrl = savedPrefs.getString("last_url", null)
        val savedTs  = savedPrefs.getLong("last_ts", 0L)
        val savedFresh = (System.currentTimeMillis() - savedTs) < 30 * 60 * 1000L
        val defaultBoot = "file:///android_asset/web/index.html"
        val restoreUrl = savedUrl?.takeIf {
            savedFresh && devUrl == null &&
                (it.startsWith(defaultBoot) ||
                 it.startsWith("file:///android_asset/web/index.html"))
        }
        val bootUrl = devUrl ?: restoreUrl ?: defaultBoot

        setContentView(webView)
        webViewReady = true
        webView.loadUrl(bootUrl)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        if (webViewReady) webView.onResume()
        consumeNextEpisodeIntent()
    }

    /**
     * If `VlcPlayerActivity` saved a "next-episode" intent before
     * finishing (either user pressed "Next Episode" or the episode
     * ended naturally), navigate the WebView to the series page so
     * the user lands on either:
     *   - the episode picker scrolled to the next episode (autoplay=false)
     *   - the next episode auto-playing (autoplay=true)
     *
     * Cleared after consumption so a stale intent never re-fires.
     * Older than 30 s also cleared (defensive).
     */
    private fun consumeNextEpisodeIntent() {
        if (!webViewReady) return
        val sp = getSharedPreferences("onnowtv_next_intent", MODE_PRIVATE)
        val ts = sp.getLong("ts", 0L)
        if (ts == 0L) return
        // Read every field before clearing.
        val imdb = sp.getString("imdb_id", null)
        val s = sp.getInt("season", 0)
        val e = sp.getInt("episode", 0)
        val autoplay = sp.getBoolean("autoplay", false)
        sp.edit().clear().apply()
        if (imdb.isNullOrBlank()) return
        if (System.currentTimeMillis() - ts > 30_000L) return
        // Build the target URL.  Detail.jsx supports
        // `episodeAutoplay=1&season=&episode=` (added in v2.6.13)
        // for direct autoplay of a specific episode without going
        // through the manual picker.  When autoplay=false we just
        // show the episode picker focused on the next episode.
        val hash = if (autoplay) {
            "#/title/series/${imdb}?episodeAutoplay=1&season=${s}&episode=${e}"
        } else {
            "#/title/series/${imdb}?focusSeason=${s}&focusEpisode=${e}"
        }
        val js = "window.location.hash = '${hash}';"
        webView.post {
            try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    override fun onPause() {
        super.onPause()
        if (webViewReady) webView.onPause()
        // v2.7.46 — persist current WebView URL so we can restore it
        // if Android kills MainActivity during ExoPlayer playback
        // (common on the HK1's limited RAM).  See onCreate() for the
        // restore logic.
        try {
            val cur = webView.url
            if (!cur.isNullOrBlank() &&
                cur.startsWith("file:///android_asset/web/index.html")
            ) {
                getSharedPreferences("onnowtv_route", MODE_PRIVATE).edit()
                    .putString("last_url", cur)
                    .putLong("last_ts", System.currentTimeMillis())
                    .apply()
            }
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        /* On Android 16 (SDK 36) `window.insetsController` returns
           null until the DecorView has been created — which only
           happens once `setContentView()` runs OR something forces
           the decor view to instantiate.  Touching `window.decorView`
           here forces creation; without it the next line NPEs and
           crashes the app at onCreate time on Pixel 8 / Fold 7 /
           every Android 16 device. */
        try {
            window.decorView
        } catch (_: Throwable) { /* swallow */ }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(false)
            } catch (_: Throwable) { /* swallow — Fold's outer cover
                screen has thrown IllegalStateException here in some
                One UI builds */ }
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
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
            if (!webViewReady) {
                finish()
                return true
            }
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
        if (webViewReady) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    /** Renders a black screen with the previous crash's stack trace
     *  and Share / Copy / Dismiss buttons.  Dismiss clears the
     *  on-disk crash log and re-launches MainActivity so the user
     *  can try to boot normally again. */
    private fun showCrashReport(crashText: String) {
        val root = android.widget.ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#06080F"))
            isFillViewport = true
        }
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        val title = android.widget.TextView(this).apply {
            text = "ON NOW TV couldn't start last time"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            setPadding(0, 0, 0, 16)
        }
        val sub = android.widget.TextView(this).apply {
            text = "Here is the exact error.  Tap Share to send it " +
                "to the developer — it tells us exactly what went " +
                "wrong on your device."
            setTextColor(android.graphics.Color.parseColor("#A8B5C7"))
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        val box = android.widget.TextView(this).apply {
            text = crashText
            setTextColor(android.graphics.Color.parseColor("#FCA5A5"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(android.graphics.Color.parseColor("#0D1422"))
            setPadding(28, 24, 28, 24)
        }
        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }
        val shareBtn = android.widget.Button(this).apply {
            text = "Share"
            setOnClickListener {
                try {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "ON NOW TV crash report")
                        putExtra(android.content.Intent.EXTRA_TEXT, crashText)
                    }
                    startActivity(android.content.Intent.createChooser(send, "Share crash report"))
                } catch (_: Throwable) { /* no chooser available */ }
            }
        }
        val copyBtn = android.widget.Button(this).apply {
            text = "Copy"
            setOnClickListener {
                try {
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ON NOW TV crash", crashText))
                    android.widget.Toast.makeText(
                        this@MainActivity, "Crash report copied",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Throwable) { /* swallow */ }
            }
        }
        val dismissBtn = android.widget.Button(this).apply {
            text = "Try again"
            setOnClickListener {
                /* Clear the on-disk log so a clean launch doesn't
                   keep showing the same report forever. */
                try {
                    java.io.File(filesDir, OnNowApplication.CRASH_LOG_NAME).delete()
                    getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let {
                        java.io.File(it, OnNowApplication.CRASH_LOG_NAME).delete()
                    }
                } catch (_: Throwable) { /* swallow */ }
                OnNowApplication.lastCrash = null
                /* Relaunch ourselves cleanly. */
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    launch.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    startActivity(launch)
                }
                finish()
            }
        }
        buttonRow.addView(shareBtn)
        buttonRow.addView(copyBtn)
        buttonRow.addView(dismissBtn)
        col.addView(title)
        col.addView(sub)
        col.addView(box)
        col.addView(buttonRow)
        root.addView(col)
        setContentView(root)
    }
}
