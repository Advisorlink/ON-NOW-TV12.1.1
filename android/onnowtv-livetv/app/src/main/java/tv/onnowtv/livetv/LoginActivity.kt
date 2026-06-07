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
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * First-launch Xtream login (v2.9.7).
 *
 * Two-stage verification:
 *   1. POST `/api/xtream/auth` on the managed backend — preferred,
 *      because a successful call ALSO kicks off the server-side
 *      EPG pre-warm so the user's first Live TV visit is instant.
 *   2. If the backend is unreachable or 5xx, fall back to a DIRECT
 *      provider `player_api.php` call from the device.  This makes
 *      login resilient to backend outages (the EPG endpoint can
 *      still be reached separately for the cached guide; only the
 *      auth proxy needs the failover).
 *
 * On success: persists username + password to [AuthStore] and jumps
 * to the EPG.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginBtn: View
    private lateinit var loginBtnLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var showPassToggle: TextView
    private lateinit var diagText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        usernameField = findViewById(R.id.login_username)
        passwordField = findViewById(R.id.login_password)
        loginBtn = findViewById(R.id.login_submit)
        loginBtnLabel = findViewById(R.id.login_submit_label)
        statusText = findViewById(R.id.login_status)
        showPassToggle = findViewById(R.id.login_show_pass)
        diagText = findViewById(R.id.login_diag)

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
        // Cursor jumps to start when input type changes; restore to end.
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
        diagText.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { verify(u, p) }
            if (result.success) {
                AuthStore.saveCredentials(this@LoginActivity, u, p)
                startActivity(Intent(this@LoginActivity, EpgActivity::class.java))
                finish()
            } else {
                loginBtn.isEnabled = true
                loginBtnLabel.text = "SIGN IN"
                statusText.text = result.message
                statusText.visibility = View.VISIBLE
                if (result.diag.isNotBlank()) {
                    diagText.text = result.diag
                    diagText.visibility = View.VISIBLE
                }
            }
        }
    }

    private data class AuthResult(
        val success: Boolean,
        val message: String = "",
        val diag: String = "",
    )

    /**
     * Verifies credentials against the backend proxy first; if the
     * proxy is unreachable, falls back to a direct provider call.
     */
    private fun verify(username: String, password: String): AuthResult {
        val backendResult = verifyWithBackend(username, password)
        if (backendResult.success) return backendResult
        // Backend proxy unreachable (5xx, network, timeout) OR auth
        // proxy returned a non-auth error — try the provider directly.
        val direct = verifyDirect(username, password)
        if (direct.success) {
            return direct.copy(
                diag = "Signed in via direct provider (backend proxy unreachable).",
            )
        }
        // Both paths failed.  Surface whichever message is more
        // useful: backend's 401 ("Invalid credentials") wins over a
        // generic direct-network error.
        return if (backendResult.message.contains("Invalid", ignoreCase = true)) {
            backendResult
        } else {
            direct
        }
    }

    /**
     * Posts the credentials to `/api/xtream/auth`.  The backend
     * proxies the call to the Xtream provider's player_api.php and
     * returns 2xx only when `user_info.auth == 1`.
     */
    private fun verifyWithBackend(username: String, password: String): AuthResult {
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
            when (code) {
                in 200..299 -> AuthResult(true)
                401 -> AuthResult(
                    false,
                    "Invalid username or password. Please try again.",
                )
                in 500..599 -> AuthResult(
                    false,
                    "Couldn't sign in — please check your details and try again.",
                    diag = "Backend proxy returned $code — trying provider directly…",
                )
                else -> AuthResult(
                    false,
                    "Couldn't sign in — please check your details and try again.",
                    diag = "Backend HTTP $code",
                )
            }
        } catch (t: Throwable) {
            AuthResult(
                false,
                "Couldn't sign in — please check your details and try again.",
                diag = "Backend unreachable (${t.javaClass.simpleName})",
            )
        }
    }

    /**
     * Direct hit against `<host>:<port>/player_api.php?username=&password=`.
     * Returns success when the JSON body has `user_info.auth == 1`.
     * Used as a fallback when the backend proxy is down or can't reach
     * the provider for whatever reason.
     */
    private fun verifyDirect(username: String, password: String): AuthResult {
        return try {
            val base = "${AuthStore.SCHEME}://${AuthStore.HOST}:${AuthStore.PORT}"
            val q = "username=" + URLEncoder.encode(username, "UTF-8") +
                    "&password=" + URLEncoder.encode(password, "UTF-8")
            val url = URL("$base/player_api.php?$q")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "OnNowTV/${BuildConfig.VERSION_NAME}")
                if (this is HttpsURLConnection) {
                    // Some Xtream providers ship invalid / expired certs
                    // on their HTTPS endpoint.  Since the user explicitly
                    // entered their credentials and the host is hard-
                    // coded to the managed provider, we trust the cert
                    // chain here — same behaviour as the backend
                    // (httpx verify=False).
                    try {
                        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
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
                        })
                        val ctx = SSLContext.getInstance("TLS")
                        ctx.init(null, trustAll, java.security.SecureRandom())
                        sslSocketFactory = ctx.socketFactory
                        hostnameVerifier = HostnameVerifier { _, _ -> true }
                    } catch (_: Throwable) {}
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return AuthResult(
                    false,
                    "Couldn't reach the TV provider. Check your internet and try again.",
                    diag = "Direct provider HTTP $code",
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            try {
                val json = JSONObject(body)
                val auth = json.optJSONObject("user_info")?.opt("auth")?.toString() ?: ""
                if (auth == "1") {
                    AuthResult(true)
                } else {
                    AuthResult(
                        false,
                        "Invalid username or password. Please try again.",
                        diag = "Provider auth=$auth",
                    )
                }
            } catch (_: Throwable) {
                AuthResult(
                    false,
                    "Couldn't sign in — please check your details and try again.",
                    diag = "Provider returned unexpected response.",
                )
            }
        } catch (t: Throwable) {
            AuthResult(
                false,
                "Couldn't reach the TV provider. Check your internet and try again.",
                diag = "${t.javaClass.simpleName}: ${t.message ?: "no detail"}",
            )
        }
    }
}
