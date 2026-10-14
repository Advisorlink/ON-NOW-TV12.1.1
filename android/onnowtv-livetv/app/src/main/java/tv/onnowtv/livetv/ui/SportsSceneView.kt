package tv.onnowtv.livetv.ui

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import org.json.JSONObject

/** Native-only 2D surface. No browser, WebGL, synthetic player formations or idle render loop. */
class SportsSceneView(context: Context) : FrameLayout(context) {
    var aspectRatio: Float? = null
    private val field = GamePlotView(context)
    private val caption = SportsUi.identify(SportsUi.text(context, "2D FIELD", 10f, SportsUi.DIM), "field-location-status")
    private val teams = SportsUi.identify(SportsUi.text(context, "", 11f, SportsUi.DIM, bold = true), "field-team-labels")
    private var plays: List<JSONObject> = emptyList()
    init {
        SportsUi.identify(this, "sports-2d-field"); setBackgroundColor(SportsUi.PLOT)
        addView(field, LayoutParams(-1, -1))
        val inset = SportsUi.dp(context, 12f)
        addView(teams, LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(inset, inset, inset, 0) })
        addView(caption, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(inset, 0, inset, inset) })
    }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val height = aspectRatio?.let { MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthSpec) / it).toInt(), MeasureSpec.EXACTLY) } ?: heightSpec
        super.onMeasure(widthSpec, height)
    }
    fun setGame(sport: String, accent: Int, homeColor: Int, awayColor: Int, plays: List<JSONObject>) {
        this.plays = plays; field.setGame(sport, accent, homeColor, awayColor, plays); showUpTo(plays.lastIndex)
    }
    fun setTeams(game: JSONObject) {
        teams.text = listOf("away", "home").map { game.optJSONObject(it)?.optString("abbr").orEmpty() }.filter { it.isNotBlank() }.joinToString("   /   ")
    }
    fun showUpTo(value: Int) {
        field.showUpTo(value)
        caption.text = when {
            plays.isEmpty() -> "2D FIELD · Awaiting play-by-play"
            field.hasLocation(value) -> "PLAY ${value + 1} · ● Selected  · ○ Earlier plays"
            else -> "PLAY ${value + 1} · Location not supplied"
        }
    }
    fun pauseScene() = Unit
    fun resumeScene() = Unit
    fun destroyScene() = Unit
}