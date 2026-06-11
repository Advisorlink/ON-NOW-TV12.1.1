package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Disk-cached copy of the last-fetched Xtream bundle.  Writes the
 * raw gzipped JSON response to `filesDir/bundle.json.gz` and
 * reads it back on subsequent app boots.
 *
 * Why this exists:
 *   The user's #1 frustration was that EVERY app launch showed
 *   the loader.  After the bundle has been fetched once we hold a
 *   copy on disk so subsequent boots open straight into the EPG —
 *   no loader screen, no network round-trip on the critical path.
 *   A background refresh runs after the EPG opens to keep the
 *   cache fresh.
 *
 * Files:
 *   - `bundle.json.gz` — the raw response bytes (gzipped JSON).
 *   - `bundle.timestamp` — unix-ms write time as text.
 */
object BundleCache {
    private const val TAG = "BundleCache"
    private const val FILE_NAME = "bundle.json.gz"
    private const val TS_FILE_NAME = "bundle.timestamp"

    /** Maximum age before we consider the cache stale enough to
     *  show a "refreshing" hint.  We still USE the cache; we just
     *  trigger a background refresh sooner. */
    const val FRESH_MS = 2L * 60L * 60L * 1000L  // 2 hours

    fun fileFor(context: Context): File = File(context.filesDir, FILE_NAME)
    fun timestampFile(context: Context): File = File(context.filesDir, TS_FILE_NAME)

    /** Persist the raw gzipped JSON bytes.  Atomic via tmp file. */
    fun save(context: Context, gzBytes: ByteArray) {
        try {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeBytes(gzBytes)
            tmp.renameTo(fileFor(context))
            timestampFile(context).writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "saved ${gzBytes.size} bytes")
        } catch (t: Throwable) {
            Log.w(TAG, "save failed: ${t.message}")
        }
    }

    /** Persist a JSON string (we'll gzip it before writing). */
    fun saveJson(context: Context, json: String) {
        try {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            GZIPOutputStream(tmp.outputStream()).use { gz ->
                gz.write(json.toByteArray(Charsets.UTF_8))
            }
            tmp.renameTo(fileFor(context))
            timestampFile(context).writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "saved json: ${json.length} chars")
        } catch (t: Throwable) {
            Log.w(TAG, "saveJson failed: ${t.message}")
        }
    }

    /** Read the cached JSON back.  Returns null if no cache. */
    fun loadJson(context: Context): String? {
        val f = fileFor(context)
        if (!f.exists()) return null
        return try {
            GZIPInputStream(f.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.message}")
            null
        }
    }

    fun ageMs(context: Context): Long {
        val ts = timestampFile(context)
        if (!ts.exists()) return Long.MAX_VALUE
        val written = ts.readText().trim().toLongOrNull() ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - written
    }

    fun exists(context: Context): Boolean = fileFor(context).exists()

    /** v2.9.11 — Wipe the cache (used on sign-out so a stale
     *  bundle baked with the previous user's stream URLs is
     *  never reused after the new user signs in). */
    fun delete(context: Context) {
        try { fileFor(context).delete() } catch (_: Throwable) {}
        try { timestampFile(context).delete() } catch (_: Throwable) {}
    }
}
