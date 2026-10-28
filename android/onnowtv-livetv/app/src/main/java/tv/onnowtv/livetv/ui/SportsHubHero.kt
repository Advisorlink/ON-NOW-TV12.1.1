package tv.onnowtv.livetv.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.SportsCatchUpActivity
import tv.onnowtv.livetv.data.CatchUpIndex
import tv.onnowtv.livetv.data.LiveSportsClassifier
import tv.onnowtv.livetv.data.SportsCatchUpData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sports Hub hero: a big cover-art "Catch Up & Replays" tile plus a strip of the chosen sport's recordings. */
class SportsHubHero(private val activity: AppCompatActivity, compact: Boolean = false) : LinearLayout(activity) {
    private val cyan = Color.parseColor("#5DC8FF")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private var sport: String? = null
    private var job: Job? = null
    private val label = text("", 11f, cyan, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .22f; tag = "hub-replays-label" }
    private val hint = text("", 13f, Color.parseColor("#9DA5B5"), Typeface.DEFAULT).apply { tag = "hub-replays-empty" }
    private val cards = Cards()

    init {
        orientation = if (compact) VERTICAL else HORIZONTAL
        tag = "hub-hero"
        clipChildren = false; clipToPadding = false
        setPadding(dp(4), dp(6), dp(4), dp(6))
        addView(coverTile(), if (compact) LayoutParams(-1, dp(120)) else LayoutParams(dp(360), dp(128)))
        addView(LinearLayout(activity).apply {
            orientation = VERTICAL; clipChildren = false; clipToPadding = false
            addView(label, LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(hint, LayoutParams(-2, -2).apply { marginStart = dp(8); topMargin = dp(6) })
            addView(RecyclerView(activity).apply {
                tag = "hub-replays-strip"; itemAnimator = null; clipToPadding = false; clipChildren = false
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false); adapter = cards
            }, LayoutParams(-1, dp(112)))
        }, if (compact) LayoutParams(-1, dp(136)).apply { topMargin = dp(10) } else LayoutParams(0, dp(136), 1f).apply { marginStart = dp(16) })
    }

    private fun text(t: String, size: Float, color: Int, face: Typeface) = TextView(activity).apply {
        text = t; textSize = size; setTextColor(color); typeface = face
    }

