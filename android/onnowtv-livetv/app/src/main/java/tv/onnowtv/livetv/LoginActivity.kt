package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tv.onnowtv.livetv.data.AuthStore

/**
 * First-launch Xtream sign-in (v2.9.14 — back to pure pass-through).
 *
 * The screen is purely a credentials capture step — NO network
 * round-trip happens here.  Creds go to [AuthStore] and we jump
 * straight to [MainActivity], the familiar "channels found" loader.
 *
 * The actual credential check happens IMPLICITLY at the loader
 * step: MainActivity attempts to fetch the channel bundle directly
 * from the provider with the saved creds.  If the provider rejects
 * them (HTTP 404, or `user_info.auth == 0`), MainActivity wipes
 * the creds and bounces back here with a "Wrong username or
 * password" error.  This is the same flow that was working
 * before — no upfront verify, no over-strict rejection.
 *
 * The caller (typically MainActivity) can re-launch us with the
 * `EXTRA_AUTH_ERROR` intent extra set to surface a previous
 * failure (e.g. "Wrong username or password.").
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginBtn: View
    private lateinit var loginBtnLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var showPassToggle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
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

        // Surface a previously-failed attempt forwarded to us by
        // MainActivity (after the loader detected the provider
        // rejected the saved credentials).
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
     * No verification — save and jump.  The bundle fetcher
     * in MainActivity is the real auth gate.
     *
     * v2.10.54 — If the new username differs from any previously-
     * saved username, wipe the bundle + EPG disk caches first.
     * Otherwise MainActivity's fast path will happily load the
     * PREVIOUS user's channels (cached on disk) instead of fetching
     * fresh ones for the new account — the user's complaint that
     * "it's not loading everyone's credentials properly … when you
     * try to log in under a different Xtream Codes login, it's not
     * logging you in".
     */
    private fun proceed() {
        val u = usernameField.text.toString().trim()
        val p = passwordField.text.toString().trim()
        if (u.isBlank() || p.isBlank()) {
            statusText.text = "Please enter both your username and password."
            statusText.visibility = View.VISIBLE
            return
        }
        val previousUser = AuthStore.username(this)
        val previousPass = AuthStore.password(this)
        val accountChanged = previousUser.isNotBlank() &&
            (previousUser != u || previousPass != p)
        if (accountChanged) {
            // Different Xtream account → drop stale per-account
            // disk caches so the next bundle fetch hits the
            // provider with the NEW creds.  signOut() does exactly
            // that (clears creds + caches + workers + holder), but
            // we don't want to remove the new creds we're about to
            // save, so we replicate the cache-wipe directly.
            try { tv.onnowtv.livetv.data.BundleCache.delete(this) } catch (_: Throwable) {}
            try { tv.onnowtv.livetv.data.EpgCache.delete(this) } catch (_: Throwable) {}
            try {
                tv.onnowtv.livetv.data.EpgRefreshWorker.cancel(this)
            } catch (_: Throwable) {}
            tv.onnowtv.livetv.BundleHolder.current = null
            tv.onnowtv.livetv.BundleHolder.needsBackgroundRefresh = false
        }
        AuthStore.saveCredentials(this, u, p)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        overridePendingTransition(0, 0)
        finish()
    }

    companion object {
        const val EXTRA_AUTH_ERROR = "auth_error"
    }
}
