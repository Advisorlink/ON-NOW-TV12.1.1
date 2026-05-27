package tv.onnow.launcher.speedtest

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tv.onnow.launcher.R

/**
 * v2.8.21 — Speed test is now a thin shell around Ookla's
 * `speedtest.net` web app, loaded in a chrome-less WebView.
 *
 * Why this rewrite?
 *   The previous homebrew measurement (parallel HTTP streams) was
 *   ~25% under Ookla on the user's box and occasionally froze on
 *   slow links.  Ookla maintain their own server network + tuned
 *   measurement protocol — they'll always beat a hand-rolled JVM
 *   implementation.  Wrapping their official page gives the user
 *   the EXACT result they'd see on a laptop.
 *
 * UI:
 *   • Small "ON NOW TV V2 · SPEED TEST" header bar at the top so
 *     the screen still reads as our app rather than just "a
 *     webpage we opened".
 *   • Full-bleed WebView below — desktop user-agent so Ookla
 *     serves its gauge-style HTML5 UI instead of the mobile one.
 *   • An on-screen "Reload" pill (TV remote OK / Enter) so users
 *     can rerun without finding the in-page button.
 *
 * Network-failure handling: WebView's default behaviour shows a
 * native error page, which is fine for a TV — no extra plumbing.
 */
class SpeedTestActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        web.loadUrl("https://www.speedtest.net/")
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /* ──────────────────  UI  ────────────────── */

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.onb_bg_glow)
        }

        // Slim top bar so we look like part of the launcher.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(36), dp(20), dp(36), dp(20))
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#FF06101D"),
                    Color.parseColor("#FF040712"),
                )
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
        }
        header.addView(TextView(this).apply {
            text = "ON NOW TV V2"
            textSize = 12f
            letterSpacing = 0.30f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0f,
            )
        })
        header.addView(TextView(this).apply {
            text = "  ·  Speed Test"
            textSize = 13f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            letterSpacing = 0.10f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        })

        // Reload pill on the right side of the header.
        val reload = TextView(this).apply {
            text = "Reload"
            textSize = 14f
            setTextColor(Color.parseColor("#FF04060B"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#FF2BB6FF"))
            }
            setPadding(dp(22), dp(8), dp(22), dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { web.reload() }
        }
        header.addView(reload)
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // Full-bleed WebView.
        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 10; OnNowTV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                @Suppress("DEPRECATION")
                allowFileAccess = false
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.parseColor("#FF04060B"))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(web, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        // First-arrival focus on the WebView so D-pad clicks GO inside.
        web.post { web.requestFocus() }
        return root
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
