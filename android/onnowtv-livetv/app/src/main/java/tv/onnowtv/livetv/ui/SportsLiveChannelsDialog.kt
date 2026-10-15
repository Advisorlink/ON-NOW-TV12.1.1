package tv.onnowtv.livetv.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.SportsPlayback
import tv.onnowtv.livetv.data.SportsEventMetadata
import tv.onnowtv.livetv.data.SportsLiveEvents
import tv.onnowtv.livetv.ui.SportsUi as UI

object SportsLiveChannelsDialog {
    fun show(ctx: Activity, event: SportsLiveEvents.Event, openMatch: (JSONObject) -> Unit) {
        val body = UI.col(ctx, UI.dp(ctx, 12f)).apply {
            setBackgroundColor(UI.BG); setPadding(UI.dp(ctx, 20f), UI.dp(ctx, 20f), UI.dp(ctx, 20f), UI.dp(ctx, 20f))
        }
        UI.identify(body, "live-event-channels")
        body.addView(UI.identify(UI.text(ctx, event.title, 25f, bold = true), "live-event-title"))
        val fixture = event.fixtureJson?.let(::JSONObject)
        body.addView(UI.identify(UI.text(ctx, SportsEventMetadata.eventLabel(fixture), 13f, UI.ACCENT), "live-event-date"))
        body.addView(UI.identify(UI.text(ctx, "${event.sources.size} channel choices", 14f, UI.DIM), "live-event-channel-count"))
        val dialog = AlertDialog.Builder(ctx).setView(ScrollView(ctx).apply { addView(body) }).setNegativeButton("Back", null).create()
        event.sources.forEach { entry ->
            val label = listOfNotNull(entry.channel.lcn, entry.channel.name).joinToString(" · ")
            val button = UI.identify(UI.pill(ctx, "▶ $label") {
                val current = BundleHolder.current?.channels?.firstOrNull { it.id == entry.channel.id && it.streamUrl == entry.channel.streamUrl }
                if (current != null && entry.programme.isLiveAt(System.currentTimeMillis())) {
                    dialog.dismiss(); SportsPlayback.open(ctx, current, subtitle = event.title)
                } else Toast.makeText(ctx, "This broadcast ended or the guide account changed", Toast.LENGTH_SHORT).show()
            }, "live-event-channel-${entry.channel.id}").apply { setSingleLine(false); maxLines = 3; setHorizontallyScrolling(false) }
            body.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
        if (fixture != null) body.addView(UI.identify(UI.pill(ctx, "Match centre  ›") { dialog.dismiss(); openMatch(fixture) }, "live-event-match-centre"))
        dialog.show(); UI.identify(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), "live-event-channels-back")
        dialog.window?.setLayout((ctx.resources.displayMetrics.widthPixels * .88f).toInt().coerceAtMost(UI.dp(ctx, 720f)), -2)
    }
}