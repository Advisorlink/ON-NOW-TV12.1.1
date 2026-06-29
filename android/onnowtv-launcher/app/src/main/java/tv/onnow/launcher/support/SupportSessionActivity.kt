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
    // v2.10.89 — Activity no longer owns capture / polling state.
    // Everything lives in SupportForegroundService now.

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
        // v2.10.89 — Hand off to the foreground service.  The service
        // takes ownership of MediaProjection, the persistent root
        // shell, the screen-capture loop, AND the input long-poller,
        // so all of those survive the activity finishing.  This is
        // what makes the customer's launcher home naturally come
        // back to the foreground (and to the captured screen) after
        // they grant projection consent.
        val svc = Intent(this, SupportForegroundService::class.java).apply {
            action = SupportForegroundService.ACTION_START
            putExtra(SupportForegroundService.EX_RESULT_CODE, resultCode)
            putExtra(SupportForegroundService.EX_RESULT_DATA, data)
            putExtra(SupportForegroundService.EX_SESSION_ID, sid)
            putExtra(SupportForegroundService.EX_BASE_URL, base)
            putExtra(SupportForegroundService.EX_DEVICE_ID, deviceId())
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        // Finish so the launcher home returns to view.  The service
        // keeps capturing + dispatching inputs in the background.
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // v2.10.89 — Activity no longer owns the session.  All the
        // teardown logic (screen capture, input poller, shell,
        // /host/cancel POST) lives in
        // SupportForegroundService.onDestroy().  Stopping the
        // service from outside (notification tap, or the operator
        // hanging up) is the canonical way to end a session.
    }

    private fun deviceId(): String =
        tv.onnow.launcher.onboarding.OnboardingActivity.deviceId(this)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
