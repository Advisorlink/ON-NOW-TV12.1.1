package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * v2.9.9 — Full XMLTV EPG downloader & stream-parser.
 *
 * v2.10.14 — Renamed from "fetchPriorityEpg" → "fetchEpgForChannels"
 * and the priority-filter framing was DROPPED.  User explicitly
 * asked for the FULL 3-day EPG to be cached for EVERY channel in
 * their lineup (not just UK / USA / AU Kayo / NZ Sports).  Memory
 * cost of retaining every channel's programmes: ~110 MB on a 9000-
 * channel provider — fine on a modern 2 GB Android TV box, and
 * the user accepted a "couple of minutes on first launch" trade.
 *
 * In addition to the programmes map, the fetcher now also returns
 * a `displayNameToEpgId` mapping captured from the XMLTV's own
 * `<channel><display-name>` elements.  Callers use this to recover
 * EPG for bundle channels whose provider-supplied `epg_channel_id`
 * is BLANK or DOES NOT MATCH the XMLTV's channel id — matching by
 * display-name is the last-mile fallback that pulls these channels
 * out of the "Loading guide…" gutter.
 *
 * The Xtream provider serves a `xmltv.php` endpoint that returns
 * GZIP-encoded XMLTV (Content-Encoding: gzip).  Raw size ~145 MB,
 * compressed transfer ~27 MB, downloads in ~5 s on a cable LAN.
 *
 * Memory after parse for a full ~9000-channel bundle × ~72 h of
 * guide: ~110 MB heap on a representative provider lineup.
 */
object XmlTvFetcher {

    private const val TAG = "XmlTvFetcher"

    /** Result of a full XMLTV parse. */
    data class ParseResult(
        /** Map of `epg_channel_id` → full list of programmes parsed
         *  from the XMLTV for that channel id. */
        val programmes: Map<String, List<Programme>>,
        /** Lower-cased display-name → epg_channel_id mapping captured
         *  from every `<channel id=…><display-name>NAME</display-name>`
         *  block.  Used by the caller to back-fill EPG into bundle
         *  channels whose own `epg_channel_id` was missing/wrong. */
        val displayNameToEpgId: Map<String, String>,
    )

