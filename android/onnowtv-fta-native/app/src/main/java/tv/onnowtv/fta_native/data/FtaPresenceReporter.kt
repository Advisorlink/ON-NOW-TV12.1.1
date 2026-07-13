package tv.onnowtv.fta_native.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * v2.16.29 — Reports FTA-native playback to the launcher-admin
 * "Live" tab so the operator sees who's watching what across all
 * apps in one view.  Mirrors the LiveTV `PresenceReporter` design
 * but uses the device's Android-ID as the client_key because the
 * FTA app has no user login concept.  The admin sees the row as
 * "fta:AABBCC…" instead of a username, which is still enough to
 * distinguish devices in the field.
 */
object FtaPresenceReporter {

    private const val TAG = "FtaPresenceReporter"
    private const val HEARTBEAT_MS = 30_000L
    private const val INGEST_KEY = "onnow-presence-ingest-3f9c2a71b8de405e9047ac1d6f8b3e5c"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile private var sessionId: String? = null
    @Volatile private var channelName: String? = null
    @Volatile private var channelId: String? = null
    @Volatile private var deviceKey: String? = null
    @Volatile private var future: ScheduledFuture<*>? = null

    /** Kick off heartbeats for the currently-playing FTA channel. */
    fun start(ctx: Context, channelId: String, channelName: String) {
        val id = channelName.trim().ifBlank { channelId }
        if (id.isBlank()) return
        val key = deviceKey ?: run {
            val android = try {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Throwable) { null }
            val short = (android ?: "unknown").take(8)
            "fta-device:$short".also { deviceKey = it }
        }
        val prev = sessionId
        val prevName = this.channelName
        if (prev != null && prevName != channelName) {
            postEnd(prev)
        }
        val ses = prev?.takeIf { prevName == channelName } ?: UUID.randomUUID().toString()
        sessionId = ses
        this.channelName = channelName
        this.channelId = channelId
        future?.cancel(false)
        io.submit { postHeartbeat(key) }
        future = io.scheduleWithFixedDelay(
            { postHeartbeat(key) },
            HEARTBEAT_MS,
            HEARTBEAT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        future?.cancel(false)
        future = null
        val ses = sessionId
        sessionId = null
        channelName = null
        channelId = null
        if (ses != null) postEnd(ses)
    }

    private fun apiBase(): String = FtaRepository.BACKEND_BASE.trimEnd('/')

    private fun postHeartbeat(user: String) {
        val ses = sessionId ?: return
        val name = channelName ?: return
        val payload = JSONObject().apply {
            put("session_id", ses)
            put("app", "fta")
            put("content_kind", "fta_channel")
            put("content_id", channelId ?: "")
            put("content_title", name)
            put("client_key", user)
        }
        val req = Request.Builder()
            .url("${apiBase()}/api/presence/heartbeat")
            .addHeader("X-Presence-Key", INGEST_KEY)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) Log.w(TAG, "hb http ${r.code}")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "hb failed: ${t.message}")
        }
    }

    private fun postEnd(ses: String) {
        val payload = JSONObject().apply {
            put("session_id", ses)
            put("app", "fta")
        }
        val req = Request.Builder()
            .url("${apiBase()}/api/presence/end")
            .addHeader("X-Presence-Key", INGEST_KEY)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        io.submit {
            try {
                http.newCall(req).execute().close()
            } catch (t: Throwable) {
                Log.d(TAG, "end failed: ${t.message}")
            }
        }
    }
}
