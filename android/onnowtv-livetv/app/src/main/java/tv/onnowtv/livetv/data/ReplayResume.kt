package tv.onnowtv.livetv.data

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import tv.onnowtv.livetv.PlayerActivity

/** Where you stopped in a catch-up recording, so it can be continued later (player prompt + Continue Watching). */
object ReplayResume {
    class Parts(val prefix: String, val totalMs: Long, val start: String, val tail: String) { val id get() = "$tail@$start@$totalMs" }
    class Entry(val id: String, val url: String, val title: String, val subtitle: String, val logo: String?, val sport: String?,
                val positionMs: Long, val durationMs: Long, val updatedAt: Long, val json: JSONObject)

    private val SHAPE = Regex("^(.*/timeshift/[^/]+/[^/]+/)(\\d+)/(\\d{4}-\\d{2}-\\d{2}:\\d{2}-\\d{2})/(.+)$")
    private const val KEEP_MS = 3 * 86_400_000L   // recordings are only kept by the service for a few days
    fun parse(url: String?): Parts? = SHAPE.find(url.orEmpty())?.let { Parts(it.groupValues[1], it.groupValues[2].toLong() * 60_000L, it.groupValues[3], it.groupValues[4]) }
    private fun p(ctx: Context) = ctx.getSharedPreferences("replay_resume", Context.MODE_PRIVATE)

    fun save(ctx: Context, intent: Intent, positionMs: Long) {
        val url = intent.getStringExtra(PlayerActivity.EXTRA_URL) ?: return
        val parts = parse(url) ?: return
        if (positionMs > parts.totalMs - 3 * 60_000L) { remove(ctx, parts.id); return }   // finished
        if (positionMs < 30_000L) return
        val title = intent.getStringExtra(PlayerActivity.EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(PlayerActivity.EXTRA_SUBTITLE).orEmpty()
        val teams = intent.getStringExtra(PlayerActivity.EXTRA_HOME_NAME) != null
        val o = JSONObject().put("url", url).put("title", title).put("subtitle", subtitle).put("pos", positionMs).put("dur", parts.totalMs).put("at", System.currentTimeMillis())
            .put("sport", LiveSportsClassifier.programmeBucket(title) ?: LiveSportsClassifier.programmeBucket(subtitle) ?: if (teams) LiveSportsClassifier.OTHER_SPORT else "")
        listOf(PlayerActivity.EXTRA_HOME_NAME, PlayerActivity.EXTRA_HOME_LOGO, PlayerActivity.EXTRA_HOME_COLOR, PlayerActivity.EXTRA_AWAY_NAME, PlayerActivity.EXTRA_AWAY_LOGO,
            PlayerActivity.EXTRA_AWAY_COLOR, PlayerActivity.EXTRA_CHANNEL_LOGO).forEach { k -> intent.getStringExtra(k)?.let { o.put(k, it) } }
        intent.getLongExtra(PlayerActivity.EXTRA_KICKOFF_OFFSET_MS, -1L).takeIf { it > 0 }?.let { o.put(PlayerActivity.EXTRA_KICKOFF_OFFSET_MS, it) }
        p(ctx).edit().putString(parts.id, o.toString()).apply()
    }

    fun get(ctx: Context, id: String): Entry? = runCatching {
        val o = JSONObject(p(ctx).getString(id, null) ?: return null)
        // Gone from the service once the recording is older than its keep window.
        val recorded = parse(o.optString("url"))?.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(it.start)?.time }.getOrNull() } ?: o.optLong("at")
        if (System.currentTimeMillis() - recorded > KEEP_MS) { remove(ctx, id); return null }
        Entry(id, o.getString("url"), o.optString("title"), o.optString("subtitle"), o.optString(PlayerActivity.EXTRA_CHANNEL_LOGO).takeIf { it.isNotBlank() },
            o.optString("sport").takeIf { it.isNotBlank() }, o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o)
    }.getOrNull()

    fun list(ctx: Context): List<Entry> = p(ctx).all.keys.toList().mapNotNull { get(ctx, it) }.sortedByDescending { it.updatedAt }
    fun remove(ctx: Context, id: String) = p(ctx).edit().remove(id).apply()

    /** Opens the recording again at the saved place. */
    fun intent(ctx: Context, e: Entry, fromStart: Boolean = false) = Intent(ctx, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_URL, e.url); putExtra(PlayerActivity.EXTRA_TITLE, e.title); putExtra(PlayerActivity.EXTRA_SUBTITLE, e.subtitle)
        putExtra(PlayerActivity.EXTRA_STOP_ON_EXIT, true)
        if (!fromStart) putExtra(PlayerActivity.EXTRA_RESUME_MS, e.positionMs)
        listOf(PlayerActivity.EXTRA_HOME_NAME, PlayerActivity.EXTRA_HOME_LOGO, PlayerActivity.EXTRA_HOME_COLOR, PlayerActivity.EXTRA_AWAY_NAME, PlayerActivity.EXTRA_AWAY_LOGO,
            PlayerActivity.EXTRA_AWAY_COLOR, PlayerActivity.EXTRA_CHANNEL_LOGO).forEach { k -> e.json.optString(k).takeIf { it.isNotBlank() }?.let { putExtra(k, it) } }
        e.json.optLong(PlayerActivity.EXTRA_KICKOFF_OFFSET_MS, -1L).takeIf { it > 0 }?.let { putExtra(PlayerActivity.EXTRA_KICKOFF_OFFSET_MS, it) }
    }

    fun clock(ms: Long): String { val m = ms / 60_000L; return if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "${m}m" }
}
