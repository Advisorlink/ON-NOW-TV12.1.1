package tv.onnow.launcher.support

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnow.launcher.data.LauncherRepository
import tv.onnow.launcher.net.ResilientHttp
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.animation.ValueAnimator
import android.animation.ArgbEvaluator
import android.view.WindowManager

/**
 * v2.10.84 — Remote-maintenance host activity.
 *
 *   1. Mints a pairing code via POST /api/support/host/register.
 *   2. Displays the code BIG on screen ("475 882") with a soft pulse.
 *   3. After the user OKs the MediaProjection consent dialog, opens
 *      the host WebSocket and streams JPEG frames to the backend.
 *   4. Receives input commands from the operator's laptop and
 *      dispatches them via root shell (boxes are rooted per operator
 *      requirement) — `input tap`, `input keyevent`, `input text`.
 *
 *   5. Press BACK on the remote to end the session early (sends
 *      /api/support/host/cancel to drop the session server-side
 *      and releases MediaProjection).
 *
 * Design — TV-friendly:
 *   - Big centred code (96 sp), monospace, soft cyan glow
 *   - "Waiting for technician…" / "Technician connected" status pill
 *   - Always-on indicator so the customer can verify before granting
 *     full remote-control access.
 */
class SupportSessionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SupportSession"
    }

    private lateinit var repo: LauncherRepository
    private var sessionId: String? = null
    private var sessionCode: String? = null
    private var hostWs: WebSocket? = null
    private var screenCapture: ScreenCaptureController? = null

    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var subtitleView: TextView

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startStreaming(result.resultCode, result.data!!)
        } else {
            setStatus("Screen sharing permission denied — restart the session to retry.", warn = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on so the code stays visible while waiting.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = LauncherRepository(applicationContext)
        setContentView(buildUi())
        mintSession()
    }

    /* ─────────────────────────────  UI  ──────────────────────────── */

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF06080F.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // Eyebrow label
        column.addView(TextView(this).apply {
            text = "ON NOW TV · REMOTE SUPPORT"
            textSize = 12f
            setTextColor(0xFF5DC8FF.toInt())
            letterSpacing = 0.32f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        // Headset icon
        column.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(80), dp(80),
            ).apply { topMargin = dp(24); bottomMargin = dp(20); gravity = Gravity.CENTER_HORIZONTAL }
            background = headsetIconDrawable()
        })

        // Big title
        column.addView(TextView(this).apply {
            text = "Read this code to your technician"
            textSize = 28f
            setTextColor(0xFFE8ECF3.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL }
        })

        // Code
        codeView = TextView(this).apply {
            text = "— — — — — —"
            textSize = 88f
            setTextColor(0xFF5DC8FF.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28); bottomMargin = dp(28); gravity = Gravity.CENTER_HORIZONTAL }
        }
        column.addView(codeView)

        // Status pill
        statusView = TextView(this).apply {
            text = "Waiting for technician…"
            textSize = 14f
            setTextColor(0xFFFFAE5D.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x14FFAE5D.toInt())
                setStroke(dp(1), 0x33FFAE5D.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        column.addView(statusView)

        // Subtitle
        subtitleView = TextView(this).apply {
            text = "When the technician enters the code on their dashboard,\nyou'll see their name appear here."
            textSize = 13f
            setTextColor(0x99E8ECF3.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20); gravity = Gravity.CENTER_HORIZONTAL }
        }
        column.addView(subtitleView)

        // Footer hint
        column.addView(TextView(this).apply {
            text = "Press BACK on the remote to end this session."
            textSize = 11f
            setTextColor(0x66E8ECF3.toInt())
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(60); gravity = Gravity.CENTER_HORIZONTAL }
        })

        root.addView(column)
        return root
    }

    private fun headsetIconDrawable(): GradientDrawable {
        // Soft cyan rounded badge — actual icon rendered via Unicode
        // or skipped; the placeholder badge suffices visually.
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x335DC8FF.toInt())
            setStroke(dp(1), 0x665DC8FF.toInt())
        }
    }

    private fun setStatus(text: String, warn: Boolean = false) {
        statusView.text = text
        statusView.setTextColor(
            if (warn) 0xFFFFAE5D.toInt() else 0xFF5DFFAB.toInt()
        )
        val ringColor = if (warn) 0x33FFAE5D.toInt() else 0x335DFFAB.toInt()
        val bgColor   = if (warn) 0x14FFAE5D.toInt() else 0x145DFFAB.toInt()
        statusView.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(bgColor)
            setStroke(dp(1), ringColor)
        }
    }

    /* ────────────────────────  Session lifecycle  ────────────────── */

    private fun mintSession() {
        lifecycleScope.launch {
            val resp = withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("device_id", deviceId())
                    }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val url = repo.baseUrlPublic().trimEnd('/') + "/api/support/host/register"
                    val req = Request.Builder().url(url).post(body).build()
                    ResilientHttp.client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "host/register failed", t)
                    null
                }
            }
            if (resp == null) {
                codeView.text = "ERROR"
                setStatus("Couldn't reach the support service — check your internet.", warn = true)
                return@launch
            }
            sessionId = resp.optString("session_id")
            sessionCode = resp.optString("code")
            codeView.text = formatCode(sessionCode!!)
            startMediaProjectionConsent()
        }
    }

    private fun formatCode(c: String): String =
        if (c.length == 6) "${c.substring(0, 3)}  ${c.substring(3)}" else c

    private fun startMediaProjectionConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startStreaming(resultCode: Int, data: Intent) {
        val sid = sessionId ?: return
        val wsUrl = repo.baseUrlPublic().trimEnd('/').replaceFirst("http", "ws") +
                "/api/support/host/$sid"
        val req = Request.Builder().url(wsUrl).build()
        hostWs = ResilientHttp.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { setStatus("Connected — waiting for technician…") }
                val hello = JSONObject().apply {
                    put("type", "hello")
                    put("device_id", deviceId())
                    put("build", android.os.Build.MODEL ?: "unknown")
                    put("screen_w", resources.displayMetrics.widthPixels)
                    put("screen_h", resources.displayMetrics.heightPixels)
                }
                webSocket.send(hello.toString())
                screenCapture = ScreenCaptureController(
                    this@SupportSessionActivity, resultCode, data, webSocket,
                )
                screenCapture?.start()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = try { JSONObject(text) } catch (_: Throwable) { return }
                if (parsed.optString("type") == "input") {
                    runOnUiThread { setStatus("Technician connected — live") }
                    RootInputDispatcher.handle(this@SupportSessionActivity, parsed)
                } else if (parsed.optString("type") == "controller_bye") {
                    runOnUiThread {
                        setStatus("Technician disconnected — session ending…", warn = true)
                        finish()
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    setStatus("Session closed.", warn = true)
                    finish()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "host ws failure", t)
                runOnUiThread { setStatus("Connection lost — restart to retry.", warn = true) }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        screenCapture?.stop()
        screenCapture = null
        hostWs?.close(1000, "user_exit")
        hostWs = null
        sessionId?.let { sid ->
            // Fire-and-forget cancel so the backend reaps fast.
            Thread {
                try {
                    val body = JSONObject().apply { put("session_id", sid) }.toString()
                        .toRequestBody("application/json".toMediaTypeOrNull())
                    val url = repo.baseUrlPublic().trimEnd('/') + "/api/support/host/cancel"
                    ResilientHttp.client.newCall(Request.Builder().url(url).post(body).build())
                        .execute().close()
                } catch (_: Throwable) { /* */ }
            }.start()
        }
    }

    private fun deviceId(): String =
        tv.onnow.launcher.onboarding.OnboardingActivity.deviceId(this)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
