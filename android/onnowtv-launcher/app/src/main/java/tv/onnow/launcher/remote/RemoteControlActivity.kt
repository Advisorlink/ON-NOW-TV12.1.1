package tv.onnow.launcher.remote

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tv.onnow.launcher.ImageLoader

/**
 * Big-QR screen for the phone remote (zero-PIN flow).
 *
 * The QR is PERSISTENT and box-specific — it encodes an auto-connect
 * URL with this box's device id + secret token, so scanning it opens
 * the full-screen web remote already connected.  No code entry.  The
 * 6-digit short code is shown small as a manual fallback (e.g. phone
 * camera won't scan).
 *
 * The always-on RemoteControlService does the hosting; this screen
 * just renders whatever registration it last published via [RemoteControlService.regFlow].
 */
class RemoteControlActivity : ComponentActivity() {

    private lateinit var qrView: ImageView
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        ensureService()
        observeRegistration()
    }

    private fun ensureService() {
        try {
            val svc = Intent(this, RemoteControlService::class.java)
                .setAction(RemoteControlService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        } catch (_: Throwable) {}
    }

    private fun observeRegistration() {
        lifecycleScope.launch {
            RemoteControlService.regFlow.collect { reg ->
                if (reg == null) {
                    statusView.text = "Connecting to the remote service…"
                    return@collect
                }
                reg.qrImageUrl?.let { ImageLoader.load(qrView, it) }
                codeView.text = reg.shortCode?.let { formatCode(it) } ?: ""
                statusView.text = "Ready — scan with your phone camera"
                setStatusOk(true)
            }
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF06080F.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(32), dp(48), dp(32))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ── LEFT: QR card ──
        val qrCard = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply {
                marginEnd = dp(48)
            }
        }
        qrView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        qrCard.addView(qrView)
        row.addView(qrCard)

        // ── RIGHT: text column ──
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        col.addView(TextView(this).apply {
            text = "ON NOW TV · PHONE REMOTE"
            textSize = 12f
            setTextColor(0xFF5DC8FF.toInt())
            letterSpacing = 0.30f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Scan to turn your phone\ninto the remote"
            textSize = 30f
            setTextColor(0xFFEAF2FF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        })
        col.addView(TextView(this).apply {
            text = "It connects instantly — no code needed. Save the page to " +
                "your phone's home screen for one-tap access anytime."
            textSize = 14f
            setTextColor(0x99EAF2FF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(380),
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        })
        statusView = TextView(this).apply {
            text = "Connecting to the remote service…"
            textSize = 14f
            setTextColor(0xFFFFAE5D.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = pill(0x14FFAE5D, 0x33FFAE5D)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        col.addView(statusView)
        col.addView(TextView(this).apply {
            text = "Can't scan? Open the remote page on your phone and enter:"
            textSize = 12f
            setTextColor(0x66EAF2FF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        })
        codeView = TextView(this).apply {
            text = ""
            textSize = 34f
            setTextColor(0xFF5DC8FF.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        col.addView(codeView)
        col.addView(TextView(this).apply {
            text = "Press BACK to close this screen."
            textSize = 11f
            setTextColor(0x66EAF2FF.toInt())
            letterSpacing = 0.14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        })
        row.addView(col)
        root.addView(row)
        return root
    }

    private fun setStatusOk(ok: Boolean) {
        statusView.setTextColor(if (ok) 0xFF5DFFAB.toInt() else 0xFFFFAE5D.toInt())
        statusView.background =
            if (ok) pill(0x145DFFAB, 0x335DFFAB) else pill(0x14FFAE5D, 0x33FFAE5D)
    }

    private fun pill(bg: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(bg)
        setStroke(dp(1), stroke)
    }

    private fun formatCode(c: String): String =
        if (c.length == 6) "${c.substring(0, 3)}  ${c.substring(3)}" else c

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
