package tv.onnowtv.livetv.data

import android.content.Context
import tv.onnowtv.livetv.R

/** The five Sports Hub looks. Same data in each; only menu, cards and featured area change. */
enum class SportsHubLayout(val title: String, val blurb: String, val preview: Int, val accent: Int) {
    RAIL("Broadcast Rail", "Sport icons and names down the side, game cards in a grid", R.drawable.img_hub_layout_a, 0xFF5DC8FF.toInt()),
    DOCK("Icon Dock", "A slim icon bar so more games fit on screen", R.drawable.img_hub_layout_b, 0xFF5DC8FF.toInt()),
    TILES("Stadium Tiles", "Sport artwork tiles with a full-width scoreboard", R.drawable.img_hub_layout_c, 0xFFFFC850.toInt()),
    TICKER("Scoreboard Ticker", "Sharp TV-graphics score bars in two columns", R.drawable.img_hub_layout_d, 0xFFFF2535.toInt()),
    SPOTLIGHT("Spotlight", "A big featured game up top, then large cards", R.drawable.img_hub_layout_e, 0xFFFFB648.toInt());

    val rows get() = this == TILES || this == TICKER

    companion object {
        private const val PREFS = "sports_hub_layout"
        fun get(ctx: Context): SportsHubLayout =
            values().firstOrNull { it.name == ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("layout", null) } ?: RAIL
        fun set(ctx: Context, layout: SportsHubLayout) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("layout", layout.name).apply()
    }
}
