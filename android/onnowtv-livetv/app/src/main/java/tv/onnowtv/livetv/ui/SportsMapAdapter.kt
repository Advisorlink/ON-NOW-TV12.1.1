package tv.onnowtv.livetv.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.ui.SportsUi.dp

class SportsMapAdapter(private val select: (JSONObject) -> Unit, private val open: (JSONObject) -> Unit) : ListAdapter<SportsMapAdapter.Row, SportsMapAdapter.Holder>(Diff) {
    data class Row(val key: String, val json: String)
    object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }
    class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root)
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder = Holder(SportsUi.col(parent.context, dp(parent.context, 6f)).apply {
        val ctx = parent.context; setPadding(dp(ctx, 12f), dp(ctx, 13f), dp(ctx, 12f), dp(ctx, 13f))
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f)) }
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position); val g = JSONObject(row.json); val root = holder.root; val ctx = root.context
        root.removeAllViews(); SportsUi.identify(root, "map-game-${row.key}")
        SportsUi.focusable(root, dp(ctx, 12f).toFloat(), onFocus = { if (it) select(g) })
        val teams = "${Data.abbreviation(g.optJSONObject("away"))} / ${Data.abbreviation(g.optJSONObject("home"))}"
        root.addView(SportsUi.singleLine(SportsUi.text(ctx, teams, 22f, bold = true)))
        root.addView(SportsUi.singleLine(SportsUi.text(ctx, "${Data.value(g.optJSONObject("venue"), "city")} · ${g.optString("leagueName")}", 10f, SportsUi.DIM)))
        val score = if (g.optString("state") == "pre") g.optString("statusDetail") else "${Data.value(g.optJSONObject("away"), "score", "—")} : ${Data.value(g.optJSONObject("home"), "score", "—")} · ${g.optString("statusDetail") }"
        root.addView(SportsUi.singleLine(SportsUi.text(ctx, score, 11f, SportsUi.ACCENT)))
        root.setOnClickListener { open(g) }
    }
    fun show(games: List<JSONObject>) = submitList(games.map { Row(Data.key(it), it.toString()) })
}