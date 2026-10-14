package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.data.LiveSportsClassifier as Sport
import tv.onnowtv.livetv.data.SportsReplayCatalogue
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SportsReplayAdapter(private val context: Context, private val select: (String) -> Unit,
    private val play: (SportsReplayCatalogue.Entry) -> Unit) : ListAdapter<SportsReplayAdapter.Item, SportsReplayAdapter.Holder>(DIFF) {
    data class Item(val bucket: String, val entry: SportsReplayCatalogue.Entry? = null) {
        val key get() = entry?.key ?: bucket
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(a: Item, b: Item) = a.key == b.key
            override fun areContentsTheSame(a: Item, b: Item) = a == b
        }
    }
    class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(FrameLayout(context).apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(context, 5f), dp(context, 5f), dp(context, 5f), dp(context, 5f)) }
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position); val entry = item.entry
        val frame = holder.frame; frame.removeAllViews()
        UI.identify(frame, if (entry == null) "sport-category-tile-${item.bucket}" else "replay-match-${entry.key}")
        UI.focusable(frame, dp(context, 8f).toFloat(), base = 0xFF121620.toInt())
        val inset = dp(context, 3f); frame.setPadding(inset, inset, inset, inset)
        frame.clipToOutline = true
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; alpha = .35f
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            load(Sport.iconOf(item.bucket)) { size(320); crossfade(false) }
        }
        frame.addView(art, FrameLayout.LayoutParams(dp(context, 144f), dp(context, 144f), Gravity.END or Gravity.TOP).apply { topMargin = dp(context, 8f); marginEnd = dp(context, 4f) })
        frame.addView(android.view.View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00121620, 0xE6121620.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val copy = UI.col(context, dp(context, 8f)).apply {
            minimumHeight = dp(context, if (entry == null) 165f else 220f)
            setPadding(dp(context, 14f), dp(context, 14f), dp(context, 14f), dp(context, 16f))
        }
        val accent = UI.parseColor(String.format("%06X", Sport.colorOf(item.bucket) and 0xFFFFFF), UI.ACCENT)
        copy.addView(UI.identify(UI.eyebrow(context, if (entry == null) "↺ REPLAYS" else "▶ WATCH REPLAY", accent), "replay-action-${item.key}"))
        copy.addView(android.view.View(context), LinearLayout.LayoutParams(1, 0, 1f))
        copy.addView(UI.identify(UI.text(context, entry?.programme?.title ?: Sport.labelOf(item.bucket), if (entry == null) 26f else 23f, bold = true).apply {
            maxLines = if (entry == null) 2 else 4; ellipsize = android.text.TextUtils.TruncateAt.END
        }, "replay-name-${item.key}"))
        if (entry == null) copy.addView(UI.identify(UI.text(context, "Browse recordings  →", 11f, UI.DIM), "replay-category-detail-${item.key}"))
        else {
            copy.addView(UI.identify(UI.text(context, entry.channel.name, 13f, UI.ACCENT), "replay-channel-${item.key}"))
            val date = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(entry.programme.startMs))
            copy.addView(UI.identify(UI.text(context, "$date · ${entry.programme.durationMin} min", 11f, UI.DIM), "replay-date-${item.key}"))
        }
        frame.addView(copy, FrameLayout.LayoutParams(-1, -2))
        frame.contentDescription = if (entry == null) "${Sport.labelOf(item.bucket)} replays" else "Watch replay. ${entry.programme.title}. ${entry.channel.name}"
        frame.setOnClickListener { if (entry == null) select(item.bucket) else play(entry) }
    }
}