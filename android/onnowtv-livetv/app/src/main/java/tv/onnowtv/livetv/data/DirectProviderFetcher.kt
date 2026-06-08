package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
 * v2.9.8 — Direct-from-provider channel + EPG fetcher.
 *
 * Used when the backend `/api/xtream/instant-bundle` is unreachable
 * or returns an empty bundle (which is happening because the
 * Contabo VPS has been IP-blocked by the Xtream provider).  The
 * device's own ISP IP IS allowed by the provider, so we just bypass
 * the backend and assemble an equivalent bundle here.
 *
 * v2.9.14 — Throws [InvalidCredentialsException] specifically
 * when the provider rejects our auth (HTTP 404 on
 * `player_api.php`).  Callers can catch this and route the user
 * back to the login screen with a "Wrong username or password"
 * message, instead of treating it as a generic network failure.
 */
object DirectProviderFetcher {

    /** Distinguishes "provider rejected your credentials" from
     *  every other failure mode.  Thrown by [fetchBundleJson] /
     *  [playerApi] when the provider returns HTTP 404 OR a
     *  user_info with `auth == 0`. */
    class InvalidCredentialsException(message: String) : RuntimeException(message)

    private const val TAG = "DirectFetcher"

    /** Build a bundle JSON identical in shape to the backend bundle. */
    suspend fun fetchBundleJson(ctx: Context): String = withContext(Dispatchers.IO) {
        val u = AuthStore.username(ctx).takeIf { it.isNotBlank() }
            ?: throw RuntimeException("No saved credentials — sign in first.")
        val p = AuthStore.password(ctx).takeIf { it.isNotBlank() }
            ?: throw RuntimeException("No saved credentials — sign in first.")
        val base = "${AuthStore.SCHEME}://${AuthStore.HOST}:${AuthStore.PORT}"

        // Fetch categories + streams IN PARALLEL — both calls are
        // independent and the streams call is the big one (~4 MB).
        coroutineScope {
            val catsDeferred = async { playerApi(base, u, p, "get_live_categories") }
            val streamsDeferred = async { playerApi(base, u, p, "get_live_streams") }
            val cats = catsDeferred.await()
            val streams = streamsDeferred.await()
            buildBundle(base, u, p, cats, streams)
        }
    }

