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
    private val cardW = (sw * 0.118f).toInt(); private val cardH = (sh * 0.092f).toInt(); private val gap = (sh * 0.008f).toInt()
    private val slot = 2                                    // the highlighted channel sits third from the top
    private val timeFmt = android.text.format.DateFormat.getTimeFormat(activity)
    private var channels: List<Channel> = emptyList()
    private var sel = 0
    private var root: FrameLayout? = null
    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var infoArt: ImageView; private lateinit var infoLogo: ImageView
    private lateinit var infoTitle: TextView; private lateinit var infoDesc: TextView
    private lateinit var infoStart: TextView; private lateinit var infoEnd: TextView; private lateinit var infoBar: ProgressBar
    private lateinit var infoNext: TextView; private lateinit var timeRow: View
    private val hide = Runnable { hide() }
    private val paintArt = Runnable { paintArtNow() }

    val visible: Boolean get() = root?.visibility == View.VISIBLE

    private fun px(f: Float) = sw * f
    private fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor) }
    private fun text(size: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); setTextColor(color); maxLines = lines; ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(px(0.002f), 0f, px(0.0006f), 0x99000000.toInt())
    }

    private fun build(): FrameLayout {
        val r = FrameLayout(activity)
        r.clipChildren = false
        // soft shade down the left so the cards read over any picture
        r.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xD9000000.toInt(), 0x80000000.toInt(), 0)) }, FrameLayout.LayoutParams((sw * 0.34f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        lm = LinearLayoutManager(activity)
        list = RecyclerView(activity).apply {
            layoutManager = lm; adapter = Rows(); itemAnimator = null; clipChildren = false; clipToPadding = false
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, gap, 0, (sh * 0.5f).toInt())
        }
        r.addView(list, FrameLayout.LayoutParams((cardW * 1.12f).toInt() + (sw * 0.018f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = (sw * 0.018f).toInt() })

        // what's on for the highlighted channel
        val cardWd = (sw * 0.281f).toInt(); val rad = px(0.011f)
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; background = round(0xEB1C1F25.toInt(), rad, maxOf(1, (px(0.0008f)).toInt()), 0x40FFFFFF); clipToOutline = true; elevation = px(0.008f) }
        val artBox = FrameLayout(activity).apply { setBackgroundColor(0xFF0B1220.toInt()) }
        infoArt = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        infoLogo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        artBox.addView(infoArt, FrameLayout.LayoutParams(-1, -1))
        artBox.addView(infoLogo, FrameLayout.LayoutParams((cardWd * 0.5f).toInt(), (cardWd * 0.26f).toInt(), Gravity.CENTER))
        card.addView(artBox, LinearLayout.LayoutParams(-1, (cardWd * 0.415f).toInt()))
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; val p = px(0.0145f).toInt(); setPadding(p, (p * 0.75f).toInt(), p, p) }
        infoTitle = text(0.0152f, Color.WHITE, bold = true, lines = 2)
        infoDesc = text(0.0105f, 0xFFD3D7DE.toInt(), lines = 2).apply { setLineSpacing(px(0.002f), 1f) }
        infoStart = text(0.0098f, 0xFFE4E7EC.toInt()); infoEnd = text(0.0098f, 0xFFE4E7EC.toInt())
        infoBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(round(0x55FFFFFF, px(0.002f)), android.graphics.drawable.ClipDrawable(round(Color.WHITE, px(0.002f)), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL))).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        }
        timeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(infoStart)
            addView(infoBar, LinearLayout.LayoutParams(0, maxOf(3, px(0.0028f).toInt()), 1f).apply { leftMargin = px(0.009f).toInt(); rightMargin = px(0.009f).toInt() })
            addView(infoEnd)
        }
        infoNext = text(0.0105f, 0xFFD3D7DE.toInt())
        body.addView(infoTitle)
        body.addView(infoDesc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(0.005f).toInt() })
        body.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(0.012f).toInt() })
        body.addView(infoNext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(0.009f).toInt() })
        card.addView(body, LinearLayout.LayoutParams(-1, -2))
        r.addView(card, FrameLayout.LayoutParams(cardWd, -2).apply { leftMargin = (sw * 0.147f).toInt(); topMargin = (sh * 0.043f).toInt() })
        r.visibility = View.GONE
        activity.findViewById<ViewGroup>(android.R.id.content).addView(r, ViewGroup.LayoutParams(-1, -1))
        return r
    }

    fun show(all: List<Channel>, currentId: String?) {
        if (all.isEmpty()) return
        val r = root ?: build().also { root = it }
        channels = all
        sel = all.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        list.adapter?.notifyDataSetChanged()
        place(); paintInfo()
        r.visibility = View.VISIBLE; r.alpha = 0f; r.translationX = -sw * 0.04f
        r.animate().alpha(1f).translationX(0f).setDuration(180).start()
        bump()
    }

    fun hide() {
        ui.removeCallbacks(hide); ui.removeCallbacks(paintArt)
        val r = root ?: return
        if (r.visibility != View.VISIBLE) return
        r.animate().alpha(0f).translationX(-sw * 0.04f).setDuration(140).withEndAction { r.visibility = View.GONE }.start()
    }

    private fun bump() { ui.removeCallbacks(hide); ui.postDelayed(hide, 12_000L) }
    private fun place() = lm.scrollToPositionWithOffset(sel, slot * (cardH + gap))

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
        infoTitle.text = now?.title ?: ch.name
        infoDesc.text = now?.description?.trim().orEmpty(); infoDesc.visibility = if (infoDesc.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (now != null) {
            timeRow.visibility = View.VISIBLE
            infoStart.text = timeFmt.format(java.util.Date(now.startMs)); infoEnd.text = timeFmt.format(java.util.Date(now.stopMs))
            infoBar.progress = (((System.currentTimeMillis() - now.startMs).toFloat() / (now.stopMs - now.startMs).coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
        } else timeRow.visibility = View.GONE
        infoNext.text = next?.let { "Up next: ${it.title}" }.orEmpty(); infoNext.visibility = if (next == null) View.GONE else View.VISIBLE
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

    private inner class Row(val box: FrameLayout, val logo: ImageView, val title: TextView) : RecyclerView.ViewHolder(box)

    private inner class Rows : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = channels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val box = FrameLayout(activity)
            val pad = (cardW * 0.09f).toInt()
            val logo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_START }
            val title = text(0.0092f, Color.WHITE)
            box.addView(logo, FrameLayout.LayoutParams(cardW - pad * 2, (cardH * 0.46f).toInt()).apply { leftMargin = pad; topMargin = (cardH * 0.14f).toInt() })
            box.addView(title, FrameLayout.LayoutParams(cardW - pad * 2, -2, Gravity.BOTTOM).apply { leftMargin = pad; bottomMargin = (cardH * 0.10f).toInt() })
            box.layoutParams = RecyclerView.LayoutParams(cardW, cardH).apply { bottomMargin = gap }
            box.pivotX = 0f; box.pivotY = cardH / 2f
            return Row(box, logo, title)
        }
        override fun onBindViewHolder(h: Row, position: Int) {
            val ch = channels[position]
            val on = position == sel
            h.box.background = if (on) round(0xF02A2E36.toInt(), px(0.0075f), maxOf(2, px(0.0016f).toInt()), Color.WHITE) else round(0xC71F2228.toInt(), px(0.0075f), 1, 0x26FFFFFF)
            h.box.scaleX = if (on) 1.08f else 1f; h.box.scaleY = if (on) 1.08f else 1f
            h.box.translationZ = if (on) 6f else 0f
            h.logo.load(ch.logoUrl)
            val now = nowNext(ch).first
            h.title.text = now?.title ?: ch.name
            h.title.alpha = if (on) 1f else 0.92f
            if (now == null) needEpg(ch) { val i = channels.indexOfFirst { it.id == ch.id }; if (i >= 0 && visible) notifyItemChanged(i) }
        }
    }
}
