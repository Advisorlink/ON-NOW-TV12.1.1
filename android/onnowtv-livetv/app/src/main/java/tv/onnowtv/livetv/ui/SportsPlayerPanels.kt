package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import coil.load
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsHubApi.objects
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp

object SportsPlayerPanels {
    fun portrait(ctx: Context, player: JSONObject, team: JSONObject?, size: Int, slot: String): FrameLayout = UI.identify(FrameLayout(ctx), "$slot-portrait-${player.optString("id")}").apply {
        val color = UI.parseColor(team?.optString("color"), UI.ACCENT)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color and 0xffffff or 0x66000000, UI.CARD)).apply { cornerRadius = dp(ctx, 12f).toFloat() }
        clipToOutline = true; layoutParams = LinearLayout.LayoutParams(size, size)
        val crest = UI.logo(ctx, Data.value(team, "logo"), size).apply { background = null }
        addView(crest, FrameLayout.LayoutParams(-1, -1))
        val url = Data.value(player, "headshot")
        if (url.isNotBlank()) addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(url) { crossfade(false); listener(onSuccess = { _, _ -> crest.visibility = android.view.View.GONE }) }; contentDescription = Data.value(player, "name") }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun team(game: JSONObject, player: JSONObject) = listOf("home", "away").mapNotNull { game.optJSONObject(it) }.firstOrNull { it.optString("id") == player.optString("teamId") }
    fun active(ctx: Context, game: JSONObject): LinearLayout? {
        val sit = game.optJSONObject("situation") ?: return null
        val entries = listOf("pitcherPlayer" to "PITCHING", "batterPlayer" to "AT BAT").mapNotNull { (key, role) -> sit.optJSONObject(key)?.let { role to it } }
        if (entries.isEmpty()) return null
        return UI.col(ctx, dp(ctx, 10f)).apply {
            entries.forEach { (role, p) -> addView(UI.row(ctx, dp(ctx, 12f)).apply {
                addView(portrait(ctx, p, team(game, p), dp(ctx, 56f), role.lowercase().replace(' ', '-')))
                addView(UI.col(ctx, dp(ctx, 5f)).apply { addView(UI.eyebrow(ctx, role, UI.ACCENT)); addView(UI.text(ctx, Data.value(p, "name"), 20f, bold = true)); addView(UI.text(ctx, Data.value(p, "line"), 11f, UI.DIM).apply { maxLines = 2 }) }, LinearLayout.LayoutParams(0, -2, 1f))
            }) }
        }
    }
    fun players(ctx: Context, game: JSONObject, selected: String, select: (String) -> Unit): LinearLayout {
        val content = UI.col(ctx, dp(ctx, 12f)); content.addView(UI.eyebrow(ctx, "Players", UI.ACCENT))
        val filter = UI.row(ctx, dp(ctx, 7f))
        listOf("both", "away", "home").forEach { side ->
            val t = game.optJSONObject(side)
            val pill = UI.row(ctx, dp(ctx, 5f)).apply { setPadding(dp(ctx, 8f), dp(ctx, 6f), dp(ctx, 8f), dp(ctx, 6f)) }
            UI.identify(pill, "players-filter-$side"); UI.focusable(pill, dp(ctx, 22f).toFloat(), base = if (side == selected) UI.CARD_FOCUS else UI.CARD)
            if (t != null) pill.addView(UI.logo(ctx, Data.value(t, "logo"), dp(ctx, 23f)))
            pill.addView(UI.text(ctx, if (side == "both") "Both" else Data.abbreviation(t), 12f, if (side == selected) UI.ACCENT else UI.DIM, bold = true))
            pill.setOnClickListener { select(side) }; filter.addView(pill)
        }; content.addView(filter)
        val data = game.optJSONObject("players")
        val rows = listOf("away", "home").filter { selected == "both" || selected == it }.flatMap { side -> data?.optJSONArray(side).objects().filter { it.optBoolean("active", true) }.take(if (selected == "both") 4 else 10).map { side to it } }
        if (rows.isEmpty()) content.addView(UI.text(ctx, "Player lines are not available yet", 12f, UI.DIM))
        rows.forEachIndexed { index, (side, p) -> val t = game.optJSONObject(side); content.addView(UI.row(ctx, dp(ctx, 11f)).apply {
            addView(portrait(ctx, p, t, dp(ctx, 47f), "leader-$side-$index"))
            addView(UI.col(ctx, dp(ctx, 5f)).apply { addView(UI.text(ctx, Data.value(p, "name"), 15f, bold = true)); addView(UI.text(ctx, "${Data.abbreviation(t)} · ${Data.value(p, "pos")}  ${Data.value(p, "line")}", 11f, UI.DIM).apply { maxLines = 2 }) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(UI.logo(ctx, Data.value(t, "logo"), dp(ctx, 24f)))
        }) }
        return content
    }
}