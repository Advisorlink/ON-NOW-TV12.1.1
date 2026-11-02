package tv.onnowtv.livetv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.data.FavouritesStore
import tv.onnowtv.livetv.data.ParentalLock
import tv.onnowtv.livetv.data.Settings

/** Remote-friendly list editor: pick-up-and-move reordering, or tick/untick toggling. */
class ListEditActivity : AppCompatActivity() {

    private data class Item(val id: String, val label: String, val sub: String?)

    private lateinit var mode: String
    private val items = ArrayList<Item>()
    private val ticked = HashSet<String>()
    private var grabbed = -1
    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private val adapter = Adapter()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Settings.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_GROUPS
        load()
        setContentView(buildUi())
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private val reorder get() = mode == MODE_GROUPS || mode == MODE_FAVOURITES

    private fun load() {
        val bundle = BundleHolder.current
        when (mode) {
            MODE_FAVOURITES -> {
                val favs = FavouritesStore.load(this)
                val byId = bundle?.channels.orEmpty().associateBy { it.id }
                val ordered = Settings.favouritesOrder(this).filter { it in favs } + favs.filter { it !in Settings.favouritesOrder(this) }
                items.addAll(ordered.mapNotNull { id -> byId[id]?.let { Item(id, Settings.cleanName(this, it.name), it.lcn) } })
            }
            else -> {
                val cats = bundle?.categories.orEmpty().filter { !it.name.contains("#####") && !it.name.matches(Regex("^={3,}.*={3,}$")) }
                val order = Settings.groupOrder(this)
                val sorted = cats.sortedBy { c -> order.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it } }
                items.addAll(sorted.map { Item(it.id, it.name, "${it.channelCount} channels") })
                when (mode) {
                    MODE_HIDE -> ticked.addAll(Settings.hiddenGroups(this))
                    MODE_LOCK -> ticked.addAll(ParentalLock.lockedIds(this))
                }
            }
        }
    }

    private fun save() {
        when (mode) {
            MODE_GROUPS -> Settings.setGroupOrder(this, items.map { it.id })
            MODE_FAVOURITES -> Settings.setFavouritesOrder(this, items.map { it.id })
            MODE_HIDE -> Settings.setHiddenGroups(this, ticked)
            MODE_LOCK -> {
                val before = ParentalLock.lockedIds(this)
                (ticked - before).forEach { ParentalLock.lock(this, it) }
                (before - ticked).forEach { ParentalLock.unlock(this, it) }
                Settings.dirty = true
            }
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B1020.toInt()); setPadding(dp(48f), dp(32f), dp(48f), dp(24f)) }
        val title = when (mode) { MODE_GROUPS -> "Reorder groups"; MODE_FAVOURITES -> "Reorder favourites"; MODE_HIDE -> "Hide groups"; else -> "Locked groups" }
        root.addView(TextView(this).apply { text = "SETTINGS"; textSize = 11f; setTextColor(ACCENT); letterSpacing = 0.3f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = title; textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        hint = TextView(this).apply { textSize = 13f; setTextColor(DIM); setPadding(0, dp(4f), 0, dp(16f)) }
        root.addView(hint)
        updateHint()
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ListEditActivity); adapter = this@ListEditActivity.adapter; isVerticalScrollBarEnabled = false }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply { text = if (reorder) "BACK  saves and returns" else "OK  tick / untick   ·   BACK  saves and returns"; textSize = 12f; setTextColor(DIM); setPadding(0, dp(14f), 0, 0) })
        if (items.isEmpty()) hint.text = if (mode == MODE_FAVOURITES) "No favourites yet — long-press a channel in the guide to add one." else "No groups found."
        return root
    }

    private fun updateHint() {
        hint.text = when {
            !reorder -> "Press OK on a group to tick or untick it."
            grabbed >= 0 -> "Moving “${items[grabbed].label}” — Up/Down to move, OK to drop."
            else -> "Press OK on an item to pick it up, Up/Down to move it, OK again to drop."
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (grabbed >= 0 && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            val to = grabbed + if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
            if (to in items.indices) {
                java.util.Collections.swap(items, grabbed, to)
                adapter.notifyItemMoved(grabbed, to)
                grabbed = to
                list.scrollToPosition(to)
                list.post { list.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus(); adapter.notifyItemChanged(to) }
                updateHint()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (grabbed >= 0) { grabbed = -1; adapter.notifyDataSetChanged(); updateHint(); return true }
            save(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); finish(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val row: LinearLayout, val mark: TextView, val label: TextView, val sub: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setPadding(dp(18f), dp(13f), dp(18f), dp(13f))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) }
            }
            val mark = TextView(parent.context).apply { textSize = 18f; width = dp(40f); setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD) }
            val label = TextView(parent.context).apply { textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }
            val sub = TextView(parent.context).apply { textSize = 12f; setTextColor(DIM) }
            val texts = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(sub) }
            row.addView(mark); row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            return VH(row, mark, label, sub)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            h.label.text = it.label
            h.sub.text = it.sub ?: ""; h.sub.visibility = if (it.sub == null) View.GONE else View.VISIBLE
            h.mark.text = when { reorder -> if (position == grabbed) "⇅" else "≡"; it.id in ticked -> "☑"; else -> "☐" }
            h.mark.setTextColor(if (!reorder && it.id in ticked) 0xFF7CF1A1.toInt() else ACCENT)
            fun paint(f: Boolean) {
                val g = h.bindingAdapterPosition == grabbed
                h.row.background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(when { g -> 0x335DC8FF; f -> 0x2EFFFFFF; else -> 0x14FFFFFF })
                    if (f || g) setStroke(dp(2f), if (g) 0xFF7CF1A1.toInt() else ACCENT)
                }
                h.row.scaleX = if (f || g) 1.01f else 1f; h.row.scaleY = h.row.scaleX
            }
            paint(h.row.hasFocus())
            h.row.setOnFocusChangeListener { _, f -> paint(f) }
            h.row.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (reorder) {
                    val prev = grabbed
                    grabbed = if (grabbed == pos) -1 else pos
                    if (prev >= 0) notifyItemChanged(prev)
                    notifyItemChanged(pos)
                    if (grabbed < 0) save()
                } else {
                    if (!ticked.remove(items[pos].id)) ticked.add(items[pos].id)
                    notifyItemChanged(pos); save()
                }
                updateHint()
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_GROUPS = "groups"
        const val MODE_FAVOURITES = "favourites"
        const val MODE_HIDE = "hide"
        const val MODE_LOCK = "lock"
        private const val ACCENT = 0xFF5DC8FF.toInt()
        private const val DIM = 0xFF9DA5B5.toInt()
        fun open(ctx: Context, mode: String) = ctx.startActivity(Intent(ctx, ListEditActivity::class.java).putExtra(EXTRA_MODE, mode))
    }
}
