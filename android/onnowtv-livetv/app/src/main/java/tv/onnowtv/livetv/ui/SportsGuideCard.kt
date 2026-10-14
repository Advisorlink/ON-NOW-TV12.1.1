package tv.onnowtv.livetv.ui

import android.widget.LinearLayout
import tv.onnowtv.livetv.data.LiveSportsClassifier
import tv.onnowtv.livetv.data.SportsLiveGuide
import tv.onnowtv.livetv.ui.SportsUi as UI

object SportsGuideCard {
    fun bind(root: LinearLayout, entry: SportsLiveGuide.Entry, play: () -> Unit) {
        val ctx = root.context; val pad = UI.dp(ctx, 14f)
        root.setPadding(pad, pad, pad, pad)
        root.addView(UI.identify(UI.eyebrow(ctx, "● LIVE · ${LiveSportsClassifier.labelOf(entry.bucket)}", UI.LIVE), "guide-live-${entry.key}"))
        root.addView(UI.identify(UI.text(ctx, entry.programme.title, 18f, bold = true).apply { maxLines = 3 }, "guide-title-${entry.key}"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UI.dp(ctx, 9f); bottomMargin = UI.dp(ctx, 10f) })
        root.addView(UI.row(ctx, UI.dp(ctx, 8f)).apply {
            addView(UI.logo(ctx, entry.channel.logoUrl, UI.dp(ctx, 34f)))
            addView(UI.identify(UI.text(ctx, listOfNotNull(entry.channel.lcn, entry.channel.name).joinToString(" · "), 12f, UI.DIM).apply { maxLines = 2 }, "guide-channel-${entry.key}"), LinearLayout.LayoutParams(0, -2, 1f))
        })
        val fixture = entry.fixtureJson?.let { org.json.JSONObject(it) }
        root.addView(UI.identify(UI.text(ctx, tv.onnowtv.livetv.data.SportsEventMetadata.eventLabel(fixture), 12f, UI.ACCENT), "guide-event-date-${entry.key}"))
        root.addView(UI.identify(UI.text(ctx, tv.onnowtv.livetv.data.SportsEventMetadata.programmeLabel(entry.programme), 11f, UI.DIM), "guide-broadcast-date-${entry.key}"))
        root.addView(UI.identify(UI.pill(ctx, "▶ Watch live", true, onClick = play), "guide-watch-${entry.key}"),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = UI.dp(ctx, 12f) })
    }
}