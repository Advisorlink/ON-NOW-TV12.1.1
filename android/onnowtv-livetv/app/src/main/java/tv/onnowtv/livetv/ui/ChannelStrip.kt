package tv.onnowtv.livetv.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme

/** Down while watching: the channels of the current category down the left of the picture, with what's on for the highlighted one. */
class ChannelStrip(
    private val activity: Activity,
    private val nowNext: (Channel) -> Pair<Programme?, Programme?>,
    private val needEpg: (Channel, () -> Unit) -> Unit,
    private val art: (String, (String) -> Unit) -> Unit,
    private val onPick: (Channel) -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val dm = activity.resources.displayMetrics
    private val sw = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
    private val sh = minOf(dm.widthPixels, dm.heightPixels).toFloat()
    private val u = sw / 1913f                              // every size below is in pixels of the 1913-wide design
    private fun n(v: Float) = (v * u).toInt()
    private val rowH = n(98f); private val gap = n(11f)
    private val slot = 3                                    // the highlighted channel sits fourth from the top
    private val gold = 0xFFF0C95E.toInt()
    private val timeFmt = android.text.format.DateFormat.getTimeFormat(activity)
    private var channels: List<Channel> = emptyList()
    private var sel = 0
    private var playing: Channel? = null
    private var root: FrameLayout? = null
    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var infoArt: ImageView; private lateinit var infoLogo: ImageView
    private lateinit var infoOn: TextView; private lateinit var infoTitle: TextView; private lateinit var infoDesc: TextView
    private lateinit var infoStart: TextView; private lateinit var infoLeft: TextView; private lateinit var infoEnd: TextView; private lateinit var infoBar: ProgressBar
    private lateinit var infoNext: TextView; private lateinit var infoNextAt: TextView; private lateinit var timeBox: View; private lateinit var nextBox: View
    private lateinit var count: TextView; private lateinit var clock: TextView; private lateinit var bigTitle: TextView; private lateinit var bigSub: TextView
    private val hide = Runnable { hide() }
    private val paintArt = Runnable { paintArtNow() }

    val visible: Boolean get() = root?.visibility == View.VISIBLE

    private fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor) }
    private fun text(size: Float, color: Int, bold: Boolean = false, lines: Int = 1, spacing: Float = 0f, caps: Boolean = false) = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u); setTextColor(color); maxLines = lines; ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
        letterSpacing = spacing; isAllCaps = caps; includeFontPadding = false
        setShadowLayer(4f * u, 0f, 1.5f * u, 0xB3000000.toInt())
    }
    private fun bar(height: Float) = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(round(0x38FFFFFF, height * u), android.graphics.drawable.ClipDrawable(round(gold, height * u), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL))).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
    }
    private fun progress(p: Programme) = (((System.currentTimeMillis() - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
    private fun left(p: Programme): String { val m = ((p.stopMs - System.currentTimeMillis()) / 60000L).coerceAtLeast(0); return if (m >= 60) "${m / 60}h ${m % 60}m left" else "${m}m left" }
    private fun head(ch: Channel): String = listOfNotNull(ch.lcn?.toString()?.takeIf { it.isNotBlank() && it != "0" }?.let { "CH $it" }, ch.name).joinToString(" · ")
    private fun time(ms: Long) = timeFmt.format(java.util.Date(ms)).lowercase()

    private fun build(): FrameLayout {
        val r = FrameLayout(activity)
        r.clipChildren = false
        // the picture fades to black on the left, and a little at the bottom
        r.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF5000000.toInt(), 0xE0000000.toInt(), 0x8C000000.toInt(), 0)) }, FrameLayout.LayoutParams(n(900f), -1))
        r.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0)) }, FrameLayout.LayoutParams(-1, n(260f), Gravity.BOTTOM))

        r.addView(text(24f, Color.WHITE, bold = true, spacing = 0.2f).apply { text = "LIVE TV" }, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = n(80f); topMargin = n(36f) })
        count = text(21f, 0xFFAEB4BE.toInt())
        r.addView(count, FrameLayout.LayoutParams(n(300f), -2).apply { leftMargin = n(307f); topMargin = n(38f) }); count.gravity = Gravity.END

        lm = LinearLayoutManager(activity)
        list = RecyclerView(activity).apply {
            layoutManager = lm; adapter = Rows(); itemAnimator = null; clipChildren = false; clipToPadding = false
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, n(600f))
        }
        r.addView(list, FrameLayout.LayoutParams(n(540f), -1).apply { leftMargin = n(80f); topMargin = n(84f); bottomMargin = n(72f) })
        r.addView(text(21f, 0xFFAEB4BE.toInt()).apply { text = "▲ ▼ Channel   •   OK Watch   •   ◄ Close" }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM).apply { leftMargin = n(84f); bottomMargin = n(30f) })

        // top right: LIVE + clock
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(21f, Color.WHITE, bold = true, spacing = 0.04f).apply { text = "●  LIVE"; background = round(0xFFE5202E.toInt(), 22f * u); setPadding(n(18f), n(9f), n(20f), n(9f)); setShadowLayer(0f, 0f, 0f, 0) })
        clock = text(21f, Color.WHITE, bold = true).apply { background = round(0xE01A2233.toInt(), 22f * u); setPadding(n(16f), n(9f), n(16f), n(9f)); setShadowLayer(0f, 0f, 0f, 0) }
        top.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = n(12f) })
        r.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { rightMargin = n(28f); topMargin = n(28f) })

        // bottom right: what is playing behind the list
        bigTitle = text(42f, Color.WHITE, bold = true, caps = true).apply { gravity = Gravity.END }
        bigSub = text(21f, 0xFFE4E7EC.toInt()).apply { gravity = Gravity.END }
        r.addView(bigTitle, FrameLayout.LayoutParams(n(900f), -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = n(30f); bottomMargin = n(66f) })
        r.addView(bigSub, FrameLayout.LayoutParams(n(900f), -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = n(30f); bottomMargin = n(34f) })

        // what's on for the highlighted channel
        val cardWd = n(472f); val rad = 22f * u
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; background = round(0xF2141A26.toInt(), rad, maxOf(1, n(1.5f)), 0x47FFFFFF); clipToOutline = true; elevation = 18f * u }
        val artBox = FrameLayout(activity).apply { setBackgroundColor(0xFF0B1220.toInt()) }
        infoArt = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        infoLogo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        artBox.addView(infoArt, FrameLayout.LayoutParams(-1, -1))
        artBox.addView(infoLogo, FrameLayout.LayoutParams(n(200f), n(90f), Gravity.CENTER_HORIZONTAL).apply { topMargin = n(22f) })
        artBox.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2141A26.toInt(), 0x99141A26.toInt(), 0)) }, FrameLayout.LayoutParams(-1, n(130f), Gravity.BOTTOM))
        val over = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(n(21f), 0, n(21f), n(14f)) }
        infoOn = text(17f, 0xFFE4E7EC.toInt(), bold = true, spacing = 0.1f, caps = true)
        infoTitle = text(29f, Color.WHITE, bold = true, lines = 2, caps = true)
        over.addView(infoOn); over.addView(infoTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = n(4f) })
        artBox.addView(over, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        card.addView(artBox, LinearLayout.LayoutParams(-1, n(196f)))

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(n(21f), n(18f), n(21f), n(22f)) }
        infoDesc = text(21f, 0xFFE4E7EC.toInt(), lines = 3).apply { setLineSpacing(9f * u, 1f) }
        infoBar = bar(4f)
        infoStart = text(21f, 0xFFD3D7DE.toInt()); infoEnd = text(21f, 0xFFD3D7DE.toInt()).apply { gravity = Gravity.END }
        infoLeft = text(21f, gold, bold = true).apply { gravity = Gravity.CENTER }
        val times = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; addView(infoStart, LinearLayout.LayoutParams(0, -2, 1f)); addView(infoLeft, LinearLayout.LayoutParams(0, -2, 1.2f)); addView(infoEnd, LinearLayout.LayoutParams(0, -2, 1f)) }
        timeBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL
            addView(infoBar, LinearLayout.LayoutParams(-1, maxOf(3, n(7f))))
            addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = n(12f) }) }
        infoNextAt = text(21f, 0xFFD3D7DE.toInt()).apply { gravity = Gravity.END }
        infoNext = text(21f, Color.WHITE, bold = true)
        nextBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL
            addView(View(activity).apply { setBackgroundColor(0x24FFFFFF) }, LinearLayout.LayoutParams(-1, maxOf(1, n(1.5f))))
            addView(LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL
                addView(text(20f, 0xFFAEB4BE.toInt(), bold = true, spacing = 0.12f).apply { text = "UP NEXT" }, LinearLayout.LayoutParams(0, -2, 1f)); addView(infoNextAt) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = n(18f) })
            addView(infoNext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = n(10f) }) }
        body.addView(infoDesc)
        body.addView(timeBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = n(20f) })
        body.addView(nextBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = n(20f) })
        card.addView(body, LinearLayout.LayoutParams(-1, -2))
        r.addView(card, FrameLayout.LayoutParams(cardWd, -2).apply { leftMargin = n(660f); topMargin = n(205f) })
        r.visibility = View.GONE
        activity.findViewById<ViewGroup>(android.R.id.content).addView(r, ViewGroup.LayoutParams(-1, -1))
        return r
    }

    fun show(all: List<Channel>, currentId: String?) {
        if (all.isEmpty()) return
        val r = root ?: build().also { root = it }
        channels = all
        sel = all.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        playing = all.getOrNull(sel)
        list.adapter?.notifyDataSetChanged()
        place(); paintInfo()
        r.visibility = View.VISIBLE; r.alpha = 0f; r.animate().alpha(1f).setDuration(200).start()
        bump()
    }

    fun hide() {
        ui.removeCallbacks(hide); ui.removeCallbacks(paintArt)
        val r = root ?: return
        if (r.visibility != View.VISIBLE) return
        r.animate().alpha(0f).setDuration(160).withEndAction { r.visibility = View.GONE }.start()
    }

    private fun bump() { ui.removeCallbacks(hide); ui.postDelayed(hide, 12_000L) }
    private fun place() = lm.scrollToPositionWithOffset(sel, slot * (rowH + gap))

    /** Keys while the list is showing. True = handled. */
    fun key(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                val up = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP
                val old = sel
                sel = Math.floorMod(sel + if (up) -1 else 1, channels.size)
                list.adapter?.notifyItemChanged(old); list.adapter?.notifyItemChanged(sel)
                place(); paintInfo(); bump()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { val ch = channels.getOrNull(sel); hide(); ch?.let(onPick); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { hide(); return true }
        }
        return false
    }

    private fun paintInfo() {
        val ch = channels.getOrNull(sel) ?: return
        val (now, next) = nowNext(ch)
        if (now == null) needEpg(ch) { if (visible && channels.getOrNull(sel)?.id == ch.id) paintInfo() }
        count.text = "${sel + 1} of ${channels.size}"
        clock.text = time(System.currentTimeMillis())
        playing?.let { p -> val pn = nowNext(p).first; bigTitle.text = pn?.title ?: p.name; bigSub.text = head(p) + (pn?.let { " — " + left(it) } ?: "") }
        infoOn.text = "Now on " + head(ch)
        infoTitle.text = now?.title ?: ch.name
        infoDesc.text = now?.description?.trim().orEmpty(); infoDesc.visibility = if (infoDesc.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (now != null) {
            timeBox.visibility = View.VISIBLE
            infoStart.text = time(now.startMs); infoEnd.text = time(now.stopMs); infoLeft.text = left(now); infoBar.progress = progress(now)
        } else timeBox.visibility = View.GONE
        if (next != null) { nextBox.visibility = View.VISIBLE; infoNext.text = next.title; infoNextAt.text = time(next.startMs) } else nextBox.visibility = View.GONE
        // channel logo straight away; the programme picture replaces it once you settle
        infoArt.setImageDrawable(null); infoLogo.visibility = View.VISIBLE
        infoLogo.load(ch.logoUrl)
        ui.removeCallbacks(paintArt); ui.postDelayed(paintArt, 260L)
    }

    private fun paintArtNow() {
        val ch = channels.getOrNull(sel) ?: return
        val title = nowNext(ch).first?.title?.trim().orEmpty()
        if (title.isBlank()) return
        art(title) { url ->
            if (url.isNotBlank() && visible && channels.getOrNull(sel)?.id == ch.id) {
                infoArt.load(url) { crossfade(200); listener(onSuccess = { _, _ -> infoLogo.visibility = View.GONE }) }
            }
        }
    }

    private inner class Row(val box: FrameLayout, val logo: ImageView, val head: TextView, val title: TextView, val bar: ProgressBar) : RecyclerView.ViewHolder(box)

    private inner class Rows : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = channels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val box = FrameLayout(activity)
            val logo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = round(Color.WHITE, 12f * u); setPadding(n(7f), n(7f), n(7f), n(7f)) }
            val head = text(21f, 0xFFC9CDD4.toInt(), bold = true, spacing = 0.07f, caps = true)
            val title = text(22f, Color.WHITE, bold = true)
            val bar = bar(2f)
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL
                addView(head); addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = n(7f) }); addView(bar, LinearLayout.LayoutParams(-1, maxOf(2, n(4f))).apply { topMargin = n(11f) }) }
            box.addView(logo, FrameLayout.LayoutParams(n(71f), n(63f), Gravity.CENTER_VERTICAL).apply { leftMargin = n(15f) })
            box.addView(col, FrameLayout.LayoutParams(n(410f), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = n(102f) })
            box.layoutParams = RecyclerView.LayoutParams(n(527f), rowH).apply { bottomMargin = gap }
            return Row(box, logo, head, title, bar)
        }
        override fun onBindViewHolder(h: Row, position: Int) {
            val ch = channels[position]
            val on = position == sel
            h.box.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x52FFFFFF, 0x2EFFFFFF)).apply { cornerRadius = 16f * u; setStroke(maxOf(2, n(2.5f)), 0x8CFFFFFF.toInt()) } else null
            h.box.alpha = if (on) 1f else 0.94f
            h.logo.load(ch.logoUrl)
            val now = nowNext(ch).first
            h.head.text = head(ch)
            h.title.text = now?.title ?: ch.name
            h.bar.visibility = if (now != null) View.VISIBLE else View.INVISIBLE
            if (now != null) h.bar.progress = progress(now)
            else needEpg(ch) { val i = channels.indexOfFirst { it.id == ch.id }; if (i >= 0 && visible) notifyItemChanged(i) }
        }
    }
}
