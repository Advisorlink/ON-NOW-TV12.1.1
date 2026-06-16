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

    /**
     * v2.10.31 — WebView file-chooser support.
     *
     * Android's stock `WebView` does NOTHING when an `<input type="file">`
     * is clicked unless the host activity overrides
     * `WebChromeClient.onShowFileChooser(...)` and routes the click to
     * an external `Intent` like `ACTION_GET_CONTENT`.  Without this,
     * the React side's "Upload your own avatar" modal puts focus on
     * its Choose-file button, the user hits OK, and absolutely
     * nothing happens — which is exactly what the user reported.
     *
     * Flow:
     *   1) WebChromeClient.onShowFileChooser stashes the callback +
     *      launches ACTION_GET_CONTENT with the MIME filter the
     *      `<input accept=…>` declared (or `* / *` if it didn't).
     *   2) The system file picker (Photos on Android TV, Files on
     *      mobile) hands back a content:// URI.
     *   3) onActivityResult forwards that URI as a single-element
     *      array to the stashed callback, which the WebView routes
     *      back to the `<input>` as a regular file selection.
     *
     * If the user cancels (or the picker can't be launched), we
     * always call the callback with `null` so the WebView doesn't
     * leak a dangling promise — otherwise the next `<input>` click
     * would silently no-op.
     */
    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    /** MIME-type filter from the most recent onShowFileChooser call.
     *  Stored so we can also relax it to `* / *` and re-launch the
     *  picker if the strict filter returns no results — some Android
     *  TV file pickers don't index custom MIME types well. */
    private var fileChooserAcceptTypes: Array<String> = emptyArray()

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
        // v2.10.31 — File chooser result → back to the WebView.
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = fileChooserCallback
            fileChooserCallback = null
            if (cb == null) return
            val uris: Array<android.net.Uri>? =
                if (resultCode != RESULT_OK || data == null) null
                else {
                    // Single-pick path: data.data.  Multi-pick path:
                    // data.clipData — Android TV is single-pick so
                    // we just take data.data, falling back to the
                    // clipData's first entry for safety.
                    val single = data.data
                    if (single != null) arrayOf(single)
                    else {
                        val clip = data.clipData
                        if (clip != null && clip.itemCount > 0) {
                            arrayOf(clip.getItemAt(0).uri)
                        } else null
                    }
                }
            cb.onReceiveValue(uris)
            return
        }
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
        // v2.10.31 — request code for the WebView file-chooser
        // intent used by the custom-avatar upload flow.
        private const val REQ_FILE_CHOOSER = 9202
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
        //
        // v2.7.93 — HK1 / X96 / MXQ / Tanix / H96 boxes ship AOSP
        // without LEANBACK and report UI_MODE_TYPE_NORMAL.  They
        // ALSO have no touchscreen and no telephony.  Add those as
        // last-resort signals so the app locks landscape + uses
        // TV-mode UI on those devices too (previously they fell
        // through to phone mode and rendered in the top half only).
        val pm = packageManager
        val cfg = resources.configuration
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY)
        val uiModeIsTv = (cfg.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val noTouch = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
            !pm.hasSystemFeature("android.hardware.faketouch")
        val noTelephony = !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val tvBoxModel = run {
            val needle = "${Build.MANUFACTURER} ${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}"
                .lowercase()
            listOf(
                "hk1", "x96", "mxq", "tanix", "h96", "tx3", "tx6", "tx9",
                "mecool", "transpeed", "ugoos", "magicsee", "beelink",
                "amlogic", "rockchip", "rk3318", "rk3328", "rk3368", "rk3399",
                "s905", "s912", "s922", "s928", "atv", "tvbox",
            ).any { it in needle }
        }
        val isTv = hasLeanback || uiModeIsTv || tvBoxModel ||
            (noTouch && noTelephony)

        android.util.Log.i(
            "VesperMain",
            "TV detection: leanback=$hasLeanback uiModeTv=$uiModeIsTv " +
                "noTouch=$noTouch noTelephony=$noTelephony " +
                "tvBoxModel=$tvBoxModel → isTv=$isTv " +
                "(${Build.MANUFACTURER} ${Build.MODEL})",
        )

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

        // v2.7.94 — Critical HK1 S905X3 + Android 9 + 4K-HDMI fix.
        //
        // Symptom (verified by user on one specific HK1 S905X3
        // running Android 9 at 4K-auto HDMI): Vesper rendered as a
        // 1080p activity pinned to the top-left of the 4K screen.
        // The launcher (same minSdk/targetSdk, same theme parent,
        // sideloaded the same way) filled the screen fine.
        //
        // Root cause: this activity was adding FLAG_LAYOUT_NO_LIMITS
        // on top of a theme that ALREADY sets windowFullscreen=true
        // + windowTranslucentStatus/Navigation=true.  On Android 9 +
        // S905X3 + 4K HDMI, the window manager interprets that
        // combination as "render into a compatibility-sized window
        // (1080p) inside the 4K framebuffer" instead of stretching
        // to the actual display.  The launcher's theme uses ONLY
        // `windowFullscreen=true` (no NO_LIMITS, no translucent bar
        // flags) so it never trips the bug.
        //
        // Fix: drop FLAG_LAYOUT_NO_LIMITS entirely.  Edge-to-edge
        // rendering is already guaranteed by the theme's
        // `windowFullscreen=true`, which is the standard supported
        // path.  FLAG_KEEP_SCREEN_ON is preserved (critical for TV
        // — keeps the box from sleeping during playback).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

            // v2.7.94 — GPU layer policy.  THIS IS THE FIX for the
            // HK1 S905X3 + Android 9 + 4K-HDMI bug the user
            // reported (modal dialogs rendering bottom-left,
            // genre panels staying blank, splash artefacting in
            // a corner).
            //
            // PREVIOUSLY we ALWAYS forced the WebView onto
            // `LAYER_TYPE_HARDWARE` to get 60 fps shelf scrolling
            // on the older HK1 boxes (S905X / S905W2 / S922X)
            // whose GPUs are fine.  That's still the right call
            // for almost every TV box and phone we ship to.
            //
            // BUT — the S905X3's Mali-G31 GPU on Android 9 AOSP
            // firmware has a documented compositor bug where
            // hardware-layer rendering of WebView at 4K mispositions
            // `position: fixed` elements, fails async re-renders
            // of large image-grid layers, and leaves stale framebuffer
            // tiles in random corners of the surface.  Disabling
            // hardware layer rendering (forcing software composition
            // on the WebView's own layer) sidesteps the bug.
            //
            // We detect the bad combo defensively:
            //   • `Build.HARDWARE` contains "amlogic" (any S9xx
            //     chip) OR "rockchip" (cheaper RK33xx clones)
            //   • AND Android SDK ≤ 28 (Android 9 — the last AOSP
            //     release Amlogic shipped with the broken Mali
            //     driver)
            //   • OR `Build.MODEL` literally contains "S905X3"
            //
            // Working boxes (Android 10+, or non-Amlogic) keep the
            // fast HARDWARE path.  This change CANNOT regress them.
            //
            // ESCAPE HATCH: persist `compat.force_software=true` in
            // SharedPreferences and software-layer mode is used
            // regardless of detection.  This can be set without an
            // APK rebuild by launching the activity once with
            //   `--ez force_software true`
            // (via ADB) or via a deep-link `onnowtv://compat?sw=1`.
            // Critical for the user's 300–500-device rollout — any
            // box that slips past auto-detection can be patched in
            // 60 seconds remotely instead of waiting for a new APK.
            val needle = "${Build.HARDWARE} ${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}"
                .lowercase()
            val isFragileAmlogic =
                (("amlogic" in needle || "rockchip" in needle) &&
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) ||
                "s905x3" in needle

            // Persistent override — check first, then write if the
            // intent / deep-link asked us to set it.
            val compatPrefs = getSharedPreferences("vesper-compat", MODE_PRIVATE)
            val intentForce = intent?.getBooleanExtra("force_software", false) == true ||
                (intent?.data?.toString().orEmpty()).contains("sw=1", ignoreCase = true) ||
                (intent?.data?.toString().orEmpty()).contains("compat=software", ignoreCase = true)
            if (intentForce) {
                compatPrefs.edit().putBoolean("force_software", true).apply()
            }
            val forceSoftware = compatPrefs.getBoolean("force_software", false)

            val useSoftware = forceSoftware || isFragileAmlogic
            if (useSoftware) {
                android.util.Log.w(
                    "VesperMain",
                    "Software-layer WebView active. " +
                        "fragileAmlogic=$isFragileAmlogic forceSoftware=$forceSoftware " +
                        "needle=[$needle] sdk=${Build.VERSION.SDK_INT}",
                )
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            } else {
                // Default: dedicated hardware layer so every paint
                // (and especially shelf scroll transforms) is GPU-
                // composited.  On the original HK1 / S905W2 / S922X
                // this is the difference between 30 fps stuttery
                // scroll and buttery 60 fps LeanBack-style glide.
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            }
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

                // v2.10.31 — File chooser for the custom-avatar
                // upload flow.  See `fileChooserCallback` block at
                // the top of the class for the full design rationale.
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    if (filePathCallback == null) return false
                    // Cancel any prior pending chooser (shouldn't
                    // happen but defensive).
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    fileChooserAcceptTypes = fileChooserParams
                        ?.acceptTypes
                        ?.filter { it.isNotBlank() }
                        ?.toTypedArray()
                        ?: emptyArray()
                    return try {
                        val intent = fileChooserParams?.createIntent()
                            ?: android.content.Intent(
                                android.content.Intent.ACTION_GET_CONTENT
                            ).apply {
                                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                        // Force a chooser dialog so the user can
                        // pick a file manager / gallery / photos
                        // app — some Android TV launchers default
                        // to a system file browser that doesn't
                        // surface user photos at all.
                        val chooser = android.content.Intent.createChooser(
                            intent,
                            "Pick a photo or video"
                        )
                        startActivityForResult(chooser, REQ_FILE_CHOOSER)
                        true
                    } catch (t: Throwable) {
                        // No matching activity available — release
                        // the callback so the WebView doesn't hang.
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        false
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
        }?.let { stripProfileQuery(it) }
        // ^ v2.7.97 — Defensive: strip any leftover `profile=` from a
        //   pre-v2.7.97 saved URL.  Going forward `onPause()` strips
        //   it BEFORE writing, but a box upgrading from an older APK
        //   may already have a dirty value cached on disk.

        // v2.7.95 — Detect a profile deep-link FROM THE LAUNCHER.
        // The Launcher fires us with `vesper_route` extra OR an
        // `onnowtv://launch?profile=…` data URI:
        //   • profile=kids       → flip to Kids mode + clean URL
        //   • profile=exit-kids  → leave Kids mode + clean URL
        //                          (v2.7.97 — Movies/TV tile)
        //
        // v2.8.25 — Also detects `v2ai=<title>&type=…&autoplay=1` from
        // the Launcher's V2 AI voice assistant.  Same plumbing — the
        // launcher fires us with the same `vesper_route` extra and
        // `onnowtv://launch?v2ai=…` data URI, and we append the query
        // to the boot URL so App.js's synchronous reader picks it up
        // and routes to /v2ai-play which auto-plays the title.
        val routeExtra = intent?.getStringExtra("vesper_route").orEmpty()
        val dataQuery  = intent?.data?.encodedQuery.orEmpty()
        val isKidsDeepLink     = routeExtra.contains("profile=kids") ||
                                 dataQuery.contains("profile=kids")
        val isExitKidsDeepLink = routeExtra.contains("profile=exit-kids") ||
                                 dataQuery.contains("profile=exit-kids")
        val isV2AIDeepLink     = routeExtra.contains("v2ai=") ||
                                 dataQuery.contains("v2ai=")
        // v2.10.56 — Launcher's "Backup my profiles first" button on
        // the Update-available dialog fires us with
        // `vesper_route=?screen=backup`.  Boot directly to the
        // Settings → Backup section so the user can save a backup
        // code before the new APK installs.
        val isBackupDeepLink   = routeExtra.contains("screen=backup") ||
                                 dataQuery.contains("screen=backup")
        val isProfileDeepLink  = isKidsDeepLink || isExitKidsDeepLink

        val bootUrl = when {
            // Any profile-switch deep-link → always start from a
            // clean default URL, never the last-restored route.
            isProfileDeepLink -> defaultBoot
            // V2 AI deep-link → also start fresh so the auto-play
            // page handles the new title rather than landing on a
            // stale restored route.
            isV2AIDeepLink    -> defaultBoot
            // Backup-section deep-link → swap the index/Home route
            // for /settings#backup-section.
            isBackupDeepLink  -> {
                val base = defaultBoot.substringBefore("?").trimEnd('/').trimEnd('#')
                "$base/settings#backup-section"
            }
            else              -> devUrl ?: restoreUrl ?: defaultBoot
        }

        // Append the deep-link query so App.js's synchronous reader
        // can pick it up before any React component renders.  v2.7.93
        // also appends `platform=tv` so the React UI uses TV-mode
        // spatial-nav even on cheap HK1 / X96 boxes where the user
        // agent / window.matchMedia heuristics might otherwise treat
        // the WebView as a phone.
        val finalBootUrl = run {
            val deepLinkQuery = when {
                routeExtra.contains("profile=") -> routeExtra.substringAfter("?")
                routeExtra.contains("v2ai=")    -> routeExtra.substringAfter("?")
                dataQuery.contains("profile=")  -> dataQuery
                dataQuery.contains("v2ai=")     -> dataQuery
                else                            -> ""
            }
            val platformQuery = if (isTv) "mobile=0" else ""
            val parts = listOf(deepLinkQuery, platformQuery).filter { it.isNotBlank() }
            val joined = parts.joinToString("&")
            if (joined.isEmpty()) bootUrl
            else if (bootUrl.contains("?")) "$bootUrl&$joined"
            else "$bootUrl?$joined"
        }

        setContentView(webView)
        webViewReady = true
        webView.loadUrl(finalBootUrl)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        if (webViewReady) webView.onResume()
        consumeNextEpisodeIntent()
    }

    /**
     * v2.7.96+ — Handle deep-links arriving while Vesper is already
     * in the foreground/background.
     *
     * The launcher fires us with an Intent carrying
     *   • `vesper_route` extra: "/?profile=kids" or "/?profile=exit-kids"
     *   • data URI: "onnowtv://launch?profile=kids" / "?profile=exit-kids"
     *
     * Without this override, Android delivers the intent silently
     * and the WebView keeps showing whatever page the user was on.
     *
     * Fix: detect the profile deep-link, inject JS that flips the
     * active-profile localStorage key (kids OR restored adult), then
     * navigate to `/` so the right Home renders.  Uses
     * `evaluateJavascript` so the existing WebView session is
     * preserved (no full reload / flash).
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        try {
            val routeExtra = intent.getStringExtra("vesper_route").orEmpty()
            val dataQuery  = intent.data?.encodedQuery.orEmpty()
            val isKids     = routeExtra.contains("profile=kids") ||
                             dataQuery.contains("profile=kids")
            val isExitKids = routeExtra.contains("profile=exit-kids") ||
                             dataQuery.contains("profile=exit-kids")
            val isV2AI     = routeExtra.contains("v2ai=") ||
                             dataQuery.contains("v2ai=")
            // v2.10.56 — Launcher Update dialog's "Backup my profiles
            // first" button.  Navigate the WebView directly to
            // Settings → Backup section.
            val isBackup   = routeExtra.contains("screen=backup") ||
                             dataQuery.contains("screen=backup")
            if (!webViewReady || (!isKids && !isExitKids && !isV2AI && !isBackup)) return
            if (isBackup) {
                val js = """
                    (function(){
                        try {
                            window.location.hash = '#/settings';
                            setTimeout(function(){
                                try {
                                    var el = document.getElementById('backup-section');
                                    if (el) el.scrollIntoView({behavior:'smooth', block:'start'});
                                } catch (e) {}
                            }, 500);
                        } catch (e) {}
                    })();
                """.trimIndent()
                webView.post {
                    try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
                }
                return
            }
            // v2.8.25 — V2 AI deep-link: navigate the live React Router
            // to /v2ai-play?title=…&type=… which kicks the search →
            // resolve → autoplay flow.  Re-uses the SAME query the
            // launcher built, only swap `v2ai=` → `title=` for the
            // V2AIResolve page contract.
            if (isV2AI) {
                val rawQuery = when {
                    routeExtra.contains("v2ai=") -> routeExtra.substringAfter("?")
                    else                          -> dataQuery
                }
                // Translate `v2ai=` to `title=` and drop autoplay (the
                // resolve page assumes autoplay).  The encoded title
                // value is preserved verbatim.
                val translated = rawQuery
                    .replaceFirst(Regex("(^|&)v2ai="), "$1title=")
                    .replace(Regex("(^|&)autoplay=[^&]*"), "")
                    .trim('&')
                val js = """
                    (function(){
                        try {
                            window.location.hash = '#/v2ai-play?${translated.replace("'", "\\'")}';
                        } catch (e) {}
                    })();
                """.trimIndent()
                webView.post {
                    try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
                }
                return
            }
            val js = if (isKids) {
                """
                (function(){
                    try {
                        var cur = localStorage.getItem('onnowtv-active-profile-v1');
                        if (cur && cur !== 'kids') {
                            localStorage.setItem('onnowtv-last-non-kids-profile', cur);
                        }
                        localStorage.setItem('onnowtv-active-profile-v1','kids');
                        window.dispatchEvent(new CustomEvent('vesper:profile-change'));
                        window.location.hash = '#/';
                    } catch (e) {}
                })();
                """.trimIndent()
            } else {
                // exit-kids: restore the previously-active non-kids
                // profile if we have one; otherwise clear so the
                // ProfileSelect picker shows.
                //
                // v2.8.42 — Removed the previous PIN refusal block.
                // Reaching this handler means the user pressed a
                // NON-Kids tile in the launcher (Movies/TV, Music,
                // Apps, …) — and the launcher's onResume kids-lock
                // bounce (v2.8.42) guarantees the launcher is
                // unreachable WHILE Kids+PIN is active.  So if we're
                // here, either (a) the user has already PIN-exited
                // Kids (lock is false) or (b) no PIN is configured.
                // Either way the correct behaviour is to exit Kids
                // cleanly, NOT to silently refuse and trap the user.
                """
                (function(){
                    try {
                        var prev = localStorage.getItem('onnowtv-last-non-kids-profile');
                        if (prev) {
                            localStorage.setItem('onnowtv-active-profile-v1', prev);
                        } else {
                            localStorage.removeItem('onnowtv-active-profile-v1');
                        }
                        // v2.8.42 — Also tell the launcher backend
                        // we've left Kids so its onResume polls
                        // stop bouncing back here.
                        try {
                            if (window.OnNowTV && window.OnNowTV.setKidsLock) {
                                window.OnNowTV.setKidsLock(false);
                            }
                        } catch (e) {}
                        window.dispatchEvent(new CustomEvent('vesper:profile-change'));
                        window.location.hash = '#/';
                    } catch (e) {}
                })();
                """.trimIndent()
            }
            webView.post {
                try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { /* swallow — defensive */ }
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
                // v2.7.97 — STRIP any `profile=` query before saving.
                // Otherwise a later non-kids launch (Movies/TV tile)
                // restores a URL that still carries `?profile=kids`,
                // and our synchronous module-load reader in App.js
                // would re-apply kids mode on top of the user's
                // adult profile.  Profile activation must come
                // EXCLUSIVELY from a fresh launcher Intent, never
                // from the WebView's stale URL.
                val cleaned = stripVolatileHashParams(stripProfileQuery(cur))
                getSharedPreferences("onnowtv_route", MODE_PRIVATE).edit()
                    .putString("last_url", cleaned)
                    .putLong("last_ts", System.currentTimeMillis())
                    .apply()
            }
        } catch (_: Exception) {}
    }

    /** Remove any `profile=…` query parameter from a Vesper URL, while
     *  preserving the rest of the query string + hash fragment.
     *  Used to keep `last_url` free of stale Kids markers. */
    private fun stripProfileQuery(url: String): String {
        return try {
            // Split on hash first so we don't mangle the route.
            val hashIdx = url.indexOf('#')
            val base = if (hashIdx >= 0) url.substring(0, hashIdx) else url
            val hash = if (hashIdx >= 0) url.substring(hashIdx) else ""
            val qIdx = base.indexOf('?')
            if (qIdx < 0) return url  // no query → nothing to strip
            val path = base.substring(0, qIdx)
            val query = base.substring(qIdx + 1)
            val kept = query.split('&').filter {
                it.isNotEmpty() && !it.startsWith("profile=")
            }
            val newQuery = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
            path + newQuery + hash
        } catch (_: Throwable) { url }
    }

    /** v2.10.45 — Remove one-shot playback-trigger params from the
     *  HASH query before persisting `last_url`.  If Android kills
     *  MainActivity during native playback and the restore brings
     *  back a URL still carrying `episodeAutoplay=1` / `autoplay=1`,
     *  the WebView re-fires playback of the OLD episode on relaunch
     *  — the user experiences "Skip Next played the same episode
     *  again".  Watch-party params are stripped too: a party must
     *  never auto-rejoin from a stale restored URL. */
    private fun stripVolatileHashParams(url: String): String {
        return try {
            val hashIdx = url.indexOf('#')
            if (hashIdx < 0) return url
            val base = url.substring(0, hashIdx)
            val hash = url.substring(hashIdx + 1)
            val qIdx = hash.indexOf('?')
            if (qIdx < 0) return url
            val route = hash.substring(0, qIdx)
            val query = hash.substring(qIdx + 1)
            val volatileKeys = setOf(
                "episodeAutoplay", "autoplay", "season", "episode",
                "party", "at_ms", "position_ms",
            )
            val kept = query.split('&').filter { p ->
                p.isNotEmpty() && p.substringBefore('=') !in volatileKeys
            }
            val newQuery = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
            "$base#$route$newQuery"
        } catch (_: Throwable) { url }
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
    /**
     * v2.10.29 — Diagnostics escape-hatch.
     *
     * If the user holds the MENU button on their remote for ≥1.5 s,
     * launch DiagnosticsActivity directly.  This is the LAST RESORT
     * way to reach diagnostics on a box where the WebView is so
     * broken that React → Settings is unreachable.
     *
     * KEYCODE_MENU is the "three-line" / hamburger button on most TV
     * remotes (HK1, X96, Tanix, etc.).  We don't otherwise intercept
     * it, so this hijack is safe.  The 1.5-second guard prevents
     * accidental triggering during normal app navigation.
     */
    private var menuDownAtMs: Long = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // First press → record the timestamp.  Subsequent
            // repeats (D-pad autorepeat) ignore until release.
            if (event != null && event.repeatCount == 0) {
                menuDownAtMs = System.currentTimeMillis()
            } else if (event != null && menuDownAtMs > 0L) {
                val held = System.currentTimeMillis() - menuDownAtMs
                if (held >= 1500L) {
                    menuDownAtMs = 0L
                    openDiagnosticsActivity()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!webViewReady) {
                finish()
                return true
            }
            // v2.8.9 — Kids sandbox lockdown.  Check the JS flag
            // `window.__vesperKidsLocked` FIRST — if it's '1', the
            // user is in Kids mode with a PIN configured and we
            // must NOT allow normal goBack() / finish().  Instead
            // route the WebView to /kids/exit-pin so the parent
            // has to enter the PIN before any escape.
            webView.evaluateJavascript("(window.__vesperKidsLocked||'')") { lockedRaw ->
                val locked = (lockedRaw?.trim('"') ?: "") == "1"
                if (locked) {
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.location.hash = '#/kids/exit-pin';",
                            null,
                        )
                    }
                    return@evaluateJavascript
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
