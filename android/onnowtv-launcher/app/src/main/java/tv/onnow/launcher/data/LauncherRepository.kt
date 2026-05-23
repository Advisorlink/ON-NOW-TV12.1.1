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
import java.util.UUID
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

        /** Override at runtime via SharedPreferences "launcher.base_url". */
        const val DEFAULT_BASE_URL =
            "https://rebrand-app-5.preview.emergentagent.com/api/launcher-admin"

        private const val PREFS = "launcher_repo"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_DEVICE_ID   = "device_id"
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _config = MutableStateFlow<LauncherConfig?>(null)
    val config: StateFlow<LauncherConfig?> = _config.asStateFlow()

    /** Stable per-device id used for notification ack tracking. */
    val deviceId: String by lazy {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }
    }

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
        val url = "$baseUrl/api/launcher/config"
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
