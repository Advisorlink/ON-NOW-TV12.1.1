package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.AuthStore
import java.net.HttpURLConnection
import java.net.URL

/**
 * First-launch Xtream login (v2.9.5).
 *
 * Validates credentials against the managed provider via
 * `POST /api/xtream/auth` (re-used from the existing Vesper flow —
 * returns 401 for bad creds, 2xx + user_info for good).  On success,
 * persists username + password to [AuthStore] and jumps to the EPG.
 *
 * The host / port are hard-coded to `njala.ddns.me:8443` per the
 * product decision — users only see the username + password fields.
 *
 * Sign-out (from the EPG categories rail) clears [AuthStore] and
 * sends the user back here.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginBtn: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        usernameField = findViewById(R.id.login_username)
        passwordField = findViewById(R.id.login_password)
        loginBtn = findViewById(R.id.login_submit)
        statusText = findViewById(R.id.login_status)

        loginBtn.setOnClickListener { attemptLogin() }
        usernameField.requestFocus()
    }

    private fun attemptLogin() {
        val u = usernameField.text.toString().trim()
        val p = passwordField.text.toString().trim()
        if (u.isBlank() || p.isBlank()) {
            statusText.text = "Please enter both your username and password."
            statusText.visibility = View.VISIBLE
            return
        }
        loginBtn.isEnabled = false
        loginBtn.text = "SIGNING IN…"
        statusText.visibility = View.GONE
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { verifyWithBackend(u, p) }
            if (ok) {
                AuthStore.saveCredentials(this@LoginActivity, u, p)
                startActivity(Intent(this@LoginActivity, EpgActivity::class.java))
                finish()
            } else {
                loginBtn.isEnabled = true
                loginBtn.text = "SIGN IN"
                statusText.text =
                    "Couldn't sign in — please check your username + password and try again."
                statusText.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Posts the credentials to `/api/xtream/auth`.  The backend
     * proxies the call to the Xtream provider's player_api.php and
     * returns 2xx only when `user_info.auth == 1`.
     */
    private fun verifyWithBackend(username: String, password: String): Boolean {
        return try {
            val backend = tv.onnowtv.livetv.data.XtreamRepository.BACKEND_BASE.trimEnd('/')
            val url = URL("$backend/api/xtream/auth")
            val body = JSONObject().apply {
                put("scheme", AuthStore.SCHEME)
                put("host", AuthStore.HOST)
                put("port", AuthStore.PORT)
                put("username", username)
                put("password", password)
            }.toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Throwable) {
            false
        }
    }
}
