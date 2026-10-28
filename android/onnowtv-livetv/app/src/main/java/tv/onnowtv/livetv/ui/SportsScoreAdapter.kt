package tv.onnowtv.livetv.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.SportsLiveGuide
import tv.onnowtv.livetv.data.SportsLiveEvents
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.SportsReplayActivity
import tv.onnowtv.livetv.ui.SportsUi.dp

/** Recycles scorecards; stable keys keep remote focus on the same game during polling. */
class SportsScoreAdapter(private val owner: LifecycleOwner, private val open: (JSONObject) -> Unit) : ListAdapter<SportsScoreAdapter.Item, SportsScoreAdapter.Holder>(Diff) {
    var openLiveEvent: (SportsLiveEvents.Event) -> Unit = {}
    /** Scoreboard-bar style (Stadium Tiles / Ticker layouts) instead of cards; radius sets the bar shape. */
    var rows = false
    var rowRadius = 14f
    private var broadcasts: Map<String, List<GameChannelMatcher.Match>> = emptyMap()
    fun setChannelMatches(value: Map<String, List<GameChannelMatcher.Match>>) { broadcasts = value }
    data class Item(val key: String, val title: String = "", val color: Int = SportsUi.DIM, val json: String = "", val lineup: String = "", val event: SportsLiveEvents.Event? = null) {
        val programme get() = event?.representative
        val isHeader get() = json.isEmpty() && event == null
    }
    object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(old: Item, new: Item) = old.key == new.key
        override fun areContentsTheSame(old: Item, new: Item) = old == new
    }
    class Holder(val root: FrameLayout) : RecyclerView.ViewHolder(root)
    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = getItem(position).key.fold(1125899906842597L) { hash, c -> 31 * hash + c.code }
    override fun getItemViewType(position: Int) = if (getItem(position).isHeader) 0 else 1
    fun isHeader(position: Int) = currentList.getOrNull(position)?.isHeader != false
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        return Holder(FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f)) }
            if (viewType == 1) { SportsUi.focusable(this, dp(ctx, 14f).toFloat()); clipToOutline = true; val inset = dp(ctx, 2f); setPadding(inset, inset, inset, inset) }
        })
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position); val surface = holder.root; val ctx = surface.context
        surface.removeAllViews(); SportsUi.identify(surface, item.key)
        val root = SportsUi.col(ctx)
        if (!item.isHeader && item.programme == null) surface.addView(SportsArtwork.layer(ctx, JSONObject(item.json)), FrameLayout.LayoutParams(-1, -1))
        item.programme?.let { entry -> surface.addView(SportsArtwork.eventLayer(ctx, entry.fixtureJson?.let(::JSONObject), entry.bucket, entry.key), FrameLayout.LayoutParams(-1, -1)) }
        surface.addView(root, FrameLayout.LayoutParams(-1, -2))
        if (item.isHeader) {
            root.setPadding(0, dp(ctx, 10f), 0, dp(ctx, 2f))
            root.addView(SportsUi.text(ctx, item.title, 15f, item.color, bold = true))
            return
        }
        item.event?.let { event ->
            surface.contentDescription = "${event.title} · ${event.sources.size} channels"
            SportsGuideCard.bind(root, event) { openLiveEvent(event) }
            surface.setOnClickListener { openLiveEvent(event) }; return
        }
        val g = JSONObject(item.json); val live = g.optString("state") == "in"; val upcoming = g.optString("state") == "pre"
        if (rows) { bindRow(surface, g, live, upcoming); return }
        root.setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 10f))
        surface.contentDescription = "${Data.value(g, "name")} · ${Data.value(g, "statusDetail")}"
        val top = SportsUi.row(ctx, dp(ctx, 6f))
        top.addView(SportsUi.singleLine(SportsUi.eyebrow(ctx, g.optString("leagueName"))), LinearLayout.LayoutParams(0, -2, 1f))
        val status = if (upcoming && g.optLong("startMs") > 0) android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(g.optLong("startMs"))) else Data.value(g, "statusDetail", "Scheduled")
        top.addView(SportsUi.singleLine(SportsUi.text(ctx, (if (live) "● " else "") + status, 10f, if (live) SportsUi.LIVE else SportsUi.ACCENT)).apply { maxWidth = dp(ctx, 112f) })
        root.addView(top)
        root.addView(SportsSportBadge.create(ctx, tv.onnowtv.livetv.data.SportsCategories.bucket(g), Data.key(g)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 8f) })
        val mid = SportsUi.row(ctx, dp(ctx, 8f)).apply { setPadding(0, dp(ctx, 14f), 0, dp(ctx, 12f)) }
        fun side(key: String): View {
            val t = g.optJSONObject(key)
            val team = SportsUi.col(ctx, dp(ctx, 5f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val logo = SportsUi.logo(ctx, Data.value(t, "logo"), dp(ctx, 40f))
            val short = SportsUi.text(ctx, Data.abbreviation(t), 20f, bold = true).apply { maxLines = 1; maxWidth = dp(ctx, 53f) }
            team.addView(logo); team.addView(short)
            return team
        }
        mid.addView(side("away"), LinearLayout.LayoutParams(0, -2, 1f))
        val score = if (upcoming) "VS" else "${Data.value(g.optJSONObject("away"), "score", "—")} : ${Data.value(g.optJSONObject("home"), "score", "—") }"
        mid.addView(SportsUi.text(ctx, score, if (upcoming) 14f else 31f, if (upcoming) SportsUi.DIM else Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            if (!upcoming) androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 18, 31, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, -2, 1.2f))
        mid.addView(side("home"), LinearLayout.LayoutParams(0, -2, 1f)); root.addView(mid)
        val names = SportsUi.row(ctx, dp(ctx, 10f))
        listOf("away", "home").forEach { side -> names.addView(SportsUi.singleLine(SportsUi.text(ctx, Data.value(g.optJSONObject(side), "name"), 10f, SportsUi.DIM)).apply { gravity = if (side == "home") Gravity.END else Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(names)
        root.addView(SportsUi.identify(SportsUi.text(ctx, tv.onnowtv.livetv.data.SportsEventMetadata.eventLabel(g), 11f, SportsUi.ACCENT), "fixture-event-date-${Data.key(g)}"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 8f) })
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
        root.addView(foot); surface.setOnClickListener { open(g) }
        if (g.optString("state") == "post") root.addView(SportsReplayRow.create(ctx, owner, g, BundleHolder.current,
            "fixture-replay-${g.optString("id")}", { channel -> SportsReplayActivity.open(ctx, g, channel) }),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 8f) })
    }
    private fun bindRow(surface: FrameLayout, g: JSONObject, live: Boolean, upcoming: Boolean) {
        val ctx = surface.context
        surface.removeAllViews()
        SportsUi.focusable(surface, dp(ctx, rowRadius).toFloat())
        val compact = rowRadius < 4f
        val bar = SportsUi.row(ctx, dp(ctx, 10f)).apply { setPadding(0, 0, 0, 0) }
        fun stripe(key: String) = View(ctx).apply { setBackgroundColor(SportsTeamUi.teamColor(g.optJSONObject(key), SportsUi.BORDER)) }
        fun team(key: String, end: Boolean) = SportsUi.row(ctx, dp(ctx, 8f)).apply {
            gravity = (if (end) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            val t = g.optJSONObject(key)
            val logo = SportsUi.logo(ctx, Data.value(t, "logo"), dp(ctx, if (compact) 28f else 34f))
            val name = SportsUi.singleLine(SportsUi.text(ctx, Data.abbreviation(t), 17f, Color.WHITE, bold = true))
            if (end) { addView(name); addView(logo) } else { addView(logo); addView(name) }
        }
        fun score(key: String): android.widget.TextView { val v = if (upcoming) "" else Data.value(g.optJSONObject(key), "score", "–")
            return SportsUi.singleLine(SportsUi.text(ctx, v, if (v.length > 5) 16f else 24f, Color.WHITE, bold = true)).apply { gravity = Gravity.CENTER; minWidth = dp(ctx, 46f); maxWidth = dp(ctx, if (compact) 84f else 120f) } }
        val status = if (upcoming && g.optLong("startMs") > 0) android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(g.optLong("startMs"))) else Data.value(g, "statusDetail", "Final")
        if (compact) {
            // TV score-bug: away and home on two stacked lines, status block on the right.
            val lines = SportsUi.col(ctx, dp(ctx, 4f)).apply { setPadding(0, dp(ctx, 6f), dp(ctx, 10f), dp(ctx, 6f)) }
            listOf("away", "home").forEach { key ->
                val t = g.optJSONObject(key)
                lines.addView(SportsUi.row(ctx, dp(ctx, 8f)).apply {
                    addView(stripe(key), LinearLayout.LayoutParams(dp(ctx, 4f), dp(ctx, 22f)))
                    addView(SportsUi.logo(ctx, Data.value(t, "logo"), dp(ctx, 22f)))
                    addView(SportsUi.singleLine(SportsUi.text(ctx, Data.value(t, "name"), 14f, Color.WHITE, bold = true)), LinearLayout.LayoutParams(0, -2, 1f))
                    val v = if (upcoming) "" else Data.value(t, "score", "–")
                    addView(SportsUi.singleLine(SportsUi.text(ctx, v, if (v.length > 5) 14f else 19f, Color.WHITE, bold = true)).apply { gravity = Gravity.END; maxWidth = dp(ctx, 110f) })
                }, LinearLayout.LayoutParams(-1, -2))
            }
            bar.addView(lines, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(SportsUi.text(ctx, (if (live && !status.equals("live", true)) "● LIVE\n" else if (live) "● " else "") + status.uppercase(), 11f, Color.WHITE, mono = true).apply {
                gravity = Gravity.CENTER; maxLines = 3; setPadding(dp(ctx, 6f), 0, dp(ctx, 6f), 0); setBackgroundColor(if (live) SportsUi.LIVE else SportsUi.PLOT)
            }, LinearLayout.LayoutParams(dp(ctx, 88f), -1))
            surface.addView(bar, FrameLayout.LayoutParams(-1, dp(ctx, 68f)))
            surface.contentDescription = "${Data.value(g, "name")} · ${Data.value(g, "statusDetail")}"
            surface.setOnClickListener { open(g) }
            return
        }
        bar.addView(stripe("away"), LinearLayout.LayoutParams(dp(ctx, 6f), -1))
        bar.addView(team("away", false), LinearLayout.LayoutParams(0, -2, 1.3f))
        bar.addView(score("away"), LinearLayout.LayoutParams(-2, -2))
        bar.addView(SportsUi.singleLine(SportsUi.text(ctx, (if (live) "● " else "") + status.uppercase(), 11f, Color.WHITE, mono = true)).apply {
            gravity = Gravity.CENTER; setPadding(dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 8f), dp(ctx, 4f))
            background = SportsUi.bg(if (live) SportsUi.LIVE else SportsUi.PLOT, dp(ctx, if (rowRadius < 4f) 2f else 8f).toFloat())
        }, LinearLayout.LayoutParams(dp(ctx, if (compact) 80f else 104f), -2))
        bar.addView(score("home"), LinearLayout.LayoutParams(-2, -2))
        bar.addView(team("home", true), LinearLayout.LayoutParams(0, -2, 1.3f))
        val match = broadcasts[Data.key(g)].orEmpty().firstOrNull()
        if (!compact) bar.addView(SportsUi.singleLine(SportsUi.text(ctx, match?.channel?.name ?: g.optString("leagueName"), 10f, if (match?.confirmed == true) SportsUi.ACCENT else SportsUi.DIM)).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, -2, 0.9f))
        bar.addView(stripe("home"), LinearLayout.LayoutParams(dp(ctx, 6f), -1))
        surface.addView(bar, FrameLayout.LayoutParams(-1, dp(ctx, 62f)))
        surface.contentDescription = "${Data.value(g, "name")} · ${Data.value(g, "statusDetail")}"
        surface.setOnClickListener { open(g) }
    }
    fun show(games: List<JSONObject>, programmes: List<SportsLiveGuide.Entry> = emptyList(), onCommitted: (() -> Unit)? = null) {
        val rows = ArrayList<Item>()
        val fixtureKeys = games.map { Data.key(it) }.toSet()
        val events = SportsLiveEvents.group(programmes).filter { event -> event.fixtureJson?.let { Data.key(JSONObject(it)) } !in fixtureKeys }
        listOf(Triple("in", "Live Now", SportsUi.LIVE), Triple("pre", "Coming Up", SportsUi.ACCENT), Triple("post", "Previous Games & Replays", SportsUi.DIM)).forEach { (state, title, color) ->
            val group = games.filter { it.optString("state") == state }; if (group.isNotEmpty()) {
                rows.add(Item("section-$state", "$title   ${group.size}", color))
                group.forEach { g -> rows.add(Item("game-${Data.key(g)}", json = g.toString(), lineup = broadcasts[Data.key(g)].orEmpty().joinToString { "${it.channel.id}:${it.confirmed}:${it.channel.archiveDays}:${it.programme?.startMs}" })) }
            }
            if (state == "in" && events.isNotEmpty()) {
                rows.add(Item("section-guide-live", "Live on your channels   ${events.size} events", SportsUi.LIVE))
                events.forEach { event -> rows.add(Item(event.key, event = event)) }
            }
        }
        submitList(rows) { onCommitted?.invoke() }
    }
}