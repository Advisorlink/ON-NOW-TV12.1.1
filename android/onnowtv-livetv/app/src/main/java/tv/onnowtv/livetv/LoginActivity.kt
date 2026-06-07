package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.text.InputType
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
import java.net.URLEncoder
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * First-launch Xtream sign-in (v2.9.11).
 *
 * Validates the user's credentials against the provider directly
 * (single ~500 ms call to `player_api.php`) BEFORE saving them
 * to `AuthStore`.  If the response's `user_info.auth` is not "1",
 * we reject the login with a clear error and stay on this screen.
 *
 * The previous behaviour (v2.9.7-fast) was a pure pass-through —
 * ANY username/password was accepted — which created a security
 * hole: once a valid user signed in once, anyone with physical
 * access could sign out and back in with garbage and still get
 * into the cached channel list.  Validating against the provider
 * closes that hole.
 *
 * On success: persists creds → jumps to [MainActivity] (the
 * familiar "channels found" loader screen) — same fast path as
 * before, no extra delay.
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

        loginBtn.setOnClickListener { attemptLogin() }
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

    private fun attemptLogin() {
        val u = usernameField.text.toString().trim()
        val p = passwordField.text.toString().trim()
        if (u.isBlank() || p.isBlank()) {
            statusText.text = "Please enter both your username and password."
            statusText.visibility = View.VISIBLE
            return
        }
        loginBtn.isEnabled = false
        loginBtnLabel.text = "SIGNING IN…"
        statusText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { verifyDirect(u, p) }
            if (result == VerifyResult.OK) {
                AuthStore.saveCredentials(this@LoginActivity, u, p)
                startActivity(
                    Intent(this@LoginActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                overridePendingTransition(0, 0)
                finish()
            } else {
                loginBtn.isEnabled = true
                loginBtnLabel.text = "SIGN IN"
                statusText.text = when (result) {
                    VerifyResult.INVALID -> "Wrong username or password. Please try again."
                    VerifyResult.NETWORK -> "Couldn't reach the TV provider. Check your internet."
                    else -> "Sign-in failed. Please try again."
                }
                statusText.visibility = View.VISIBLE
            }
        }
    }

    private enum class VerifyResult { OK, INVALID, NETWORK }

    /**
     * Direct credential check.  Hits the provider's `player_api.php`
     * with NO action — returns `user_info` JSON containing an
     * `auth` field that equals "1" iff the creds are valid.
     *
     * Timeouts are intentionally short — this is a sign-in gate,
     * not a bundle fetch.  A working provider answers in <500 ms.
     */
    private fun verifyDirect(username: String, password: String): VerifyResult {
        val base = "${AuthStore.SCHEME}://${AuthStore.HOST}:${AuthStore.PORT}"
        val url = URL(
            "$base/player_api.php?" +
                "username=${URLEncoder.encode(username, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}",
        )
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ONNowTV/1.0")
                if (this is HttpsURLConnection) {
                    try {
                        val trustAll = arrayOf<TrustManager>(
                            object : X509TrustManager {
                                override fun checkClientTrusted(
                                    chain: Array<out java.security.cert.X509Certificate>?,
                                    authType: String?,
                                ) {}
                                override fun checkServerTrusted(
                                    chain: Array<out java.security.cert.X509Certificate>?,
                                    authType: String?,
                                ) {}
                                override fun getAcceptedIssuers():
                                    Array<java.security.cert.X509Certificate> = emptyArray()
                            },
                        )
                        val sslCtx = SSLContext.getInstance("TLS")
                        sslCtx.init(null, trustAll, SecureRandom())
                        sslSocketFactory = sslCtx.socketFactory
                        hostnameVerifier = HostnameVerifier { _, _ -> true }
                    } catch (_: Throwable) {}
                }
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    val auth = JSONObject(text).optJSONObject("user_info")
                        ?.opt("auth")?.toString() ?: ""
                    if (auth == "1") VerifyResult.OK else VerifyResult.INVALID
                } else {
                    // 401, 403, 404 are all "wrong creds" from this
                    // provider's perspective.  Anything else is a
                    // network/server problem.
                    if (code in 400..404) VerifyResult.INVALID else VerifyResult.NETWORK
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            VerifyResult.NETWORK
        }
    }
}
