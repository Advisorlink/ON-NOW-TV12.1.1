package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.AuthStore
import tv.onnowtv.livetv.data.XtreamRepository
import java.net.HttpURLConnection
import java.net.URL

/**
 * First-launch sign-in.
 *
 * v2.14.5 — Two-stage authentication:
 *   1. Attempt a Vesper login (`/api/auth/login`) using the entered
 *      credentials.  If the account has an Xtream Codes mapping
 *      (`xtream_username` + `xtream_password` set by the operator on
 *      the launcher-backend admin), the backend returns those in the
 *      response `iptv` block — we save the MAPPED creds to
 *      [AuthStore], NOT what the user typed.  The client sees only
 *      their memorable Vesper login (e.g. "Damo26") but the Live TV
 *      app authenticates against the provider with the real creds.
 *   2. Any failure (Vesper 401 / no mapping / network down) falls
 *      through to the legacy pass-through: whatever the user typed
 *      is treated directly as their Xtream username + password.
 *      Preserves backwards compatibility with the previous flow —
 *      customers whose operator hasn't set up the mapping (or who
 *      are on a self-provisioned build) still work exactly as before.
 *
 * The provider auth check itself happens later in MainActivity
 * (bundle fetch).  If the provider rejects whatever creds we saved,
 * MainActivity bounces the user back here with an "auth error" —
 * unchanged since v2.9.14.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginBtn: View
    private lateinit var loginBtnLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var showPassToggle: TextView
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // v2.16.7 — Restore the normal NoActionBar theme AFTER the
        // manifest-declared Splash theme has already painted the
        // login-screen backdrop instantly.  Must run before
        // super.onCreate so subsequent theming is correct.
        setTheme(R.style.Theme_OnNowLiveTV_NoActionBar)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        usernameField = findViewById(R.id.login_username)
        passwordField = findViewById(R.id.login_password)
        loginBtn = findViewById(R.id.login_submit)
        loginBtnLabel = findViewById(R.id.login_submit_label)
        statusText = findViewById(R.id.login_status)
        showPassToggle = findViewById(R.id.login_show_pass)

        loginBtn.setOnClickListener { proceed() }
        showPassToggle.setOnClickListener { togglePasswordVisibility() }
        usernameField.requestFocus()

        intent?.getStringExtra(EXTRA_AUTH_ERROR)?.takeIf { it.isNotBlank() }?.let { msg ->
            statusText.text = msg
            statusText.visibility = View.VISIBLE
        }
    }

    private fun togglePasswordVisibility() {
        val showing = passwordField.inputType ==
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        passwordField.inputType = if (showing) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        showPassToggle.text = if (showing) "Show" else "Hide"
        passwordField.setSelection(passwordField.text?.length ?: 0)
    }

    /**
     * v2.14.5 — Save AFTER attempting to swap the entered
     * memorable-name creds for real Xtream provider creds via the
     * Vesper backend mapping.  Falls back to pass-through save on
     * any error so the app never bricks itself waiting on network.
     */
    private fun proceed() {
        if (busy) return
        val u = usernameField.text.toString().trim()
        val p = passwordField.text.toString().trim()
        if (u.isBlank() || p.isBlank()) {
            statusText.text = "Please enter both your username and password."
            statusText.visibility = View.VISIBLE
            return
        }
        busy = true
        statusText.text = "Signing in\u2026"
        statusText.visibility = View.VISIBLE
        loginBtn.isEnabled = false
        val ctx = this
        CoroutineScope(Dispatchers.Main).launch {
            val mapped = withContext(Dispatchers.IO) { resolveXtreamMapping(u, p) }
            // If the backend returned a mapping, use those creds;
            // otherwise use exactly what the user typed (legacy path).
            val (saveUser, savePass) = mapped ?: (u to p)
            AuthStore.saveCredentials(ctx, saveUser, savePass)

            // v2.16.12 — On successful sign-in, check the cloud
            // backup keyed by this Xtream login.  If a snapshot
            // exists we prompt the user before restoring, per user
            // preference (Vesper-style popup rather than silent).
            statusText.text = "Checking backups\u2026"
            val snap = withContext(Dispatchers.IO) {
                pullSnapshotBlocking(ctx)
            }
            if (snap != null &&
                tv.onnowtv.livetv.data.SyncManager
                    .isRestorableSnapshot(snap)) {
                showRestorePrompt(ctx, snap)
            } else {
                launchMain(ctx)
            }
        }
    }

    /** Blocking cloud-backup fetch.  Called from IO. */
    private fun pullSnapshotBlocking(ctx: android.content.Context): org.json.JSONObject? {
        val out = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        tv.onnowtv.livetv.data.SyncManager.pullOnce(ctx) { snap ->
            out.set(snap)
            latch.countDown()
        }
        try {
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // Fall through with whatever we got (probably null).
        }
        return out.get()
    }

    private fun showRestorePrompt(
        ctx: android.content.Context,
        snap: org.json.JSONObject,
    ) {
        val favCount = snap.optJSONArray("favourites")?.length() ?: 0
        val colCount = snap.optJSONArray("collections")?.length() ?: 0
        val remCount = snap.optJSONArray("reminders")?.length() ?: 0
        val parts = mutableListOf<String>()
        if (favCount > 0) parts.add("$favCount favourite${if (favCount != 1) "s" else ""}")
        if (colCount > 0) parts.add("$colCount collection${if (colCount != 1) "s" else ""}")
        if (remCount > 0) parts.add("$remCount reminder${if (remCount != 1) "s" else ""}")

        // v2.16.15 — Replaced the stock AlertDialog with the same
        // brand-styled ActionSheetDialog used across the rest of
        // the app (channel long-press, sign-out, etc.) so the
        // popup no longer looks like a bare Android system dialog.
        val body = buildString {
            append("We found a backup of your data on the cloud:\n\n")
            for (p in parts) append("•  ").append(p).append('\n')
            append("\nRestore them onto this device?")
        }

        tv.onnowtv.livetv.ui.ActionSheetDialog(this)
            .title("Restore your data?")
            .subtitle("Cloud backup found")
            .body(body)
            .item("Restore", icon = "↺") {
                statusText.text = "Restoring\u2026"
                CoroutineScope(Dispatchers.Main).launch {
                    withContext(Dispatchers.IO) {
                        tv.onnowtv.livetv.data.SyncManager
                            .applySnapshot(ctx, snap)
                    }
                    launchMain(ctx)
                }
            }
            .item("Start fresh", icon = "✕") {
                launchMain(ctx)
            }
            .show()
            .apply {
                // The action-sheet is cancelable by default; if the
                // user hits BACK we still need to leave the login
                // screen behind so the flow doesn't wedge.  If they
                // picked an action explicitly the dismiss listener
                // fires AFTER launchMain has already been called
                // above — `finish()` inside launchMain means this
                // no-ops harmlessly.
                setOnDismissListener {
                    if (!isFinishing) launchMain(ctx)
                }
            }
    }

    private fun launchMain(ctx: android.content.Context) {
        startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        overridePendingTransition(0, 0)
        finish()
    }

    /**
     * POST /api/auth/login on the Vesper backend.  Returns the
     * (xtream_username, xtream_password) pair when the account has
     * a mapping; null on any failure (network down, 401, no
     * mapping, malformed response).  Never throws — always returns
     * quickly so the login UI stays responsive on flaky networks.
     */
    private fun resolveXtreamMapping(username: String, password: String): Pair<String, String>? {
        val base = XtreamRepository.BACKEND_BASE.trimEnd('/')
        val url = URL("$base/api/auth/login")
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("client_id", "livetv")
        }.toString()
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val iptv = root.optJSONObject("iptv") ?: return null
            val xu = iptv.optString("xtream_username", "").trim()
            val xp = iptv.optString("xtream_password", "").trim()
            if (xu.isEmpty() || xp.isEmpty()) null else (xu to xp)
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    companion object {
        const val EXTRA_AUTH_ERROR = "auth_error"
    }
}
