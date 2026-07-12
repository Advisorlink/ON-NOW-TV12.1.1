package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.onnowtv.livetv.BuildConfig
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * v2.16.12 — Silent cloud backup for the native Live TV app.
 *
 * Every add/remove of a Favourite, Collection or Reminder pushes an
 * opaque JSON snapshot up to /api/livetv/sync/push keyed by
 * SHA-256(host + "|" + username).  Every fresh login pulls the last
 * snapshot down via /api/livetv/sync/pull.
 *
 * We never see the password — the key is derived purely from the
 * username and the (baked-in) host.  If a user logs in with the
 * same Xtream credentials on a new box, their favourites +
 * collections + reminders re-materialise within a couple of
 * seconds.
 *
 * ── Threading model ──────────────────────────────────────────
 * All network I/O runs on a single-thread background executor so
 * the store callers (FavouritesStore.save, CollectionsStore.save,
 * ReminderStore.save) are never blocked.  Debounce works via a
 * shared `pushGeneration` counter — the scheduled task only fires
 * if its generation is still the latest, which lets us collapse a
 * rapid burst of edits into ONE push.
 *
 * ── Merge semantics (user requested UNION on conflict) ──────
 * Favourites: set union of local + remote.
 * Collections: union by id — if the same id exists in both, we
 *   keep the one with the higher `addedAt` and merge their
 *   channelIds sets.
 * Reminders: map union by key — future events take precedence,
 *   expired ones on either side get pruned on the next
 *   `pruneExpired` pass.
 */
object SyncManager {

    private const val TAG = "SyncManager"
    private const val DEBOUNCE_MS = 30_000L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val pushGeneration = AtomicLong(0L)

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ── keys ────────────────────────────────────────────────────

    fun userKey(ctx: Context): String? {
        if (!AuthStore.isSignedIn(ctx)) return null
        val user = AuthStore.username(ctx).lowercase().trim()
        val host = AuthStore.HOST.lowercase().trim()
        if (user.isEmpty()) return null
        return sha256("$host|$user")
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun apiBase(): String {
        // Reuse the same backend URL that the Xtream repository +
        // livestats screen already speak to.  This is baked into
        // XtreamRepository so it stays in sync with the pod's env.
        return XtreamRepository.BACKEND_BASE.trimEnd('/')
    }

    // ── snapshot builder ────────────────────────────────────────

    /** Serialise the entire user-data footprint into a single JSON
     *  object.  Adding a new store means adding a new field here +
     *  a matching branch in [applySnapshot]. */
    fun buildSnapshot(ctx: Context): JSONObject {
        val out = JSONObject()
        out.put("version", 1)

        // Favourites → JSON array of channel ids
        val favs = FavouritesStore.load(ctx)
        out.put("favourites", JSONArray().apply {
            for (id in favs.sorted()) put(id)
        })

        // Collections → JSON array (mirrors CollectionsStore.save shape)
        val cols = CollectionsStore.load(ctx)
        val colArr = JSONArray()
        for (c in cols) {
            colArr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("coverHash", c.coverHash ?: "")
                put("coverUrl", c.coverUrl ?: "")
                put("addedAt", c.addedAt)
                put("channelIds", JSONArray().apply {
                    c.channelIds.forEach { put(it) }
                })
                put("categoryId", c.categoryId)
            })
        }
        out.put("collections", colArr)

