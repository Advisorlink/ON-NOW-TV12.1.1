package tv.onnowtv.tunes.youtube

import android.util.Log
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * **Authenticated** InnerTube resolver — v2.8.55.
 *
 *   resolve("Adele", "Hello") →
 *       success:
 *           {
 *             "ok":       true,
 *             "url":      "https://rr*--*.googlevideo.com/videoplayback?…",
 *             "yt_id":    "YQHsXMglC9A",
 *             "title":    "Adele - Hello",
 *             "uploader": "AdeleVEVO",
 *             "duration": 367,
 *             "bitrate":  256000,
 *             "source":   "youtube-direct"
 *           }
 *       fallback (user not signed in OR every InnerTube path blocked):
 *           {
 *             "ok":     true,
 *             "yt_id":  "YQHsXMglC9A",
 *             "title":  "Adele - Hello",
 *             "source": "youtube-iframe"
 *           }
 *
 * Why this works in late 2025 / early 2026:
 *
 *   • Every UNAUTH InnerTube client (ANDROID, IOS, TVHTML5_*) now
 *     returns either `LOGIN_REQUIRED` or empty `playabilityStatus`
 *     thanks to YouTube's PoToken rollout.
 *
 *   • The **TVHTML5** client (note: not `_SIMPLY_EMBEDDED_PLAYER`)
 *     still works with authenticated requests because YouTube
 *     treats it as a signed-in TV — same auth path as a real
 *     smart-TV YouTube app.
 *
 *   • Auth recipe (cribbed straight from YouTube's own web app):
 *       Authorization: SAPISIDHASH <ts>_<sha1(ts + " " + SAPISID + " " + origin)>
 *       Cookie:        <full cookie string from WebView>
 *       X-Origin:      https://www.youtube.com
 *       X-Goog-AuthUser: 0
 *
 *   • TVHTML5 audio formats include direct `url` fields with NO
 *     signatureCipher to decode — clean googlevideo.com URLs the
 *     HTML5 `<audio>` element can stream.
 *
 *   • The audio bytes are AD-FREE.  Ads are injected by YouTube's
 *     player UI; the CDN-served audio file has no ad inserts.
 *
 * If TVHTML5-with-auth ever stops working, we fall back to the
 * IFrame Player API (with ads on free accounts).  Returning
 * `source: "youtube-iframe"` triggers that path on the React side.
 */
object InnerTubeResolver {

    private const val TAG = "InnerTubeResolver"
    private const val CACHE_TTL_MS = 4L * 60 * 60 * 1000  // 4 h

    private data class CacheEntry(val payload: JSONObject, val expiresAt: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    // WEB client — used only for the search step (still works
    // unauthenticated in 2026).
    private const val WEB_KEY     = "REDACTED_PUBLIC_YOUTUBE_WEB_KEY"
    private const val WEB_VERSION = "2.20240726.00.00"

    // TVHTML5 client — for the authenticated player step.  Version
    // string is the one YouTube's own TV app shipped at the start
    // of 2025; YouTube allows ±3-month drift on this.
    private const val TVHTML5_VERSION = "7.20250119.10.00"

    private const val UA_WEB =
        "Mozilla/5.0 (Linux; Android 11; HK1 Box) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
    private const val UA_TVHTML5 =
        "Mozilla/5.0 (PlayStation; PlayStation 4/12.50) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
            "Version/16.6 Safari/605.1.15"

    private const val ORIGIN = "https://www.youtube.com"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun resolve(artist: String, title: String): JSONObject {
        // v2.8.55 — 4-hour cache.  Googlevideo URLs are signed and
        // typically live ~6 h, so we stay well under expiry.  This
        // makes scrubbing back to a recent track + re-playing it
        // essentially instant (no network calls at all).
        val cacheKey = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
        val now = System.currentTimeMillis()
        val cached = cache[cacheKey]
        if (cached != null && cached.expiresAt > now) {
            return JSONObject(cached.payload.toString()).put("cached", true)
        }

        return try {
            val videoId = searchFirstVideoId("$artist $title")
                ?: return error("no search result for '$artist $title'")

            // Try the authenticated TVHTML5 path first.  Returns a
            // direct CDN URL on success → ad-free playback via
            // HTML5 <audio>.
            val direct = directAudioUrl(videoId)
            if (direct != null) {
                cache[cacheKey] = CacheEntry(direct, now + CACHE_TTL_MS)
                return direct
            }

            // Fallback — hand back just the videoId so the React
            // side mounts the IFrame Player.  May show ads on free
            // YouTube accounts; Premium accounts still ad-free.
            val fallback = JSONObject().apply {
                put("ok", true)
                put("yt_id", videoId)
                put("source", "youtube-iframe")
                put("fallback_reason", "tvhtml5 auth path returned no URL")
            }
            // Cache the videoId for 24 h — the videoId itself
            // doesn't expire; only the audio URL did.  Saves the
            // search roundtrip on retry.
            cache[cacheKey] = CacheEntry(fallback, now + 24 * 60 * 60 * 1000L)
            fallback
        } catch (t: Throwable) {
            Log.w(TAG, "resolve failed for $artist - $title", t)
            error(t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }

    // ── Step 1 — Search (WEB, unauthenticated) ─────────────────
    private fun searchFirstVideoId(query: String): String? {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", query)
            put("params", "EgIQAQ%3D%3D")  // filter: type=video
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?key=$WEB_KEY&prettyPrint=false")
            .header("User-Agent", UA_WEB)
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", WEB_VERSION)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            return findFirstVideoId(root)
        }
    }

    private fun findFirstVideoId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer")) {
                    val id = node.optJSONObject("videoRenderer")?.optString("videoId")
                    if (!id.isNullOrEmpty()) return id
                }
                val it = node.keys()
                while (it.hasNext()) {
                    val found = findFirstVideoId(node.opt(it.next()))
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findFirstVideoId(node.opt(i))
                    if (found != null) return found
                }
            }
        }
        return null
    }

    // ── Step 2 — Authenticated TVHTML5 player call ─────────────
    private fun directAudioUrl(videoId: String): JSONObject? {
        val authHeader = sapisidHashHeader()
        if (authHeader == null) {
            Log.i(TAG, "no SAPISID cookie — user not signed in to YouTube; will fall back to iframe")
            return null
        }

        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "TVHTML5")
                    put("clientVersion", TVHTML5_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                    put("userAgent", UA_TVHTML5)
                })
                put("thirdParty", JSONObject().apply {
                    put("embedUrl", "https://www.youtube.com/")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", UA_TVHTML5)
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", "7")
            .header("X-YouTube-Client-Version", TVHTML5_VERSION)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Authorization", authHeader)
            .header("X-Origin", ORIGIN)
            .header("X-Goog-AuthUser", "0")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val raw = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "tvhtml5 player HTTP ${resp.code}")
                return null
            }
            resp.body?.string() ?: return null
        }
        val root = JSONObject(raw)

        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status") ?: "?"
        if (status != "OK") {
            Log.w(TAG, "tvhtml5 playabilityStatus=$status (${playability?.optString("reason")})")
            return null
        }

        val streamingData = root.optJSONObject("streamingData") ?: return null
        val adaptive = streamingData.optJSONArray("adaptiveFormats")
            ?: streamingData.optJSONArray("formats")
            ?: return null

        var bestUrl: String? = null
        var bestBitrate = -1
        var bestMime = ""
        for (i in 0 until adaptive.length()) {
            val f = adaptive.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType", "")
            if (!mime.startsWith("audio/")) continue
            // TVHTML5 audio formats expose direct `url` fields.  If
            // a row only has signatureCipher we skip it — we don't
            // bundle a JS cipher engine.
            val url = f.optString("url", "")
            if (url.isEmpty()) continue
            val br = f.optInt("bitrate", 0)
            if (br > bestBitrate) {
                bestBitrate = br
                bestUrl = url
                bestMime = mime
            }
        }
        if (bestUrl == null) {
            Log.w(TAG, "tvhtml5 returned formats but none had direct URLs")
            return null
        }

        val details = root.optJSONObject("videoDetails")
        return JSONObject().apply {
            put("ok", true)
            put("url", bestUrl)
            put("yt_id", videoId)
            put("title",    details?.optString("title")    ?: "")
            put("uploader", details?.optString("author")   ?: "")
            put("duration", details?.optString("lengthSeconds")?.toIntOrNull() ?: 0)
            put("bitrate", bestBitrate)
            put("format", bestMime)
            put("source", "youtube-direct")
        }
    }

    /** Build the `Authorization: SAPISIDHASH <ts>_<sha1>` header.
     *  Returns null when the user isn't signed in (no SAPISID
     *  cookie yet). */
    private fun sapisidHashHeader(): String? {
        val sapisid = readCookieValue("SAPISID")
            ?: readCookieValue("__Secure-3PAPISID")  // some sessions
            ?: return null
        val ts = System.currentTimeMillis() / 1000
        val raw = "$ts $sapisid $ORIGIN"
        val sha = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        val hex = sha.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "SAPISIDHASH ${ts}_${hex}"
    }

    private fun readCookieValue(name: String): String? {
        val all = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return null
        return all.split(";").asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", msg)
        put("source", "innertube")
    }
}