    /** Fetch a multi-day EPG (next ~200 programmes) for a single
     *  channel directly from the provider.  Used by EpgActivity to
     *  fill rows on demand when the bundle's XMLTV preload had no
     *  data for this channel.
     *
     *  v2.10.14 — Default limit bumped 20 → 200 so lazy-fetch
     *  returns ~3 days of EPG (matching the XMLTV preload depth),
     *  not just the ~6-12 hours we were getting before.  User
     *  explicitly asked: "EPG should be loaded for 3 days, not
     *  less than 24 hours". */
    suspend fun fetchShortEpg(
        ctx: Context,
        streamId: String,
        limit: Int = 200,
    ): List<Programme> = withContext(Dispatchers.IO) {
        val u = AuthStore.username(ctx).takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val p = AuthStore.password(ctx).takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val base = "${AuthStore.SCHEME}://${AuthStore.HOST}:${AuthStore.PORT}"
        val url = URL(
            "$base/player_api.php?" +
                "username=${URLEncoder.encode(u, "UTF-8")}" +
                "&password=${URLEncoder.encode(p, "UTF-8")}" +
                "&action=get_short_epg" +
                "&stream_id=${URLEncoder.encode(streamId, "UTF-8")}" +
                "&limit=$limit",
        )
        try {
            val text = getJson(url)
            val obj = JSONObject(text)
            val arr = obj.optJSONArray("epg_listings") ?: return@withContext emptyList()
            val out = mutableListOf<Programme>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val startTs = e.optLong("start_timestamp", 0L)
                val stopTs = e.optLong("stop_timestamp", 0L)
                if (startTs <= 0L) continue
                out.add(
                    Programme(
                        title = decodeBase64Field(e.optString("title")).ifBlank { "—" },
                        description = decodeBase64Field(e.optString("description"))
                            .takeIf { it.isNotBlank() },
                        startMs = startTs * 1000L,
                        stopMs = stopTs * 1000L,
                    ),
                )
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "fetchShortEpg($streamId) failed: ${t.message}")
            emptyList()
        }
    }

    /* ─────────────────────────── helpers ─────────────────────────── */

    private fun playerApi(
        base: String,
        username: String,
        password: String,
        action: String,
    ): JSONArray {
        val url = URL(
            "$base/player_api.php?" +
                "username=${URLEncoder.encode(username, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}" +
                "&action=$action",
        )
        val text = getJson(url)
        // v2.9.14 — A successful HTTP 200 with `{"user_info":{"auth":"0"}}`
        // is the provider's "rejected" path when creds parse but
        // don't authenticate.  Surface as InvalidCredentialsException
        // so the caller can route to login.
        if (text.contains("\"auth\":\"0\"") || text.contains("\"auth\":0,")) {
            throw InvalidCredentialsException("Provider auth=0 — wrong username or password.")
        }
        return JSONArray(text)
    }

    /**
     * Build the bundle JSON.  Shape MUST match `instant_bundle.py`'s
     * `_rebuild_cached_payload` so `XtreamRepository.parseBundle()`
     * needs no changes.
     */
    private fun buildBundle(
        base: String,
        username: String,
        password: String,
        cats: JSONArray,
        streams: JSONArray,
    ): String {
        val categoriesOut = JSONArray()
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            categoriesOut.put(
                JSONObject().apply {
                    put("id", c.opt("category_id")?.toString() ?: "")
                    put("name", c.optString("category_name").trim())
                },
            )
        }
        val channelsOut = JSONArray()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val sid = s.opt("stream_id")?.toString() ?: continue
            channelsOut.put(
                JSONObject().apply {
                    put("stream_id", sid)
                    put("name", s.optString("name").trim())
                    put("logo", s.optString("stream_icon"))
                    put("category_id", s.opt("category_id")?.toString() ?: "")
                    put("epg_channel_id", s.optString("epg_channel_id"))
                    put("tv_archive", s.optInt("tv_archive", 0))
                    put("stream_url", "$base/live/$username/$password/$sid.ts")
                },
            )
        }
        val provider = JSONObject().apply {
            put("id", "direct")
            put("name", "On Now TV")
            put("host", AuthStore.HOST)
            put("port", AuthStore.PORT)
            put("scheme", AuthStore.SCHEME)
        }
        val nowSec = System.currentTimeMillis() / 1000L
        return JSONObject().apply {
            put("provider", provider)
            put("categories", categoriesOut)
            put("channels", channelsOut)
            put("epg", JSONObject()) // lazy-loaded per channel later
            put("generated_at", nowSec)
            put("channels_fetched_at", nowSec)
            put("epg_fetched_at", 0)
        }.toString()
    }

    /** Decode Xtream's commonly base64-encoded title/description fields. */
    private fun decodeBase64Field(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Throwable) {
            raw
        }
    }

    /**
     * GET a URL as UTF-8 text.  Disables strict TLS verification — the
     * provider ships a self-signed / expired cert and the backend
     * does the same (`verify=False` in `instant_bundle.py`).
     */
    private fun getJson(url: URL): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // v2.9.10 — Tightened timeouts.  Old values (12s connect,
            // 30s read) meant a flaky network kept the loader stuck
            // for nearly a minute before falling through to the
            // error UI.  10s + 20s is plenty for a working provider.
            connectTimeout = 10_000
            readTimeout = 20_000
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
                    val ctx = SSLContext.getInstance("TLS")
                    ctx.init(null, trustAll, SecureRandom())
                    sslSocketFactory = ctx.socketFactory
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                } catch (_: Throwable) {}
            }
        }
        return try {
            val code = conn.responseCode
            // v2.9.14 — HTTP 404 from `player_api.php` is the
            // provider's "wrong username/password" sentinel.
            // Surface it as a structured error so the loader can
            // bounce the user back to LoginActivity with a
            // friendly message.
            if (code == 404) {
                throw InvalidCredentialsException("Provider returned HTTP 404 — wrong username or password.")
            }
            if (code !in 200..299) {
                throw RuntimeException("Direct HTTP $code")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