        // Reminders → JSON array (drop `firedAt` — per-device transient)
        val rem = ReminderStore.load(ctx)
        val remArr = JSONArray()
        for (r in rem.values) {
            remArr.put(JSONObject().apply {
                put("key", r.key)
                put("channel_id", r.channelId)
                put("channel_name", r.channelName)
                put("channel_logo", r.channelLogo ?: "")
                put("channel_lcn", r.channelLcn ?: "")
                put("title", r.title)
                put("start_ms", r.startMs)
                put("stop_ms", r.stopMs)
            })
        }
        out.put("reminders", remArr)
        return out
    }

    /** Merge a server snapshot INTO local state using UNION semantics.
     *  Never destroys local data; only adds what the cloud has. */
    fun applySnapshot(ctx: Context, snap: JSONObject) {
        try {
            // Favourites — set union
            val remoteFavs = snap.optJSONArray("favourites") ?: JSONArray()
            if (remoteFavs.length() > 0) {
                val local = FavouritesStore.load(ctx)
                for (i in 0 until remoteFavs.length()) {
                    val id = remoteFavs.optString(i)
                    if (id.isNotBlank()) local.add(id)
                }
                FavouritesStore.save(ctx, local)
            }

            // Collections — union by id, keep newer addedAt + union channelIds
            val remoteCols = snap.optJSONArray("collections") ?: JSONArray()
            if (remoteCols.length() > 0) {
                val local = CollectionsStore.load(ctx)
                val byId = local.associateBy { it.id }.toMutableMap()
                for (i in 0 until remoteCols.length()) {
                    val o = remoteCols.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    val remoteIds = mutableListOf<String>()
                    o.optJSONArray("channelIds")?.let { ja ->
                        for (j in 0 until ja.length()) {
                            val s = ja.optString(j)
                            if (s.isNotBlank()) remoteIds.add(s)
                        }
                    }
                    val existing = byId[id]
                    val merged = LibraryCollection(
                        id = id,
                        name = if (existing != null &&
                                existing.addedAt >= o.optLong("addedAt", 0L))
                            existing.name else o.optString("name"),
                        coverHash = (existing?.coverHash?.takeIf { it.isNotBlank() })
                            ?: o.optString("coverHash").ifBlank { null },
                        coverUrl = (existing?.coverUrl?.takeIf { it.isNotBlank() })
                            ?: o.optString("coverUrl").ifBlank { null },
                        addedAt = maxOf(existing?.addedAt ?: 0L,
                            o.optLong("addedAt", 0L)),
                        channelIds = (
                            (existing?.channelIds.orEmpty()) + remoteIds
                        ).distinct(),
                        categoryId = existing?.categoryId?.takeIf { it.isNotBlank() }
                            ?: o.optString("categoryId"),
                    )
                    byId[id] = merged
                }
                CollectionsStore.save(ctx, byId.values.toList()
                    .sortedByDescending { it.addedAt })
            }

            // Reminders — map union by key
            val remoteRem = snap.optJSONArray("reminders") ?: JSONArray()
            if (remoteRem.length() > 0) {
                val local = ReminderStore.load(ctx)
                for (i in 0 until remoteRem.length()) {
                    val o = remoteRem.optJSONObject(i) ?: continue
                    val key = o.optString("key")
                    if (key.isBlank() || local.containsKey(key)) continue
                    local[key] = ReminderStore.Reminder(
                        key = key,
                        channelId = o.optString("channel_id"),
                        channelName = o.optString("channel_name"),
                        channelLogo = o.optString("channel_logo").ifBlank { null },
                        channelLcn = o.optString("channel_lcn").ifBlank { null },
                        title = o.optString("title"),
                        startMs = o.optLong("start_ms", 0L),
                        stopMs = o.optLong("stop_ms", 0L),
                    )
                }
                ReminderStore.pruneExpired(ctx, local)
                ReminderStore.save(ctx, local)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applySnapshot failed: ${t.message}")
        }
    }

    // ── network ─────────────────────────────────────────────────

    /** Immediate one-shot push that bypasses the 30 s debounce.
     *  Wired to the long-press on the sign-out rail button so the
     *  user can force a "Sync now" for cross-device continuity.
     *  Callback fires on the IO thread with true = HTTP 2xx, false
     *  = anything else (network, HTTP error, not signed in).  Any
     *  pending debounced push is also invalidated so we don't POST
     *  the same snapshot twice within a second. */
    fun forcePush(ctx: Context, cb: (Boolean) -> Unit) {
        val ctxApp = ctx.applicationContext
        // Bump generation so any queued debounced push aborts on run.
        pushGeneration.incrementAndGet()
        io.execute {
            try {
                val key = userKey(ctxApp) ?: return@execute cb(false)
                val body = JSONObject().apply {
                    put("user_key", key)
                    put("data", buildSnapshot(ctxApp))
                    put("client_updated_at", System.currentTimeMillis())
                }
                val req = Request.Builder()
                    .url("${apiBase()}/api/livetv/sync/push")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { r ->
                    val ok = r.isSuccessful
                    if (ok) Log.i(TAG, "forcePush OK (${body.toString().length}B)")
                    else Log.w(TAG, "forcePush failed: HTTP ${r.code}")
                    cb(ok)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "forcePush threw: ${t.message}")
                cb(false)
            }
        }
    }

    /** Fire-and-forget push, debounced 30 s.  Every call resets the
     *  clock; the actual HTTP POST only happens once the burst has
     *  settled.  Callable from any thread.  No-op if not signed in. */
    fun pushDebounced(ctx: Context) {
        val ctxApp = ctx.applicationContext
        val gen = pushGeneration.incrementAndGet()
        io.schedule({
            if (gen != pushGeneration.get()) return@schedule // superseded
            try {
                val key = userKey(ctxApp) ?: return@schedule
                val body = JSONObject().apply {
                    put("user_key", key)
                    put("data", buildSnapshot(ctxApp))
                    put("client_updated_at", System.currentTimeMillis())
                }
                val req = Request.Builder()
                    .url("${apiBase()}/api/livetv/sync/push")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        Log.w(TAG, "push failed: HTTP ${r.code}")
                    } else {
                        Log.i(TAG, "push OK (${body.toString().length}B)")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "push threw: ${t.message}")
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    /** One-shot pull.  Callback fires on the IO thread with the
     *  remote snapshot (or null if none / on any error).  Caller is
     *  responsible for hopping back to the UI thread to show a
     *  restore prompt. */
    fun pullOnce(ctx: Context, cb: (JSONObject?) -> Unit) {
        val ctxApp = ctx.applicationContext
        io.execute {
            try {
                val key = userKey(ctxApp) ?: return@execute cb(null)
                val req = Request.Builder()
                    .url("${apiBase()}/api/livetv/sync/pull?user_key=$key")
                    .get()
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        Log.w(TAG, "pull failed: HTTP ${r.code}")
                        return@execute cb(null)
                    }
                    val body = r.body?.string() ?: return@execute cb(null)
                    val root = JSONObject(body)
                    if (!root.optBoolean("found", false)) return@execute cb(null)
                    val data = root.optJSONObject("data") ?: return@execute cb(null)
                    cb(data)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pull threw: ${t.message}")
                cb(null)
            }
        }
    }

    /** Quick heuristic for the restore prompt: does the snapshot
     *  contain any non-trivial data worth restoring? */
    fun isRestorableSnapshot(snap: JSONObject): Boolean {
        val f = (snap.optJSONArray("favourites")?.length() ?: 0)
        val c = (snap.optJSONArray("collections")?.length() ?: 0)
        val r = (snap.optJSONArray("reminders")?.length() ?: 0)
        return f + c + r > 0
    }
}
