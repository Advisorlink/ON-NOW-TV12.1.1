package tv.onnow.launcher.onboarding

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnow.launcher.MainActivity
import tv.onnow.launcher.data.LauncherRepository
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * v1.7 — First-boot onboarding gate.
 *
 * Three phases (driven by `currentPhase`):
 *   1. PHASE_WIFI    — no internet → "Setup Wi-Fi" button → opens
 *                      Android's native Wi-Fi settings activity.
 *                      Auto-advances to PHASE_REGISTER once a
 *                      network is detected.
 *   2. PHASE_REGISTER — user types a nickname.  POSTs to
 *                       /api/launcher/register which creates a
 *                       PENDING device record.  Auto-advances to
 *                       PHASE_BLOCKED.
 *   3. PHASE_BLOCKED  — polls /api/launcher/activation every 8 s.
 *                       The moment admin flips status to "active",
 *                       finishes this activity and lets MainActivity
 *                       boot the normal launcher.  Retry button
 *                       force-polls immediately.
 *
 * Activation state persists in SharedPreferences ("onnow_activation"):
 *   - device_id   : UUID generated once on first launch
 *   - status      : "pending" | "active" | "blocked" | "unregistered"
 *   - name        : the nickname user typed
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val PREFS         = "onnow_activation"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_STATUS    = "status"
        const val KEY_NAME      = "name"

        const val PHASE_WIFI     = 0
        const val PHASE_REGISTER = 1
        const val PHASE_BLOCKED  = 2

        /** Stable device id, generated lazily on first call. */
        fun deviceId(ctx: android.content.Context): String {
            val sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
            var id = sp.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                sp.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

        fun currentStatus(ctx: android.content.Context): String =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_STATUS, null) ?: "unregistered"
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var repo: LauncherRepository
    private var currentPhase = PHASE_WIFI
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on during onboarding so the user can read
        // the messages without the box dimming.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = LauncherRepository(this)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF04060B.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(64), dp(32), dp(64), dp(32))
        }
        setContentView(rootLayout)
        decidePhase()
    }

    override fun onResume() {
        super.onResume()
        // Returning from native Wi-Fi settings — re-evaluate.
        if (currentPhase == PHASE_WIFI && hasNetwork()) {
            decidePhase()
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    /** Walk the state machine: pick the appropriate phase for the
     *  current device state and render its UI. */
    private fun decidePhase() {
        pollJob?.cancel()
        val status = currentStatus(this)
        currentPhase = when {
            !hasNetwork()              -> PHASE_WIFI
            status == "active"         -> {
                // Already activated — bounce straight to launcher.
                proceedToLauncher(); return
            }
            status == "unregistered"   -> PHASE_REGISTER
            else /* pending|blocked */ -> PHASE_BLOCKED
        }
        when (currentPhase) {
            PHASE_WIFI     -> renderWifiPhase()
            PHASE_REGISTER -> renderRegisterPhase()
            PHASE_BLOCKED  -> renderBlockedPhase()
        }
    }

    /* ──────────────── Phase 1: Wi-Fi ──────────────── */

    private fun renderWifiPhase() {
        rootLayout.removeAllViews()
        rootLayout.addView(bigTitle("Welcome to ON NOW TV"))
        rootLayout.addView(subtitle("Let's connect to the internet to get started."))
        rootLayout.addView(spacer(dp(40)))
        rootLayout.addView(primaryButton("Setup Wi-Fi") {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Throwable) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                catch (_: Throwable) { /* impossible */ }
            }
        })
    }

    /* ──────────────── Phase 2: Register ──────────────── */

    private fun renderRegisterPhase() {
        rootLayout.removeAllViews()
        rootLayout.addView(bigTitle("Register Your Device"))
        rootLayout.addView(subtitle("Enter a name so we can identify this box on your account."))
        rootLayout.addView(spacer(dp(32)))

        val nameInput = EditText(this).apply {
            hint = "Your name"
            setHintTextColor(0xFF6B7C92.toInt())
            setTextColor(0xFFF4F7FB.toInt())
            textSize = 22f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setBackgroundColor(0xFF0F1928.toInt())
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                dp(480), ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(24)) }
        }
        rootLayout.addView(nameInput)

        val modelLabel = TextView(this).apply {
            text = "Box model: ${Build.MANUFACTURER} ${Build.MODEL}"
            textSize = 13f
            setTextColor(0xFF6B7C92.toInt())
        }
        rootLayout.addView(modelLabel)
        rootLayout.addView(spacer(dp(20)))

        val statusLabel = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFFFF5573.toInt())
            visibility = View.GONE
        }
        rootLayout.addView(statusLabel)

        rootLayout.addView(primaryButton("Register") {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                statusLabel.text = "Please enter a name."
                statusLabel.visibility = View.VISIBLE
                return@primaryButton
            }
            statusLabel.setTextColor(0xFF2EEAC2.toInt())
            statusLabel.text = "Registering…"
            statusLabel.visibility = View.VISIBLE
            lifecycleScope.launch {
                val err = doRegister(name)
                if (err != null) {
                    statusLabel.setTextColor(0xFFFF5573.toInt())
                    statusLabel.text = err
                    return@launch
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_STATUS, "pending")
                    .putString(KEY_NAME, name)
                    .apply()
                decidePhase()
            }
        })
    }

    /* ──────────────── Phase 3: Blocked / Pending popup ──────────────── */

    private fun renderBlockedPhase() {
        rootLayout.removeAllViews()
        // Card-style popup container so the message reads as a
        // proper modal rather than naked text on black.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1928.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
            layoutParams = LinearLayout.LayoutParams(
                dp(640), ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            elevation = dp(12).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "🔒"
            textSize = 44f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        card.addView(spacer(dp(8)))
        card.addView(TextView(this).apply {
            text = "ON NOW TV is blocked"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF4F7FB.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        })
        card.addView(spacer(dp(14)))
        card.addView(TextView(this).apply {
            text = "Please contact support for further assistance."
            textSize = 16f
            setTextColor(0xFFB8C5D6.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        })
        card.addView(spacer(dp(28)))

        val statusLine = TextView(this).apply {
            text = "Status: pending approval"
            textSize = 13f
            setTextColor(0xFF6B7C92.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        card.addView(statusLine)
        card.addView(spacer(dp(20)))

        card.addView(primaryButton("Retry") {
            statusLine.text = "Checking…"
            lifecycleScope.launch { pollOnce(statusLine) }
        })
        rootLayout.addView(card)

        // Background poll every 8 s.
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(8_000)
                pollOnce(statusLine)
            }
        }
    }

    /* ──────────────── Helpers ──────────────── */

    private suspend fun pollOnce(statusLine: TextView) {
        val (status, _) = fetchActivationStatus()
        if (status == "active") {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, "active")
                .apply()
            proceedToLauncher()
            return
        }
        // Keep the local status in sync (pending ↔ blocked).
        if (status == "pending" || status == "blocked") {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, status)
                .apply()
            statusLine.text = "Status: $status"
        }
    }

    private fun proceedToLauncher() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
        })
        finish()
    }

    private fun hasNetwork(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) { false }
    }

    private suspend fun doRegister(name: String): String? = withContext(Dispatchers.IO) {
        val url = repo.baseUrlPublic().trimEnd('/') + "/api/launcher/register"
        try {
            val payload = JSONObject().apply {
                put("id", deviceId(this@OnboardingActivity))
                put("name", name)
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            }.toString()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            conn.doOutput = true
            conn.outputStream.use { (it as OutputStream).write(payload.toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            if (ok) null else "Registration failed (HTTP ${conn.responseCode})."
        } catch (t: Throwable) {
            "Couldn't reach the activation server: ${t.message ?: "unknown"}"
        }
    }

    private suspend fun fetchActivationStatus(): Pair<String, String?> = withContext(Dispatchers.IO) {
        val url = repo.baseUrlPublic().trimEnd('/') +
                  "/api/launcher/activation?device_id=" + deviceId(this@OnboardingActivity)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6_000
            conn.readTimeout    = 6_000
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(txt)
            val status = json.optString("status", "unregistered")
            val name   = if (json.has("name")) json.optString("name") else null
            status to name
        } catch (_: Throwable) {
            "error" to null
        }
    }

    /* ──────────────── UI primitives ──────────────── */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }
    private fun bigTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 40f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xFFF4F7FB.toInt())
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(0xFF8EA0B7.toInt())
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(8), 0, 0)
    }
    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setTextColor(0xFF04060B.toInt())
        setBackgroundColor(0xFF2BB6FF.toInt())
        setPadding(dp(48), dp(18), dp(48), dp(18))
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener {
            Handler(Looper.getMainLooper()).post(onClick)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
