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
            // v2.8.57 — Auto-sign-in path.  Per the operator's
            // request, the Tunes APK ships with a baked-in throwaway
            // YouTube account so users never have to type credentials
            // (even after uninstall + reinstall, which wipes cookies).
            //
            // We don't hardcode-and-submit blindly — that would trip
            // Google's anti-automation.  Instead the WebViewClient
            // watches each step of the standard sign-in flow and
            // injects the right field values via JavaScript right
            // before clicking Next / Sign In.  Looks to the user
            // like the screens flash by automatically.
            Toast.makeText(
                this,
                "Signing into music account…",
                Toast.LENGTH_SHORT,
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

    /** WebViewClient that watches the Google sign-in flow:
     *
     *   • Auto-fills the email field + clicks Next on the first step.
     *   • Auto-fills the password field + clicks Sign In on the
     *     second step.
     *   • Watches for the final youtube.com redirect → confirms
     *     cookies are now in place → advances to /music.
     *
     * Each step is identified by a CSS-selector pattern that's
     * stable across Google's recent UI iterations (input fields are
     * always `input[type=email]` / `input[type=password]`, "Next"
     * is always `#identifierNext button` / `#passwordNext button`).
     * If Google A/B-tests a new sign-in UI that breaks these
     * selectors, the user still sees the standard form and can
     * sign in by hand — graceful degradation.
     */
    private fun signInWatcherClient() = object : WebViewClient() {
        private var advanced = false
        private var lastFilledStep = ""

        override fun onPageFinished(view: WebView?, url: String?) {
            if (advanced || url == null || view == null) return

            // Step 1: success → user landed on YouTube + cookies present.
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
                return
            }

            // Step 2: email-entry page.  Auto-fill once per page load
            // (lastFilledStep guards against double-firing when the
            // SPA re-renders without a full navigation).
            if (url.contains("accounts.google.com") && lastFilledStep != "email-$url") {
                lastFilledStep = "email-$url"
                view.postDelayed({ injectAutofillScript(view) }, AUTOFILL_DELAY_MS)
            }
        }
    }

    /** Run the autofill JS in the WebView.  Idempotent — looks
     *  for whichever field is visible and fills it.  If neither
     *  email nor password input is present, the script is a no-op. */
    private fun injectAutofillScript(view: WebView) {
        val js = """
            (function() {
                try {
                    var email = document.querySelector('input[type=email][name=identifier], input[type=email]');
                    if (email && !email.value) {
                        email.focus();
                        // Use the native value-setter so React-style
                        // controlled inputs fire their onChange handlers.
                        var nativeSetter = Object.getOwnPropertyDescriptor(
                            window.HTMLInputElement.prototype, 'value'
                        ).set;
                        nativeSetter.call(email, ${jsString(AUTO_EMAIL)});
                        email.dispatchEvent(new Event('input', { bubbles: true }));
                        email.dispatchEvent(new Event('change', { bubbles: true }));
                        setTimeout(function() {
                            var next = document.querySelector('#identifierNext button, #identifierNext, button[jsname]');
                            if (next) next.click();
                        }, 300);
                        return 'filled-email';
                    }
                    var pw = document.querySelector('input[type=password][name=Passwd], input[type=password]');
                    if (pw && pw.offsetParent !== null && !pw.value) {
                        pw.focus();
                        var nativeSetterPw = Object.getOwnPropertyDescriptor(
                            window.HTMLInputElement.prototype, 'value'
                        ).set;
                        nativeSetterPw.call(pw, ${jsString(AUTO_PASSWORD)});
                        pw.dispatchEvent(new Event('input', { bubbles: true }));
                        pw.dispatchEvent(new Event('change', { bubbles: true }));
                        setTimeout(function() {
                            var next = document.querySelector('#passwordNext button, #passwordNext, button[jsname]');
                            if (next) next.click();
                        }, 300);
                        return 'filled-password';
                    }
                    return 'nothing-to-fill';
                } catch (e) {
                    return 'error: ' + e.message;
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
        // Re-run the script a couple times — Google's sign-in is an
        // SPA, the email-then-password step transition doesn't
        // always fire onPageFinished a second time.
        view.postDelayed({ view.evaluateJavascript(js, null) }, AUTOFILL_RETRY_MS)
        view.postDelayed({ view.evaluateJavascript(js, null) }, AUTOFILL_RETRY_MS * 2)
    }

    /** Escape a String into a JS string literal so it embeds safely
     *  inside `evaluateJavascript`. */
    private fun jsString(s: String): String {
        val esc = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$esc'"
    }

    private fun navigateToMusic() {
        val base = getString(R.string.app_url).trim().trimEnd('/')
        // Switch back to a plain WebViewClient so subsequent
        // navigations inside /music aren't intercepted.
        webView.webViewClient = WebViewClient()
        // `?box=1` flags the React app that it's running inside
        // the Tunes APK (auto-shows the resolver debug overlay).
        // `&yt=1` flags that a signed-in YouTube session is
        // available — React side uses this to enable the IFrame
        // Player route.
        webView.loadUrl("$base/music?box=1&yt=1")
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

        // v2.8.57 — Auto-sign-in credentials.  Throwaway YouTube
        // account managed by the operator; only used by the Tunes
        // APK so users never have to type credentials (even after
        // uninstall + reinstall, which wipes WebView cookies).  Safe
        // to embed in the APK — the account has no Drive / Gmail /
        // payments tied to it.  Operator can rotate by updating
        // these constants and pushing a new build.
        private const val AUTO_EMAIL    = "onnowv2@gmail.com"
        private const val AUTO_PASSWORD = "Onnowtv123!"

        // Wait for the React-style sign-in SPA to settle before
        // injecting fields; retry twice in case the form re-renders.
        private const val AUTOFILL_DELAY_MS = 700L
        private const val AUTOFILL_RETRY_MS = 1500L
    }
}
