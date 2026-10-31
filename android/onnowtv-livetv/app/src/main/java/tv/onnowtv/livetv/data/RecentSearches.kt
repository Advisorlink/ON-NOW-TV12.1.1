package tv.onnowtv.livetv.data

import android.content.Context

/** Last 8 Live TV searches, newest first, plus the last query for reopening search. */
object RecentSearches {
    private const val PREFS = "livetv_search"
    private const val KEY = "recent"
    private const val MAX = 8

    fun list(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

    fun add(ctx: Context, query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val next = (listOf(q) + list(ctx).filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString("\n")).apply()
    }

    fun last(ctx: Context): String = list(ctx).firstOrNull().orEmpty()
}
