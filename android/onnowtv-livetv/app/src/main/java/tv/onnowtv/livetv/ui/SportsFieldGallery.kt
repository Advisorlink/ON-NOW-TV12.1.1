package tv.onnowtv.livetv.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import tv.onnowtv.livetv.ui.SportsUi.dp

object SportsFieldGallery {
    private val fields = listOf("soccer" to "Soccer", "mlb" to "Baseball", "nba" to "Basketball", "nhl" to "Hockey", "nfl" to "Football", "tennis" to "Tennis", "cricket" to "Cricket", "afl" to "Australian football", "rugby" to "Rugby", "mma" to "MMA", "f1" to "Racing", "golf" to "Golf")
    fun show(ctx: Context) {
        val col = SportsUi.col(ctx, dp(ctx, 12f)).apply { setPadding(dp(ctx, 22f), dp(ctx, 20f), dp(ctx, 22f), dp(ctx, 20f)); background = SportsUi.bg(SportsUi.BG, dp(ctx, 16f).toFloat()) }
        col.addView(SportsUi.wordmark(ctx, true)); col.addView(SportsUi.text(ctx, "Fields of play", 24f, bold = true))
        fields.chunked(4).forEach { group ->
            val row = SportsUi.row(ctx, dp(ctx, 10f))
            group.forEach { (id, name) ->
                val tile = SportsUi.col(ctx).apply { setPadding(dp(ctx, 10f), dp(ctx, 10f), dp(ctx, 10f), dp(ctx, 10f)) }
                SportsUi.identify(tile, "sports-field-$id"); SportsUi.focusable(tile, dp(ctx, 14f).toFloat())
                tile.addView(SportsUi.text(ctx, name, 14f, bold = true))
                tile.addView(SportFieldView(ctx).apply { setSport(id, SportsUi.ACCENT); setHighContrast(true) }, LinearLayout.LayoutParams(-1, dp(ctx, 95f)))
                tile.setOnClickListener {
                    val large = SportFieldView(ctx).apply { setSport(id, SportsUi.ACCENT); setHighContrast(true); minimumHeight = dp(ctx, 230f); setBackgroundColor(SportsUi.PLOT) }
                    AlertDialog.Builder(ctx).setTitle(name).setView(large).setPositiveButton("Close", null).show()
                }
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
            }; col.addView(row)
        }
        AlertDialog.Builder(ctx).setView(ScrollView(ctx).apply { addView(col) }).setPositiveButton("Back to scores", null).show().apply {
            window?.setLayout((ctx.resources.displayMetrics.widthPixels * .9f).toInt(), (ctx.resources.displayMetrics.heightPixels * .9f).toInt())
        }
    }
}