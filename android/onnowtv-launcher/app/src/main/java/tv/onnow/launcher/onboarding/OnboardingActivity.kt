package tv.onnow.launcher.onboarding

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnow.launcher.MainActivity
import tv.onnow.launcher.R
import tv.onnow.launcher.data.LauncherRepository
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * First-boot onboarding gate — v2 Vesper redesign.
 *
 * Three phases (driven by `currentPhase`):
 *   1. PHASE_WIFI     — no internet → "Setup Wi-Fi" CTA opens
 *                       Android's Wi-Fi settings.  Auto-advances
 *                       to PHASE_REGISTER once a network appears.
 *   2. PHASE_REGISTER — user types a name with the BUILT-IN
 *                       on-screen keyboard (no native IME).
 *                       POSTs to /api/launcher/register.
 *   3. PHASE_BLOCKED  — polls /api/launcher/activation every 8 s.
 *                       Once admin sets status="active" the
 *                       launcher boots.
 *
 * Design language mirrors Vesper: deep inky background with a
 * subtle cyan radial glow, neon-blue accent (#5DC8FF), glass-
 * morphism cards with 1 px white-translucent borders, modern sans-
 * serif typography (Montserrat) and D-pad-focusable keys with a
 * bright cyan focus ring.
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

        /** Stable device id — generated once on first call. */
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

    private lateinit var root: FrameLayout
    private lateinit var repo: LauncherRepository
    private var currentPhase = PHASE_WIFI
    private var pollJob: Job? = null

    /* ── Register-phase state (only used inside PHASE_REGISTER) ─ */
    private var typedName: String = ""
    private var shiftOn: Boolean = false
    private var statusLine: TextView? = null
    private var shiftKey: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = LauncherRepository(this)

        root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.onb_bg_glow)
        }
        setContentView(root)
        decidePhase()
    }

    override fun onResume() {
        super.onResume()
        // Returning from native Wi-Fi settings — re-evaluate state.
        if (currentPhase == PHASE_WIFI && hasNetwork()) {
            decidePhase()
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    /* ──────────────────  State machine  ────────────────── */

    private fun decidePhase() {
        pollJob?.cancel()
        val status = currentStatus(this)
        currentPhase = when {
            !hasNetwork()            -> PHASE_WIFI
            status == "active"       -> { proceedToLauncher(); return }
            status == "unregistered" -> PHASE_REGISTER
            else /* pending|blocked */ -> PHASE_BLOCKED
        }
        when (currentPhase) {
            PHASE_WIFI     -> renderWifiPhase()
            PHASE_REGISTER -> renderRegisterPhase()
            PHASE_BLOCKED  -> renderBlockedPhase()
        }
    }

    /* ──────────────────  Phase 1 · Wi-Fi  ────────────────── */

    private fun renderWifiPhase() {
        root.removeAllViews()
        val col = vertical().apply {
            gravity = Gravity.CENTER
            setPadding(dp(64), dp(48), dp(64), dp(48))
        }
        col.addView(eyebrow("ON NOW TV V2 · WELCOME"))
        col.addView(spacer(dp(14)))
        col.addView(displayTitle("Connect to the internet"))
        col.addView(spacer(dp(14)))
        col.addView(
            subtitle("Choose your Wi-Fi network to get started.")
        )
        col.addView(spacer(dp(40)))
        col.addView(primaryCta("SETUP WI-FI") {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Throwable) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                catch (_: Throwable) {}
            }
        })
        addCenteredColumn(col)
    }

    /* ──────────────────  Phase 2 · Register  ────────────────── */

    private fun renderRegisterPhase() {
        root.removeAllViews()
        typedName = ""
        shiftOn = false

        // Outer scroll-safe column.
        val col = vertical().apply {
            setPadding(dp(80), dp(36), dp(80), dp(28))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(eyebrow("ON NOW TV V2 · DEVICE REGISTRATION"))
        col.addView(spacer(dp(10)))
        col.addView(displayTitle("Register your device"))
        col.addView(spacer(dp(8)))
        col.addView(
            subtitle("Give this box a name so we know which one is yours.")
        )
        col.addView(spacer(dp(20)))

        // Glass input "display" — TextView, NOT EditText, so the
        // native IME never appears.  Holds a TextView for the typed
        // text + a 2 dp blinking cursor View, sat in a horizontal
        // LinearLayout.
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.onb_input_glass)
            setPadding(dp(22), dp(18), dp(22), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(560), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val nameText = TextView(this).apply {
            text = ""
            textSize = 22f
            setTextColor(0xFFF4F7FB.toInt())
            typeface = font(weightBold = false)
            letterSpacing = 0.02f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val placeholder = TextView(this).apply {
            text = "Your name"
            textSize = 22f
            setTextColor(0xFF5A6A82.toInt())
            typeface = font(weightBold = false)
            letterSpacing = 0.02f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        // Stack: nameText fills, placeholder shown only while empty.
        val displayInner = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            addView(placeholder)
            addView(nameText)
        }
        display.addView(displayInner)
        val cursor = View(this).apply {
            setBackgroundColor(0xFF5DC8FF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(2), dp(28)).apply {
                marginStart = dp(2)
            }
        }
        display.addView(cursor)
        startCursorBlink(cursor)

        // Keep cursor + placeholder synced with `typedName`.
        val syncDisplay = {
            nameText.text = typedName
            placeholder.visibility =
                if (typedName.isEmpty()) View.VISIBLE else View.GONE
        }
        syncDisplay()

        col.addView(display)
        col.addView(spacer(dp(10)))

        val modelLine = TextView(this).apply {
            text = "BOX MODEL · ${Build.MANUFACTURER.uppercase()} ${Build.MODEL.uppercase()}"
            textSize = 11f
            letterSpacing = 0.22f
            setTextColor(0xFF5A6A82.toInt())
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(modelLine)
        col.addView(spacer(dp(22)))

        val status = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFFFF5573.toInt())
            visibility = View.INVISIBLE
            gravity = Gravity.CENTER_HORIZONTAL
            letterSpacing = 0.04f
        }
        statusLine = status
        col.addView(status)
        col.addView(spacer(dp(12)))

        // The keyboard.  Built fresh on every render so we don't
        // share state across phase transitions.
        val keyboard = buildKeyboard(
            onChar = { ch ->
                if (typedName.length < 28) {
                    val toAppend = if (shiftOn) ch.uppercase() else ch.lowercase()
                    typedName += toAppend
                    if (shiftOn) {
                        shiftOn = false
                        shiftKey?.let { paintShiftKey(it) }
                    }
                    syncDisplay()
                }
            },
            onBackspace = {
                if (typedName.isNotEmpty()) {
                    typedName = typedName.dropLast(1)
                    syncDisplay()
                }
            },
            onSpace = {
                if (typedName.isNotEmpty() &&
                    typedName.length < 28 &&
                    !typedName.endsWith(" ")
                ) {
                    typedName += " "
                    syncDisplay()
                }
            },
            onShift = {
                shiftOn = !shiftOn
                shiftKey?.let { paintShiftKey(it) }
            },
            onSubmit = { submitRegister() },
        )
        col.addView(keyboard)

        addCenteredColumn(col)

        // Auto-focus the first letter so D-pad works immediately.
        // Wait until the next frame so the view tree has measured.
        keyboard.post {
            findFirstFocusableKey(keyboard)?.requestFocus()
        }
    }

    private fun submitRegister() {
        val name = typedName.trim()
        val status = statusLine ?: return
        if (name.isEmpty()) {
            status.setTextColor(0xFFFF5573.toInt())
            status.text = "Please enter a name."
            status.visibility = View.VISIBLE
            return
        }
        status.setTextColor(0xFF2EEAC2.toInt())
        status.text = "REGISTERING…"
        status.visibility = View.VISIBLE
        lifecycleScope.launch {
            val err = doRegister(name)
            if (err != null) {
                status.setTextColor(0xFFFF5573.toInt())
                status.text = err
                return@launch
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, "pending")
                .putString(KEY_NAME, name)
                .apply()
            decidePhase()
        }
    }

    /* ──────────────────  Phase 3 · Blocked  ────────────────── */

    private fun renderBlockedPhase() {
        root.removeAllViews()

        val outer = vertical().apply {
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
        }

        val card = vertical().apply {
            setBackgroundResource(R.drawable.onb_card_glass)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(56), dp(48), dp(56), dp(48))
            layoutParams = LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Lock icon glyph — neon-cyan.
        card.addView(TextView(this).apply {
            text = "\u26BF"  // 🔿 keyhole U+26BF
            textSize = 56f
            setTextColor(0xFF5DC8FF.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        })
        card.addView(spacer(dp(20)))
        card.addView(eyebrow("ACTIVATION REQUIRED"))
        card.addView(spacer(dp(12)))
        card.addView(displayTitle("ON NOW TV is blocked"))
        card.addView(spacer(dp(18)))
        card.addView(
            subtitle("Please contact support for further assistance.")
        )
        card.addView(spacer(dp(28)))

        val stat = TextView(this).apply {
            text = "STATUS · PENDING APPROVAL"
            textSize = 11f
            letterSpacing = 0.22f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF8EA0B7.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        card.addView(stat)
        card.addView(spacer(dp(24)))

        card.addView(primaryCta("RETRY") {
            stat.text = "STATUS · CHECKING…"
            lifecycleScope.launch { pollOnce(stat) }
        })
        outer.addView(card)
        addCenteredColumn(outer)

        // Background poll every 8 s.
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(8_000)
                pollOnce(stat)
            }
        }
    }

    /* ──────────────────  Keyboard builder  ────────────────── */

    private fun buildKeyboard(
        onChar:      (String) -> Unit,
        onBackspace: () -> Unit,
        onSpace:     () -> Unit,
        onShift:     () -> Unit,
        onSubmit:    () -> Unit,
    ): View {
        val grid = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val rows = listOf(
            listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
            listOf("Z", "X", "C", "V", "B", "N", "M", "'", "-"),
            listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
        )
        for (row in rows) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            }
            for (ch in row) {
                rowView.addView(keyView(ch, primary = false) { onChar(ch) })
            }
            grid.addView(rowView)
        }

        // Action row: Shift · Space · Backspace · Register
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }
        val shift = keyView("\u21E7", primary = false, widePx = dp(84)) { onShift() }
        shiftKey = shift
        actionRow.addView(shift)
        actionRow.addView(keyView("SPACE", primary = false, widePx = dp(280)) { onSpace() })
        actionRow.addView(keyView("\u232B", primary = false, widePx = dp(84)) { onBackspace() })
        actionRow.addView(
            keyView("REGISTER  \u2192", primary = true, widePx = dp(220)) { onSubmit() }
        )
        grid.addView(actionRow)
        return grid
    }

    /** Build a single keyboard key.  `widePx` is the explicit width
     *  for action keys; letter keys auto-size to 56×56 dp. */
    private fun keyView(
        label: String,
        primary: Boolean,
        widePx: Int = dp(56),
        onClick: () -> Unit,
    ): TextView {
        val key = TextView(this).apply {
            text = label
            textSize = if (primary) 13f else 19f
            typeface = font(weightBold = primary)
            isAllCaps = false
            letterSpacing = if (primary) 0.16f else 0.02f
            gravity = Gravity.CENTER
            setBackgroundResource(
                if (primary) R.drawable.onb_primary_selector
                else R.drawable.onb_key_selector
            )
            // Resting text colour:
            //   • primary key  → bright cyan (against cyan-tinted bg)
            //   • letter key   → soft white
            setTextColor(
                if (primary) 0xFF5DC8FF.toInt() else 0xFFF4F7FB.toInt()
            )
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnFocusChangeListener { v, focused ->
                v as TextView
                if (primary) {
                    // Solid cyan bg on focus → flip to inky text.
                    v.setTextColor(
                        if (focused) 0xFF04060B.toInt() else 0xFF5DC8FF.toInt()
                    )
                } else {
                    // White text always for letter keys; brighten on focus.
                    v.setTextColor(
                        if (focused) 0xFFFFFFFF.toInt() else 0xFFF4F7FB.toInt()
                    )
                }
                animateScale(v, focused)
            }
            setOnClickListener {
                Handler(Looper.getMainLooper()).post { onClick() }
            }
        }
        key.layoutParams = LinearLayout.LayoutParams(widePx, dp(56)).apply {
            marginStart = dp(5)
            marginEnd = dp(5)
        }
        return key
    }

    /** Subtle 1.06× scale on focus — matches Vesper's tile feel. */
    private fun animateScale(v: View, focused: Boolean) {
        v.animate().cancel()
        v.animate()
            .scaleX(if (focused) 1.06f else 1.0f)
            .scaleY(if (focused) 1.06f else 1.0f)
            .setDuration(160)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }

    /** Paint Shift in its toggled state. */
    private fun paintShiftKey(key: TextView) {
        if (shiftOn) {
            key.setBackgroundResource(R.drawable.onb_key_active)
            key.setTextColor(0xFF04060B.toInt())
        } else {
            key.setBackgroundResource(R.drawable.onb_key_selector)
            key.setTextColor(0xFFF4F7FB.toInt())
        }
    }

    /** Walks the keyboard tree and returns the first focusable key
     *  (top-left "Q" in PHASE_REGISTER). */
    private fun findFirstFocusableKey(root: View): View? {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val r = findFirstFocusableKey(root.getChildAt(i))
                if (r != null) return r
            }
        } else if (root.isFocusable) return root
        return null
    }

    /* ──────────────────  Cursor blinking  ────────────────── */

    private fun startCursorBlink(cursor: View) {
        val anim = ValueAnimator.ofFloat(1f, 0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { cursor.alpha = it.animatedValue as Float }
        }
        anim.start()
    }

    /* ──────────────────  Network / activation  ────────────── */

    private suspend fun pollOnce(stat: TextView) {
        val (status, _) = fetchActivationStatus()
        if (status == "active") {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, "active")
                .apply()
            proceedToLauncher()
            return
        }
        if (status == "pending" || status == "blocked") {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, status)
                .apply()
            stat.text = "STATUS · ${status.uppercase()}"
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
            val code = conn.responseCode
            conn.disconnect()
            if (ok) null else "Registration failed (HTTP $code)."
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

    /* ──────────────────  UI primitives  ────────────────── */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun font(weightBold: Boolean): Typeface {
        val family = ResourcesCompat.getFont(this, R.font.montserrat)
            ?: return Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(family, if (weightBold) 700 else 400, false)
        } else {
            Typeface.create(family, if (weightBold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun eyebrow(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.32f
        typeface = Typeface.MONOSPACE
        setTextColor(0xFF5DC8FF.toInt())
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun displayTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 46f
        typeface = font(weightBold = true)
        letterSpacing = -0.01f
        setTextColor(0xFFF4F7FB.toInt())
        gravity = Gravity.CENTER_HORIZONTAL
        // Subtle cyan glow under the title — outline-style, no clip.
        setShadowLayer(28f, 0f, 4f, 0xB02BB6FF.toInt())
        includeFontPadding = false
        setPadding(0, dp(6), 0, dp(10))
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(0xFF9CADC6.toInt())
        typeface = font(weightBold = false)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    /** Vesper-style pill CTA: solid neon background, focus halo. */
    private fun primaryCta(label: String, onClick: () -> Unit): TextView {
        val btn = TextView(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            letterSpacing = 0.22f
            typeface = font(weightBold = true)
            setBackgroundResource(R.drawable.onb_primary_selector)
            // Resting: bright cyan text on cyan-tinted bg.
            // Focused: inky text on solid cyan (handled below).
            setTextColor(0xFF5DC8FF.toInt())
            setPadding(dp(56), dp(20), dp(56), dp(20))
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener {
                Handler(Looper.getMainLooper()).post(onClick)
            }
            setOnFocusChangeListener { v, focused ->
                v as TextView
                v.setTextColor(
                    if (focused) 0xFF04060B.toInt() else 0xFF5DC8FF.toInt()
                )
                v.animate().cancel()
                v.animate()
                    .scaleX(if (focused) 1.06f else 1.0f)
                    .scaleY(if (focused) 1.06f else 1.0f)
                    .setDuration(180)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
        }
        btn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        return btn
    }

    /** Drops a vertical column into the FrameLayout root, centred. */
    private fun addCenteredColumn(col: LinearLayout) {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = Gravity.CENTER
        root.addView(col, lp)
    }
}
