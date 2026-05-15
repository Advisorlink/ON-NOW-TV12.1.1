package tv.vesper.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-player Live Guide overlay controller.
 *
 * The overlay XML lives in `activity_vlc_player.xml` under
 * `@id/guide_root`.  This class is responsible for:
 *   • Loading the channel/category/EPG payload that LiveTV.jsx
 *     pushed via `WebAppInterface.setLiveGuide(…)`.
 *   • Binding both RecyclerViews and managing D-pad focus +
 *     category-selection state.
 *   • Calling back to VlcPlayerActivity when the user picks a
 *     channel — the activity then swaps the libVLC media in place
 *     (no Activity restart, no boot splash).
 *   • Computing "Now / Next" + a live progress bar from the EPG
 *     timestamps.
 *
 * Built deliberately small + framework-only so it runs comfortably
 * on the HK1 box's Cortex-A7 / Android 7.1.2.  No external image
 * libraries (we just decode logos lazily via a tiny background
 * thread pool).  No coroutines (the WebView already pre-built the
 * stream URLs so channel switching is purely synchronous on the
 * main thread).
 */
class LiveGuideController(
    private val activity: VlcPlayerActivity,
    private val root: View,
) {
    data class Category(val id: String, val name: String, val count: Int)
    data class Channel(
        val streamId: String,
        val name: String,
        val logo: String,
        val categoryId: String,
        val epgChannelId: String,
        val streamUrl: String,
    )
    data class Programme(val title: String, val startTs: Long, val stopTs: Long)

    /* Loaded once on first open then cached in memory.  The JSON
       payload comes from SharedPreferences key "live_guide" written
       by WebAppInterface.setLiveGuide(). */
    private var categories: List<Category> = emptyList()
    private var channels: List<Channel> = emptyList()
    private var epg: Map<String, List<Programme>> = emptyMap()

    /* UI state */
    private var selectedCategoryId: String? = null
    private var currentChannelStreamId: String? = null
    private val visibleChannels: MutableList<Channel> = mutableListOf()

    /* Cached views */
    private val empty: TextView = root.findViewById(R.id.guide_empty)
    private val categoryLabel: TextView = root.findViewById(R.id.guide_category_label)
    private val subtitle: TextView = root.findViewById(R.id.guide_subtitle)
    private val catRv: RecyclerView = root.findViewById(R.id.guide_categories)
    private val chanRv: RecyclerView = root.findViewById(R.id.guide_channels)

    private val catAdapter = CategoryAdapter()
    private val chanAdapter = ChannelAdapter()

    /* Tiny LRU + background decoder for channel logos.  We can't
       pull in Glide/Coil from this layer (they balloon the APK by
       ~500 KB each), so a 16-entry in-memory bitmap cache is the
       cheapest thing that works on the HK1's slow disk. */
    private val logoCache = androidx.collection.LruCache<String, android.graphics.Bitmap>(48)
    private val logoExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    init {
        catRv.layoutManager = LinearLayoutManager(activity)
        catRv.adapter = catAdapter
        chanRv.layoutManager = LinearLayoutManager(activity)
        chanRv.adapter = chanAdapter
    }

    /** Set the channel that's currently playing — used so the overlay
     *  can mark it with the "ON NOW" pill and highlight its row. */
    fun setCurrentPlayingChannel(streamId: String?) {
        currentChannelStreamId = streamId
    }

    /** Show the overlay.  Loads data lazily on first open + every
     *  subsequent open (so freshly-pushed channel lists from the
     *  WebView take effect without a player restart). */
    fun open() {
        loadFromPreferences()
        if (channels.isEmpty()) {
            empty.visibility = View.VISIBLE
            catRv.visibility = View.GONE
            chanRv.visibility = View.GONE
            subtitle.text = ""
        } else {
            empty.visibility = View.GONE
            catRv.visibility = View.VISIBLE
            chanRv.visibility = View.VISIBLE
            /* Default to the currently-playing channel's category if
               we know it; otherwise the first category in the list. */
            val current = channels.firstOrNull { it.streamId == currentChannelStreamId }
            selectedCategoryId = current?.categoryId ?: categories.firstOrNull()?.id
            rebuildVisibleChannels()
            subtitle.text = "${channels.size} channels · ${categories.size} categories"
        }
        catAdapter.notifyDataSetChanged()
        chanAdapter.notifyDataSetChanged()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
        /* Focus the currently-playing channel if visible, else the
           first channel row. */
        chanRv.post {
            val idx = visibleChannels.indexOfFirst { it.streamId == currentChannelStreamId }
            val target = if (idx >= 0) idx else 0
            val vh = chanRv.findViewHolderForAdapterPosition(target)
            vh?.itemView?.requestFocus() ?: run {
                /* Layout may not have completed yet — retry. */
                chanRv.postDelayed({
                    chanRv.findViewHolderForAdapterPosition(target)
                        ?.itemView?.requestFocus()
                }, 80)
            }
        }
    }

    fun close() {
        root.animate().alpha(0f).setDuration(140).withEndAction {
            root.visibility = View.GONE
        }.start()
    }

    fun isOpen(): Boolean = root.visibility == View.VISIBLE

    /** Returns true if the key was consumed by the overlay (so the
     *  Activity should NOT fall through to its own back-handler). */
    fun onBackPressed(): Boolean {
        if (!isOpen()) return false
        close()
        return true
    }

    /** Reload the channel/category/EPG payload that LiveTV.jsx
     *  pushed into SharedPreferences via setLiveGuide. */
    private fun loadFromPreferences() {
        val prefs = activity.getSharedPreferences(
            "live_guide", Context.MODE_PRIVATE
        )
        categories = parseCategories(prefs.getString("categories", "[]") ?: "[]")
        channels = parseChannels(prefs.getString("channels", "[]") ?: "[]")
        epg = parseEpg(prefs.getString("epg", "{}") ?: "{}")
    }

    private fun parseCategories(raw: String): List<Category> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Category(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                count = o.optInt("count", 0),
            )
        }.filter { it.id.isNotBlank() }
    } catch (_: Throwable) { emptyList() }

    private fun parseChannels(raw: String): List<Channel> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val sid = o.optString("stream_id", "")
            val url = o.optString("stream_url", "")
            if (sid.isBlank() || url.isBlank()) null else Channel(
                streamId = sid,
                name = o.optString("name", "Channel $sid"),
                logo = o.optString("logo", ""),
                categoryId = o.optString("category_id", ""),
                epgChannelId = o.optString("epg_channel_id", ""),
                streamUrl = url,
            )
        }
    } catch (_: Throwable) { emptyList() }

    private fun parseEpg(raw: String): Map<String, List<Programme>> = try {
        val obj = JSONObject(raw)
        val out = HashMap<String, List<Programme>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val sid = keys.next()
            val arr = obj.optJSONArray(sid) ?: continue
            val list = ArrayList<Programme>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                list.add(
                    Programme(
                        title = p.optString("title", ""),
                        startTs = p.optLong("startTimestamp", 0L),
                        stopTs  = p.optLong("stopTimestamp", 0L),
                    )
                )
            }
            if (list.isNotEmpty()) out[sid] = list
        }
        out
    } catch (_: Throwable) { emptyMap() }

    private fun rebuildVisibleChannels() {
        visibleChannels.clear()
        val cat = categories.firstOrNull { it.id == selectedCategoryId }
        categoryLabel.text = cat?.name?.uppercase() ?: "ALL CHANNELS"
        val target = selectedCategoryId
        channels.forEach {
            if (target == null || it.categoryId == target) visibleChannels.add(it)
        }
        chanAdapter.notifyDataSetChanged()
    }

    // ───────────────────────── Category adapter ─────────────────────────

    private inner class CategoryAdapter :
        RecyclerView.Adapter<CategoryAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val root: View = itemView.findViewById(R.id.row_root)
            val accent: View = itemView.findViewById(R.id.row_accent)
            val label: TextView = itemView.findViewById(R.id.row_label)
            val count: TextView = itemView.findViewById(R.id.row_count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_guide_category, parent, false)
        )

        override fun getItemCount() = categories.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val cat = categories[position]
            holder.label.text = cat.name
            holder.count.text = if (cat.count > 0) "${cat.count}" else ""
            val isSelected = cat.id == selectedCategoryId
            holder.accent.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            holder.label.setTextColor(
                if (isSelected) 0xFFFFFFFF.toInt() else 0xFFC7CFDB.toInt()
            )
            /* Focus → make this category the selected one and
               update the channel list.  This means the user can
               just D-pad up/down the categories rail and watch the
               channel list change in real-time.  Pressing OK in
               addition then focuses across to the channel list. */
            holder.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && selectedCategoryId != cat.id) {
                    selectedCategoryId = cat.id
                    rebuildVisibleChannels()
                    notifyDataSetChanged()
                }
            }
            holder.root.setOnClickListener {
                /* OK on a category jumps focus into the channel list. */
                chanRv.requestFocus()
                chanRv.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }
        }
    }

    // ───────────────────────── Channel adapter ─────────────────────────

    private inner class ChannelAdapter :
        RecyclerView.Adapter<ChannelAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val root: View = itemView.findViewById(R.id.row_root)
            val logo: ImageView = itemView.findViewById(R.id.row_logo)
            val name: TextView = itemView.findViewById(R.id.row_name)
            val now: TextView = itemView.findViewById(R.id.row_now)
            val next: TextView = itemView.findViewById(R.id.row_next)
            val progress: View = itemView.findViewById(R.id.row_progress)
            val playingPill: TextView = itemView.findViewById(R.id.row_playing_pill)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_guide_channel, parent, false)
        )

        override fun getItemCount() = visibleChannels.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = visibleChannels[position]
            holder.name.text = ch.name

            /* Programme info — Now + Next + progress. */
            val nowSec = System.currentTimeMillis() / 1000
            val list = epg[ch.streamId].orEmpty()
            val nowProg = list.firstOrNull { it.startTs <= nowSec && it.stopTs > nowSec }
            val nextProg = if (nowProg != null) {
                list.firstOrNull { it.startTs >= nowProg.stopTs }
            } else list.firstOrNull { it.startTs > nowSec }

            if (nowProg != null) {
                holder.now.text = nowProg.title
                /* Width of progress = container width × elapsed/total. */
                val total = (nowProg.stopTs - nowProg.startTs).coerceAtLeast(1L)
                val elapsed = (nowSec - nowProg.startTs).coerceIn(0L, total)
                val ratio = elapsed.toFloat() / total.toFloat()
                /* Defer measurement to post so we know the parent width. */
                holder.progress.post {
                    val parentW = (holder.progress.parent as? View)?.width ?: 0
                    val lp = holder.progress.layoutParams
                    lp.width = (parentW * ratio).toInt().coerceAtLeast(2)
                    holder.progress.layoutParams = lp
                }
            } else {
                holder.now.text = "No EPG data"
                holder.progress.post {
                    val lp = holder.progress.layoutParams
                    lp.width = 0
                    holder.progress.layoutParams = lp
                }
            }

            holder.next.text = if (nextProg != null) {
                "NEXT · ${formatTime(nextProg.startTs)} · ${nextProg.title}"
            } else ""

            holder.playingPill.visibility =
                if (ch.streamId == currentChannelStreamId) View.VISIBLE else View.GONE

            /* Logo loading.  Async, cached, gracefully falls back to
               a tinted initial letter on failure / no URL. */
            holder.logo.tag = ch.logo
            holder.logo.setImageDrawable(null)
            if (ch.logo.isBlank()) {
                holder.logo.setImageDrawable(makeInitialDrawable(ch.name))
            } else {
                val cached = logoCache.get(ch.logo)
                if (cached != null) {
                    holder.logo.setImageBitmap(cached)
                } else {
                    holder.logo.setImageDrawable(makeInitialDrawable(ch.name))
                    val url = ch.logo
                    logoExecutor.execute {
                        try {
                            val bm = loadBitmap(url) ?: return@execute
                            logoCache.put(url, bm)
                            holder.logo.post {
                                /* Ensure the row hasn't been recycled
                                   into a different channel before we
                                   apply the bitmap. */
                                if (holder.logo.tag == url) {
                                    holder.logo.setImageBitmap(bm)
                                }
                            }
                        } catch (_: Throwable) { /* swallow */ }
                    }
                }
            }

            holder.root.setOnClickListener {
                activity.swapChannel(ch.streamUrl, ch.name, ch.logo, ch.streamId)
                close()
            }
        }

        override fun onViewRecycled(holder: VH) {
            holder.logo.setImageDrawable(null)
        }
    }

    // ───────────────────────── Helpers ─────────────────────────

    private fun formatTime(ts: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        return String.format(java.util.Locale.US, "%02d:%02d", h, m)
    }

    private fun loadBitmap(url: String): android.graphics.Bitmap? {
        return try {
            val u = java.net.URL(url)
            val c = u.openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.requestMethod = "GET"
            c.instanceFollowRedirects = true
            val code = c.responseCode
            if (code !in 200..299) return null
            c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) { null }
    }

    private fun makeInitialDrawable(name: String): android.graphics.drawable.Drawable {
        val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        return InitialDrawable(letter)
    }

    /** Lightweight initial-letter avatar — drawn directly via Canvas
     *  so we don't need a Bitmap allocation per row. */
    private inner class InitialDrawable(private val letter: String) :
        android.graphics.drawable.Drawable() {
        private val bgPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = 0x335DC8FF
        }
        private val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = 0xFF5DC8FF.toInt()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val r = Math.min(b.width(), b.height()) * 0.5f
            canvas.drawCircle(
                b.exactCenterX(), b.exactCenterY(), r * 0.85f, bgPaint
            )
            textPaint.textSize = r * 0.9f
            val cy = b.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(letter, b.exactCenterX(), cy, textPaint)
        }
        override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha; textPaint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { /* no-op */ }
        @Suppress("DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
