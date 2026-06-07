package tv.onnowtv.livetv.data

import android.content.Context

/**
 * Per-device Xtream credentials store (v2.9.5).
 *
 * Saved to a dedicated SharedPreferences file so wiping the auth
 * (sign-out) doesn't touch any of the other native stores
 * (`FavouritesStore`, `CollectionsStore`, etc.).
 *
 * The host / port / scheme are hard-coded to the managed provider
 * (`njala.ddns.me:8443` over HTTPS) per the v2.9.5 product
 * decision — users only enter their own username + password on the
 * login screen.
 */
object AuthStore {
    private const val PREFS = "v2_livetv_auth"
    private const val KEY_USER = "xtream_username"
    private const val KEY_PASS = "xtream_password"

    const val HOST = "njala.ddns.me"
    const val PORT = "8443"
    const val SCHEME = "https"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSignedIn(ctx: Context): Boolean {
        val p = prefs(ctx)
        return !p.getString(KEY_USER, null).isNullOrBlank() &&
                !p.getString(KEY_PASS, null).isNullOrBlank()
    }

    fun username(ctx: Context): String =
        prefs(ctx).getString(KEY_USER, "").orEmpty()

    fun password(ctx: Context): String =
        prefs(ctx).getString(KEY_PASS, "").orEmpty()

    fun saveCredentials(ctx: Context, username: String, password: String) {
        prefs(ctx).edit()
            .putString(KEY_USER, username.trim())
            .putString(KEY_PASS, password.trim())
            .apply()
    }

    /**
     * Full sign-out — clears the saved Xtream credentials AND
     * every piece of cached data that depends on them.  After
     * this, the next launch MUST go back through `LoginActivity`
     * AND the user has to re-enter (and we re-validate) their
     * credentials — the previous behaviour of "credentials linger
     * silently so any garbage gets accepted next time" was a
     * security hole.
     *
     * Cleared:
     *   • SharedPrefs creds (username + password)
     *   • Bundle disk cache (`bundle.json.gz`)
     *   • Priority EPG disk cache (`epg_priority.json.gz`)
     *   • In-memory BundleHolder
     */
    fun signOut(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_USER)
            .remove(KEY_PASS)
            .apply()
        // Wipe disk caches so a stale stream-URL with the old user
        // is never reused on the next sign-in.
        try { BundleCache.delete(ctx) } catch (_: Throwable) {}
        try { EpgCache.delete(ctx) } catch (_: Throwable) {}
        tv.onnowtv.livetv.BundleHolder.current = null
        tv.onnowtv.livetv.BundleHolder.needsBackgroundRefresh = false
    }

    /**
     * Rewrite an Xtream stream URL so it uses the locally-saved
     * username + password instead of whatever creds the backend
     * baked in.  Matches `/live/<USER>/<PASS>/` and `/movie/...`
     * and `/series/...` path styles.  Returns the URL unchanged
     * when no creds are saved (the user hasn't signed in yet) or
     * the URL isn't an Xtream stream URL.
     */
    fun rewriteStreamUrl(ctx: Context, url: String): String {
        if (url.isBlank() || !isSignedIn(ctx)) return url
        val u = username(ctx)
        val p = password(ctx)
        // Standard Xtream path layout is
        //   /<kind>/<user>/<pass>/<id>(.ext)?
        // for kind ∈ { live, movie, series, ts }.  Replace the
        // 2nd + 3rd path segments after `<kind>/` with the saved
        // creds so we don't have to know what `<kind>` is.
        val re = Regex("""/(live|movie|series|ts)/[^/]+/[^/]+/""")
        return re.replace(url) { m ->
            "/${m.groupValues[1]}/$u/$p/"
        }
    }
}
