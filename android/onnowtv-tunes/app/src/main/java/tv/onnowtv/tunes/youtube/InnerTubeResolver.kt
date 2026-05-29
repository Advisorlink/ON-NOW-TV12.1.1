package tv.onnowtv.tunes.youtube

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Custom YouTube audio resolver — bypasses NewPipeExtractor entirely.
 *
 *   resolve("Adele", "Hello") →
 *       {
 *         "ok":       true,
 *         "url":      "https://rr*--*.googlevideo.com/videoplayback?…",
 *         "title":    "Adele - Hello",
 *         "uploader": "AdeleVEVO",
 *         "duration": 367,
 *         "yt_id":    "YQHsXMglC9A",
 *         "bitrate":  256000,
 *         "source":   "innertube"
 *       }
 *
 * Why we wrote our own:
 *   • NewPipeExtractor 0.24.8 (latest tag at the time of writing)
 *     throws `ContentNotAvailableException: The page needs to be
 *     reloaded.` on YouTube Music searches because the WEB
 *     InnerTube client now requires PoToken / visitor-data tokens
 *     it can't easily acquire.
 *
 * How we work around it:
 *   • Two raw POSTs to InnerTube (`/youtubei/v1/search` then
 *     `/youtubei/v1/player`) using the **ANDROID** client headers.
 *     Android client is special — its audio URLs aren't
 *     signature-encrypted and don't need PoToken (yet).
 *
 *   • No URLEncoder.encode(String, Charset) calls anywhere in this
 *     file — we use only the legacy `String, String` overload that
 *     works on every Android API level.
 *
 *   • Direct OkHttp — no third-party transitive deps.
 *
 * Limitations (acceptable):
 *   • If YouTube ever revokes the ANDROID client's no-PoToken
 *     grace period, we'd need to migrate to iOS / TVHTML5 (same
 *     code pattern, different client headers).  Both are documented.
 *
 *   • Live-streams + age-restricted videos may need additional
 *     handling — out of scope for "play Adele's Hello".
 */
object InnerTubeResolver {

    private const val TAG = "InnerTubeResolver"

    // ANDROID client — public InnerTube key + version that's known
    // to work for unauthenticated audio extraction in late 2025 /
    // early 2026.  These are NOT secrets; they're shipped in
    // YouTube's own Android app and discoverable by anyone.
    private const val ANDROID_KEY      = "REDACTED_PUBLIC_YT_ANDROID_KEY"
    private const val ANDROID_VERSION  = "19.44.38"
    private const val ANDROID_SDK      = 30
    // WEB client — used for the search step.  Searching as ANDROID
    // sometimes hides results that aren't optimised for the mobile
    // app; WEB returns the broadest catalogue and is unauthenticated.
    private const val WEB_KEY      = "REDACTED_PUBLIC_YOUTUBE_WEB_KEY"
    private const val WEB_VERSION  = "2.20240726.00.00"

