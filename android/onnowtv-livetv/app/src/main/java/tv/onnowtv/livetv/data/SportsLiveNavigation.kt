package tv.onnowtv.livetv.data

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import okio.ByteString.Companion.encodeUtf8
import tv.onnowtv.livetv.GameActivity
import tv.onnowtv.livetv.SportsLiveDetailsActivity

object SportsLiveNavigation {
    fun matchIntent(context: Context, game: JSONObject): Intent = Intent(context, GameActivity::class.java).apply {
        putExtra(GameActivity.EXTRA_SPORT, game.optString("sport")); putExtra(GameActivity.EXTRA_LEAGUE, game.optString("league"))
        putExtra(GameActivity.EXTRA_ID, game.optString("id")); putExtra(GameActivity.EXTRA_SEED, game.toString())
    }
    fun hasFixture(game: JSONObject) = listOf("sport", "league", "id").all { !game.isNull(it) && game.optString(it).isNotBlank() && game.optString(it) != "null" }
    fun scope(channels: List<Channel>): String = channels.distinctBy { it.id }.sortedBy { it.id }.joinToString("\n") { "${it.id}|${it.streamUrl}" }.encodeUtf8().sha256().hex()
    fun intent(context: Context, event: SportsLiveEvents.Event): Intent {
        val fixture = event.fixtureJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf(::hasFixture)
        if (fixture != null) return matchIntent(context, fixture)
        return Intent(context, SportsLiveDetailsActivity::class.java).apply {
            putExtra("event-title", event.title); putExtra("event-sport", event.bucket)
            putStringArrayListExtra("event-channel-ids", ArrayList(event.sources.map { it.channel.id }.distinct()))
            putExtra("event-account-scope", scope(event.sources.map { it.channel }))
        }
    }
}