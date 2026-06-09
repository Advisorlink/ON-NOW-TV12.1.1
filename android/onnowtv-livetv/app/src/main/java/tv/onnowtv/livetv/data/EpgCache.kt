package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * v2.10.15 — PER-CHANNEL on-disk EPG cache.
 *
 * Previous design (v2.10.14) loaded ALL channels' programmes into
 * a single `Map<String, List<Programme>>` for the whole parse and
 * the whole runtime — which OOM'd on the user's TV box (256 MB
 * heap, ~115 MB used just by the parsed EPG before the OS could
 * deliver the next binder transaction):
 *
 *     V2 LIVE TV — CRASH DIAGNOSTIC
 *     java.lang.OutOfMemoryError: Failed to allocate a 64 byte
 *     allocation with 8 free bytes and 8B until OOM,
 *     max allowed footprint 268435456, growth limit 268435456
 *
 * New design holds NO programmes in memory longer than necessary:
 *
 *   • Parse writes one gzipped JSONL file per channel to
 *     `filesDir/epg-channels-v3/<sha1-of-id>.jsonl.gz` as the XMLTV
 *     stream produces programmes.  Peak working set ≈ a couple of
 *     MB (one [StreamingWriter] flush buffer + the active XML
 *     parser state).
 *
 *   • Runtime reads ONE channel's file on demand via
 *     [loadChannel] (a couple of milliseconds for a ~50-programme
 *     channel) — never the whole catalogue.
 *
 *   • [exists] reports the cache as present only when the
 *     directory contains the schema stamp matching
 *     [CURRENT_SCHEMA_VERSION], so the boot-time fast path can
 *     skip the XMLTV preload safely on the next launch.
 *
 * The blanket [save] / [load] / [mergeChannel] convenience
 * functions are kept for backward compatibility with older call
 * sites but are now thin wrappers over the per-channel API.
 */
object EpgCache {
    private const val TAG = "EpgCache"

    private const val DIR_NAME = "epg-channels-v3"
    private const val SCHEMA_FILE = ".schema"
    private const val TS_FILE = ".timestamp"
    private const val DIR_DONE_FILE = ".done"
    private const val NAMEMAP_FILE = ".namemap.json"

    /**
     * Schema-version stamp.  Bump whenever the per-channel file
     * format or the directory layout changes.
     *   v3 = per-channel JSONL.gz under `epg-channels-v3/`
     */
    private const val CURRENT_SCHEMA_VERSION = 3

    /** v2.9.12 — Cache is permanent.  Kept as a sentinel constant
     *  so any caller that previously branched on `ageMs() < FRESH_MS`
     *  always takes the "fresh" branch. */
    const val FRESH_MS = Long.MAX_VALUE

    private fun cacheDir(ctx: Context): File = File(ctx.filesDir, DIR_NAME)
    private fun schemaFile(ctx: Context): File = File(cacheDir(ctx), SCHEMA_FILE)
    private fun tsFile(ctx: Context): File = File(cacheDir(ctx), TS_FILE)
    private fun doneFile(ctx: Context): File = File(cacheDir(ctx), DIR_DONE_FILE)
    private fun nameMapFile(ctx: Context): File = File(cacheDir(ctx), NAMEMAP_FILE)

    /** Persist the XMLTV `<channel><display-name>` → channel-id
     *  map so the fast-path boot can re-apply name-based fallback
     *  patching of the bundle's channel list without re-running
     *  the XMLTV parse.  Called by [StreamingWriter.finish]. */
    fun saveNameMap(ctx: Context, nameToId: Map<String, String>) {
        if (nameToId.isEmpty()) return
        try {
            val obj = JSONObject()
            for ((k, v) in nameToId) obj.put(k, v)
            nameMapFile(ctx).writeText(obj.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "saveNameMap failed: ${t.message}")
        }
    }

