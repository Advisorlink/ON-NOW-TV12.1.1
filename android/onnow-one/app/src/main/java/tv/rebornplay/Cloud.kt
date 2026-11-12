package tv.rebornplay

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remembers this box's login on the server (so a fresh install signs itself in) and keeps every profile's
 * lists, Continue Watching and resume points against the service login, not the device.
 */
object Cloud {
    private val stores = listOf("reborn_library", "reborn_cw", "reborn_notify", "reborn_actors", "onnowtv_progress")
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_cloud", Context.MODE_PRIVATE)
    private fun deviceId(ctx: Context): String = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty()

    // The save service lives on the published app; the main address is tried first in case it is moved there later.
    private val hosts = listOf(RebornApi.BASE, "https://rebrand-app-5.emergent.host")
    @Volatile private var good: String? = null
    private fun post(path: String, body: JSONObject): JSONObject? {
        for (h in listOfNotNull(good) + hosts.filter { it != good }) { val r = postTo(h, path, body); if (r != null) { good = h; return r } }
        return null
    }
    private fun postTo(host: String, path: String, body: JSONObject): JSONObject? = runCatching {
        val c = (URL(host + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 20_000; setRequestProperty("Content-Type", "application/json")
        }
        try { c.outputStream.use { it.write(body.toString().toByteArray()) }; if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null } finally { c.disconnect() }
    }.getOrNull()

    private fun creds(ctx: Context) = JSONObject().put("username", Gorilla.user(ctx)).put("password", Gorilla.pass(ctx))

    suspend fun remember(ctx: Context) = withContext(Dispatchers.IO) { val d = deviceId(ctx); if (d.length >= 8) post("/api/reborn/device/remember", creds(ctx).put("device_id", d)); Unit }
    suspend fun forget(ctx: Context) = withContext(Dispatchers.IO) { val d = deviceId(ctx); if (d.length >= 8) post("/api/reborn/device/forget", JSONObject().put("device_id", d)); Unit }
    /** Fresh install on a box we've seen before → its saved login. */
    suspend fun recall(ctx: Context): Pair<String, String>? = withContext(Dispatchers.IO) {
        val d = deviceId(ctx); if (d.length < 8) return@withContext null
        val r = post("/api/reborn/device/recall", JSONObject().put("device_id", d)) ?: return@withContext null
        if (!r.optBoolean("found")) null else r.optString("username") to r.optString("password")
    }

    private fun snapshot(ctx: Context): JSONObject {
        val o = JSONObject().put("profiles", ctx.getSharedPreferences("reborn_profiles", Context.MODE_PRIVATE).getString("reborn-profiles", "[]"))
        stores.forEach { s -> o.put(s, JSONObject(ctx.getSharedPreferences(s, Context.MODE_PRIVATE).all.filterValues { it is String }.mapValues { it.value.toString() })) }
        return o
    }
    private fun restore(ctx: Context, data: JSONObject) {
        data.optString("profiles").takeIf { it.startsWith("[") }?.let { ctx.getSharedPreferences("reborn_profiles", Context.MODE_PRIVATE).edit().putString("reborn-profiles", it).apply() }
        stores.forEach { s -> data.optJSONObject(s)?.let { src -> val e = ctx.getSharedPreferences(s, Context.MODE_PRIVATE).edit().clear(); src.keys().forEach { k -> e.putString(k, src.optString(k)) }; e.apply() } }
    }
    private fun wipe(ctx: Context) {
        ctx.getSharedPreferences("reborn_profiles", Context.MODE_PRIVATE).edit().clear().apply()
        stores.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply() }
    }

    /** Sign-in / app start: bring down whatever is saved against this login if it is newer than what this box last saw. */
    suspend fun pull(ctx: Context) = withContext(Dispatchers.IO) {
        val user = Gorilla.user(ctx).trim().lowercase(); if (user.isBlank()) return@withContext
        val owner = p(ctx).getString("owner", null)
        // Data left on the box by a different login never leaks into this one.
        if (owner != null && owner != user) { wipe(ctx); p(ctx).edit().clear().apply() }
        val r = post("/api/reborn/sync/pull", creds(ctx)) ?: return@withContext
        p(ctx).edit().putString("owner", user).apply()
        if (!r.optBoolean("found")) return@withContext
        val at = r.optLong("updated_at")
        val dirty = owner == user && snapshot(ctx).toString().hashCode() != p(ctx).getInt("hash", 0)
        if (at > p(ctx).getLong("seen", 0L) && !dirty) {
            r.optJSONObject("data")?.let { restore(ctx, it) }
            p(ctx).edit().putLong("seen", at).putInt("hash", snapshot(ctx).toString().hashCode()).apply()
        }
    }

    /** Called whenever the app goes to the background: upload if anything changed. */
    fun pushIfChanged(ctx: Context) {
        val app = ctx.applicationContext
        if (RebornSession.token(app) == null) return
        Thread {
            runCatching {
                val user = Gorilla.user(app).trim().lowercase()
                if (user.isBlank() || p(app).getString("owner", null) != user) return@runCatching
                val snap = snapshot(app); val h = snap.toString().hashCode()
                if (h == p(app).getInt("hash", 0)) return@runCatching
                val r = post("/api/reborn/sync/push", creds(app).put("data", snap)) ?: return@runCatching
                p(app).edit().putInt("hash", h).putLong("seen", r.optLong("updated_at")).apply()
            }
        }.start()
    }
}
