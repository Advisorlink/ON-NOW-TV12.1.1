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
 * First-launch Xtream sign-in (v2.9.7-fast).
 *
 * The screen is now PURELY a credentials capture step — NO network
 * round-trip happens here.  We save the username/password to
 * [AuthStore] and immediately hand off to [MainActivity], which is
 * the existing loader screen with the "channels found" counters,
 * tips and progress bar.  That loader does the gzipped bundle fetch
 * exactly the way it always did — keeping the boot experience
 * identical to before per-user auth was introduced.
 *
 * Rationale: the EPG bundle is served from the backend's MASTER
 * Xtream account, so it loads instantly regardless of what the user
 * typed here.  The user's personal creds are only used locally to
 * rewrite `.ts` stream URLs at playback time, so wrong creds surface
 * later at stream-play time (acceptable), not as a 30-second delay
 * blocking the loader.
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
     * No verification — we just persist the creds and jump to the
     * loader.  The loader's gzipped instant-bundle fetch is the
     * exact same fast path that existed before per-user auth was
     * added.  The user sees "channels found" counts within a few
     * hundred milliseconds.
     */
    private fun proceed() {
        val u = usernameField.text.toString().trim()
        val p = passwordField.text.toString().trim()
        if (u.isBlank() || p.isBlank()) {
            statusText.text = "Please enter both your username and password."
            statusText.visibility = View.VISIBLE
            return
        }
        AuthStore.saveCredentials(this, u, p)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        overridePendingTransition(0, 0)
        finish()
    }
}
