package tv.onnow.launcher.remote

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tv.onnow.launcher.ImageLoader
import tv.onnow.launcher.data.LauncherRepository
import tv.onnow.launcher.net.ResilientHttp

/**
 * Phone-remote pairing screen.
 *
 *   1. POST /api/remote/host/register → {session_id, code, qr_image_url}
 *   2. Show the QR + the 6-digit code big on the TV.
 *   3. Start RemoteControlService (foreground) so phone inputs are
 *      dispatched even after this screen is dismissed.
 *   4. Poll /api/remote/state until the phone pairs, flip the UI to
 *      "Connected", then auto-close (service keeps running).
 *
 * BACK before pairing tears the session down; after pairing it just
 * closes the screen and the phone keeps controlling the box.
 */
class RemoteControlActivity : ComponentActivity() {

    companion object { private const val TAG = "RemoteControl" }

    private lateinit var repo: LauncherRepository
    private var sessionId: String? = null
    private var sessionCode: String? = null
    @Volatile private var paired = false
    @Volatile private var pollerActive = true
    private var pollerThread: Thread? = null

    private lateinit var qrView: ImageView
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = LauncherRepository(applicationContext)
        setContentView(buildUi())
        mintSession()
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
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply {
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
        titleView = TextView(this).apply {
            text = "Scan to turn your phone\ninto the remote"
            textSize = 30f
            setTextColor(0xFFEAF2FF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        col.addView(titleView)
        subtitleView = TextView(this).apply {
            text = "Point your phone camera at the QR code, then enter this code:"
            textSize = 14f
            setTextColor(0x99EAF2FF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(360),
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        col.addView(subtitleView)
        codeView = TextView(this).apply {
            text = "— — —  — — —"
            textSize = 64f
            setTextColor(0xFF5DC8FF.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18); bottomMargin = dp(18) }
        }
        col.addView(codeView)
        statusView = TextView(this).apply {
            text = "Waiting for your phone…"
            textSize = 14f
            setTextColor(0xFFFFAE5D.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = pill(0x14FFAE5D, 0x33FFAE5D)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        col.addView(statusView)
        col.addView(TextView(this).apply {
            text = "Press BACK to close this screen."
            textSize = 11f
            setTextColor(0x66EAF2FF.toInt())
            letterSpacing = 0.14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(28) }
        })
        row.addView(col)
        root.addView(row)
        return root
    }

    private fun pill(bg: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(bg)
        setStroke(dp(1), stroke)
    }

    private fun mintSession() {
        lifecycleScope.launch {
            val resp = withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply { put("device_id", deviceId()) }
                        .toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val url = repo.baseUrlPublic().trimEnd('/') + "/api/remote/host/register"
                    val req = Request.Builder().url(url).post(body).build()
                    ResilientHttp.client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "remote/host/register failed", t); null
                }
            }
            if (resp == null) {
                codeView.text = "ERROR"
                setStatus("Couldn't reach the remote service — check your internet.", warn = true)
                return@launch
            }
            sessionId = resp.optString("session_id")
            sessionCode = resp.optString("code")
            codeView.text = formatCode(sessionCode!!)
            val qrUrl = resp.optString("qr_image_url")
            if (qrUrl.isNotBlank()) ImageLoader.load(qrView, qrUrl)
            startService()
            startPairingPoller()
        }
    }

    private fun startService() {
        val sid = sessionId ?: return
        val svc = Intent(this, RemoteControlService::class.java).apply {
            action = RemoteControlService.ACTION_START
            putExtra(RemoteControlService.EX_SESSION_ID, sid)
            putExtra(RemoteControlService.EX_BASE_URL, repo.baseUrlPublic().trimEnd('/'))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
    }

    private fun startPairingPoller() {
        val sid = sessionId ?: return
        val code = sessionCode ?: return
        val base = repo.baseUrlPublic().trimEnd('/')
        pollerThread = Thread {
            while (pollerActive && !paired) {
                try {
                    val url = "$base/api/remote/state/$sid?code=$code"
                    val req = Request.Builder().url(url).get().build()
                    val body = ResilientHttp.client.newCall(req).execute()
                        .use { it.body?.string().orEmpty() }
                    if (body.isNotEmpty() && JSONObject(body).optBoolean("paired", false)) {
                        paired = true
                        runOnUiThread { onPhonePaired() }
                        return@Thread
                    }
                    Thread.sleep(1200)
                } catch (t: Throwable) {
                    if (!pollerActive) return@Thread
                    try { Thread.sleep(1200) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun onPhonePaired() {
        setStatus("Phone connected — you're all set!", warn = false)
        subtitleView.text = "Your phone is now the remote. You can close this screen; " +
            "the connection stays active until you disconnect from your phone."
        codeView.setTextColor(0x665DC8FF.toInt())
        titleView.text = "Connected!"
        qrView.postDelayed({ if (!isFinishing) finish() }, 1800)
    }

    private fun setStatus(text: String, warn: Boolean) {
        statusView.text = text
        statusView.setTextColor(if (warn) 0xFFFFAE5D.toInt() else 0xFF5DFFAB.toInt())
        statusView.background =
            if (warn) pill(0x14FFAE5D, 0x33FFAE5D) else pill(0x145DFFAB, 0x335DFFAB)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollerActive = false
        pollerThread?.interrupt()
        pollerThread = null
        // If the user backed out BEFORE pairing, stop the service so
        // the dangling session is torn down immediately (it POSTs
        // /host/cancel).  If already paired, LEAVE it running so the
        // phone keeps controlling the box.
        if (!paired) {
            try {
                stopService(Intent(this, RemoteControlService::class.java))
            } catch (_: Throwable) {}
        }
    }

    private fun formatCode(c: String): String =
        if (c.length == 6) "${c.substring(0, 3)}  ${c.substring(3)}" else c

    private fun deviceId(): String =
        tv.onnow.launcher.onboarding.OnboardingActivity.deviceId(this)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
