package tv.vesper.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-player Live Guide controller — REDESIGNED in v2.6.2.
 *
 * Old design (v2.5.7-v2.6.1): full-screen overlay, two-column
 * category-and-channels layout, with the video hidden behind it.
 *
 * New design: a 460dp-wide vertical panel that slides in from the
 * LEFT edge so the video remains fully visible to the right of it.
 * The currently-focused channel renders a programme detail card in
 * the bottom-right corner with a poster backdrop, programme title,
 * time range, progress bar, and Next-on text.  Categories tucked
 * behind a pill rail at the top of the panel so the channel list is
 * the star.
 *
 * Design language: dark glass + cyan accent (`#5DC8FF`), red LIVE
 * pill, monospace eyebrows.  No traditional player buttons in the
 * overlay — D-pad up/down to navigate, OK to tune, BACK to close.
 *
 * Performance: still framework-only (no external image libs), with
 * a tiny LRU bitmap cache for logos and a background executor.
 * Tested down to the HK1 box's Cortex-A7 / Android 7.1.2.
 */
class LiveGuideController(
    private val activity: VlcPlayerActivity,
    private val root: View,
) {
    data class Category(val id: String, val name: String, val count: Int)
    data class Channel(
        val streamId: String,
        val number: Int,     // 1-based ordinal within its category
        val name: String,
        val logo: String,
        val categoryId: String,
        val epgChannelId: String,
        val streamUrl: String,
    )
    data class Programme(
        val title: String,
        val desc: String,
        val startTs: Long,
        val stopTs: Long,
        val season: String,
        val episode: String,
        val episodeTitle: String,
        val year: String,
        val rating: String,
        val category: String,
    )

    private var categories: List<Category> = emptyList()
    private var channels: List<Channel> = emptyList()
    private var epg: Map<String, List<Programme>> = emptyMap()
    private var favorites: Set<String> = emptySet()

    /* UI state */
    private var selectedCategoryId: String? = null
    private var favoritesActive: Boolean = false  // "★ Favourites" pill selected
    private var currentChannelStreamId: String? = null
    private val visibleChannels: MutableList<Channel> = mutableListOf()

    /* Views */
    private val panel: View = root.findViewById(R.id.guide_panel)
    private val scrim: View = root.findViewById(R.id.guide_scrim)
    private val empty: TextView = root.findViewById(R.id.guide_empty)
    private val subtitle: TextView = root.findViewById(R.id.guide_subtitle)
    private val chanRv: RecyclerView = root.findViewById(R.id.guide_channels)
    private val categoryPillRow: LinearLayout = root.findViewById(R.id.guide_category_pills)

    /* M14 layout containers — we animate these on open/close
       instead of the legacy `panel`. */
    private val m14Header: View = root.findViewById(R.id.m14_header)
    private val m14ListWrap: View = root.findViewById(R.id.m14_list_wrap)
    private val m14Rail: View = root.findViewById(R.id.m14_rail)

    /* Programme detail card */
    private val detailCard: View = root.findViewById(R.id.guide_detail)
    private val detailBackdrop: ImageView = root.findViewById(R.id.detail_backdrop)
    private val detailLogo: ImageView = root.findViewById(R.id.detail_channel_logo)
    private val detailChannelName: TextView = root.findViewById(R.id.detail_channel_name)
    private val detailProgrammeTitle: TextView = root.findViewById(R.id.detail_programme_title)
    private val detailTimeRange: TextView = root.findViewById(R.id.detail_time_range)
    private val detailNext: TextView = root.findViewById(R.id.detail_next)
    private val detailProgress: View = root.findViewById(R.id.detail_progress)
    private val detailDescription: TextView = root.findViewById(R.id.detail_description)
    private val detailChipEpisode: TextView = root.findViewById(R.id.detail_chip_episode)
    private val detailChipYear: TextView = root.findViewById(R.id.detail_chip_year)
    private val detailChipRating: TextView = root.findViewById(R.id.detail_chip_rating)
    private val detailChipCategory: TextView = root.findViewById(R.id.detail_chip_category)
    private val detailDivider: View = root.findViewById(R.id.detail_divider)

    /* v2.7.03 — M14 layout views.  Top header (channel logo, name,
       clock, date) + the four "Coming Up Next" cards in the bottom
       rail.  The "On Now" card on the left of the rail reuses the
       existing detail_* views, repurposed inside the M14 layout. */
    private val m14HeaderLogo: ImageView = root.findViewById(R.id.m14_header_logo)
    private val m14HeaderName: TextView = root.findViewById(R.id.m14_header_name)
    private val m14HeaderClock: TextView = root.findViewById(R.id.m14_header_clock)
    private val m14HeaderDate: TextView = root.findViewById(R.id.m14_header_date)
    private val m14Next1Title: TextView = root.findViewById(R.id.m14_next1_title)
    private val m14Next1Time: TextView = root.findViewById(R.id.m14_next1_time)
    private val m14Next1Bg: ImageView = root.findViewById(R.id.m14_next1_bg)
    private val m14Next2Title: TextView = root.findViewById(R.id.m14_next2_title)
    private val m14Next2Time: TextView = root.findViewById(R.id.m14_next2_time)
    private val m14Next2Bg: ImageView = root.findViewById(R.id.m14_next2_bg)
    private val m14Next3Title: TextView = root.findViewById(R.id.m14_next3_title)
    private val m14Next3Time: TextView = root.findViewById(R.id.m14_next3_time)
    private val m14Next3Bg: ImageView = root.findViewById(R.id.m14_next3_bg)
    private val m14Next4Title: TextView = root.findViewById(R.id.m14_next4_title)
    private val m14Next4Time: TextView = root.findViewById(R.id.m14_next4_time)
    private val m14Next4Bg: ImageView = root.findViewById(R.id.m14_next4_bg)
    private val m14OnNowBg: ImageView = root.findViewById(R.id.m14_onnow_bg)

    /* v2.7.04 — TMDB backdrop cache for the M14 rail.  Key is the
       lowercased EPG programme title; value is the full TMDB
       backdrop URL (or "" as a negative cache so we don't retry
       the lookup every 30 s for a title that has no TMDB match).
       Resolved by hitting /api/tmdb/search?q={title} on the
       backend (which itself caches for 1 h).  This lets the
       "Coming Up Next" cards feel like Plex / Netflix Up Next
       without a heavy per-render API cost. */
    private val tmdbBackdropCache = androidx.collection.LruCache<String, String>(256)
    private val tmdbExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val backendBase: String by lazy {
        activity.getString(R.string.app_url).trim().trimEnd('/')
    }

    /* Repeating clock-tick runnable — refreshes the top-right time
       display every 30 s while the guide is open. */
    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    private val chanAdapter = ChannelAdapter()

    /* Logo cache (used by both the channel list and the detail card). */
    private val logoCache = androidx.collection.LruCache<String, android.graphics.Bitmap>(64)
    private val logoExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    init {
        chanRv.layoutManager = LinearLayoutManager(activity)
        chanRv.adapter = chanAdapter
    }

    fun setCurrentPlayingChannel(streamId: String?) {
        currentChannelStreamId = streamId
    }

    fun isOpen(): Boolean = root.visibility == View.VISIBLE

    /**
     * Show the overlay with a left-edge slide-in animation.  The
     * panel is the only thing that animates — the scrim and detail
     * card cross-fade.  Total in-time ~240ms which feels snappy
     * without being abrupt.
     */
    fun open() {
        loadFromPreferences()
        if (channels.isEmpty()) {
            empty.visibility = View.VISIBLE
            chanRv.visibility = View.GONE
            categoryPillRow.visibility = View.GONE
            subtitle.text = ""
        } else {
            empty.visibility = View.GONE
            chanRv.visibility = View.VISIBLE
            categoryPillRow.visibility = View.VISIBLE
            /* Default category = the currently-playing channel's
               category if known, else "All channels". */
            val current = channels.firstOrNull { it.streamId == currentChannelStreamId }
            selectedCategoryId = current?.categoryId
            renderCategoryPills()
            rebuildVisibleChannels()
            subtitle.text = "${channels.size} channels · ${categories.size} categories"
        }

        /* v2.7.03 — set initial M14 entry states BEFORE making
           root visible so we never flash the fully-rendered UI
           for a frame and then animate it. */
        m14Header.translationY = -60f
        m14Header.alpha = 0f
        m14ListWrap.alpha = 0f
        m14Rail.translationY = 120f
        m14Rail.alpha = 0f
        scrim.alpha = 0f

        root.visibility = View.VISIBLE

        /* M14 — start the clock tick.  Hourly date display is set
           once per open + every clock tick. */
        updateClock()
        clockHandler.removeCallbacks(clockTick)
        clockHandler.postDelayed(clockTick, 30_000L)

        /* Animate the scrim (cross-fade) and the panel (slide-in).
           Initial state set explicitly because the previous open
           may have left them mid-animation. */
        scrim.animate().alpha(1f).setDuration(240).start()

        panel.translationX = -panel.width.toFloat().let { if (it == 0f) -460f else it }
        panel.animate()
            .translationX(0f)
            .setDuration(280)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        /* v2.7.03 — M14 entry choreography: header drops from
           above, list fades in, rail rises from below.  Each
           runs on its own timeline so the screen "assembles"
           cinematically over ~340 ms. */
        m14Header.animate().translationY(0f).alpha(1f)
            .setDuration(280)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        m14ListWrap.animate().alpha(1f).setStartDelay(80)
            .setDuration(260).start()
        m14Rail.animate().translationY(0f).alpha(1f)
            .setStartDelay(60)
            .setDuration(320)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        /* Auto-focus the currently-playing channel so the user can
           D-pad-OK to refresh / re-tune.  Falls back to row 0. */
        chanRv.post {
            val idx = visibleChannels.indexOfFirst { it.streamId == currentChannelStreamId }
            val target = if (idx >= 0) idx else 0
            chanRv.scrollToPosition(target)
            chanRv.postDelayed({
                val vh = chanRv.findViewHolderForAdapterPosition(target)
                vh?.itemView?.requestFocus()
                /* Populate the detail card for the auto-focused
                   channel so the right-side card isn't empty on
                   first open. */
                visibleChannels.getOrNull(target)?.let { renderDetail(it) }
            }, 80)
        }
    }

    fun close() {
        if (root.visibility != View.VISIBLE) return
        clockHandler.removeCallbacks(clockTick)
        scrim.animate().alpha(0f).setDuration(180).start()
        panel.animate()
            .translationX(-panel.width.toFloat())
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { root.visibility = View.GONE }
            .start()
        detailCard.animate().alpha(0f).setDuration(140).start()
        /* v2.7.03 — M14 exit: header rises out top, rail drops
           out bottom, list cross-fades.  Same total duration as
           the legacy slide so the visibility=GONE end-callback
           still fires at the right time. */
        m14Header.animate().translationY(-60f).alpha(0f)
            .setDuration(180).start()
        m14ListWrap.animate().alpha(0f).setDuration(160).start()
        m14Rail.animate().translationY(120f).alpha(0f)
            .setDuration(220).start()
    }

    fun onBackPressed(): Boolean {
        if (!isOpen()) return false
        close()
        return true
    }

    // ───────────────────────── Data load ──────────────────────────

    private fun loadFromPreferences() {
        val prefs = activity.getSharedPreferences(
            "live_guide", Context.MODE_PRIVATE
        )
        categories = parseCategories(prefs.getString("categories", "[]") ?: "[]")
        channels = parseChannels(prefs.getString("channels", "[]") ?: "[]")
        epg = parseEpg(prefs.getString("epg", "{}") ?: "{}")
        favorites = parseFavorites(prefs.getString("favorites", "[]") ?: "[]")
    }

    private fun parseFavorites(raw: String): Set<String> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    } catch (_: Throwable) { emptySet() }

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
        /* Pre-compute per-category running index for the row number
           badge.  Order in the JSON IS the order the user expects to
           see, so we just increment within each category. */
        val countByCat = HashMap<String, Int>()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val sid = o.optString("stream_id", "")
            val url = o.optString("stream_url", "")
            val catId = o.optString("category_id", "")
            if (sid.isBlank() || url.isBlank()) return@mapNotNull null
            val n = (countByCat[catId] ?: 0) + 1
            countByCat[catId] = n
            Channel(
                streamId = sid,
                number = n,
                name = o.optString("name", "Channel $sid"),
                logo = o.optString("logo", ""),
                categoryId = catId,
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
                        title       = p.optString("title", ""),
                        desc        = p.optString("desc", ""),
                        startTs     = p.optLong("startTimestamp", 0L),
                        stopTs      = p.optLong("stopTimestamp", 0L),
                        season      = p.optString("season", ""),
                        episode     = p.optString("episode", ""),
                        episodeTitle= p.optString("episodeTitle", ""),
                        year        = p.optString("year", ""),
                        rating      = p.optString("rating", ""),
                        category    = p.optString("category", ""),
                    )
                )
            }
            if (list.isNotEmpty()) out[sid] = list
        }
        out
    } catch (_: Throwable) { emptyMap() }

    private fun rebuildVisibleChannels() {
        visibleChannels.clear()
        val target = selectedCategoryId
        channels.forEach { ch ->
            if (favoritesActive) {
                if (ch.streamId in favorites) visibleChannels.add(ch)
            } else if (target == null || ch.categoryId == target) {
                visibleChannels.add(ch)
            }
        }
        chanAdapter.notifyDataSetChanged()
    }

    // ───────────────────────── Category pills ─────────────────────

    private fun renderCategoryPills() {
        categoryPillRow.removeAllViews()
        /* Favourites pill FIRST so it's always one D-pad tap away.
           Only shown when the user actually has at least one favourite. */
        if (favorites.isNotEmpty()) {
            addPill(
                "★ FAVOURITES · ${favorites.size}",
                catId = null,
                active = favoritesActive,
                isFavorites = true,
            )
        }
        /* Then "All" — clears both the category filter AND the
           favourites filter. */
        addPill(
            "ALL · ${channels.size}",
            catId = null,
            active = !favoritesActive && selectedCategoryId == null,
            isFavorites = false,
        )
        categories.forEach { cat ->
            addPill(
                cat.name.uppercase(),
                catId = cat.id,
                active = !favoritesActive && selectedCategoryId == cat.id,
                isFavorites = false,
            )
        }
    }

    private fun addPill(
        label: String,
        catId: String?,
        active: Boolean,
        isFavorites: Boolean,
    ) {
        val tv = LayoutInflater.from(activity)
            .inflate(R.layout.item_guide_category_pill, categoryPillRow, false) as TextView
        tv.text = label
        tv.isSelected = active
        tv.setOnClickListener {
            if (isFavorites) {
                favoritesActive = true
                selectedCategoryId = null
            } else {
                favoritesActive = false
                selectedCategoryId = catId
            }
            renderCategoryPills()
            rebuildVisibleChannels()
            chanRv.post {
                chanRv.scrollToPosition(0)
                visibleChannels.firstOrNull()?.let { renderDetail(it) }
            }
        }
        categoryPillRow.addView(tv)
    }

    // ───────────────────────── Channel adapter ────────────────────

    private inner class ChannelAdapter :
        RecyclerView.Adapter<ChannelAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val root: View = itemView.findViewById(R.id.row_root)
            val logo: ImageView = itemView.findViewById(R.id.row_logo)
            val name: TextView = itemView.findViewById(R.id.row_name)
            val now: TextView = itemView.findViewById(R.id.row_now)
            val progress: View = itemView.findViewById(R.id.row_progress)
            val number: TextView = itemView.findViewById(R.id.row_number)
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
            holder.number.text = String.format("%03d", ch.number)

            val nowSec = System.currentTimeMillis() / 1000
            val list = epg[ch.streamId].orEmpty()
            val nowProg = list.firstOrNull { it.startTs <= nowSec && it.stopTs > nowSec }

            if (nowProg != null) {
                holder.now.text = nowProg.title
                val total = (nowProg.stopTs - nowProg.startTs).coerceAtLeast(1L)
                val elapsed = (nowSec - nowProg.startTs).coerceIn(0L, total)
                val ratio = elapsed.toFloat() / total.toFloat()
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

            holder.playingPill.visibility =
                if (ch.streamId == currentChannelStreamId) View.VISIBLE else View.GONE

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
                                if (holder.logo.tag == url) holder.logo.setImageBitmap(bm)
                            }
                        } catch (_: Throwable) { /* swallow */ }
                    }
                }
            }

            /* v2.7.03 — Focus → live-refresh the detail card AND
               scale the row up (M14 spec: focused row pops out at
               1.12×, with a soft blue glow elevation). */
            holder.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    renderDetail(ch)
                    holder.root.animate()
                        .scaleX(1.12f).scaleY(1.12f)
                        .translationX(24f)
                        .setDuration(160).start()
                    holder.root.elevation = 24f
                } else {
                    holder.root.animate()
                        .scaleX(1f).scaleY(1f)
                        .translationX(0f)
                        .setDuration(140).start()
                    holder.root.elevation = 0f
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

    // ───────────────────────── Detail card ────────────────────────

    /**
     * Refresh the bottom-right detail card to reflect the given
     * channel — channel logo + name, currently-airing programme
     * title, time range, progress bar, and the next programme
     * preview.
     *
     * Fades the card in (alpha 0→1) on the first call after the
     * overlay opens — subsequent calls update content in place
     * without re-animating, so D-padding through the list feels
     * fluid.
     */
    private fun renderDetail(ch: Channel) {
        detailChannelName.text = ch.name.uppercase()

        val list = epg[ch.streamId].orEmpty()
        val nowSec = System.currentTimeMillis() / 1000
        val nowProg = list.firstOrNull { it.startTs <= nowSec && it.stopTs > nowSec }
        val nextProg = if (nowProg != null) {
            list.firstOrNull { it.startTs >= nowProg.stopTs }
        } else list.firstOrNull { it.startTs > nowSec }

        /* v2.7.03 — M14 top header: channel logo + name reflect
           the currently-focused channel.  The clock + date are
           managed independently by updateClock(). */
        m14HeaderName.text = ch.name
        bindLogo(m14HeaderLogo, ch)

        /* v2.7.03 — M14 bottom rail: bind the next FOUR upcoming
           programmes after the current one into the Next +0..+3
           cards.  Each card hides cleanly when there's no EPG
           data available beyond what we've shown. */
        val pivotStop = nowProg?.stopTs ?: nowSec
        val upcoming = list.asSequence()
            .filter { it.startTs >= pivotStop }
            .sortedBy { it.startTs }
            .take(4)
            .toList()
        bindNextCard(m14Next1Title, m14Next1Time, m14Next1Bg, upcoming.getOrNull(0))
        bindNextCard(m14Next2Title, m14Next2Time, m14Next2Bg, upcoming.getOrNull(1))
        bindNextCard(m14Next3Title, m14Next3Time, m14Next3Bg, upcoming.getOrNull(2))
        bindNextCard(m14Next4Title, m14Next4Time, m14Next4Bg, upcoming.getOrNull(3))

        /* v2.7.04 — TMDB backdrop on the On Now card too, so the
           focused programme shows its actual poster/backdrop art
           (Plex-style "Up Next" feel) instead of just the channel
           logo. */
        if (nowProg != null) {
            bindTmdbBackdrop(m14OnNowBg, nowProg.title)
        } else {
            m14OnNowBg.tag = null
            m14OnNowBg.setImageDrawable(null)
        }

        detailProgrammeTitle.text =
            nowProg?.title?.ifBlank { ch.name } ?: ch.name
        detailTimeRange.text = if (nowProg != null) {
            "${formatTime(nowProg.startTs)} – ${formatTime(nowProg.stopTs)}"
        } else {
            "Schedule unavailable"
        }
        detailNext.text = if (nextProg != null) {
            "UP NEXT · ${formatTime(nextProg.startTs)} · ${nextProg.title}"
        } else { "" }
        detailDivider.visibility =
            if (nextProg != null) View.VISIBLE else View.GONE

        /* Description — large body copy under the progress bar.
           Hidden cleanly when we have no description. */
        val desc = nowProg?.desc?.trim().orEmpty()
        if (desc.isNotEmpty()) {
            detailDescription.text = desc
            detailDescription.visibility = View.VISIBLE
        } else {
            detailDescription.text = ""
            detailDescription.visibility = View.GONE
        }

        /* Meta chips — episode badge, year, content rating, EPG
           category.  Each chip hides itself when the data is empty. */
        fun applyChip(tv: TextView, text: String) {
            if (text.isBlank()) {
                tv.visibility = View.GONE
            } else {
                tv.text = text
                tv.visibility = View.VISIBLE
            }
        }
        val epLabel = when {
            nowProg?.season?.isNotBlank() == true && nowProg.episode.isNotBlank() ->
                "S${nowProg.season} · E${nowProg.episode}"
            nowProg?.episode?.isNotBlank() == true -> "E${nowProg.episode}"
            else -> ""
        }
        applyChip(detailChipEpisode, epLabel)
        applyChip(detailChipYear, nowProg?.year ?: "")
        applyChip(detailChipRating, nowProg?.rating?.uppercase() ?: "")
        applyChip(detailChipCategory, nowProg?.category?.uppercase() ?: "")

        /* Progress bar width on the detail card.  Same calc as the
           row, defers measure until layout pass completes. */
        val total = nowProg?.let { (it.stopTs - it.startTs).coerceAtLeast(1L) } ?: 1L
        val elapsed = nowProg?.let { (nowSec - it.startTs).coerceIn(0L, total) } ?: 0L
        val ratio = if (nowProg != null) elapsed.toFloat() / total.toFloat() else 0f
        detailProgress.post {
            val parentW = (detailProgress.parent as? View)?.width ?: 0
            val lp = detailProgress.layoutParams
            lp.width = if (ratio == 0f) 0 else (parentW * ratio).toInt().coerceAtLeast(2)
            detailProgress.layoutParams = lp
        }

        /* Channel logo on glass plate. */
        detailLogo.tag = ch.logo
        if (ch.logo.isBlank()) {
            detailLogo.setImageDrawable(makeInitialDrawable(ch.name))
        } else {
            val cached = logoCache.get(ch.logo)
            if (cached != null) {
                detailLogo.setImageBitmap(cached)
            } else {
                detailLogo.setImageDrawable(makeInitialDrawable(ch.name))
                logoExecutor.execute {
                    try {
                        val bm = loadBitmap(ch.logo) ?: return@execute
                        logoCache.put(ch.logo, bm)
                        detailLogo.post {
                            if (detailLogo.tag == ch.logo) detailLogo.setImageBitmap(bm)
                        }
                    } catch (_: Throwable) { /* swallow */ }
                }
            }
        }

        /* Backdrop image — same as the logo for now (most providers
           ship one art asset per channel).  Future: programme-art
           lookup via TMDB when EPG title matches a known title. */
        detailBackdrop.tag = ch.logo
        if (ch.logo.isBlank()) {
            detailBackdrop.setImageDrawable(null)
        } else {
            val cached = logoCache.get(ch.logo)
            if (cached != null) {
                detailBackdrop.setImageBitmap(cached)
            } else {
                detailBackdrop.setImageDrawable(null)
                logoExecutor.execute {
                    try {
                        val bm = loadBitmap(ch.logo) ?: return@execute
                        logoCache.put(ch.logo, bm)
                        detailBackdrop.post {
                            if (detailBackdrop.tag == ch.logo) detailBackdrop.setImageBitmap(bm)
                        }
                    } catch (_: Throwable) { /* swallow */ }
                }
            }
        }

        /* Fade-in on first show.  Idempotent. */
        if (detailCard.alpha < 1f) {
            detailCard.animate()
                .alpha(1f)
                .setStartDelay(120)
                .setDuration(240)
                .start()
        }
    }

    // ───────────────────────── Helpers ────────────────────────────

    /* v2.7.03 — refreshes the M14 top-right clock + date strip.
       Called once on open and every 30 s while the guide is open. */
    private fun updateClock() {
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        m14HeaderClock.text = String.format(java.util.Locale.US, "%02d:%02d", h, m)
        val dayFmt = java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault())
        m14HeaderDate.text = dayFmt.format(cal.time)
    }

    /* v2.7.04 — binds one Next-card group (title + time caption +
       TMDB backdrop image).  When prog is null we clear everything
       so empty upcoming slots read as ghosted placeholders.  The
       backdrop lookup happens asynchronously off the main thread;
       a View-tag race guard keeps fast D-pad scrolling from
       painting last channel's backdrop into a new card. */
    private fun bindNextCard(
        titleTv: TextView, timeTv: TextView, bgIv: ImageView, prog: Programme?
    ) {
        if (prog == null) {
            titleTv.text = ""
            timeTv.text = ""
            bgIv.tag = null
            bgIv.setImageDrawable(null)
        } else {
            titleTv.text = prog.title.ifBlank { "—" }
            timeTv.text = "${formatTime(prog.startTs)} → ${formatTime(prog.stopTs)}"
            bindTmdbBackdrop(bgIv, prog.title)
        }
    }

    /* v2.7.04 — async TMDB backdrop loader.  Looks up the
       programme title on the backend's /api/tmdb/search endpoint,
       picks the first movie/tv hit, and loads its backdrop into
       the target ImageView.  Negative results are cached so we
       don't retry empty titles.  Race-safe via View tag. */
    private fun bindTmdbBackdrop(target: ImageView, title: String) {
        val q = title.trim()
        target.tag = q
        if (q.isBlank()) {
            target.setImageDrawable(null)
            return
        }
        val key = q.lowercase(java.util.Locale.ROOT)
        val cached = tmdbBackdropCache.get(key)
        if (cached != null) {
            if (cached.isEmpty()) {
                target.setImageDrawable(null)
            } else {
                val bm = logoCache.get(cached)
                if (bm != null) {
                    target.setImageBitmap(bm)
                } else {
                    target.setImageDrawable(null)
                    loadBackdropBitmapInto(target, q, cached)
                }
            }
            return
        }
        target.setImageDrawable(null)
        tmdbExecutor.execute {
            try {
                val backdrop = resolveTmdbBackdrop(q) ?: ""
                tmdbBackdropCache.put(key, backdrop)
                if (backdrop.isNotEmpty()) {
                    loadBackdropBitmapInto(target, q, backdrop)
                }
            } catch (_: Throwable) { /* swallow */ }
        }
    }

    /* Fetch the JSON, grab the first movie/tv hit's `backdrop` URL.
       Returns null on any failure / no-match.  Runs on the tmdb
       executor — never the main thread. */
    private fun resolveTmdbBackdrop(title: String): String? {
        return try {
            val url = "$backendBase/api/tmdb/search?q=" +
                java.net.URLEncoder.encode(title, "UTF-8")
            val u = java.net.URL(url)
            val c = u.openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 6000
            c.requestMethod = "GET"
            if (c.responseCode !in 200..299) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val json = org.json.JSONObject(body)
            val arr = json.optJSONArray("data") ?: return null
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val mt = it.optString("media_type", "")
                if (mt != "movie" && mt != "tv") continue
                val b = it.optString("backdrop", "")
                if (b.isNotBlank()) return b
            }
            null
        } catch (_: Throwable) { null }
    }

    /* Race-safe bitmap loader that also remembers the original
       title — if the target ImageView has been re-bound to a
       different title by the time the bitmap arrives, we drop
       silently. */
    private fun loadBackdropBitmapInto(target: ImageView, originalTitle: String, url: String) {
        tmdbExecutor.execute {
            try {
                val bm = loadBitmap(url) ?: return@execute
                logoCache.put(url, bm)
                target.post {
                    if (target.tag == originalTitle) {
                        target.setImageBitmap(bm)
                        target.alpha = 0f
                        target.animate().alpha(0.55f).setDuration(240).start()
                    }
                }
            } catch (_: Throwable) { /* swallow */ }
        }
    }

    /* v2.7.03 — async-loads the channel logo into an ImageView,
       respecting the LRU cache and the View-tag race guard so a
       fast D-pad scroll never paints the wrong logo into the
       wrong slot. */
    private fun bindLogo(target: ImageView, ch: Channel) {
        target.tag = ch.logo
        if (ch.logo.isBlank()) {
            target.setImageDrawable(makeInitialDrawable(ch.name))
            return
        }
        val cached = logoCache.get(ch.logo)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageDrawable(makeInitialDrawable(ch.name))
        val url = ch.logo
        logoExecutor.execute {
            try {
                val bm = loadBitmap(url) ?: return@execute
                logoCache.put(url, bm)
                target.post {
                    if (target.tag == url) target.setImageBitmap(bm)
                }
            } catch (_: Throwable) { /* swallow */ }
        }
    }

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

    /** Inexpensive Canvas-drawn placeholder — used both inside the
     *  channel logos AND the detail card while real artwork loads. */
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
