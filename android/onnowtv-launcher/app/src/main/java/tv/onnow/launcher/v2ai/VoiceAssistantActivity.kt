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
import kotlin.math.cos
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
    /* v2.8.39 — Header label at the top of the result screen that
     * echoes the user's transcribed question/statement so they
     * know what V2 AI is answering. */
    private lateinit var resultQuestion: TextView
    /* v2.8.39 — Last successful Whisper transcript.  Read by all
     * three render* methods to populate the result-screen header. */
    private var lastTranscript: String = ""
    /* v2.8.35 — Standby group (heading + waveform + hold button +
     * status line) is hidden when results are shown so the result
     * cards get the full screen height and never get clipped at
     * the top or bottom. */
    private var standbyGroup: LinearLayout? = null
    /* v2.8.35 — Wraps the resultArea + a top "ASK AGAIN" hint
     * row.  Visible only when there's content to display. */
    private var resultGroup: LinearLayout? = null
    /* v2.8.29 — Optional dimming scrim painted over the activity
     * root whenever we're showing recommendation / QA results so
     * the user's eye locks onto the cards instead of competing
     * with the admin-uploaded background image. */
    private var stageDim: View? = null
    /* v2.8.30 — Visual "HOLD OK" affordance painted between the
     * waveform and the status line.  Admin can supply a custom
     * image OR hide it entirely via the launcher portal.  Default
     * state shows a circular cyan button with the text "HOLD OK". */
    private var holdButton: android.widget.FrameLayout? = null
    private var holdButtonLabel: TextView? = null
    private var holdButtonImage: android.widget.ImageView? = null

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
        cfg.v2ai.headingText?.takeIf { it.isNotBlank() }?.let {
            bigHint.text = it
            cachedStandbyHint = it
        }
        // v2.8.26 — Waveform style ('bars' default).
        val style = (cfg.v2ai.waveformStyle ?: "bars").lowercase()
        waveform.style = when (style) {
            "dots"      -> VoiceWaveform.Style.DOTS
            "ring"      -> VoiceWaveform.Style.RING
            "sweep"     -> VoiceWaveform.Style.SWEEP
            "pulse"     -> VoiceWaveform.Style.PULSE
            "aurora"    -> VoiceWaveform.Style.AURORA
            "orb"       -> VoiceWaveform.Style.ORB
            "particles" -> VoiceWaveform.Style.PARTICLES
            "neon"      -> VoiceWaveform.Style.NEON
            "prism"     -> VoiceWaveform.Style.PRISM
            else        -> VoiceWaveform.Style.BARS
        }
        // v2.8.30 — Hold-to-talk button: visibility + optional image
        // override.  Admin can hide the badge entirely OR replace it
        // with any uploaded PNG.
        val hb = holdButton
        if (hb != null) {
            if (!cfg.v2ai.holdButtonVisible) {
                hb.visibility = View.GONE
            } else {
                hb.visibility = View.VISIBLE
                val url = cfg.v2ai.holdButtonImageUrl
                if (!url.isNullOrBlank()) {
                    // Image override — show the ImageView, hide the
                    // default badge TextView.
                    holdButtonLabel?.visibility = View.GONE
                    holdButtonImage?.visibility = View.VISIBLE
                    holdButtonImage?.let {
                        tv.onnow.launcher.ImageLoader.load(it, url)
                    }
                } else {
                    holdButtonLabel?.visibility = View.VISIBLE
                    holdButtonImage?.visibility = View.GONE
                }
            }
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

    /* v2.8.35 — Back button: results visible → return to standby.
     * Only finish() the activity when we're already on the
     * standby screen.  This makes the BACK button feel natural:
     * results → standby → home, two presses to fully leave. */
    override fun onBackPressed() {
        if (resultGroup?.visibility == View.VISIBLE) {
            showStandby()
        } else {
            super.onBackPressed()
        }
    }

    /** v2.8.35 — Switch between standby (heading + waveform +
     *  hold button visible, no results) and result (cards full
     *  screen, standby hidden) modes.
     *  v2.8.39 — Also paints the transcribed question header
     *  at the top of the result screen when transitioning into
     *  result mode. */
    private fun setResultMode(showResults: Boolean, transcript: String? = null) {
        standbyGroup?.visibility = if (showResults) View.GONE else View.VISIBLE
        resultGroup?.visibility  = if (showResults) View.VISIBLE else View.GONE
        setStageDimmed(showResults)
        if (showResults && !transcript.isNullOrBlank()) {
            resultQuestion.text = "“${transcript.trim()}”"
            resultQuestion.visibility = View.VISIBLE
        } else if (!showResults) {
            resultQuestion.visibility = View.GONE
            resultQuestion.text = ""
        }
    }

    /** v2.8.35 — Reset to the standby screen + restore mic state. */
    private fun showStandby() {
        setResultMode(false)
        resultArea.removeAllViews()
        bigHint.text = cachedStandbyHint
            ?: "Hold OK and ask anything about movies, TV, or apps."
        statusLine.text = "Ready"
    }
    private var cachedStandbyHint: String? = null

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
        // v2.8.35 — Outer column splits into a STANDBY half (eyebrow
        // + heading + waveform + hold-button + status) and a RESULT
        // half (top hint + horizontal carousel).  Whenever the user
        // gets cards we hide standby + show result, so cards have
        // the full screen height with no chance of being clipped.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }

        val standby = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        standbyGroup = standby

        standby.addView(TextView(this).apply {
            text = "ON NOW TV V2 · V2 AI"
            textSize = 12f
            letterSpacing = 0.30f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            typeface = Typeface.MONOSPACE
        })
        standby.addView(spacer(dp(10)))

        bigHint = TextView(this).apply {
            text = "Hold OK and ask anything about movies, TV, or apps."
            textSize = 30f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
            gravity = Gravity.CENTER
        }
        standby.addView(bigHint)
        standby.addView(spacer(dp(24)))

        waveform = VoiceWaveform(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(640), dp(120))
        }
        standby.addView(waveform)
        standby.addView(spacer(dp(16)))

        // v2.8.30 — Hold-to-talk button.  Default: a glassy
        // circular cyan badge with the text "HOLD OK".  Admin can
        // swap the badge for any uploaded image OR hide it via the
        // launcher portal.  We use a FrameLayout container that
        // holds BOTH the default badge (TextView) AND the override
        // ImageView so we can switch between them at runtime
        // without rebuilding the layout.
        val holdContainer = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(140))
        }
        val holdLabel = TextView(this).apply {
            text = "HOLD OK"
            textSize = 16f
            setTextColor(Color.parseColor("#FF04060B"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = makeDefaultHoldButtonBg()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val holdImage = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            contentDescription = "Hold OK to talk"
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        holdContainer.addView(holdLabel)
        holdContainer.addView(holdImage)
        holdButton       = holdContainer
        holdButtonLabel  = holdLabel
        holdButtonImage  = holdImage
        standby.addView(holdContainer)
        standby.addView(spacer(dp(16)))

        statusLine = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
        }
        standby.addView(statusLine)
        column.addView(standby)

        // v2.8.35 — Result group.  Hidden by default; revealed
        // when the user gets recommendations / QA / person info.
        // Has its OWN top hint row so the user knows what they
        // can do from here.
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        resultGroup = results
        // Result-mode top hint (replaces the standby heading).
        val resultsHint = TextView(this).apply {
            this.text = "Press BACK to ask again"
            textSize = 13f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            letterSpacing = 0.16f
            gravity = Gravity.START
            setPadding(dp(8), 0, 0, dp(14))
        }
        results.addView(resultsHint)

        // v2.8.39 — Shows the user's transcribed question/statement
        // at the top of the result screen so they know what V2 AI
        // is answering.  Populated by setResultMode(true, transcript).
        resultQuestion = TextView(this).apply {
            this.text = ""
            textSize = 22f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.01f
            setPadding(dp(8), 0, dp(8), dp(18))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        results.addView(resultQuestion)

        // Result area — holds either the speech_reply card, the
        // recommendation poster carousel, or the QA hero card.
        // v2.8.29 — Now a horizontal scroller so multiple poster
        // cards fit side-by-side.  Width = full screen minus
        // padding so cards have room to breathe.
        // v2.8.35 — Result area gets the FULL screen height when
        // results are shown (standby is hidden).  Inner cards are
        // rectangular and fit comfortably without clipping.
        // v2.8.37 — gravity=CENTER on the inner LinearLayout +
        // setFillViewport(true) on the HorizontalScrollView mean
        // a small number of cards stay centered horizontally,
        // while a long carousel (10-20 cards) still scrolls
        // smoothly with the first card flush to the left edge.
        // The card row is also centered vertically so it never
        // hugs the top or bottom of the 1080p screen.
        resultArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val resultScroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFillViewport = true
            addView(resultArea, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        results.addView(resultScroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
        ).apply { weight = 1f })

        column.addView(results, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
        ).apply { weight = 1f })

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })

        // v2.8.29 — Stage-dimmer scrim.  Sits between the
        // background image and the foreground column; toggled by
        // `setStageDimmed` whenever the user gets recommendation
        // or QA results so the content stays legible against
        // any bright admin-uploaded background.
        stageDim = View(this).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        // Insert under the column (z-order: bg → scrim → column).
        root.addView(stageDim, root.childCount - 1)
        return root
    }

    /** v2.8.29 — Toggle the stage-dimmer scrim on/off. */
    private fun setStageDimmed(dim: Boolean) {
        stageDim?.visibility = if (dim) View.VISIBLE else View.GONE
    }

    /** v2.8.30 — Default circular badge for the hold-to-talk
     *  button when the admin hasn't uploaded an image.  Solid
     *  cyan circle so the centred "HOLD OK" TextView reads
     *  cleanly against any background. */
    private fun makeDefaultHoldButtonBg(): android.graphics.drawable.Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#FF2BB6FF"))
            setStroke(dp(3), Color.parseColor("#FFFFFFFF"))
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
        // v2.8.29 — Clear any previous result card + un-dim the stage
        // so the user always sees the waveform unobstructed while
        // they're speaking.
        resultArea.removeAllViews()
        setResultMode(false)
        statusLine.text = "Listening…"
        bigHint.text = "Speaking…"
        val out = File(cacheDir, "v2ai-${System.currentTimeMillis()}.m4a")
        audioFile = out
        recordingStartedMs = System.currentTimeMillis()
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            // v2.8.33 — Better audio capture for Whisper accuracy:
            //   • VOICE_RECOGNITION audio source applies Android's
            //     built-in AGC (auto gain control) + noise suppression
            //     + echo cancellation specifically tuned for speech.
            //     This is the BIG win — same hardware mic, 3-5× more
            //     intelligible audio for Whisper.
            //   • Bit-rate bumped 64 → 96 kbps so faster speech and
            //     consonants (s, t, k) survive the AAC compression.
            //   • Sample rate stays at 16 kHz — Whisper internally
            //     resamples to 16k anyway; sending 16k means zero
            //     resampling artefacts at the API boundary.
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(16_000)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            // v2.8.33 — VOICE_RECOGNITION audio source already
            // applies the OEM's built-in noise suppression + AGC +
            // echo cancellation on every modern Android box.  No
            // need to attach the AudioEffect.create(sessionId)
            // helpers — they require the audio session ID which
            // MediaRecorder doesn't expose, and would be redundant
            // here anyway.
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
            val client = tv.onnow.launcher.net.ResilientHttp.client.newBuilder()
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
                // v2.8.35 — Stable per-box device id lets the backend
                // thread a rolling 6-turn conversation history so the
                // user can ask follow-up questions ("and what about
                // his other movies?") naturally.
                .addFormDataPart(
                    "device_id",
                    tv.onnow.launcher.onboarding.OnboardingActivity
                        .deviceId(applicationContext),
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
        // v2.8.39 — Remember the transcript so all 3 render methods
        // can show it as a question header on the result screen.
        lastTranscript = parsed.optString("transcript", "")
        statusLine.text = lastTranscript
        when (intent) {
            "play_movie", "play_series" -> {
                val title = parsed.optString("title", "").trim()
                if (title.isEmpty()) {
                    renderRejectCard("I didn't catch the title.  Hold OK and try again.")
                    return
                }
                bigHint.text = reply.ifBlank { "Loading $title…" }
                setResultMode(false)
                launchVesperPlay(title, intent == "play_series")
            }
            "open_app" -> {
                val appName = parsed.optString("app_name", "").trim()
                bigHint.text = reply.ifBlank { "Opening $appName…" }
                setResultMode(false)
                openAppByName(appName)
            }
            "recommend", "search", "trending" -> {
                bigHint.text = reply.ifBlank { "Here are some picks for you." }
                renderRecommendations(parsed.optJSONArray("recommendations"))
            }
            "qa" -> {
                bigHint.text = reply.ifBlank { "Here's what I found." }
                renderQa(parsed)
            }
            "person_info" -> {
                bigHint.text = reply.ifBlank { "Here's the person you asked about." }
                renderPersonInfo(parsed)
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
        // v2.8.35 — Reject cards stay on the STANDBY screen so the
        // user sees the waveform / hold button immediately and
        // can try again without pressing BACK first.  The rejection
        // reason renders in the existing statusLine instead of a
        // separate card — keeps the screen uncluttered.
        setResultMode(false)
        statusLine.text = reason
    }

    private fun renderRecommendations(arr: org.json.JSONArray?) {
        resultArea.removeAllViews()
        if (arr == null || arr.length() == 0) {
            renderRejectCard("No matches found.")
            return
        }
        // v2.8.35 — Switch to full-screen result mode so cards
        // aren't clipped by the waveform / hold button above.
        // v2.8.39 — Echo the user's question at the top.
        setResultMode(true, lastTranscript)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            resultArea.addView(buildPosterCard(item))
        }
        resultArea.post { resultArea.getChildAt(0)?.requestFocus() }
    }

    /** v2.8.29 — Glassy card backdrop with focus-driven accent. */
    private fun makeRecCardBg(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(
                if (focused) Color.parseColor("#E61A2542")
                else         Color.parseColor("#B30E1834")
            )
            setStroke(
                dp(2),
                if (focused) Color.parseColor("#FF2BB6FF")
                else         Color.parseColor("#3343587F")
            )
        }

    /** v2.8.29 — Render a QA (factual Q&A) response.  Big hero
     *  layout: poster on the left, big answer text on the right,
     *  rating + year row underneath the title. */
    private fun renderQa(parsed: JSONObject) {
        resultArea.removeAllViews()
        setResultMode(true, lastTranscript)
        val answer  = parsed.optString("answer", "").ifBlank { parsed.optString("speech_reply", "") }
        val subject = parsed.optString("answer_subject", "")
        val poster  = parsed.optString("subject_poster_url", "").trim()
        val rating  = parsed.opt("subject_rating")?.toString()?.takeIf { it != "null" } ?: ""
        val year    = parsed.opt("subject_year")?.toString()?.takeIf { it != "null" } ?: ""
        val overview = parsed.optString("subject_overview", "")

        // v2.8.37 — No leading spacer; gravity=CENTER on the parent
        // resultArea centers the QA card horizontally on the screen.

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = makeRecCardBg(focused = false)
            layoutParams = LinearLayout.LayoutParams(dp(900), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left — poster.
        if (poster.isNotEmpty()) {
            val posterView = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(200), dp(290))
                    .apply { setMargins(0, 0, dp(24), 0) }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                }
            }
            tv.onnow.launcher.ImageLoader.load(posterView, poster)
            card.addView(posterView)
        }

        // Right — text column.
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Eyebrow.
        text.addView(TextView(this@VoiceAssistantActivity).apply {
            this.text = "V2 AI ANSWER"
            textSize = 11f
            letterSpacing = 0.30f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            setTypeface(typeface, Typeface.BOLD)
        })
        text.addView(spacer(dp(6)))
        // Subject title.
        if (subject.isNotEmpty()) {
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this@VoiceAssistantActivity).apply {
                this.text = subject
                textSize = 24f
                setTextColor(Color.parseColor("#FFF4F7FB"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (rating.isNotEmpty() && rating != "0.0" && rating != "0") {
                titleRow.addView(TextView(this@VoiceAssistantActivity).apply {
                    this.text = "★ $rating"
                    textSize = 13f
                    setTextColor(Color.parseColor("#FF0E1834"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(Color.parseColor("#FFFFC857"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(dp(12), 0, 0, 0) }
                })
            }
            text.addView(titleRow)
            if (year.isNotEmpty()) {
                text.addView(TextView(this@VoiceAssistantActivity).apply {
                    this.text = year
                    textSize = 13f
                    setTextColor(Color.parseColor("#FF8EA0B7"))
                    setPadding(0, dp(3), 0, 0)
                })
            }
            text.addView(spacer(dp(14)))
        }
        // Answer body.
        text.addView(TextView(this@VoiceAssistantActivity).apply {
            this.text = answer
            textSize = 17f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setLineSpacing(0f, 1.35f)
        })
        // Show overview underneath if non-empty and different.
        if (overview.isNotBlank() && !answer.contains(overview, ignoreCase = true)) {
            text.addView(spacer(dp(12)))
            text.addView(TextView(this@VoiceAssistantActivity).apply {
                this.text = overview
                textSize = 12f
                setTextColor(Color.parseColor("#FFC2D1E6"))
                setLineSpacing(0f, 1.30f)
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        card.addView(text)
        resultArea.addView(card)
    }

    /** v2.8.30 — Render an actor / director / writer info card
     *  with their photo, bio, and a poster carousel of their
     *  known_for titles.  Same beautiful design language as the
     *  QA layout but tuned for a person rather than a single
     *  movie / show. */
    private fun renderPersonInfo(parsed: JSONObject) {
        resultArea.removeAllViews()
        setResultMode(true, lastTranscript)
        val name    = parsed.optString("person_name", "")
        val bio     = parsed.optString("person_bio", "").ifBlank { parsed.optString("speech_reply", "") }
        val profile = parsed.optString("person_profile_url", "").trim()
        val known   = parsed.optJSONArray("known_for")

        // v2.8.37 — No leading spacer; gravity=CENTER on the parent
        // resultArea centers the bio card + carousel as a unit.

        // Big person-bio card.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = makeRecCardBg(focused = false)
            layoutParams = LinearLayout.LayoutParams(dp(720), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, dp(20), 0) }
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left — circular profile photo.
        if (profile.isNotEmpty()) {
            val photo = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(160), dp(220))
                    .apply { setMargins(0, 0, dp(20), 0) }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                }
            }
            tv.onnow.launcher.ImageLoader.load(photo, profile)
            card.addView(photo)
        }

        // Right — text column.
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this@VoiceAssistantActivity).apply {
            this.text = "V2 AI · CAST INFO"
            textSize = 11f
            letterSpacing = 0.30f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            setTypeface(typeface, Typeface.BOLD)
        })
        text.addView(spacer(dp(6)))
        text.addView(TextView(this@VoiceAssistantActivity).apply {
            this.text = name
            textSize = 24f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
        })
        text.addView(spacer(dp(10)))
        if (bio.isNotBlank()) {
            text.addView(TextView(this@VoiceAssistantActivity).apply {
                this.text = bio
                textSize = 14f
                setTextColor(Color.parseColor("#FFC2D1E6"))
                setLineSpacing(0f, 1.40f)
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        card.addView(text)
        resultArea.addView(card)

        // Known-for carousel — same shape as the recommendations
        // (220×320 poster, title + rating + year, focusable).
        if (known != null) {
            for (i in 0 until known.length()) {
                val item = known.optJSONObject(i) ?: continue
                resultArea.addView(buildPosterCard(item))
            }
        }
    }

    /** v2.8.35 — Shared rectangle card.  Each is a single tall
     *  rectangle (240dp wide × wrapping height) with:
     *    • 240 × 135 dp landscape backdrop (or scaled poster if no
     *      backdrop available) — clean 16:9 framing, never clipped
     *    • Title (16 sp bold, 2-line max)
     *    • Year · Type · ★ Rating chip row
     *    • 5-line synopsis (13 sp, line-spacing 1.30)
     *  Total: ~360 dp tall.  Fits 4-5 across a 1920 dp screen + 20
     *  scrolls smoothly with D-pad.  Click → opens in Vesper. */
    private fun buildPosterCard(item: JSONObject): View {
        val title    = item.optString("title", "")
        val year     = item.opt("year")?.toString()?.takeIf { it != "null" && it.isNotBlank() } ?: ""
        val type     = item.optString("type", "movie")
        val backdrop = item.optString("backdrop_url", "").trim()
        val poster   = item.optString("poster_url", "").trim()
        val overview = item.optString("overview", "").ifBlank { item.optString("why", "") }
        val rating   = item.opt("rating")?.toString()?.takeIf { it != "null" } ?: ""

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(14))
            background = makeRecCardBg(focused = false)
            layoutParams = LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, dp(14), 0) }
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { launchVesperPlay(title, type == "series") }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = makeRecCardBg(focused = hasFocus)
                if (hasFocus) v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(180).start()
                else v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
        }

        // Landscape backdrop (16:9 — 240 × 135 dp).  Falls back to
        // a portrait poster if no backdrop URL; either way we
        // center-crop into the 16:9 frame so all cards align cleanly.
        val artView = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(216), dp(122))
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#FF1A2542"),
                    Color.parseColor("#FF0E1834"),
                )
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
        }
        val art = backdrop.ifBlank { poster }
        if (art.isNotEmpty()) {
            tv.onnow.launcher.ImageLoader.load(artView, art)
        }
        card.addView(artView)
        card.addView(spacer(dp(10)))

        // Title (2-line max).
        card.addView(TextView(this@VoiceAssistantActivity).apply {
            this.text = title
            textSize = 15f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        })
        card.addView(spacer(dp(6)))

        // Meta row: Year · Type · Rating chip.
        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val metaText = buildString {
            if (year.isNotEmpty()) append(year)
            if (year.isNotEmpty()) append("  ·  ")
            append(if (type == "series") "TV" else "Movie")
        }
        metaRow.addView(TextView(this@VoiceAssistantActivity).apply {
            this.text = metaText
            textSize = 11f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (rating.isNotEmpty() && rating != "0.0" && rating != "0") {
            metaRow.addView(TextView(this@VoiceAssistantActivity).apply {
                this.text = "★ $rating"
                textSize = 11f
                setTextColor(Color.parseColor("#FF0E1834"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.parseColor("#FFFFC857"))
                }
            })
        }
        card.addView(metaRow)
        card.addView(spacer(dp(8)))

        // Synopsis (5 lines).
        if (overview.isNotBlank()) {
            card.addView(TextView(this@VoiceAssistantActivity).apply {
                this.text = overview
                textSize = 12f
                setTextColor(Color.parseColor("#FFC2D1E6"))
                setLineSpacing(0f, 1.30f)
                maxLines = 5
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        return card
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

    /* v2.8.26 — 5 admin-selectable render styles + v2.8.31 — 5
     * additional premium "Apple-feel" variants.  10 total. */
    enum class Style { BARS, DOTS, RING, SWEEP, PULSE, AURORA, ORB, PARTICLES, NEON, PRISM }

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
            Style.BARS      -> drawBars(canvas, w, h)
            Style.DOTS      -> drawDots(canvas, w, h)
            Style.RING      -> drawRing(canvas, w, h)
            Style.SWEEP     -> drawSweep(canvas, w, h)
            Style.PULSE     -> drawPulse(canvas, w, h)
            Style.AURORA    -> drawAurora(canvas, w, h)
            Style.ORB       -> drawOrb(canvas, w, h)
            Style.PARTICLES -> drawParticles(canvas, w, h)
            Style.NEON      -> drawNeon(canvas, w, h)
            Style.PRISM     -> drawPrism(canvas, w, h)
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

    // ── v2.8.31 — Premium "Apple-feel" visualizers ────────────────

    /** Aurora — flowing multi-ribbon northern-lights effect.
     *  Two horizontal sine ribbons in cyan→teal and pink→violet
     *  glide across the canvas with phase + amplitude derived
     *  from the audio level.  Heavy Gaussian-like falloff via
     *  RadialGradient + maskFilter blur for a soft glow. */
    private fun drawAurora(canvas: Canvas, w: Float, h: Float) {
        val amp = peakAmp()
        // Ribbon paint with soft blur.
        val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
            maskFilter = android.graphics.BlurMaskFilter(
                14f, android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
        // Ribbon 1 — cyan → teal, upper half.
        val path1 = android.graphics.Path()
        val midY1 = h * 0.40f
        val phase1 = idlePhase
        path1.moveTo(0f, midY1)
        var x = 0f
        while (x <= w) {
            val s = sin((phase1 + x / w * (2f * Math.PI).toFloat() * 2.5f).toDouble()).toFloat()
            val y = midY1 + s * h * (0.10f + amp * 0.25f)
            path1.lineTo(x, y)
            x += 6f
        }
        // Close into a thick ribbon.
        x = w
        while (x >= 0f) {
            val s = sin((phase1 + x / w * (2f * Math.PI).toFloat() * 2.5f).toDouble()).toFloat()
            val y = midY1 + s * h * (0.10f + amp * 0.25f) + h * (0.18f + amp * 0.12f)
            path1.lineTo(x, y)
            x -= 6f
        }
        path1.close()
        ribbonPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                Color.argb(0, 0x2B, 0xB6, 0xFF),
                Color.argb(220, 0x2B, 0xB6, 0xFF),
                Color.argb(220, 0x00, 0xE0, 0xB5),
                Color.argb(0, 0x00, 0xE0, 0xB5),
            ),
            floatArrayOf(0f, 0.25f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path1, ribbonPaint)
        // Ribbon 2 — pink → violet, lower half, reversed phase.
        val path2 = android.graphics.Path()
        val midY2 = h * 0.62f
        val phase2 = -idlePhase * 0.6f
        path2.moveTo(0f, midY2)
        x = 0f
        while (x <= w) {
            val s = sin((phase2 + x / w * (2f * Math.PI).toFloat() * 3.0f).toDouble()).toFloat()
            val y = midY2 + s * h * (0.10f + amp * 0.30f)
            path2.lineTo(x, y)
            x += 6f
        }
        x = w
        while (x >= 0f) {
            val s = sin((phase2 + x / w * (2f * Math.PI).toFloat() * 3.0f).toDouble()).toFloat()
            val y = midY2 + s * h * (0.10f + amp * 0.30f) + h * (0.16f + amp * 0.12f)
            path2.lineTo(x, y)
            x -= 6f
        }
        path2.close()
        ribbonPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                Color.argb(0, 0xFF, 0x7A, 0xB6),
                Color.argb(220, 0xFF, 0x7A, 0xB6),
                Color.argb(220, 0x9B, 0x57, 0xFF),
                Color.argb(0, 0x9B, 0x57, 0xFF),
            ),
            floatArrayOf(0f, 0.25f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path2, ribbonPaint)
    }

    /** Liquid Orb — Siri-style morphing sphere.  Single centred
     *  blob whose Path is rebuilt every frame from 16 cosine-
     *  perturbed radii.  Multi-stop RadialGradient fill + glow
     *  ring shadow for the premium feel. */
    private fun drawOrb(canvas: Canvas, w: Float, h: Float) {
        val amp = peakAmp()
        val cx = w / 2f; val cy = h / 2f
        val baseR = (kotlin.math.min(w, h) / 2f) * (0.34f + amp * 0.18f)
        val path = android.graphics.Path()
        val pts = 24
        for (i in 0..pts) {
            val t = i / pts.toFloat() * (2f * Math.PI).toFloat()
            // Two-frequency perturbation for an organic morph.
            val wob = (
                sin((t * 3f + idlePhase * 1.4f).toDouble()).toFloat() * (0.05f + amp * 0.10f) +
                sin((t * 5f - idlePhase * 0.7f).toDouble()).toFloat() * (0.03f + amp * 0.05f)
            )
            val r = baseR * (1f + wob)
            val x = cx + cos(t.toDouble()).toFloat() * r
            val y = cy + sin(t.toDouble()).toFloat() * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        // Outer glow rings — drawn first so they sit behind the orb.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
        for (idx in 2 downTo 1) {
            glow.color = Color.argb((24 - idx * 6).coerceAtLeast(6), 0x5D, 0xC8, 0xFF)
            canvas.drawCircle(cx, cy, baseR * (1f + idx * 0.45f), glow)
        }
        // Orb body — radial gradient (white-hot core → cyan → magenta).
        val grad = android.graphics.RadialGradient(
            cx - baseR * 0.25f, cy - baseR * 0.30f, baseR * 1.4f,
            intArrayOf(
                Color.parseColor("#FFFFFFFF"),
                Color.parseColor("#FF5DC8FF"),
                Color.parseColor("#FF9B57FF"),
                Color.parseColor("#FFFF7AB6"),
            ),
            floatArrayOf(0f, 0.35f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.shader = grad
        fillPaint.color = Color.WHITE
        canvas.drawPath(path, fillPaint)
    }

    /** Particles — swirling multicoloured dot field that follows
     *  a polar parametric path.  Each particle has a fixed radius
     *  + angular velocity offset, both pre-computed.  Cheap,
     *  smooth, scales perfectly with amplitude. */
    private val particleCount = 36
    private val particleSeed = FloatArray(particleCount * 3).also {
        // [radius_factor, angle_offset, hue_t] per particle.
        val r = java.util.Random(7L)
        for (i in 0 until particleCount) {
            it[i * 3]     = 0.30f + r.nextFloat() * 0.55f      // radius factor
            it[i * 3 + 1] = r.nextFloat() * (2f * Math.PI).toFloat()  // angle offset
            it[i * 3 + 2] = r.nextFloat()                      // hue [0,1]
        }
    }
    private fun particleHue(t: Float): Int {
        // Premium palette interpolation: cyan → teal → pink → violet → gold.
        val stops = intArrayOf(
            Color.parseColor("#5DC8FF"),
            Color.parseColor("#00E0B5"),
            Color.parseColor("#FF7AB6"),
            Color.parseColor("#9B57FF"),
            Color.parseColor("#FFC857"),
        )
        val seg = (t * (stops.size - 1)).coerceIn(0f, (stops.size - 1).toFloat())
        val i0 = seg.toInt().coerceAtMost(stops.size - 2)
        val frac = seg - i0
        fun lerp(a: Int, b: Int) = (a + (b - a) * frac).toInt().coerceIn(0, 255)
        val c0 = stops[i0]; val c1 = stops[i0 + 1]
        return Color.argb(
            255,
            lerp(Color.red(c0),   Color.red(c1)),
            lerp(Color.green(c0), Color.green(c1)),
            lerp(Color.blue(c0),  Color.blue(c1)),
        )
    }
    private fun drawParticles(canvas: Canvas, w: Float, h: Float) {
        val amp = peakAmp()
        val cx = w / 2f; val cy = h / 2f
        val baseR = kotlin.math.min(w, h) / 2f * 0.85f
        fillPaint.shader = null
        // Inner glow halo for depth.
        for (idx in 2 downTo 1) {
            fillPaint.color = Color.argb((20 - idx * 5).coerceAtLeast(5), 0x5D, 0xC8, 0xFF)
            canvas.drawCircle(cx, cy, baseR * (0.40f + idx * 0.10f), fillPaint)
        }
        for (i in 0 until particleCount) {
            val radF   = particleSeed[i * 3]
            val angOff = particleSeed[i * 3 + 1]
            val hueT   = particleSeed[i * 3 + 2]
            val angle  = angOff + idlePhase * (0.4f + radF * 0.4f) * if (i % 2 == 0) 1f else -1f
            val r      = baseR * (radF + amp * 0.15f * (0.5f - radF))
            val px     = cx + cos(angle.toDouble()).toFloat() * r
            val py     = cy + sin(angle.toDouble()).toFloat() * r * 0.42f  // squished vertically
            val dotR   = (1.5f + amp * 4f) * (0.6f + radF * 0.7f)
            fillPaint.color = particleHue(hueT)
            canvas.drawCircle(px, py, dotR, fillPaint)
        }
    }

    /** Neon Wave — a single thick chromatic sine line with
     *  multi-layer drop shadow (cyan + magenta) for a neon-sign
     *  glow.  Apple-feel "Hey Siri" line. */
    private fun drawNeon(canvas: Canvas, w: Float, h: Float) {
        val amp = peakAmp()
        val midY = h / 2f
        val amplitude = h * (0.08f + amp * 0.32f)
        val path = android.graphics.Path()
        path.moveTo(0f, midY)
        var x = 0f
        while (x <= w) {
            val s = sin((idlePhase * 1.6f + x / w * (2f * Math.PI).toFloat() * 3f).toDouble()).toFloat()
            val s2 = sin((idlePhase * -0.8f + x / w * (2f * Math.PI).toFloat() * 7f).toDouble()).toFloat() * 0.35f
            val y = midY + (s + s2) * amplitude
            path.lineTo(x, y)
            x += 4f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 6f + amp * 4f
        }
        // Glow layer 1 (cyan, blurred wide).
        linePaint.color = Color.parseColor("#FF5DC8FF")
        linePaint.maskFilter = android.graphics.BlurMaskFilter(
            18f, android.graphics.BlurMaskFilter.Blur.NORMAL,
        )
        linePaint.alpha = 180
        canvas.drawPath(path, linePaint)
        // Glow layer 2 (magenta, narrower blur).
        linePaint.color = Color.parseColor("#FFFF7AB6")
        linePaint.maskFilter = android.graphics.BlurMaskFilter(
            10f, android.graphics.BlurMaskFilter.Blur.NORMAL,
        )
        linePaint.alpha = 200
        canvas.drawPath(path, linePaint)
        // Crisp top line with shader gradient.
        linePaint.maskFilter = null
        linePaint.alpha = 255
        linePaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                Color.parseColor("#FF5DC8FF"),
                Color.parseColor("#FFFFFFFF"),
                Color.parseColor("#FFFF7AB6"),
            ),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, linePaint)
    }

    /** Prism — rainbow spectrum bars that flex up/down with amp,
     *  fixed colour per bar for a perfect chromatic gradient.
     *  Iridescent drop-shadow for shine. */
    private val prismColors = intArrayOf(
        Color.parseColor("#FFFF6B6B"),
        Color.parseColor("#FFFF9F43"),
        Color.parseColor("#FFFFC857"),
        Color.parseColor("#FF00E0B5"),
        Color.parseColor("#FF2BB6FF"),
        Color.parseColor("#FF9B57FF"),
        Color.parseColor("#FFFF7AB6"),
    )
    private fun drawPrism(canvas: Canvas, w: Float, h: Float) {
        val barCount = prismColors.size
        val gapTotal = w * 0.18f
        val barW = (w - gapTotal) / barCount
        val gap = gapTotal / (barCount + 1)
        val cy = h / 2f
        for (i in 0 until barCount) {
            val phase = idlePhase + i * 0.35f
            val s = sin(phase.toDouble()).toFloat().coerceAtLeast(-1f)
            val ampMix = peakAmp()
            val factor = 0.45f + (0.5f + 0.5f * s) * (0.4f + ampMix * 0.6f)
            val bh = h * factor.coerceIn(0.18f, 0.95f)
            val left = gap + i * (barW + gap)
            val top = cy - bh / 2f
            val rect = android.graphics.RectF(left, top, left + barW, top + bh)
            // Glow shadow.
            fillPaint.shader = null
            fillPaint.maskFilter = android.graphics.BlurMaskFilter(
                14f, android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
            fillPaint.color = prismColors[i]
            fillPaint.alpha = 150
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, fillPaint)
            // Crisp bar with gentle vertical gradient.
            fillPaint.maskFilter = null
            fillPaint.shader = LinearGradient(
                left, top, left, top + bh,
                Color.argb(255,
                    (Color.red(prismColors[i])   + 60).coerceAtMost(255),
                    (Color.green(prismColors[i]) + 60).coerceAtMost(255),
                    (Color.blue(prismColors[i])  + 60).coerceAtMost(255),
                ),
                prismColors[i],
                Shader.TileMode.CLAMP,
            )
            fillPaint.alpha = 255
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, fillPaint)
        }
        fillPaint.shader = null
        fillPaint.maskFilter = null
    }
}
