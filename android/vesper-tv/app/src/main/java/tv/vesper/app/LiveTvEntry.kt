package tv.vesper.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONObject

/**
 * Bridge to the Live TV module when it is bundled into this APK (ON NOW one-app build).
 * Resolved by intent action inside our own package, so the standalone Vesper build —
 * which has no Live TV module — compiles unchanged and simply reports "not available".
 */
object LiveTvEntry {
    const val ACTION_OPEN = "tv.onnowtv.one.LIVE_TV"
    const val ACTION_SETTINGS = "tv.onnowtv.one.LIVE_TV_SETTINGS"
    private const val AUTH_PREFS = "v2_livetv_auth"   // must match tv.onnowtv.livetv.data.AuthStore

    private fun intent(ctx: Context, action: String): Intent? {
        val i = Intent(action).setPackage(ctx.packageName)
        return if (ctx.packageManager.resolveActivity(i, 0) != null) i else null
    }

    fun available(ctx: Context): Boolean = intent(ctx, ACTION_OPEN) != null

    fun open(activity: Activity, settings: Boolean = false) {
        val i = intent(activity, if (settings) ACTION_SETTINGS else ACTION_OPEN)
        if (i == null) { Toast.makeText(activity, "Live TV isn't included in this build", Toast.LENGTH_SHORT).show(); return }
        activity.startActivity(i)
    }

    /** Hand the ON NOW login to Live TV: the operator-mapped Xtream creds if present, else the typed ones. */
    /** Sign-in: load channels + guide now (shows the Live TV loading screen, then returns). */
    fun prefetch(activity: Activity, fullReload: Boolean = false) {
        val i = intent(activity, ACTION_OPEN) ?: return
        i.putExtra("reborn_prefetch_only", true)
        if (fullReload) i.putExtra("full_reload", true)
        activity.startActivity(i)
    }

    fun onLogin(ctx: Context, username: String, password: String, iptvJson: String?) {
        if (!available(ctx)) return
        var u = username; var p = password
        try {
            val o = JSONObject(iptvJson ?: "{}")
            val mu = o.optString("xtream_username").ifBlank { o.optString("username") }
            val mp = o.optString("xtream_password").ifBlank { o.optString("password") }
            if (mu.isNotBlank() && mp.isNotBlank()) { u = mu; p = mp }
        } catch (_: Throwable) {}
        val prefs = ctx.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("xtream_username", "") == u && prefs.getString("xtream_password", "") == p) return
        prefs.edit().putString("xtream_username", u).putString("xtream_password", p)
            .putString("session_generation", System.currentTimeMillis().toString()).apply()
    }

    fun onLogout(ctx: Context) {
        ctx.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
