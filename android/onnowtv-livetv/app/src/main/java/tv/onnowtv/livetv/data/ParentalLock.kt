package tv.onnowtv.livetv.data

import android.content.Context
import java.security.MessageDigest

/** Parental lock for Live TV categories: locked categories (and their
 *  channels) are hidden everywhere until unlocked with the PIN. */
object ParentalLock {
    private const val PREFS = "parental_lock"
    private const val KEY_PIN = "pin_sha256"
    private const val KEY_LOCKED = "locked_category_ids"

    /** Locked categories shown for the rest of this visit after the PIN. */
    @Volatile var sessionUnlocked = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hash(pin: String) = MessageDigest.getInstance("SHA-256")
        .digest("onnow-pin:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin(ctx: Context) = !prefs(ctx).getString(KEY_PIN, null).isNullOrBlank()
    fun setPin(ctx: Context, pin: String) = prefs(ctx).edit().putString(KEY_PIN, hash(pin)).apply()
    fun checkPin(ctx: Context, pin: String) = prefs(ctx).getString(KEY_PIN, null) == hash(pin)

    fun lockedIds(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_LOCKED, emptySet()).orEmpty().toSet()
    fun lock(ctx: Context, categoryId: String) = prefs(ctx).edit().putStringSet(KEY_LOCKED, lockedIds(ctx) + categoryId).apply()
    fun unlock(ctx: Context, categoryId: String) = prefs(ctx).edit().putStringSet(KEY_LOCKED, lockedIds(ctx) - categoryId).apply()

    /** The bundle the UI should show: locked categories and their channels removed. */
    fun visible(ctx: Context, full: XtreamBundle): XtreamBundle {
        val locked = lockedIds(ctx)
        if (locked.isEmpty() || sessionUnlocked) return full
        return full.copy(
            categories = full.categories.filter { it.id !in locked },
            channels = full.channels.filter { it.categoryId !in locked },
        )
    }
}
