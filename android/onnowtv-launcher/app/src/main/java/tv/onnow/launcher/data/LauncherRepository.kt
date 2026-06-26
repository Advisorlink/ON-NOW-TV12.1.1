package tv.onnow.launcher.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LauncherRepository
 * ──────────────────
 * Talks to the admin backend at `BASE_URL`.  Exposes the latest
 * pulled config as a StateFlow so the UI can subscribe and re-render
 * automatically on every successful refresh.
 *
 * Caches the last good config to SharedPreferences so a brand-new
 * cold launch always has SOMETHING to render even when the network
 * is briefly offline.
 */
class LauncherRepository(
    private val ctx: Context,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    companion object {
        private const val TAG = "LauncherRepository"

        /** Override at runtime via SharedPreferences "launcher.base_url".
         *
         *  v2.8.40 — Cut over to the Contabo VPS production host.
         *  Nginx proxies the /launcher/ path to 127.0.0.1:8002 (the
         *  launcher backend's uvicorn).  FastAPI's root_path is
         *  configured for the /launcher prefix server-side, so
         *  every relative path under this base URL Just Works.
         *
         *  Earlier value (preview pod, retired):
         *    https://rebrand-app-5.preview.emergentagent.com/api/launcher-admin
         */
        const val DEFAULT_BASE_URL =
            "https://onnowtv.duckdns.org/launcher"

        private const val PREFS = "launcher_repo"
        private const val KEY_CONFIG_JSON = "config_json"
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _config = MutableStateFlow<LauncherConfig?>(null)
    val config: StateFlow<LauncherConfig?> = _config.asStateFlow()

    /** Stable per-device id.
     *
     *  v2.8.7 — Delegates to `OnboardingActivity.deviceId(ctx)` so
     *  the heartbeat id sent on every `/api/launcher/config` poll
     *  MATCHES the id used for `/api/launcher/register` and
     *  `/api/launcher/activation`.  Previously these two paths used
     *  separate UUIDs in two different SharedPreferences files, so
     *  the "Connected devices" admin telemetry showed a phantom
     *  second id for every box and the user could not see his
     *  registered devices heartbeating in the admin UI.  Single
     *  source of truth now.
     */
    val deviceId: String
        get() = tv.onnow.launcher.onboarding.OnboardingActivity.deviceId(ctx)

    /** v0.4 — Public read-only base URL accessor so the
     *  MainActivity's debug pill can show the user which backend
     *  the launcher is hitting. */
    fun baseUrlPublic(): String = baseUrl

    /** Restore last known config from disk synchronously so the UI
     *  has something to render on the very first frame. */
    fun loadCached(): LauncherConfig? {
        val cached = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG_JSON, null) ?: return null
        return try {
            val parsed = parseLauncherConfig(cached)
            _config.value = parsed
            parsed
        } catch (t: Throwable) {
            Log.w(TAG, "loadCached: parse failed", t)
            null
        }
    }

    suspend fun refresh(): LauncherConfig? = withContext(Dispatchers.IO) {
        // v0.4 — Send device_id with every config poll so the admin
        // backend can record which devices are online and which
        // config generation they have applied.  Powers the
        // "Connected devices" panel in the admin UI.
        val url = "$baseUrl/api/launcher/config?device_id=$deviceId"
        try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "refresh: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val parsed = parseLauncherConfig(body)
                _config.value = parsed
                // Cache for next cold start.
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CONFIG_JSON, body).apply()
                parsed
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed", t)
            null
        }
    }

    suspend fun ackNotification(notificationId: String) = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/launcher/ack-notification"
        val payload = """{"id":"$notificationId","device_id":"$deviceId"}"""
        try {
            val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder().url(url).post(body).build()
            http.newCall(req).execute().close()
        } catch (t: Throwable) {
            Log.w(TAG, "ackNotification failed", t)
        }
    }
}