    /** Load the persisted display-name → id map.  Returns an empty
     *  map when the file is missing (e.g. legacy v2 caches or a
     *  fresh install before the first XMLTV parse). */
    fun loadNameMap(ctx: Context): Map<String, String> {
        val f = nameMapFile(ctx)
        if (!f.exists()) return emptyMap()
        return try {
            val obj = JSONObject(f.readText())
            val out = HashMap<String, String>(obj.length())
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = obj.optString(k)
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "loadNameMap failed: ${t.message}")
            emptyMap()
        }
    }

    /** True iff a per-channel cache file exists for [channelId].
     *  Cheap (single `File.exists()` syscall) — used by the
     *  fast-path patcher to validate that a name-fallback target
     *  actually has data on disk before rewriting the channel id. */
    fun channelExists(ctx: Context, channelId: String): Boolean {
        if (channelId.isBlank()) return false
        return fileFor(ctx, channelId).exists()
    }

    /** Filename for a given channel id — SHA-1 hex digest so we
     *  never trip over channel ids that contain `/`, `?`, etc. */
    private fun fileFor(ctx: Context, channelId: String): File {
        val md = MessageDigest.getInstance("SHA-1")
        val hash = md.digest(channelId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir(ctx), "$hash.jsonl.gz")
    }

    /** True only when a COMPLETED cache of the current schema lives
     *  on disk.  A directory that contains a schema stamp but no
     *  `.done` file (e.g. the parse crashed mid-flight) is treated
     *  as missing so the loader will re-run the XMLTV preload. */
    fun exists(ctx: Context): Boolean {
        val dir = cacheDir(ctx)
        if (!dir.exists() || !dir.isDirectory) return false
        if (!doneFile(ctx).exists()) return false
        val v = readSchemaVersion(ctx) ?: return false
        return v >= CURRENT_SCHEMA_VERSION
    }

    private fun readSchemaVersion(ctx: Context): Int? {
        val f = schemaFile(ctx)
        if (!f.exists()) return null
        return f.readText().trim().toIntOrNull()
    }

    fun ageMs(ctx: Context): Long {
        val ts = tsFile(ctx)
        if (!ts.exists()) return Long.MAX_VALUE
        val w = ts.readText().trim().toLongOrNull() ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - w
    }

    /** Load a single channel's programmes from disk.  Returns null
     *  when no file exists for the channel (caller should fall back
     *  to the network short_epg path). */
    fun loadChannel(ctx: Context, channelId: String): List<Programme>? {
        if (channelId.isBlank()) return null
        val f = fileFor(ctx, channelId)
        if (!f.exists()) return null
        return try {
            val out = ArrayList<Programme>(64)
            GZIPInputStream(f.inputStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val p = try { JSONObject(line) } catch (_: Throwable) { continue }
                    out.add(
                        Programme(
                            title = p.optString("t").ifBlank { "—" },
                            description = p.optString("d").takeIf { it.isNotBlank() },
                            startMs = p.optLong("s", 0L) * 1000L,
                            stopMs = p.optLong("e", 0L) * 1000L,
                        ),
                    )
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "loadChannel($channelId) failed: ${t.message}")
            null
        }
    }

    /** Persist a single channel's programmes.  Called by the
     *  lazy-fetch path in EpgActivity when it hits the network
     *  short_epg endpoint, AND by the per-channel writer at the end
     *  of the XMLTV parse. */
    fun saveChannel(ctx: Context, channelId: String, programmes: List<Programme>) {
        if (channelId.isBlank() || programmes.isEmpty()) return
        try {
            val dir = cacheDir(ctx).apply { mkdirs() }
            val tmp = File(dir, "${java.util.UUID.randomUUID()}.tmp")
            GZIPOutputStream(tmp.outputStream()).bufferedWriter(Charsets.UTF_8).use { w ->
                for (p in programmes) {
                    val o = JSONObject()
                    o.put("t", p.title)
                    if (!p.description.isNullOrBlank()) o.put("d", p.description)
                    o.put("s", p.startMs / 1000L)
                    o.put("e", p.stopMs / 1000L)
                    w.write(o.toString())
                    w.write("\n")
                }
            }
            tmp.renameTo(fileFor(ctx, channelId))
        } catch (t: Throwable) {
            Log.w(TAG, "saveChannel($channelId) failed: ${t.message}")
        }
    }

    /** Open a [StreamingWriter] for the XMLTV parse.  The writer
     *  accumulates programmes in a small in-memory buffer per
     *  channel, then flushes complete channels to disk and clears
     *  the buffer — keeping the heap footprint bounded regardless
     *  of how many programmes the XMLTV ships.  Caller MUST call
     *  [StreamingWriter.finish] when the parse completes. */
    fun openStreamingWriter(ctx: Context): StreamingWriter {
        val dir = cacheDir(ctx)
        // Wipe any in-progress write from a previous crash.
        if (dir.exists()) {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
        dir.mkdirs()
        return StreamingWriter(ctx)
    }

    /** v2.9.11 — Wipe the cache (used on sign-out). */
    fun delete(ctx: Context) {
        val dir = cacheDir(ctx)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        runCatching { dir.delete() }
    }

    // ─── Streaming writer ───────────────────────────────────────

    /**
     * Programmes are produced by the XMLTV parser in time order
     * (mixing channels).  The streaming writer keeps a small
     * per-channel in-memory buffer and flushes the LARGEST half
     * of those buffers to disk every time the total in-memory
     * programme count exceeds [FLUSH_AT_TOTAL_PROGRAMMES] (10 000
     * progs ≈ 2.5 MB).
     *
     * Worst-case working set is bounded by:
     *   • ~9 000 channels × ~48 bytes empty-bucket overhead
     *   • the live programme buffers (≤ 10 000 progs × ~250 B)
     * Comfortably under 5 MB on the 256 MB-heap user box.
     */
    class StreamingWriter internal constructor(
        private val ctx: Context,
    ) {
        private val buffers = HashMap<String, MutableList<Programme>>(512)
        private var bufferedProgrammes = 0
        private var totalProgrammes = 0
        private var totalChannelsFlushed = 0

        fun addProgramme(channelId: String, p: Programme) {
            val list = buffers.getOrPut(channelId) { ArrayList(64) }
            list.add(p)
            bufferedProgrammes += 1
            totalProgrammes += 1
            if (bufferedProgrammes >= FLUSH_AT_TOTAL_PROGRAMMES) {
                flushOldest()
            }
        }

        /** Flush the half of the channels with the LARGEST buffers
         *  first — releases the most memory per syscall. */
        private fun flushOldest() {
            if (buffers.isEmpty()) return
            val sorted = buffers.entries.sortedByDescending { it.value.size }
            val cut = sorted.size / 2 + 1
            val toFlush = sorted.take(cut)
            for ((id, programmes) in toFlush) {
                appendToChannelFile(id, programmes)
                bufferedProgrammes -= programmes.size
                buffers.remove(id)
                totalChannelsFlushed += 1
            }
        }

        private fun appendToChannelFile(channelId: String, programmes: List<Programme>) {
            try {
                val f = fileFor(ctx, channelId)
                // APPEND mode — the same channel id can be flushed
                // multiple times as the parser walks the file.  We
                // wrap each chunk in its own GZIPOutputStream which
                // gzip concatenates transparently on read.
                java.io.FileOutputStream(f, /* append = */ true).use { raw ->
                    GZIPOutputStream(raw).bufferedWriter(Charsets.UTF_8).use { w ->
                        for (p in programmes) {
                            val o = JSONObject()
                            o.put("t", p.title)
                            if (!p.description.isNullOrBlank()) o.put("d", p.description)
                            o.put("s", p.startMs / 1000L)
                            o.put("e", p.stopMs / 1000L)
                            w.write(o.toString())
                            w.write("\n")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "append channel=$channelId failed: ${t.message}")
            }
        }

        /** Flush any remaining buffers, stamp schema + .done marker.
         *  Caller MUST invoke this exactly once when the parse ends
         *  (or after catching a parse error — the cache only counts
         *  as committed once the .done file is written). */
        /** Flush any remaining buffers, persist the optional
         *  display-name → id mapping, then stamp schema + .done.
         *  Caller MUST invoke this exactly once when the parse
         *  ends (or after catching a parse error — the cache only
         *  counts as committed once the .done file is written).
         *
         *  Pass the [nameMap] captured by the XMLTV parser so the
         *  fast-path boot can re-apply name-based channel-id
         *  patching without re-running the XMLTV parse.  Pass an
         *  empty map for callers (e.g. the lazy network path) that
         *  don't have one. */
        fun finish(nameMap: Map<String, String> = emptyMap()): WriteResult {
            // Final flush of every remaining channel.
            for ((id, programmes) in buffers) {
                if (programmes.isNotEmpty()) {
                    appendToChannelFile(id, programmes)
                    totalChannelsFlushed += 1
                }
            }
            buffers.clear()
            bufferedProgrammes = 0
            // Persist the name map FIRST so any reader of the
            // committed (.done-stamped) cache always sees a
            // consistent (channels + name-map) snapshot.
            if (nameMap.isNotEmpty()) saveNameMap(ctx, nameMap)
            // Stamp + .done in that order so a partial finish is
            // never treated as a complete cache.
            try {
                schemaFile(ctx).writeText(CURRENT_SCHEMA_VERSION.toString())
                tsFile(ctx).writeText(System.currentTimeMillis().toString())
                doneFile(ctx).writeText(System.currentTimeMillis().toString())
            } catch (t: Throwable) {
                Log.w(TAG, "stamp failed: ${t.message}")
            }
            Log.i(
                TAG,
                "streamed write committed: $totalChannelsFlushed channels, " +
                    "$totalProgrammes programmes",
            )
            return WriteResult(totalChannelsFlushed, totalProgrammes)
        }

        /** Abort without writing the .done marker, leaving the
         *  cache as "missing" so the next boot retries the parse. */
        fun abort() {
            buffers.clear()
            bufferedProgrammes = 0
            runCatching { doneFile(ctx).delete() }
        }

        data class WriteResult(val channelsFlushed: Int, val totalProgrammes: Int)

        companion object {
            /** Flush whenever the total in-memory programme count
             *  exceeds this number.  At ~250 bytes/programme this is
             *  a ~2.5 MB working set — comfortable headroom even on
             *  the 256 MB heap user's box. */
            private const val FLUSH_AT_TOTAL_PROGRAMMES = 10_000
        }
    }

    // ─── Legacy wrappers for callers that haven't migrated ──────

    /** Legacy bulk save — used by the WorkManager refresh path
     *  when the worker chose to accumulate in-memory before
     *  persisting (deprecated — workers now use the streaming
     *  writer too).  Implementation falls through to per-channel
     *  writes. */
    fun save(ctx: Context, epg: Map<String, List<Programme>>) {
        // Wipe and recreate.
        delete(ctx)
        cacheDir(ctx).mkdirs()
        for ((id, programmes) in epg) {
            if (programmes.isNotEmpty()) saveChannel(ctx, id, programmes)
        }
        try {
            schemaFile(ctx).writeText(CURRENT_SCHEMA_VERSION.toString())
            tsFile(ctx).writeText(System.currentTimeMillis().toString())
            doneFile(ctx).writeText(System.currentTimeMillis().toString())
        } catch (t: Throwable) {
            Log.w(TAG, "stamp failed: ${t.message}")
        }
        Log.i(TAG, "save() wrote ${epg.size} channels (legacy path)")
    }

    /** Legacy bulk load.  Returns null instead of an empty map
     *  when no cache exists, so callers can branch on "do I have
     *  any EPG to hydrate".
     *
     *  WARNING: This loads EVERY channel into memory.  Prefer
     *  [loadChannel] for the per-channel UI lookup path. */
    fun load(ctx: Context): Map<String, List<Programme>>? {
        if (!exists(ctx)) return null
        val dir = cacheDir(ctx)
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl.gz") } ?: return null
        if (files.isEmpty()) return null
        // Build a sha1→id reverse map by streaming the schema file
        // doesn't help us recover the channel id from the hash, so
        // this legacy path can only return what was indexed under
        // the same hash function.  We keep it as a NO-OP returning
        // an empty map so callers that previously merged it into
        // bundle.epg don't crash, but actual EPG access MUST go
        // through [loadChannel] which knows the channel id.
        Log.i(TAG, "load() returning empty map — callers should use loadChannel() (${files.size} files on disk)")
        return emptyMap()
    }

    /**
     * v2.9.12 — Merge a single channel's freshly-fetched programmes
     * into the on-disk cache.  Called by `EpgActivity` whenever
     * the per-channel lazy-load path hits the provider directly.
     */
    @Synchronized
    fun mergeChannel(ctx: Context, channelId: String, programmes: List<Programme>) {
        if (channelId.isBlank() || programmes.isEmpty()) return
        // Ensure the schema stamp exists so subsequent `exists()`
        // calls correctly report the cache as present even if the
        // user has only ever fetched a handful of channels via
        // the lazy network path.
        cacheDir(ctx).mkdirs()
        saveChannel(ctx, channelId, programmes)
        try {
            if (!schemaFile(ctx).exists()) {
                schemaFile(ctx).writeText(CURRENT_SCHEMA_VERSION.toString())
            }
            tsFile(ctx).writeText(System.currentTimeMillis().toString())
            doneFile(ctx).writeText(System.currentTimeMillis().toString())
        } catch (t: Throwable) {
            Log.w(TAG, "mergeChannel stamp failed: ${t.message}")
        }
    }
}
