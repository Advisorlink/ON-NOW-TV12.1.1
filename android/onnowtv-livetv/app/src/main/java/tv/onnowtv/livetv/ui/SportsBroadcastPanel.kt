package tv.onnowtv.livetv.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.SportsReplayPolicy
import tv.onnowtv.livetv.data.SportsReplayRepository
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp

object SportsBroadcastPanel {
    fun build(ctx: Context, game: JSONObject, matches: List<GameChannelMatcher.Match>, tune: (List<Channel>, Channel) -> Unit, replay: (String) -> Unit): LinearLayout = UI.col(ctx, dp(ctx, 9f)).apply {
        val state = game.optString("state")
        addView(UI.eyebrow(ctx, when (state) { "pre" -> "Scheduled on your channels"; "post" -> "Broadcast & catch-up"; else -> "Watch on your channels" }, UI.ACCENT))
        if (matches.isEmpty()) addView(UI.text(ctx, "No confirmed channel in your guide yet", 13f, UI.DIM))
        matches.forEach { match ->
            val row = UI.row(ctx, dp(ctx, 11f)).apply { setPadding(dp(ctx, 11f), dp(ctx, 10f), dp(ctx, 11f), dp(ctx, 10f)) }
            UI.identify(row, "game-channel-${match.channel.id}"); UI.focusable(row, dp(ctx, 13f).toFloat())
            row.addView(UI.logo(ctx, match.channel.logoUrl, dp(ctx, 38f)))
            row.addView(UI.col(ctx, dp(ctx, 5f)).apply {
                addView(UI.text(ctx, listOfNotNull(match.channel.lcn, match.channel.name).joinToString(" · "), 14f, bold = true).apply { maxLines = 2 })
                val time = match.programme?.let { java.text.SimpleDateFormat("EEE, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it.startMs)) }
                addView(UI.text(ctx, if (match.confirmed) "Guide confirmed · $time" else "Possible broadcast · guide unconfirmed", 11f, if (match.confirmed) UI.ACCENT else UI.DIM).apply { maxLines = 2 })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(UI.text(ctx, if (state == "in" && match.confirmed) "▶" else "›", 22f, UI.ACCENT, bold = true))
            row.setOnClickListener { tune(matches.map { it.channel }, match.channel) }; addView(row)
            if (state == "post" && match.confirmed) {
                val decision = SportsReplayPolicy.check(game, match)
                if (decision.eligible) {
                    val verified = match.programme?.let { SportsReplayRepository.isVerified(match.channel, it) } == true
                    addView(UI.identify(UI.pill(ctx, if (verified) "↻ Watch replay" else "↻ Check replay") { replay(match.channel.id) }, "game-replay-${match.channel.id}"), LinearLayout.LayoutParams(-2, -2))
                } else addView(UI.text(ctx, decision.reason, 11f, UI.DIM))
            }
        }
    }
}