package tv.onnow.launcher.v2ai

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import tv.onnow.launcher.R
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/**
 * v2.8.23 — V2 AI: push-and-hold voice assistant.
 *
 * Flow:
 *   1. User hovers focus on the V2 AI top-bar pill in MainActivity
 *      and presses + holds the OK / Enter / Center button.
 *   2. We launch this Activity.  It auto-starts recording on
 *      arrival (key press is buffered) until the user releases.
 *   3. While recording, an animated waveform pulses to show audio
 *      is being captured.
 *   4. On release, we upload the m4a to `/api/launcher/v2ai/process`.
 *      Backend transcribes (Whisper) + parses intent (GPT-5).
 *   5. We dispatch the returned intent:
 *        play_movie / play_series → launch Vesper with deep-link
 *        open_app                 → launch the named installed app
 *        recommend / search       → render a beautiful list on-screen
 *        reject                   → show the friendly "I only handle
 *                                   movies/TV/apps" message.
 *
 * Strict guardrails: NO local intent fallback when the LLM rejects.
 * No troubleshooting / weather / chat — those all surface as a
 * friendly "Sorry I only help with movies, TV, and apps." card.
 */
class VoiceAssistantActivity : AppCompatActivity() {

    private lateinit var waveform: VoiceWaveform
    private lateinit var statusLine: TextView
    private lateinit var bigHint: TextView
    private lateinit var resultArea: LinearLayout

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recordingStartedMs = 0L
    private var keyIsDown = false
    private var processingJob: Job? = null

