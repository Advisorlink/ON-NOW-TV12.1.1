package tv.onnowtv.livetv.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp

/** A bounded slice of real feed plays. Stepping backwards can reach the complete timeline. */
class SportsPlayTimeline(context: Context) : LinearLayout(context) {
    private var plays: List<JSONObject> = emptyList()
    private var callback: (Int) -> Unit = {}
    private var windowStart = -1
    private val buttons = mutableListOf<Pair<Int, TextView>>()
    init { orientation = VERTICAL; UI.identify(this, "game-play-timeline") }
    fun setPlays(value: List<JSONObject>, select: (Int) -> Unit) { plays = value; callback = select; windowStart = -1 }
    fun select(index: Int) {
        val start = (index - 7).coerceAtLeast(0)
        if (start != windowStart) {
            windowStart = start; removeAllViews(); buttons.clear()
            addView(UI.eyebrow(context, "PLAY-BY-PLAY", UI.ACCENT), LayoutParams(-1, -2).apply { bottomMargin = dp(context, 8f) })
            plays.drop(start).take(10).forEachIndexed { offset, play ->
                val i = start + offset
                val clock = listOf(play.optString("period").takeUnless { it.isBlank() || it == "null" }?.let { "P$it" }, play.optString("clock").takeUnless { it.isBlank() || it == "null" }).filterNotNull().joinToString(" · ")
                val button = UI.identify(UI.text(context, "${i + 1}  $clock\n${play.optString("text")}", 13f), "game-timeline-play-$i")
                button.setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
                UI.focusable(button, dp(context, 8f).toFloat()); button.setOnClickListener { callback(i) }
                buttons.add(i to button); addView(button, LayoutParams(-1, -2).apply { bottomMargin = dp(context, 5f) })
            }
            if (plays.isEmpty()) addView(UI.text(context, "Play-by-play is not supplied for this event yet.", 13f, UI.DIM))
        }
        buttons.forEach { (i, button) -> button.isSelected = i == index; button.setTextColor(if (i == index) UI.GOLD else android.graphics.Color.WHITE) }
    }
}