package tv.onnowtv.livetv.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.SportsReplayPolicy
import tv.onnowtv.livetv.ui.SportsUi.dp

/** Recycles scorecards; stable keys keep remote focus on the same game during polling. */
class SportsScoreAdapter(private val open: (JSONObject) -> Unit) : ListAdapter<SportsScoreAdapter.Item, SportsScoreAdapter.Holder>(Diff) {
    private var broadcasts: Map<String, List<GameChannelMatcher.Match>> = emptyMap()
    fun setChannelMatches(value: Map<String, List<GameChannelMatcher.Match>>) { broadcasts = value }
    data class Item(val key: String, val title: String = "", val color: Int = SportsUi.DIM, val json: String = "", val lineup: String = "") {
        val isHeader get() = json.isEmpty()
    }
    object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(old: Item, new: Item) = old.key == new.key
        override fun areContentsTheSame(old: Item, new: Item) = old == new
    }
    class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root)
    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = getItem(position).key.fold(1125899906842597L) { hash, c -> 31 * hash + c.code }
    override fun getItemViewType(position: Int) = if (getItem(position).isHeader) 0 else 1
    fun isHeader(position: Int) = currentList.getOrNull(position)?.isHeader != false
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        return Holder(SportsUi.col(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f)) }
            if (viewType == 1) SportsUi.focusable(this, dp(ctx, 14f).toFloat())
        })
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position); val root = holder.root; val ctx = root.context
        root.removeAllViews(); SportsUi.identify(root, item.key)
        if (item.isHeader) {
            root.setPadding(0, dp(ctx, 10f), 0, dp(ctx, 2f))
            root.addView(SportsUi.text(ctx, item.title, 15f, item.color, bold = true))
            return
        }
        val g = JSONObject(item.json); val live = g.optString("state") == "in"; val upcoming = g.optString("state") == "pre"
        root.setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 10f))
        root.contentDescription = "${Data.value(g, "name")} · ${Data.value(g, "statusDetail")}"
        val top = SportsUi.row(ctx, dp(ctx, 6f))
        top.addView(SportsUi.singleLine(SportsUi.eyebrow(ctx, g.optString("leagueName"))), LinearLayout.LayoutParams(0, -2, 1f))
        val status = if (upcoming && g.optLong("startMs") > 0) android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(g.optLong("startMs"))) else Data.value(g, "statusDetail", "Scheduled")
        top.addView(SportsUi.singleLine(SportsUi.text(ctx, (if (live) "● " else "") + status, 10f, if (live) SportsUi.LIVE else SportsUi.ACCENT)).apply { maxWidth = dp(ctx, 112f) })
        root.addView(top)
        val mid = SportsUi.row(ctx, dp(ctx, 8f)).apply { setPadding(0, dp(ctx, 14f), 0, dp(ctx, 12f)) }
        fun side(key: String): View {
            val t = g.optJSONObject(key); val home = key == "home"
            val team = SportsUi.row(ctx, dp(ctx, 7f)).apply { gravity = if (home) Gravity.END else Gravity.START }
            val logo = SportsUi.logo(ctx, Data.value(t, "logo"), dp(ctx, 40f))
            val short = SportsUi.text(ctx, Data.abbreviation(t), 20f, bold = true).apply { maxLines = 1; maxWidth = dp(ctx, 53f) }
            if (home) { team.addView(short); team.addView(logo) } else { team.addView(logo); team.addView(short) }
            return team
        }
        mid.addView(side("away"), LinearLayout.LayoutParams(0, -2, 1f))
        val score = if (upcoming) "VS" else "${Data.value(g.optJSONObject("away"), "score", "—")} : ${Data.value(g.optJSONObject("home"), "score", "—") }"
        mid.addView(SportsUi.text(ctx, score, if (upcoming) 14f else 31f, if (upcoming) SportsUi.DIM else Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1 })
        mid.addView(side("home"), LinearLayout.LayoutParams(0, -2, 1f)); root.addView(mid)
        val names = SportsUi.row(ctx, dp(ctx, 10f))
        listOf("away", "home").forEach { side -> names.addView(SportsUi.singleLine(SportsUi.text(ctx, Data.value(g.optJSONObject(side), "name"), 10f, SportsUi.DIM)).apply { gravity = if (side == "home") Gravity.END else Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(names)
        root.addView(SportsUi.divider(ctx), LinearLayout.LayoutParams(-1, dp(ctx, 1f)).apply { topMargin = dp(ctx, 11f); bottomMargin = dp(ctx, 9f) })
        val foot = SportsUi.row(ctx, dp(ctx, 5f))
        val matches = broadcasts[Data.key(g)].orEmpty(); val match = matches.firstOrNull()
        if (match != null) {
            foot.addView(SportsUi.logo(ctx, match.channel.logoUrl, dp(ctx, 25f)))
            foot.addView(SportsUi.col(ctx, dp(ctx, 3f)).apply {
                addView(SportsUi.text(ctx, listOfNotNull(match.channel.lcn, match.channel.name).joinToString(" · ") + if (matches.size > 1) " +${matches.size - 1}" else "", 10f, bold = true).apply { maxLines = 2 })
                addView(SportsUi.text(ctx, if (match.confirmed) "Guide confirmed" else "Possible broadcast", 9f, if (match.confirmed) SportsUi.ACCENT else SportsUi.DIM))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        } else foot.addView(SportsUi.text(ctx, "Channel not confirmed", 10f, SportsUi.DIM), LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(SportsUi.text(ctx, if (upcoming) "Preview  ›" else if (live) "Match centre  ›" else "Full result  ›", 10f, SportsUi.ACCENT))
        root.addView(foot); root.setOnClickListener { open(g) }
        val archive = matches.firstOrNull { SportsReplayPolicy.check(g, it).eligible }
        if (archive != null) root.addView(SportsUi.identify(SportsUi.pill(ctx, "↻ Check replay") { open(g) }, "fixture-replay-${g.optString("id")}"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(ctx, 8f) })
    }
    fun show(games: List<JSONObject>, onCommitted: (() -> Unit)? = null) {
        val rows = ArrayList<Item>()
        listOf(Triple("in", "Live now", SportsUi.LIVE), Triple("pre", "Up next", SportsUi.ACCENT), Triple("post", "Final scores", SportsUi.DIM)).forEach { (state, title, color) ->
            val group = games.filter { it.optString("state") == state }; if (group.isNotEmpty()) {
                rows.add(Item("section-$state", "$title   ${group.size}", color))
                group.forEach { g -> rows.add(Item("game-${Data.key(g)}", json = g.toString(), lineup = broadcasts[Data.key(g)].orEmpty().joinToString { "${it.channel.id}:${it.confirmed}:${it.channel.archiveDays}:${it.programme?.startMs}" })) }
            }
        }
        submitList(rows) { onCommitted?.invoke() }
    }
}