    private val PERM_REQ = 731

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        applyAdminCustomisation()
        requestMicIfNeeded()
    }

    /** v2.8.25 — Pull the admin-set V2 AI heading text + background
     *  image from the cached LauncherConfig.  Applied on every
     *  activity launch so any admin edit propagates within ~30 s.
     *  v2.8.26 — Also applies the waveform style. */
    private fun applyAdminCustomisation() {
        val cfg = try {
            tv.onnow.launcher.data.LauncherRepository(applicationContext).loadCached()
        } catch (_: Throwable) { null } ?: return
        cfg.v2ai.headingText?.takeIf { it.isNotBlank() }?.let { bigHint.text = it }
        // v2.8.26 — Waveform style ('bars' default).
        val style = (cfg.v2ai.waveformStyle ?: "bars").lowercase()
        waveform.style = when (style) {
            "dots"  -> VoiceWaveform.Style.DOTS
            "ring"  -> VoiceWaveform.Style.RING
            "sweep" -> VoiceWaveform.Style.SWEEP
            "pulse" -> VoiceWaveform.Style.PULSE
            else    -> VoiceWaveform.Style.BARS
        }
        cfg.v2ai.backgroundImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            // v2.8.27 — User wants the uploaded background rendered
            // VIBRANT, no dark overlay.  Removed the 60 % black scrim.
            // Text legibility is preserved via the heading's existing
            // shadow + the activity's content stays in the lower-
            // centre of the screen where backgrounds typically have
            // less detail.
            val host = (window.decorView as? ViewGroup) ?: return@let
            val root = host.findViewById<View>(android.R.id.content) as? ViewGroup
                ?: return@let
            val frame = (root.getChildAt(0) as? FrameLayout) ?: return@let
            val bgView = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            frame.addView(bgView, 0)
            tv.onnow.launcher.ImageLoader.load(bgView, url)
        }
    }

    override fun onPause() {
        super.onPause()
        stopRecording(discard = true)
        processingJob?.cancel()
    }

    /* ──────────────  Permission  ────────────── */

    private fun requestMicIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERM_REQ,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            statusLine.text = "Mic permission denied"
            bigHint.text = "Enable microphone in Settings to use V2 AI."
        }
    }

    /* ──────────────  UI  ────────────── */

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.onb_bg_glow)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(64), dp(48), dp(64), dp(48))
        }

        column.addView(TextView(this).apply {
            text = "ON NOW TV V2 · V2 AI"
            textSize = 12f
            letterSpacing = 0.30f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            typeface = Typeface.MONOSPACE
        })
        column.addView(spacer(dp(10)))

        bigHint = TextView(this).apply {
            text = "Hold OK and ask anything about movies, TV, or apps."
            textSize = 30f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
            gravity = Gravity.CENTER
        }
        column.addView(bigHint)
        column.addView(spacer(dp(24)))

        waveform = VoiceWaveform(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(640), dp(120))
        }
        column.addView(waveform)
        column.addView(spacer(dp(16)))

        statusLine = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
        }
        column.addView(statusLine)
        column.addView(spacer(dp(20)))

        // Result area — holds either the speech_reply card or
        // the recommendation list.
        resultArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(resultArea, LinearLayout.LayoutParams(
            dp(720),
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })
        return root
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /* ──────────────  Push-to-talk key handling  ────────────── */

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isOkKey(keyCode) && !keyIsDown) {
            keyIsDown = true
            startRecording()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isOkKey(keyCode) && keyIsDown) {
            keyIsDown = false
            stopRecording(discard = false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isOkKey(keyCode: Int): Boolean = keyCode in setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
    )

    /* ──────────────  Recording  ────────────── */

    private fun startRecording() {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED) {
            requestMicIfNeeded()
            return
        }
        resultArea.removeAllViews()
        statusLine.text = "Listening…"
        bigHint.text = "Speaking…"
        val out = File(cacheDir, "v2ai-${System.currentTimeMillis()}.m4a")
        audioFile = out
        recordingStartedMs = System.currentTimeMillis()
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(16_000)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            waveform.startAnimating { recorder?.maxAmplitude ?: 0 }
        } catch (e: Throwable) {
            statusLine.text = "Couldn't start mic: ${e.message}"
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    private fun stopRecording(discard: Boolean) {
        val rec = recorder ?: return
        recorder = null
        waveform.stopAnimating()
        try {
            rec.stop()
            rec.release()
        } catch (_: Throwable) { /* user released before any audio captured */ }
        val durationMs = System.currentTimeMillis() - recordingStartedMs
        val file = audioFile
        audioFile = null
        if (discard || file == null || durationMs < 350 || !file.exists() || file.length() < 1024) {
            file?.delete()
            statusLine.text = "Hold OK longer to speak"
            return
        }
        statusLine.text = "Thinking…"
        bigHint.text = "Processing your request…"
        processAudio(file)
    }

    /* ──────────────  Backend round-trip  ────────────── */

    private fun processAudio(file: File) {
        processingJob?.cancel()
        processingJob = lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { uploadAndParse(file) }
            file.delete()
            handleIntent(parsed)
        }
    }

    private fun uploadAndParse(file: File): UploadResult {
        return try {
            val base = tv.onnow.launcher.data.LauncherRepository
                .DEFAULT_BASE_URL.trimEnd('/')
            // v2.8.26 — Bumped timeouts.  The backend's Whisper +
            // GPT-5 round-trip is ~20-30 s on the preview pod; the
            // HK1's slow Wi-Fi can add another 10 s on top.  A 45 s
            // callTimeout was too tight — bumped to 90 s.  Separate
            // connect/read/write timeouts so we fail fast on actual
            // network errors (no DNS, no TLS) rather than waiting
            // the full 90 s for a connect that will never succeed.
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .readTimeout(75, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", file.name,
                    file.asRequestBody("audio/mp4".toMediaTypeOrNull()),
                )
                .build()
            val req = Request.Builder()
                .url("$base/api/launcher/v2ai/process")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return UploadResult.HttpError(resp.code, raw.take(200))
                }
                UploadResult.Ok(JSONObject(raw))
            }
        } catch (e: java.net.SocketTimeoutException) {
            UploadResult.Timeout
        } catch (e: java.net.UnknownHostException) {
            UploadResult.NoNetwork
        } catch (e: javax.net.ssl.SSLException) {
            UploadResult.NetworkError("TLS: ${e.message ?: "unknown"}")
        } catch (e: java.io.IOException) {
            UploadResult.NetworkError(e.message ?: "I/O failure")
        } catch (e: Throwable) {
            UploadResult.NetworkError(e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /* v2.8.26 — Richer failure modes so the UI can render specific
     * reasons instead of the generic "Couldn't reach V2 AI" card. */
    private sealed class UploadResult {
        data class Ok(val json: JSONObject) : UploadResult()
        data class HttpError(val code: Int, val body: String) : UploadResult()
        data class NetworkError(val reason: String) : UploadResult()
        object Timeout : UploadResult()
        object NoNetwork : UploadResult()
    }

    private fun handleIntent(result: UploadResult) {
        // v2.8.26 — Branch on the rich UploadResult sealed class so
        // the UI surfaces SPECIFIC failure reasons (DNS/TLS vs HTTP
        // 5xx vs timeout) instead of the generic "Couldn't reach
        // V2 AI" card that hid the actual problem.
        val parsed = when (result) {
            is UploadResult.Ok          -> result.json
            is UploadResult.HttpError   -> {
                renderRejectCard("Server returned ${result.code}.  Please try again in a moment.")
                return
            }
            is UploadResult.Timeout     -> {
                renderRejectCard("V2 AI took too long.  Try a shorter command on a faster Wi-Fi network.")
                return
            }
            is UploadResult.NoNetwork   -> {
                renderRejectCard("No internet — check Wi-Fi and try again.")
                return
            }
            is UploadResult.NetworkError -> {
                renderRejectCard("Couldn't reach V2 AI (${result.reason}).  Check Wi-Fi and try again.")
                return
            }
        }
        val intent = parsed.optString("intent", "reject")
        val reply  = parsed.optString("speech_reply", "Done.")
        statusLine.text = parsed.optString("transcript", "")
        when (intent) {
            "play_movie", "play_series" -> {
                val title = parsed.optString("title", "").trim()
                if (title.isEmpty()) {
                    renderRejectCard("I didn't catch the title.  Hold OK and try again.")
                    return
                }
                bigHint.text = reply.ifBlank { "Loading $title…" }
                launchVesperPlay(title, intent == "play_series")
            }
            "open_app" -> {
                val appName = parsed.optString("app_name", "").trim()
                bigHint.text = reply.ifBlank { "Opening $appName…" }
                openAppByName(appName)
            }
            "recommend", "search" -> {
                bigHint.text = reply.ifBlank { "Here are some picks for you." }
                renderRecommendations(parsed.optJSONArray("recommendations"))
            }
            else -> renderRejectCard(
                parsed.optString("reject_reason", "I only help with movies, TV, and apps."),
            )
        }
    }

    /* ──────────────  Intent dispatch  ────────────── */

    private fun launchVesperPlay(title: String, isSeries: Boolean) {
        // Open Vesper with `?v2ai=<encoded title>` deep-link.  The
        // Vesper React app auto-detects this on cold-start and
        // routes to its search → first-result → play flow, hitting
        // ExoPlayer fullscreen.
        //
        // v2.8.25 — Match the EXISTING `profile=kids` deep-link
        // contract: use `packageManager.getLaunchIntentForPackage`
        // (Vesper's MAIN/LAUNCHER intent) and attach the deep-link
        // BOTH as a `vesper_route` extra AND as an `onnowtv://launch`
        // data URI.  The previous `ACTION_VIEW https://onnowtv.app`
        // intent never resolved because Vesper's manifest doesn't
        // claim that host — `resolveActivity` returned null and we
        // fell back to a bare launcher entry, losing the query.
        //
        // v2.8.27 — CRITICAL FIX: the Vesper APK's installed
        // applicationId is `tv.onnowtv.app` (the Kotlin namespace
        // `tv.vesper.app` is compile-time only).  Using the wrong
        // package made `getLaunchIntentForPackage` return null on
        // every device, so V2 AI always fell into the "isn't
        // installed" reject card.
        val encoded = java.net.URLEncoder.encode(title, "UTF-8")
        val typeArg = if (isSeries) "series" else "movie"
        val launch  = packageManager.getLaunchIntentForPackage("tv.onnowtv.app")
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(
                    "vesper_route",
                    "/?v2ai=$encoded&type=$typeArg&autoplay=1",
                )
                data = android.net.Uri.parse(
                    "onnowtv://launch?v2ai=$encoded&type=$typeArg&autoplay=1",
                )
            }
        if (launch != null) {
            startActivity(launch)
            finish()
        } else {
            renderRejectCard("ON NOW TV V2 isn't installed on this box yet.")
        }
    }

    private fun openAppByName(name: String) {
        if (name.isBlank()) {
            renderRejectCard("I didn't catch the app name.")
            return
        }
        val pm = packageManager
        val installed = pm.getInstalledApplications(0)
        val match = installed.firstOrNull {
            val label = pm.getApplicationLabel(it).toString()
            label.equals(name, ignoreCase = true) ||
                label.contains(name, ignoreCase = true)
        }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.packageName) }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        } else {
            renderRejectCard("$name isn't installed on this box yet.")
        }
    }

    private fun renderRejectCard(reason: String) {
        bigHint.text = "Sorry"
        resultArea.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#33FF5573"))
                setStroke(dp(1), Color.parseColor("#FFFF5573"))
            }
        }
        card.addView(TextView(this).apply {
            text = reason
            textSize = 16f
            setTextColor(Color.parseColor("#FFFFF4F4"))
        })
        resultArea.addView(card)
    }

    private fun renderRecommendations(arr: org.json.JSONArray?) {
        resultArea.removeAllViews()
        if (arr == null || arr.length() == 0) {
            renderRejectCard("No matches found.")
            return
        }
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title", "")
            val year  = item.opt("year")?.toString()?.takeIf { it != "null" } ?: ""
            val why   = item.optString("why", "")
            val type  = item.optString("type", "movie")

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#33203A5C"))
                    setStroke(dp(1), Color.parseColor("#33B3D4FF"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, 0, dp(10)) }
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { launchVesperPlay(title, type == "series") }
            }
            card.addView(TextView(this@VoiceAssistantActivity).apply {
                text = if (year.isNotEmpty()) "$title  ·  $year" else title
                textSize = 18f
                setTextColor(Color.parseColor("#FFF4F7FB"))
                setTypeface(typeface, Typeface.BOLD)
            })
            if (why.isNotBlank()) card.addView(TextView(this@VoiceAssistantActivity).apply {
                text = why
                textSize = 13f
                setTextColor(Color.parseColor("#FF8EA0B7"))
                setPadding(0, dp(4), 0, 0)
            })
            resultArea.addView(card)
        }
        // Auto-focus the first recommendation so the user can hit OK
        // to play it immediately.
        resultArea.post { resultArea.getChildAt(0)?.requestFocus() }
    }
}