    private fun ring(radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(Color.parseColor("#0A0F1A")); setStroke(dp(3), Color.WHITE) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(Color.parseColor("#0A0F1A")); setStroke(dp(1), Color.parseColor("#555DC8FF")) })
    }

    private fun zoom(v: View) = Unit

    private fun coverTile() = FrameLayout(activity).apply {
        tag = "hub-catchup-replays-tile"; contentDescription = "Catch Up and Replays"
        isFocusable = true; isClickable = true; clipToOutline = true; background = ring(16); setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.img_catchup_sports) }, FrameLayout.LayoutParams(-1, -1))
        addView(View(activity).apply { setBackgroundResource(R.drawable.catchup_tile_shade) }, FrameLayout.LayoutParams(-1, -1))
        addView(LinearLayout(activity).apply {
            orientation = VERTICAL; setPadding(dp(18), 0, dp(12), dp(14))
            addView(text("V2 SPORTS", 10f, cyan, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .26f })
            addView(text("Catch Up & Replays", 23f, Color.WHITE, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply { setShadowLayer(8f, 0f, 2f, Color.BLACK) })
            addView(text("EVERY SPORT · TEAMS · FULL GAMES  ›", 10f, Color.WHITE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .14f; alpha = .85f })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setOnClickListener { activity.startActivity(Intent(activity, SportsCatchUpActivity::class.java)) }
        zoom(this)
    }

    /** Shows replays for this sport bucket (null = newest across every sport). */
    fun update(bucket: String?) {
        sport = bucket
        val name = bucket?.let { LiveSportsClassifier.labelOf(it).uppercase() } ?: "ALL SPORTS"
        label.text = "↺  LATEST REPLAYS · $name"
        val bundle = BundleHolder.current ?: return
        job?.cancel()
        job = activity.lifecycleScope.launch {
            hint.text = "Loading replays…"; hint.visibility = View.VISIBLE
            val catNames = bundle.categories.associate { it.id to it.name }
            val all = CatchUpIndex.recordings(activity, bundle)
            val picks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { all.filter { SportsCatchUpData.isSportsChannel(catNames[it.channel.categoryId].orEmpty() + " " + it.channel.name) }
                .mapNotNull { r -> SportsCatchUpData.sportFor(r.channel, r.programme)?.let { s -> Triple(r, s, s) } }
                .filter { bucket == null || it.second == bucket }
                .sortedByDescending { it.first.programme.startMs }
                .distinctBy { it.first.programme.title }
                .take(20) }
            SportsCatchUpData.fetchMeta(picks.map { it.second to it.first.programme.title })
            cards.submit(picks.map { Item(it.first, it.second) }, all)
            hint.text = if (picks.isEmpty()) "No $name replays recorded this week yet" else ""
            hint.visibility = if (picks.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private class Item(val rec: CatchUpIndex.Recording, val sport: String)

    private inner class Cards : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()
        private var all: List<CatchUpIndex.Recording> = emptyList()
        fun submit(list: List<Item>, everything: List<CatchUpIndex.Recording>) { items = list; all = everything; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(FrameLayout(activity).apply {
            layoutParams = RecyclerView.LayoutParams(dp(204), -1).apply { marginEnd = dp(12) }
            isFocusable = true; isClickable = true; clipToOutline = true; background = ring(14); setPadding(dp(2), dp(2), dp(2), dp(2))
        }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = bind(holder.itemView as FrameLayout, items[position])

        private fun bind(v: FrameLayout, item: Item) {
            v.removeAllViews()
            v.tag = "hub-replay-card-${items.indexOf(item)}"
            val p = item.rec.programme
            val m = SportsCatchUpData.meta(item.sport, p.title)?.takeIf { it.match && it.home != null && it.away != null }
            val accent = LiveSportsClassifier.colorOf(item.sport)
            if (m != null) {
                fun c(hex: String?, f: Int) = runCatching { Color.parseColor("#" + hex!!.removePrefix("#")) }.getOrDefault(f)
                v.addView(View(activity).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                        ColorUtils.blendARGB(Color.parseColor("#0E1422"), c(m.home!!.color, accent), .55f), Color.parseColor("#0E1422"),
                        ColorUtils.blendARGB(Color.parseColor("#0E1422"), c(m.away!!.color, Color.parseColor("#2A3547")), .55f))).apply { cornerRadius = dp(12).toFloat() }
                }, FrameLayout.LayoutParams(-1, -1))
                v.addView(LinearLayout(activity).apply {
                    orientation = HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), 0)
                    addView(crest(m.home!!), LayoutParams(0, -2, 1f))
                    addView(text("VS", 11f, Color.WHITE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { alpha = .8f }, LayoutParams(-2, -2).apply { bottomMargin = dp(18) })
                    addView(crest(m.away!!), LayoutParams(0, -2, 1f))
                }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            } else {
                v.addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(SportsCatchUpData.art(item.sport)) }, FrameLayout.LayoutParams(-1, -1))
                v.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22000000, Color.parseColor("#E6060A12"))) }, FrameLayout.LayoutParams(-1, -1))
                v.addView(text(SportsCatchUpData.noScore(p.title), 14f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(12), 0, dp(12), 0); setShadowLayer(6f, 0f, 1f, Color.BLACK)
                }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            }
            val day = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(p.startMs)).uppercase()
            v.addView(text("${item.rec.channel.name.uppercase()} · $day", 9f, cyan, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(10), 0, dp(10), dp(8))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            v.setOnClickListener {
                fun team(x: SportsCatchUpData.Side?) = x?.let { s -> PlayerLoadingOverlay.Team(s.name, s.logo, s.color?.let { h -> runCatching { Color.parseColor("#" + h.removePrefix("#")) }.getOrNull() }) }
                val title = if (m != null) "${m.home!!.name} vs ${m.away!!.name}" else SportsCatchUpData.noScore(p.title)
                val when_ = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(Date(p.startMs)).uppercase()
                CatchUpPlayback.start(activity, activity.findViewById(android.R.id.content), item.rec.channel, p, title,
                    "${item.rec.channel.name} · $when_", all.map { it.channel }.distinctBy { it.id }, team(m?.home), team(m?.away))
            }
            zoom(v)
        }

        private fun crest(s: SportsCatchUpData.Side) = LinearLayout(activity).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val box = FrameLayout(activity)
            val initials = text(s.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, 15f, Color.WHITE,
                Typeface.create("sans-serif-black", Typeface.NORMAL)).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2A3547")); setStroke(dp(2), 0x55FFFFFF) }
            }
            box.addView(initials, FrameLayout.LayoutParams(-1, -1))
            if (!s.logo.isNullOrBlank()) {
                initials.visibility = View.INVISIBLE
                box.addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(s.logo) { listener(onError = { _, _ -> initials.visibility = View.VISIBLE }) } }, FrameLayout.LayoutParams(-1, -1))
            }
            addView(box, LayoutParams(dp(38), dp(38)))
            addView(text(s.name, 11f, Color.WHITE, Typeface.DEFAULT_BOLD).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }
}
