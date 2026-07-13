package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tv.onnowtv.livetv.model.Channel
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * v2.16.29 — Reports "who is watching what right now" to the Vesper
 * backend so the launcher-admin Live tab can render it in real time.
 *
 * ── How it fits in ─────────────────────────────────────────────
 * `PlayerActivity` calls [start] after a tune completes and [stop]
 * on `onDestroy`.  We fire the first heartbeat immediately so the
 * admin sees the row within a second, then every 30 s while the
 * player is up.  Channel changes call [start] with the new channel;
 * the previous session is ended first so the admin table always
 * shows exactly ONE row per box.
 *
 * ── Auth ───────────────────────────────────────────────────────
 * The native Live TV app has NO Vesper JWT — Xtream credentials are
 * the only identity it knows.  We authenticate with the shared
 * `X-Presence-Key` header and put the Xtream username in the
 * `client_key` field of the body; the backend uses that verbatim
 * as the "username" column in the admin table.
 */
object PresenceReporter {

    private const val TAG = "PresenceReporter"
    private const val HEARTBEAT_MS = 30_000L
    // Matches PRESENCE_INGEST_KEY on the Vesper backend.  Both
    // sides fall back to the same deterministic default when no
    // env var is set, so brand-new deployments work out of the
    // box.  Rotate together with the backend env when going
    // multi-tenant.
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

    // Current heartbeat state — one at a time; a new [start] cancels
    // whatever was running before.
    @Volatile private var sessionId: String? = null
    @Volatile private var channelName: String? = null
    @Volatile private var channelId: String? = null
    @Volatile private var future: ScheduledFuture<*>? = null

    /** Kick off (or update) heartbeats for the currently-tuned channel. */
    fun start(ctx: Context, channel: Channel) {
        val user = AuthStore.username(ctx)
        if (user.isBlank()) {
            // No signed-in user → nothing meaningful to report.
            return
        }
        // Ending any prior session before we replace it means the
        // admin sees a clean "channel A → channel B" transition
        // instead of two overlapping rows.
        val prevSession = sessionId
        val prevName = channelName
        if (prevSession != null && prevName != channel.name) {
            postEnd(prevSession)
        }
        val newSession = prevSession?.takeIf { prevName == channel.name } ?: UUID.randomUUID().toString()
        sessionId = newSession
        channelName = channel.name
        channelId = channel.id
        future?.cancel(false)
        // Immediate first heartbeat + a 30 s repeating tick.
        io.submit { postHeartbeat(user) }
        future = io.scheduleWithFixedDelay(
            { postHeartbeat(user) },
            HEARTBEAT_MS,
            HEARTBEAT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Stop heartbeats and send an explicit end so the admin's row
     *  disappears the moment the user leaves the player. */
    fun stop() {
        future?.cancel(false)
        future = null
        val ses = sessionId
        sessionId = null
        channelName = null
        channelId = null
        if (ses != null) postEnd(ses)
    }

    // ── HTTP helpers ─────────────────────────────────────────────
    private fun apiBase(): String = XtreamRepository.BACKEND_BASE.trimEnd('/')

    private fun postHeartbeat(user: String) {
        val ses = sessionId ?: return
        val name = channelName ?: return
        val payload = JSONObject().apply {
            put("session_id", ses)
            put("app", "livetv")
            put("content_kind", "live_channel")
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
                if (!r.isSuccessful) {
                    Log.w(TAG, "heartbeat http ${r.code}")
                }
            }
        } catch (t: Throwable) {
            // Best-effort; missed heartbeat just delays the admin's
            // update by 30 s.  Never crash the player over it.
            Log.d(TAG, "heartbeat failed: ${t.message}")
        }
    }

    private fun postEnd(ses: String) {
        val payload = JSONObject().apply {
            put("session_id", ses)
            put("app", "livetv")
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
