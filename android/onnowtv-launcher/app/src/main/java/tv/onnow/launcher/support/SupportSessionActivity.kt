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
    private var screenCapture: ScreenCaptureController? = null
    @Volatile private var pollingActive = false
    private var inputPollerThread: Thread? = null

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
        val base = repo.baseUrlPublic().trimEnd('/')
        // v2.10.88 — Pure-HTTP session.  No WebSocket anywhere — the
        // previous WS-based design got killed by Cloudflare's free
        // plan idle timeout ~60-90s in.  Three HTTP endpoints do all
        // the work:
        //   POST  /api/support/host/hello/{sid}    — one-shot hello
        //   POST  /api/support/host/frame/{sid}    — JPEG per frame
        //   GET   /api/support/host/inputs/{sid}   — long-poll inputs
        runOnUiThread { setStatus("Connected — waiting for technician…") }

        // v2.10.89 — Open the persistent root shell NOW (while the
        // user is still looking at the Support activity) so the
        // Magisk superuser prompt appears exactly once, in a
        // predictable place.  Subsequent input commands reuse the
        // same shell — no more flashing dialog per key-press.
        Thread {
            val ok = RootInputDispatcher.ensureShell()
            if (!ok) {
                runOnUiThread {
                    setStatus(
                        "Could not get root shell — controls won't work. " +
                            "Tap the superuser prompt to allow.",
                        warn = true,
                    )
                }
            }
        }.also { it.isDaemon = true }.start()

        // 1) POST hello once so the operator's panel can render
        //    device-id + screen resolution immediately.
        Thread {
            try {
                val hello = JSONObject().apply {
                    put("device_id", deviceId())
                    put("build", android.os.Build.MODEL ?: "unknown")
                    put("screen_w", resources.displayMetrics.widthPixels)
                    put("screen_h", resources.displayMetrics.heightPixels)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = Request.Builder()
                    .url("$base/api/support/host/hello/$sid")
                    .post(hello).build()
                ResilientHttp.client.newCall(req).execute().close()
            } catch (t: Throwable) {
                Log.w(TAG, "host/hello POST failed", t)
            }
        }.start()

        // 2) Start streaming frames — the capture controller now
        //    POSTs each JPEG to /host/frame/{sid}.
        screenCapture = ScreenCaptureController(
            this@SupportSessionActivity, resultCode, data,
            "$base/api/support/host/frame/$sid",
        )
        screenCapture?.start()

        // 3) Long-poll for operator inputs in a background thread.
        pollingActive = true
        inputPollerThread = Thread {
            var since = 0L
            while (pollingActive) {
                try {
                    val url = "$base/api/support/host/inputs/$sid?since=$since&wait=20"
                    val req = Request.Builder().url(url).get().build()
                    val resp = ResilientHttp.client.newCall(req).execute()
                    val body = resp.use { it.body?.string().orEmpty() }
                    if (body.isEmpty()) {
                        Thread.sleep(500)
                        continue
                    }
                    val parsed = JSONObject(body)
                    val maxSeq = parsed.optLong("max_seq", since)
                    if (maxSeq > since) since = maxSeq
                    val arr = parsed.optJSONArray("inputs") ?: continue
                    if (arr.length() == 0) continue
                    runOnUiThread { setStatus("Technician connected — live") }
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val payload = item.optJSONObject("payload") ?: continue
                        RootInputDispatcher.handle(this@SupportSessionActivity, payload)
                    }
                } catch (t: Throwable) {
                    if (!pollingActive) break
                    Log.w(TAG, "input long-poll error", t)
                    // Brief back-off so a flapping network doesn't
                    // spin the CPU.
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        // v2.10.89 — IMPORTANT: get the Support activity out of the
        // way so MediaProjection captures the actual TV interface,
        // not this code-display screen.  `moveTaskToBack(true)`
        // keeps the activity ALIVE in the back stack (so screen
        // capture + input polling keep running) but the launcher
        // comes back to the foreground for the customer.  A 600 ms
        // delay lets the "Connected" status be visible briefly so
        // the customer knows the session started.
        window.decorView.postDelayed({
            try { moveTaskToBack(true) } catch (_: Throwable) {}
        }, 600)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingActive = false
        inputPollerThread?.interrupt()
        inputPollerThread = null
        screenCapture?.stop()
        screenCapture = null
        // v2.10.89 — Close the persistent root shell.
        try { RootInputDispatcher.shutdown() } catch (_: Throwable) {}
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