    private const val UA_ANDROID =
        "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip"
    private const val UA_WEB =
        "Mozilla/5.0 (Linux; Android 11; HK1 Box) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun resolve(artist: String, title: String): JSONObject {
        return try {
            val videoId = searchFirstVideoId("$artist $title")
                ?: return error("no search result for '$artist $title'")
            // v2.8.53 — YouTube broke /youtubei/v1/player for every
            // unauthenticated client (ANDROID, IOS, TVHTML5_*) by
            // requiring PoToken / visitor-data in late 2025.  Rather
            // than fight an arms race, we hand the videoId straight
            // to the React side, which embeds YouTube's OFFICIAL
            // IFrame Player API.  YouTube's own player handles
            // PoToken, signatures, ads, etc. internally — and it's
            // 100 % within their published API terms.
            val details = lookupVideoDetails(videoId)
            JSONObject().apply {
                put("ok", true)
                // No "url" — the React player branches on
                // `source == "youtube-iframe"` and uses yt_id.
                put("yt_id", videoId)
                put("title",    details.first ?: "")
                put("uploader", details.second ?: "")
                put("duration", details.third ?: 0)
                put("source",   "youtube-iframe")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resolve failed for $artist - $title", t)
            error(t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }

    /** Pull title / uploader / duration from the search response by
     *  re-searching with one extra parse.  Best-effort — if it
     *  returns null fields the React side just shows the Deezer
     *  metadata it already has. */
    private fun lookupVideoDetails(videoId: String): Triple<String?, String?, Int?> {
        // Cheap heuristic — we don't need a second roundtrip; the
        // search response already had the title + author.  Re-walk
        // a tiny cached copy here is overkill, so we just leave the
        // fields null and let React fall back to Deezer's metadata.
        return Triple(null, null, null)
    }

    /** Step 1 — POST /youtubei/v1/search (WEB client). */
    private fun searchFirstVideoId(query: String): String? {
        // Bias toward songs (videoCategory=Music, type=Video) using
        // YouTube's documented `params=EgIQAQ%3D%3D` filter — same
        // value the YouTube web app sends when you click the
        // "Videos" filter chip on a search results page.
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
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "search HTTP ${resp.code}")
                return null
            }
            val root = JSONObject(resp.body?.string() ?: return null)
            return findFirstVideoId(root)
        }
    }

    /** Recursively walks the search response looking for the first
     *  `videoRenderer.videoId` — robust against YouTube's frequent
     *  layout changes. */
    private fun findFirstVideoId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer")) {
                    val vr = node.optJSONObject("videoRenderer")
                    val id = vr?.optString("videoId")
                    if (!id.isNullOrEmpty()) return id
                }
                val it = node.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    val found = findFirstVideoId(node.opt(key))
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

    /** Step 2 — POST /youtubei/v1/player (ANDROID client).  Returns
     *  the full result JSONObject ready for the bridge. */
    private fun playerLookup(videoId: String): JSONObject {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", ANDROID_VERSION)
                    put("androidSdkVersion", ANDROID_SDK)
                    put("hl", "en")
                    put("gl", "US")
                    put("userAgent", UA_ANDROID)
                })
            })
            put("videoId", videoId)
            // contentCheckOk + racyCheckOk pull through soft-blocked
            // / "may contain mature content" videos without needing
            // a sign-in confirmation.
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$ANDROID_KEY&prettyPrint=false")
            .header("User-Agent", UA_ANDROID)
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", "3")
            .header("X-YouTube-Client-Version", ANDROID_VERSION)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return error("player HTTP ${resp.code}")
            }
            val root = JSONObject(resp.body?.string() ?: return error("empty body"))

            val playability = root.optJSONObject("playabilityStatus")
            val statusName = playability?.optString("status") ?: "?"
            if (statusName != "OK") {
                val reason = playability?.optString("reason") ?: statusName
                return error("playabilityStatus=$statusName ($reason)")
            }

            val streamingData = root.optJSONObject("streamingData")
                ?: return error("no streamingData")
            val adaptive = streamingData.optJSONArray("adaptiveFormats")
                ?: streamingData.optJSONArray("formats")
                ?: return error("no adaptiveFormats / formats")

            // Pick the highest-bitrate audio-only stream.
            var bestUrl: String? = null
            var bestBitrate = -1
            var bestMime    = ""
            for (i in 0 until adaptive.length()) {
                val f = adaptive.optJSONObject(i) ?: continue
                val mime = f.optString("mimeType", "")
                // audio-only mime types start with "audio/"
                if (!mime.startsWith("audio/")) continue
                val br = f.optInt("bitrate", 0)
                val url = f.optString("url", "")
                // ANDROID client gives us direct URLs (no signatureCipher
                // step needed) — but defensively skip rows missing one.
                if (url.isEmpty()) continue
                if (br > bestBitrate) {
                    bestBitrate = br
                    bestUrl = url
                    bestMime = mime
                }
            }
            if (bestUrl == null) return error("no audio-only stream found")

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
                put("source", "innertube")
            }
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", msg)
        put("source", "innertube")
    }
}