/**
 * v2.8.23 — Live waveform visualiser.  Reads `MediaRecorder
 * .maxAmplitude` every 60ms (poll callback supplied by the host)
 * and shifts a rolling buffer of N samples through a gradient
 * column-of-bars render.  Pure custom View — no third-party libs.
 */
class VoiceWaveform @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /* v2.8.26 — 5 admin-selectable render styles.  Default = bars. */
    enum class Style { BARS, DOTS, RING, SWEEP, PULSE }

    var style: Style = Style.BARS
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    private val bars = 48
    private val levels = FloatArray(bars) { 0f }
    private var pollJob: Job? = null
    private var sampler: (() -> Int)? = null
    private var idlePhase = 0f
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.FILL
    }

    fun startAnimating(amp: () -> Int) {
        sampler = amp
        pollJob?.cancel()
        pollJob = (context as? androidx.lifecycle.LifecycleOwner)
            ?.lifecycleScope?.launch {
                while (isActive) {
                    delay(55)
                    val a = (sampler?.invoke() ?: 0).coerceIn(0, 32_767)
                    val norm = (a / 32_767f).coerceIn(0f, 1f)
                    // Shift left, drop newest sample on the right.
                    for (i in 0 until bars - 1) levels[i] = levels[i + 1]
                    levels[bars - 1] = (norm * 1.6f).coerceIn(0f, 1f)
                    invalidate()
                }
            }
    }

    fun stopAnimating() {
        pollJob?.cancel()
        pollJob = null
        sampler = null
        for (i in levels.indices) levels[i] = 0f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Idle shimmer animation when not recording.
        ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 2_400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                idlePhase = it.animatedValue as Float
                // Always invalidate during idle for live shimmer/ring effects.
                if (pollJob == null) invalidate()
            }
            start()
        }
    }

    private fun ampAt(i: Int): Float =
        if (pollJob != null) levels[i] else {
            val phase = idlePhase + (i / bars.toFloat()) * (2 * Math.PI).toFloat()
            0.10f + 0.05f * sin(phase.toDouble()).toFloat()
        }

    /** Avg of the rolling buffer — used by ring/pulse variants. */
    private fun peakAmp(): Float {
        var max = 0f
        for (v in levels) if (v > max) max = v
        if (pollJob == null) {
            return 0.18f + 0.10f * sin(idlePhase.toDouble()).toFloat().coerceAtLeast(0f)
        }
        return max
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        barPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                Color.parseColor("#FF5DC8FF"),
                Color.parseColor("#FF2BB6FF"),
                Color.parseColor("#FFFF7AB6"),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        when (style) {
            Style.BARS  -> drawBars(canvas, w, h)
            Style.DOTS  -> drawDots(canvas, w, h)
            Style.RING  -> drawRing(canvas, w, h)
            Style.SWEEP -> drawSweep(canvas, w, h)
            Style.PULSE -> drawPulse(canvas, w, h)
        }
    }

    private fun drawBars(canvas: Canvas, w: Float, h: Float) {
        val barW = w / (bars * 1.5f)
        val gap = barW * 0.5f
        for (i in 0 until bars) {
            val bh = (h * (0.10f + ampAt(i) * 0.85f)).coerceAtMost(h)
            val left = i * (barW + gap)
            val top = (h - bh) / 2f
            canvas.drawRoundRect(left, top, left + barW, top + bh, barW / 2f, barW / 2f, barPaint)
        }
    }

    private fun drawDots(canvas: Canvas, w: Float, h: Float) {
        val cols = bars
        val colW = w / cols
        fillPaint.shader = barPaint.shader
        for (i in 0 until cols) {
            val a = ampAt(i)
            val r = (colW * 0.4f) * (0.35f + a * 0.65f)
            val cx = i * colW + colW / 2f
            val cy = h / 2f + (sin((idlePhase + i * 0.4f).toDouble()).toFloat()) * (h * 0.20f) * a
            canvas.drawCircle(cx, cy, r, fillPaint)
        }
    }

    private fun drawRing(canvas: Canvas, w: Float, h: Float) {
        val amp = peakAmp()
        val cx = w / 2f; val cy = h / 2f
        val baseR = (kotlin.math.min(w, h) / 2f) * 0.32f
        // Three concentric rings — each scales differently to amp.
        ringPaint.shader = barPaint.shader
        for (idx in 0..2) {
            val factor = 1f + idx * 0.4f + amp * (0.4f + idx * 0.3f)
            ringPaint.alpha = (255 - idx * 70).coerceAtLeast(40)
            ringPaint.strokeWidth = 7f - idx * 1.5f
            canvas.drawCircle(cx, cy, baseR * factor, ringPaint)
        }
        // Solid core
        fillPaint.shader = barPaint.shader
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, baseR * (0.55f + amp * 0.25f), fillPaint)
    }

    private fun drawSweep(canvas: Canvas, w: Float, h: Float) {
        val amp = peakAmp()
        // Horizontal flowing gradient ribbon that thickens with amp.
        val ribH = (h * (0.10f + amp * 0.45f))
        val top  = (h - ribH) / 2f
        val sweep = (idlePhase / (2 * Math.PI).toFloat()) * w * 2f
        val ribbon = LinearGradient(
            -w + sweep, 0f, w + sweep, 0f,
            intArrayOf(
                Color.parseColor("#005DC8FF"),
                Color.parseColor("#FF2BB6FF"),
                Color.parseColor("#FFFF7AB6"),
                Color.parseColor("#005DC8FF"),
            ),
            floatArrayOf(0f, 0.4f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.shader = ribbon
        canvas.drawRoundRect(0f, top, w, top + ribH, ribH / 2f, ribH / 2f, fillPaint)
    }

    private fun drawPulse(canvas: Canvas, w: Float, h: Float) {
        val amp = peakAmp()
        val cx = w / 2f; val cy = h / 2f
        val baseR = (kotlin.math.min(w, h) / 2f) * 0.30f
        // Outer halos (soft, low-alpha).
        for (idx in 0..2) {
            val factor = 1.4f + idx * 0.5f + amp * 1.2f
            fillPaint.shader = null
            fillPaint.color = Color.argb(
                ((50 - idx * 14).coerceAtLeast(8)).coerceAtMost(120),
                0x2B, 0xB6, 0xFF,
            )
            canvas.drawCircle(cx, cy, baseR * factor, fillPaint)
        }
        // Solid core
        fillPaint.shader = barPaint.shader
        fillPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, baseR * (0.55f + amp * 0.30f), fillPaint)
    }
}
