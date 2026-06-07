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

    companion object {
        const val EXTRA_AUTH_ERROR = "auth_error"
    }
}