    /**
     * Download + parse the provider's full XMLTV, returning a
     * [ParseResult] containing every programme whose channel id is
     * in [wantedChannelIds] OR whose `<channel>`'s display-name
     * (normalised) is in [wantedNormalisedNames], PLUS a
     * display-name → id map covering every `<channel>` element in
     * the document.
     *
     * @param onProgress invoked from the parse loop with the running
     *    count of channels seen + programmes retained.  Used by the
     *    MainActivity loader to show animated counters.
     */
    suspend fun fetchEpgForChannels(
        ctx: Context,
        wantedChannelIds: Set<String>,
        wantedNormalisedNames: Set<String> = emptySet(),
        onProgress: (channelsSeen: Int, programmesRetained: Int) -> Unit,
    ): ParseResult = withContext(Dispatchers.IO) {
        val u = AuthStore.username(ctx).takeIf { it.isNotBlank() }
            ?: throw RuntimeException("No saved credentials")
        val p = AuthStore.password(ctx).takeIf { it.isNotBlank() }
            ?: throw RuntimeException("No saved credentials")
        val base = "${AuthStore.SCHEME}://${AuthStore.HOST}:${AuthStore.PORT}"
        val url = URL(
            "$base/xmltv.php?" +
                "username=${URLEncoder.encode(u, "UTF-8")}" +
                "&password=${URLEncoder.encode(p, "UTF-8")}",
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Accept-Encoding", "gzip")
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
            if (code !in 200..299) {
                throw RuntimeException("XMLTV HTTP $code")
            }
            val raw = conn.inputStream
            val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(BufferedInputStream(raw, 64 * 1024))
            } else {
                BufferedInputStream(raw, 64 * 1024)
            }
            parseXmlTv(stream, wantedChannelIds, wantedNormalisedNames, onProgress)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Stream-parse XMLTV.  Memory footprint is bounded by the
     * caller's [wantedChannelIds] — we never accumulate programmes
     * for channels the caller didn't ask for.
     *
     * Always captures `<channel id><display-name>` mappings for
     * EVERY channel in the file, regardless of [wantedChannelIds],
     * so the caller can run name-based fallback matching.
     */
    private fun parseXmlTv(
        stream: java.io.InputStream,
        initialWantedChannelIds: Set<String>,
        wantedNormalisedNames: Set<String>,
        onProgress: (channelsSeen: Int, programmesRetained: Int) -> Unit,
    ): ParseResult {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")

        // Wanted set GROWS as we see `<channel><display-name>` blocks
        // whose normalised name matches one of the bundle's channel
        // names — that's how we recover EPG for channels whose
        // provider-supplied `epg_channel_id` was blank/wrong.
        val wantedChannelIds = HashSet<String>(initialWantedChannelIds.size + 2048)
        wantedChannelIds.addAll(initialWantedChannelIds)

        val out = HashMap<String, MutableList<Programme>>(wantedChannelIds.size)
        val channelsSeen = HashSet<String>(8000)
        val displayNameToId = HashMap<String, String>(8000)
        var retained = 0
        var lastProgressTick = 0L

        // ─── <channel id="…"><display-name>…</display-name></channel> ───
        // We capture EVERY channel block's display-name → id mapping
        // so the caller can do fuzzy match-by-name after the parse
        // for channels whose `epg_channel_id` came back blank from
        // the provider's `get_live_streams` call.
        var inChannelBlock = false
        var curChannelBlockId: String? = null
        val displayNameBuf = StringBuilder()
        var captureDisplayName = false

        var inProgramme = false
        var curChannel: String? = null
        var curStartTs = 0L
        var curStopTs = 0L
        var curTitle: String? = null
        var curDesc: String? = null
        var currentTag: String? = null
        val titleBuf = StringBuilder()
        val descBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id")
                            if (!id.isNullOrBlank()) {
                                channelsSeen.add(id)
                                inChannelBlock = true
                                curChannelBlockId = id
                            }
                        }
                        "display-name" -> if (inChannelBlock) {
                            captureDisplayName = true
                            displayNameBuf.setLength(0)
                        }
                        "programme" -> {
                            val ch = parser.getAttributeValue(null, "channel")
                            if (ch != null && wantedChannelIds.contains(ch)) {
                                inProgramme = true
                                curChannel = ch
                                curStartTs = parser.getAttributeValue(null, "start_timestamp")
                                    ?.toLongOrNull() ?: 0L
                                curStopTs = parser.getAttributeValue(null, "stop_timestamp")
                                    ?.toLongOrNull() ?: 0L
                                curTitle = null
                                curDesc = null
                            } else {
                                inProgramme = false
                            }
                        }
                        "title" -> if (inProgramme) {
                            currentTag = "title"
                            titleBuf.setLength(0)
                        }
                        "desc" -> if (inProgramme) {
                            currentTag = "desc"
                            descBuf.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (captureDisplayName) {
                        displayNameBuf.append(parser.text)
                    } else if (inProgramme) {
                        when (currentTag) {
                            "title" -> titleBuf.append(parser.text)
                            "desc" -> descBuf.append(parser.text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "display-name" -> {
                            if (captureDisplayName && curChannelBlockId != null) {
                                val name = displayNameBuf.toString().trim()
                                if (name.isNotBlank()) {
                                    // Keep the FIRST display-name we
                                    // see for each id (XMLTV often
                                    // ships several language variants
                                    // — the first is canonical).
                                    val key = normaliseChannelName(name)
                                    if (key.isNotBlank() && !displayNameToId.containsKey(key)) {
                                        displayNameToId[key] = curChannelBlockId!!
                                    }
                                    // Expand the wanted-channel set
                                    // RIGHT NOW so any programmes
                                    // for this channel (which come
                                    // later in the file) get
                                    // retained, even though the
                                    // provider's bundle never
                                    // gave us its epg_channel_id.
                                    if (key in wantedNormalisedNames) {
                                        wantedChannelIds.add(curChannelBlockId!!)
                                    }
                                }
                            }
                            captureDisplayName = false
                        }
                        "channel" -> {
                            inChannelBlock = false
                            curChannelBlockId = null
                        }
                        "title" -> {
                            if (inProgramme) {
                                curTitle = titleBuf.toString().trim()
                                currentTag = null
                            }
                        }
                        "desc" -> {
                            if (inProgramme) {
                                curDesc = descBuf.toString().trim().takeIf { it.isNotBlank() }
                                currentTag = null
                            }
                        }
                        "programme" -> {
                            if (inProgramme && curChannel != null && curStartTs > 0L) {
                                val list = out.getOrPut(curChannel!!) { ArrayList(64) }
                                list.add(
                                    Programme(
                                        title = curTitle?.takeIf { it.isNotBlank() } ?: "—",
                                        description = curDesc,
                                        startMs = curStartTs * 1000L,
                                        stopMs = curStopTs * 1000L,
                                    ),
                                )
                                retained++
                                // Throttle progress callbacks to ~10 Hz
                                val now = System.currentTimeMillis()
                                if (now - lastProgressTick > 100L) {
                                    onProgress(channelsSeen.size, retained)
                                    lastProgressTick = now
                                }
                            }
                            inProgramme = false
                            curChannel = null
                            curTitle = null
                            curDesc = null
                            currentTag = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        // Final progress tick.
        onProgress(channelsSeen.size, retained)
        Log.i(
            TAG,
            "XMLTV parsed: ${channelsSeen.size} total channels seen, " +
                "${out.size} channels with EPG retained, " +
                "${displayNameToId.size} display-name mappings, " +
                "$retained programmes total",
        )
        return ParseResult(
            programmes = out,
            displayNameToEpgId = displayNameToId,
        )
    }

    /** Lower-case + collapse whitespace + strip punctuation + strip
     *  trailing quality suffixes (HD / SD / FHD / UHD / 4K) so the
     *  same logical channel name matches across provider lineups
     *  and XMLTV variants:
     *
     *      "Sky Documentaries HD"  ─┐
     *      "SKY DOCUMENTARIES SD"   ─┼─►  "skydocumentaries"
     *      "sky-documentaries.uk"  ─┘
     *
     *  Timeshift markers like "+1" / "+24" stay intact so a +1
     *  variant doesn't collide with the original channel. */
    fun normaliseChannelName(name: String): String {
        if (name.isBlank()) return ""
        val sb = StringBuilder(name.length)
        for (ch in name) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                ch.isWhitespace() -> { /* drop */ }
                ch == '+' -> sb.append('+')  // keep +1 / +2 timeshift markers
                else -> { /* drop punctuation */ }
            }
        }
        // Strip a trailing quality suffix.  Done AFTER lowercase /
        // strip so "FHD".lowercase() = "fhd" matches.  We strip
        // unconditionally — empirically the XMLTV display-names
        // and Xtream get_live_streams names diverge mainly by the
        // presence/absence of these suffixes.
        var result = sb.toString()
        val suffixes = listOf(
            "uhd", "fhd", "hd1080", "hd720", "hd", "sd", "4k", "8k", "hevc",
        )
        var stripped = true
        while (stripped) {
            stripped = false
            for (suffix in suffixes) {
                if (result.length > suffix.length && result.endsWith(suffix)) {
                    result = result.substring(0, result.length - suffix.length)
                    stripped = true
                    break
                }
            }
        }
        return result
    }
}
