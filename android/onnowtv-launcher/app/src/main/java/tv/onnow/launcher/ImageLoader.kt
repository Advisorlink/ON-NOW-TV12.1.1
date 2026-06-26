package tv.onnow.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tiny image loader for the launcher — pulls remote JPEGs into an
 * ImageView with disk + memory cache.
 *
 * Why hand-rolled instead of Glide/Coil?  The launcher's APK budget
 * is tight (we want a sub-3 MB binary), and we only ever load 6 tile
 * images + 1 wallpaper at a time.  A 50-line loader saves ~1.5 MB of
 * library overhead.
 *
 * Cache strategy:
 *   - Memory: ConcurrentHashMap<String, Bitmap>, no eviction (we have
 *     at most ~12 images cached at any time)
 *   - Disk:   ctx.cacheDir/launcher-images/<sha-of-url>
 *
 * Thread model:
 *   - Network + disk on a single-thread executor
 *   - Bitmap decode on the same thread
 *   - ImageView.setImageBitmap on the main thread via Handler
 */
object ImageLoader {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vesper-img-loader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memCache = ConcurrentHashMap<String, Bitmap>()
    private val client = tv.onnow.launcher.net.ResilientHttp.client.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Load a URL into an ImageView.  If the URL is null/blank, the
     * placeholder is applied immediately.  Otherwise the function
     * returns instantly and the image lands on the view when ready.
     *
     * v2.10.56 — INSTANT-LOAD path.  If the URL matches a bundled
     * built-in asset (see BuiltInAssets), the bundled drawable is
     * applied IMMEDIATELY so cold-start no longer flashes grey
     * placeholders for ~2 seconds.  The network fetch still runs
     * in the background to pick up any operator-changed image,
     * and overlays only if the URL hasn't been served from
     * mem-cache yet.
     */
    fun load(view: ImageView, url: String?, placeholderRes: Int? = null) {
        if (url.isNullOrBlank()) {
            placeholderRes?.let { view.setImageResource(it) }
            return
        }
        // Memory hit — synchronous.
        memCache[url]?.let {
            view.setImageBitmap(it)
            return
        }
        // INSTANT bundled built-in (e.g. dock tile, hero, V2 AI bg)
        // — paint it before the placeholder, so cold-start is
        // visually indistinguishable from a fully cached run.
        val builtin = BuiltInAssets.resourceForUrl(url)
        if (builtin != null) {
            view.setImageResource(builtin)
        } else {
            placeholderRes?.let { view.setImageResource(it) }
        }
        // Tag so a stale view doesn't paint a slow earlier load.
        view.tag = url
        executor.submit {
            val bmp = fetch(view.context, url)
            if (bmp != null) {
                memCache[url] = bmp
                mainHandler.post {
                    if (view.tag == url) view.setImageBitmap(bmp)
                }
            }
            // If the network fetch fails AND we already painted a
            // built-in, leave the built-in in place — better than
            // a grey placeholder.  No-op needed; the early
            // setImageResource() above stays in effect.
        }
    }

    /**
     * Load a URL and call back on the main thread with the bitmap (or
     * null on failure).  Used for the fullscreen wallpaper where we
     * want to know when the image is ready so we can fade it in.
     *
     * v2.10.56 — If a bundled built-in exists for this URL, decode
     * THAT immediately and hand it to the caller so the wallpaper
     * fade-in animation can start on first frame instead of after
     * a 2-second network round-trip.  Then re-fetch from the URL
     * in the background and call back a SECOND time with the
     * network version if it differs (most callers' callback is
     * idempotent — they just swap the wallpaper bitmap, which is
     * cheap and invisible if it's the same image).
     */
    fun loadBitmap(ctx: Context, url: String?, callback: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) {
            callback(null); return
        }
        memCache[url]?.let { callback(it); return }
        // INSTANT built-in fallback.
        val builtin = BuiltInAssets.resourceForUrl(url)
        if (builtin != null) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeResource(ctx.resources, builtin)
                if (bmp != null) callback(bmp)
            } catch (_: Throwable) { /* fall through to network */ }
        }
        executor.submit {
            val bmp = fetch(ctx, url)
            if (bmp != null) memCache[url] = bmp
            mainHandler.post { callback(bmp) }
        }
    }

    private fun fetch(ctx: Context, url: String): Bitmap? {
        val dir = File(ctx.cacheDir, "launcher-images").apply { mkdirs() }
        val key = url.hashCode().toString().replace("-", "n")
        val file = File(dir, key)
        // Disk hit.
        if (file.exists() && file.length() > 0) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        // Network.
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                // Persist to disk.
                FileOutputStream(file).use { it.write(bytes) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (t: Throwable) {
            android.util.Log.w("VesperImg", "fetch failed: $url", t)
            null
        }
    }
}
