package tv.onnowtv.livetv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.CatchUpIndex
import tv.onnowtv.livetv.ui.CatchUpLoadingOverlay
import tv.onnowtv.livetv.data.CatchUpResolver
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.ParentalLock
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.SportsReplayCatalogue
import tv.onnowtv.livetv.data.SportsReplayPolicy
import tv.onnowtv.livetv.data.SportsReplayRepository
import tv.onnowtv.livetv.data.XtreamRepository
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Catch Up: "Just aired" art row + channel → day → show browser over the
 *  provider's archive. Playback goes through SportsReplayActivity. */
class CatchUpActivity : AppCompatActivity() {
    private data class Show(val channel: Channel, val programme: Programme) {
        val key get() = "${channel.id}-${programme.startMs}"
    }

    private val cyan = Color.parseColor("#5DC8FF")
    private val violet = Color.parseColor("#B388FF")
    private val dim = Color.parseColor("#9DA5B5")
    private val art = HashMap<String, String>()
    private val programmes = HashMap<String, List<Programme>>()

    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroSynopsis: TextView
    private lateinit var playBtn: TextView
    private lateinit var dayHint: TextView
    private lateinit var scheduleHint: TextView
    private lateinit var scroll: ScrollView

    private val tilesAdapter = Adapter<String>(::genreTile) { v, g -> bindTile(v, g) }
    private val featured = Adapter<Show>(::justAiredCard) { v, s -> bindCard(v, s) }
    private lateinit var featuredTitle: TextView
    private val loaded = mutableListOf<Show>()
    private var featuredGenre: String? = null
    private lateinit var railsHint: TextView
    private val genreAdapter = Adapter<String>(::dayChip) { v, g -> bindGenre(v, g) }
    private var allChannels: List<Channel> = emptyList()
    private var genre = ALL
    private val genreOf = HashMap<String, String>()
    private val channelsAdapter = Adapter<Channel>(::channelRow) { v, c -> bindChannel(v, c) }
    private val daysAdapter = Adapter<Long>(::dayChip) { v, d -> bindDay(v, d) }
    private val scheduleAdapter = Adapter<Show>(::scheduleRow) { v, s -> bindSchedule(v, s) }

    private var hero: Show? = null
    private var channel: Channel? = null
    private var dayStart = startOfDay(System.currentTimeMillis())
    private var channelJob: Job? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val full = BundleHolder.current ?: run { finish(); return }
        // Free the single provider connection held by the guide preview.
        if (LivePreviewSession.isAlive()) LivePreviewSession.release()
        val channels = SportsReplayCatalogue.channels(ParentalLock.visible(this, full)).map { CatchUpResolver.viewer(this, it) }
        setContentView(buildUi())
        if (channels.isEmpty()) {
            heroTitle.text = "No catch-up channels yet"
            heroMeta.text = "YOUR PROVIDER HASN'T SHARED ANY RECORDED CHANNELS"
            heroSynopsis.text = "When a channel supports catch-up, its last few days of shows will appear here."
            playBtn.visibility = View.GONE
            railsHint.text = "Nothing to show"
            loader.dismiss()
            return
        }
        val catNames = full.categories.associate { it.id to it.name }
        allChannels = channels
        heroMeta.text = "CHECKING YOUR RECORDINGS…"
        railsHint.text = "Checking which recordings your provider has…"
        lifecycleScope.launch {
            val index = CatchUpIndex.build(this@CatchUpActivity, channels)
            // Only channels with recordings that really play are listed.
            val live = channels.filter { it.id in index }
            loader.dismiss()
            heroMeta.text = "WATCH WHAT YOU MISSED — UP TO 7 DAYS"
            if (live.isEmpty()) {
                heroTitle.text = "No recordings right now"
                heroSynopsis.text = "Your provider isn't serving any catch-up recordings at the moment."
                playBtn.visibility = View.GONE
                railsHint.text = "No recordings available right now"
                return@launch
            }
            programmes.putAll(index)
            live.forEach { c -> genreOf[c.id] = genreFor(catNames[c.categoryId].orEmpty() + " " + c.name); loaded += index.getValue(c.id).map { Show(c, it) } }
            allChannels = live
            val present = GENRES.map { it.first }.filter { g -> live.any { genreOf[it.id] == g } }
            genreAdapter.submit(listOf(ALL) + present)
            tilesAdapter.submit(present + listOf(OTHER).filter { live.any { c -> genreOf[c.id] == OTHER } })
            channelsAdapter.submit(live)
            refreshFeatured()
            railsHint.visibility = View.GONE
            selectChannel(live.first())
        }
    }

    // ---------------------------------------------------------------- UI
    private lateinit var loader: CatchUpLoadingOverlay

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0A0F1A")) }
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#332E1A47"), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(-1, dp(260)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(28), dp(48), dp(36)) }
        col.addView(eyebrow("↺  ON NOW TV · CATCH UP", violet))
        heroTitle = text("Catch Up", 34f, Color.WHITE, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        col.addView(heroTitle, LinearLayout.LayoutParams(dp(560), -2).apply { topMargin = dp(10) })
        heroMeta = text("", 11f, cyan, Typeface.MONOSPACE).apply { letterSpacing = .14f }
        heroMeta.text = "WATCH WHAT YOU MISSED — UP TO 7 DAYS"
        col.addView(heroMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        heroSynopsis = text("", 13f, Color.parseColor("#C7CFDB"), Typeface.DEFAULT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f) }
        col.addView(heroSynopsis, LinearLayout.LayoutParams(dp(520), -2).apply { topMargin = dp(8) })
        playBtn = text("▶   PLAY FROM START", 13f, Color.parseColor("#06101C"), Typeface.DEFAULT_BOLD).apply {
            tag = "catchup-play"; gravity = Gravity.CENTER; setPadding(dp(22), 0, dp(22), 0); isFocusable = true; isClickable = true
            letterSpacing = .08f
            background = states(pill(cyan, 0, 0, 22), pill(cyan, Color.WHITE, 3, 22))
            setOnClickListener { hero?.let(::play) }
            focusScale(this)
            visibility = View.GONE
        }
        col.addView(playBtn, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(16) })

        railsHint = text("Finding recent shows…", 12f, dim, Typeface.DEFAULT)
        col.addView(railsHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        col.addView(RecyclerView(this).apply {
            tag = "catchup-tiles"; clipToPadding = false; clipChildren = false; itemAnimator = null; isNestedScrollingEnabled = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 4)
            adapter = tilesAdapter
        }, LinearLayout.LayoutParams(-1, dp(272)).apply { topMargin = dp(6) })
        featuredTitle = eyebrow("JUST AIRED", cyan)
        col.addView(featuredTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        col.addView(list(featured, horizontal = true, tag = "catchup-featured"), LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(4) })

        col.addView(eyebrow("BROWSE BY CHANNEL", cyan), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        col.addView(list(genreAdapter, horizontal = true, tag = "catchup-genres"), LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        val browser = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        browser.addView(list(channelsAdapter, horizontal = false, tag = "catchup-channels"), LinearLayout.LayoutParams(dp(230), -1))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
        right.addView(list(daysAdapter, horizontal = true, tag = "catchup-days"), LinearLayout.LayoutParams(-1, dp(46)))
        dayHint = text("", 11f, dim, Typeface.MONOSPACE)
        right.addView(dayHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        scheduleHint = text("", 13f, dim, Typeface.DEFAULT)
        right.addView(scheduleHint, LinearLayout.LayoutParams(-2, -2))
        right.addView(list(scheduleAdapter, horizontal = false, tag = "catchup-schedule"), LinearLayout.LayoutParams(-1, -1).apply { topMargin = dp(4) })
        browser.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(browser, LinearLayout.LayoutParams(-1, dp(360)).apply { topMargin = dp(8) })

        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(col) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        loader = CatchUpLoadingOverlay(this, "↺  CATCH UP")
        root.addView(loader, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun text(t: String, size: Float, color: Int, face: Typeface) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); typeface = face
    }
    private fun eyebrow(t: String, color: Int) = text(t, 10f, color, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .24f }
    private fun pill(fill: Int, stroke: Int, strokeDp: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }
    private fun states(normal: android.graphics.drawable.Drawable, focused: android.graphics.drawable.Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_activated), focused)
        addState(intArrayOf(), normal)
    }
    private fun cardStates(radius: Int = 12) = states(
        pill(Color.parseColor("#141C2A"), Color.parseColor("#1AFFFFFF"), 1, radius),
        pill(Color.parseColor("#1A2438"), cyan, 2, radius),
    )
    private fun focusScale(v: View, onFocus: (() -> Unit)? = null) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).translationZ(if (has) dp(4).toFloat() else 0f).setDuration(120).start()
            if (has) onFocus?.invoke()
        }
    }
    private fun list(adapter: RecyclerView.Adapter<*>, horizontal: Boolean, tag: String) = RecyclerView(this).apply {
        this.tag = tag; clipToPadding = false; clipChildren = false; itemAnimator = null
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutManager = LinearLayoutManager(context, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        this.adapter = adapter
    }

    // ------------------------------------------------------------- cards
    private fun justAiredCard(parent: ViewGroup): View = FrameLayout(this).apply {
        layoutParams = RecyclerView.LayoutParams(dp(232), dp(130)).apply { marginEnd = dp(16) }
        isFocusable = true; isClickable = true; background = cardStates(); clipToOutline = true
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(ImageView(context).apply { id = R_ART; scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#E6060A12")))
        }, FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(8))
            addView(text("", 13f, Color.WHITE, Typeface.DEFAULT_BOLD).apply { id = R_TITLE; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(text("", 9f, cyan, Typeface.MONOSPACE).apply { id = R_SUB; letterSpacing = .12f; maxLines = 1 })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun bindCard(v: View, s: Show) {
        v.tag = "catchup-card-${s.key}"
        v.findViewById<TextView>(R_TITLE).text = s.programme.title
        v.findViewById<TextView>(R_SUB).text = "${s.channel.name.uppercase()} · ${ago(s.programme.stopMs)}"
        val img = v.findViewById<ImageView>(R_ART)
        img.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#2E1A47"), Color.parseColor("#173C4D"))))
        artFor(s) { url -> if (v.tag == "catchup-card-${s.key}") img.load(url) { crossfade(true) } }
        v.setOnClickListener { play(s) }
        focusScale(v) { setHero(s) }
    }

    private fun channelRow(parent: ViewGroup): View = LinearLayout(this).apply {
        layoutParams = RecyclerView.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) }
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0)
        isFocusable = true; isClickable = true; background = cardStates(10)
        addView(ImageView(context).apply { id = R_ART; scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(40), dp(30)))
        addView(text("", 13f, Color.WHITE, Typeface.DEFAULT_BOLD).apply { id = R_TITLE; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10), 0, dp(6), 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("", 9f, violet, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
            id = R_SUB; setPadding(dp(6), dp(3), dp(6), dp(3)); background = pill(Color.parseColor("#2E1A47"), 0, 0, 6)
        })
    }

    private fun bindChannel(v: View, c: Channel) {
        v.tag = "catchup-channel-${c.id}"
        v.isActivated = c.id == channel?.id
        v.findViewById<TextView>(R_TITLE).text = c.name
        v.findViewById<TextView>(R_SUB).text = "${(c.archiveDays ?: 0).coerceAtMost(7)} DAYS"
        v.findViewById<ImageView>(R_ART).load(c.logoUrl)
        v.setOnClickListener { selectChannel(c) }
        focusScale(v)
    }

    private fun dayChip(parent: ViewGroup): View = text("", 12f, Color.WHITE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
        layoutParams = RecyclerView.LayoutParams(-2, dp(34)).apply { marginEnd = dp(10) }
        gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0); letterSpacing = .1f
        isFocusable = true; isClickable = true
        background = states(pill(Color.parseColor("#141C2A"), Color.parseColor("#1AFFFFFF"), 1, 17), pill(Color.parseColor("#2E1A47"), violet, 2, 17))
    }

    private fun bindDay(v: View, day: Long) {
        v.tag = "catchup-day-$day"
        v.isActivated = day == dayStart
        val count = dayShows(day).size
        (v as TextView).text = "${dayLabel(day)}  ·  $count"
        v.setOnClickListener { dayStart = day; daysAdapter.notifyDataSetChanged(); showDay() }
        focusScale(v)
    }

    private fun scheduleRow(parent: ViewGroup): View = LinearLayout(this).apply {
        layoutParams = RecyclerView.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(8) }
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(14), dp(6))
        isFocusable = true; isClickable = true; background = cardStates(10)
        addView(ImageView(context).apply { id = R_ART; scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = pill(Color.parseColor("#0E1422"), 0, 0, 8) },
            LinearLayout.LayoutParams(dp(96), dp(54)))
        addView(text("", 12f, cyan, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { id = R_TIME; setPadding(dp(14), 0, dp(8), 0) },
            LinearLayout.LayoutParams(dp(92), -2))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("", 14f, Color.WHITE, Typeface.DEFAULT_BOLD).apply { id = R_TITLE; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(text("", 11f, dim, Typeface.DEFAULT).apply { id = R_SUB; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("▶", 16f, cyan, Typeface.DEFAULT_BOLD))
    }

    private fun bindSchedule(v: View, s: Show) {
        v.tag = "catchup-show-${s.key}"
        v.findViewById<TextView>(R_TIME).text = time(s.programme.startMs)
        v.findViewById<TextView>(R_TITLE).text = s.programme.title
        v.findViewById<TextView>(R_SUB).text = "${minutes(s.programme)} min" + (s.programme.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        val img = v.findViewById<ImageView>(R_ART)
        img.setImageDrawable(null)
        artFor(s) { url -> if (v.tag == "catchup-show-${s.key}") img.load(url) { crossfade(true) } }
        v.setOnClickListener { play(s) }
        focusScale(v) { setHero(s) }
    }

    // ------------------------------------------------------------- data
    private fun shows(c: Channel): List<Show> = programmes[c.id].orEmpty().map { Show(c, it) }

    private fun selectChannel(c: Channel) {
        channel = c
        channelsAdapter.notifyDataSetChanged()
        channelJob?.cancel()
        scheduleAdapter.submit(emptyList()); daysAdapter.submit(emptyList())
        scheduleHint.text = "Loading ${c.name} recordings…"; dayHint.text = ""
        channelJob = lifecycleScope.launch {
            val list = shows(c)
            if (channel?.id != c.id) return@launch
            val days = list.map { startOfDay(it.programme.startMs) }.distinct().sortedDescending()
            dayStart = days.firstOrNull() ?: startOfDay(System.currentTimeMillis())
            daysAdapter.submit(days)
            showDay()
            if (hero == null) list.lastOrNull()?.let(::setHero)
        }
    }

    private fun dayShows(day: Long): List<Show> {
        val c = channel ?: return emptyList()
        return programmes[c.id].orEmpty().filter { startOfDay(it.startMs) == day }.map { Show(c, it) }
    }

    private fun showDay() {
        val list = dayShows(dayStart)
        scheduleAdapter.submit(list)
        dayHint.text = "${channel?.name.orEmpty().uppercase()} · ${SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(dayStart)).uppercase()}"
        scheduleHint.text = if (list.isEmpty()) "No recordings archived for this day" else ""
        scheduleHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun bindGenre(v: View, g: String) {
        v.tag = "catchup-genre-$g"
        v.isActivated = g == genre
        (v as TextView).text = g.uppercase()
        v.setOnClickListener {
            genre = g; genreAdapter.notifyDataSetChanged()
            val list = if (g == ALL) allChannels else allChannels.filter { genreOf[it.id] == g }
            channelsAdapter.submit(list)
            list.firstOrNull()?.let(::selectChannel)
        }
        focusScale(v)
    }

    private fun genreTile(parent: ViewGroup): View = FrameLayout(this).apply {
        layoutParams = RecyclerView.LayoutParams(-1, dp(118)).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) }
        isFocusable = true; isClickable = true; clipToOutline = true
        background = states(pill(Color.parseColor("#141C2A"), 0, 0, 16), pill(Color.parseColor("#141C2A"), Color.WHITE, 3, 16))
        setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(ImageView(context).apply { id = R_ART; scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        addView(View(context).apply { id = R_TIME }, FrameLayout.LayoutParams(-1, -1))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(12), dp(12))
            addView(text("", 22f, Color.WHITE, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply { id = R_TITLE; setShadowLayer(8f, 0f, 2f, Color.BLACK) })
            addView(text("", 10f, Color.WHITE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { id = R_SUB; letterSpacing = .16f; alpha = .9f })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun bindTile(v: View, g: String) {
        v.tag = "catchup-tile-$g"
        val accent = GENRE_ACCENT[g] ?: violet
        v.findViewById<TextView>(R_TITLE).text = if (g == OTHER) "More" else g
        val count = loaded.count { genreOf[it.channel.id] == g }
        val chans = allChannels.count { genreOf[it.id] == g }
        v.findViewById<TextView>(R_SUB).text = if (count > 0) "$count SHOWS · $chans CHANNELS" else "$chans CHANNELS"
        v.findViewById<View>(R_TIME).background = GradientDrawable(GradientDrawable.Orientation.BL_TR,
            intArrayOf((accent and 0x00FFFFFF) or 0xEE000000.toInt(), (accent and 0x00FFFFFF) or 0x66000000, Color.TRANSPARENT))
        val img = v.findViewById<ImageView>(R_ART)
        TILE_ART[g]?.let { img.load(it) { crossfade(true) } } ?: img.setImageDrawable(null)
        v.setOnClickListener { showGenre(g) }
        focusScale(v)
    }

    /** Tile press: the row below switches to that category and the channel browser filters to it. */
    private fun showGenre(g: String) {
        featuredGenre = if (featuredGenre == g) null else g
        refreshFeatured()
        val chip = if (featuredGenre == null) ALL else g
        genre = chip; genreAdapter.notifyDataSetChanged()
        val list = if (chip == ALL) allChannels else allChannels.filter { genreOf[it.id] == chip }
        channelsAdapter.submit(list)
        list.firstOrNull()?.let(::selectChannel)
    }

    private fun refreshFeatured() {
        val now = System.currentTimeMillis()
        val g = featuredGenre
        val items = if (g == null) loaded.filter { it.programme.stopMs >= now - 24 * 3_600_000L }
            else loaded.filter { genreOf[it.channel.id] == g }
        featured.submit(items.newestPerTitle().take(30))
        featuredTitle.text = if (g == null) "JUST AIRED" else if (g == OTHER) "MORE TO CATCH UP ON" else "CATCH UP ${g.uppercase()}"
        featuredTitle.setTextColor(g?.let { GENRE_ACCENT[it] } ?: cyan)
    }

    private fun List<Show>.newestPerTitle() =
        sortedByDescending { it.programme.stopMs }.distinctBy { it.programme.title.trim().lowercase() }

    private fun genreFor(text: String): String {
        val t = text.lowercase()
        return GENRES.firstOrNull { (_, words) -> words.split('|').any { it in t } }?.first ?: OTHER
    }

    /** Remembers the focused show (Play target). */
    private fun setHero(s: Show) { hero = s }

    /** Plays in the same player as normal Live TV. */
    private fun play(s: Show) {
        if (resolving) return
        resolving = true
        lifecycleScope.launch {
            val ready = CatchUpResolver.resolve(this@CatchUpActivity, s.channel, s.programme, allChannels)
            resolving = false
            if (ready == null) {
                android.widget.Toast.makeText(this@CatchUpActivity, "Your provider doesn't have this recording, so it's been removed from the list", android.widget.Toast.LENGTH_LONG).show()
                CatchUpIndex.forget(s.channel, s.programme)
                hideMissing(s)
                return@launch
            }
            startActivity(android.content.Intent(this@CatchUpActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, ready.url)
                putExtra(PlayerActivity.EXTRA_TITLE, s.programme.title)
                putExtra(PlayerActivity.EXTRA_SUBTITLE, "CATCH UP · ${ready.channel.name} · ${dayLabel(startOfDay(s.programme.startMs))} ${time(s.programme.startMs)}")
                putExtra(PlayerActivity.EXTRA_STOP_ON_EXIT, true)
            })
        }
    }

    private var resolving = false

    private fun hideMissing(s: Show) {
        programmes[s.channel.id]?.let { list -> programmes[s.channel.id] = list.filter { it.startMs != s.programme.startMs } }
        loaded.removeAll { it.channel.id == s.channel.id && it.programme.startMs == s.programme.startMs }
        if (hero == s) hero = null
        refreshFeatured()
        tilesAdapter.notifyDataSetChanged()
        if (channel?.id == s.channel.id) showDay()
    }

    /** TMDB backdrop/poster for a show title; channel logo as fallback. */
    private fun artFor(s: Show, onUrl: (String) -> Unit) {
        val title = s.programme.title.trim()
        art[title]?.let { onUrl(it.ifBlank { s.channel.logoUrl.orEmpty() }); return }
        lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL("${XtreamRepository.BACKEND_BASE.trimEnd('/')}/api/epg/art?title=${URLEncoder.encode(title, "UTF-8")}")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 5_000; conn.readTimeout = 8_000
                    try {
                        if (conn.responseCode !in 200..299) "" else JSONObject(conn.inputStream.bufferedReader().readText())
                            .let { it.optString("backdrop").ifBlank { it.optString("poster") } }
                    } finally { conn.disconnect() }
                }.getOrDefault("")
            }
            art[title] = url
            onUrl(url.ifBlank { s.channel.logoUrl.orEmpty() })
        }
    }

    // ------------------------------------------------------------- time
    private fun startOfDay(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun addDays(day: Long, n: Int) = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_YEAR, n) }.timeInMillis
    private fun dayLabel(day: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        return when (day) {
            today -> "TODAY"
            addDays(today, -1) -> "YESTERDAY"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(day)).uppercase()
        }
    }
    private fun time(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).uppercase()
    private fun minutes(p: Programme) = ((p.stopMs - p.startMs) / 60_000L).coerceAtLeast(1)
    private fun ago(stop: Long): String {
        val mins = (System.currentTimeMillis() - stop) / 60_000L
        return when { mins < 60 -> "${mins.coerceAtLeast(1)} MIN AGO"; mins < 24 * 60 -> "${mins / 60} HR AGO"; else -> dayLabel(startOfDay(stop)) }
    }

    private class Adapter<T>(private val create: (ViewGroup) -> View, private val bind: (View, T) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<T> = emptyList()
        fun submit(list: List<T>) { items = list; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(create(parent)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = bind(holder.itemView, items[position])
    }

    private companion object {
        const val ALL = "All"
        const val OTHER = "Other"
        val GENRES = listOf(
            "Sports" to "sport|espn|bein|kayo|fox league|fox footy|fox cricket|racing|tab|nfl|nba|nhl|mlb|afl|nrl|ufc|wwe|golf|tennis|cricket|f1|motor|dazn|optus sport|stan sport",
            "News" to "news|cnn|cnbc|bloomberg|sky news|al jazeera|weather|parliament",
            "Reality" to "reality|lifestyle|bravo|tlc|e!|real life|dmax|food|home|living|style|arena|lifetime",
            "Movies" to "movie|cinema|film|premiere|hbo|showtime|starz|sky cinema|fox classics|thriller|action",
            "Kids" to "kid|cartoon|nick|disney|junior|boomerang|baby|cbeebies|abc me|family",
            "Documentaries" to "doc|discovery|history|nat geo|national geographic|animal|science|crime|investigation|planet",
            "Entertainment" to "entertainment|comedy|drama|showcase|universal|fx|gem|go!|bold|vix|7two|9go|10 peach|peach|seven|nine|channel 10|abc|sbs",
            "Music" to "music|mtv|vh1|hits|max|country|radio",
        )
        val TILE_ART = mapOf(
            "Sports" to "https://images.pexels.com/photos/30651230/pexels-photo-30651230.jpeg?auto=compress&cs=tinysrgb&w=640",
            "News" to "https://images.pexels.com/photos/39332193/pexels-photo-39332193.jpeg?auto=compress&cs=tinysrgb&w=640",
            "Reality" to "https://images.unsplash.com/photo-1581092787765-e3feb951d987?w=640&q=70",
            "Movies" to "https://images.pexels.com/photos/14746411/pexels-photo-14746411.jpeg?auto=compress&cs=tinysrgb&w=640",
            "Kids" to "https://images.unsplash.com/photo-1545558014-8692077e9b5c?w=640&q=70",
            "Documentaries" to "https://images.pexels.com/photos/26924205/pexels-photo-26924205.jpeg?auto=compress&cs=tinysrgb&w=640",
            "Entertainment" to "https://images.pexels.com/photos/713149/pexels-photo-713149.jpeg?auto=compress&cs=tinysrgb&w=640",
            "Music" to "https://images.pexels.com/photos/38780318/pexels-photo-38780318.jpeg?auto=compress&cs=tinysrgb&w=640",
        )
        val GENRE_ACCENT = mapOf(
            "Sports" to Color.parseColor("#6CD0E3"), "News" to Color.parseColor("#FF6B6B"), "Reality" to Color.parseColor("#FF8FD1"),
            "Movies" to Color.parseColor("#FFC850"), "Kids" to Color.parseColor("#7CF29A"), "Documentaries" to Color.parseColor("#8FB8FF"),
            "Entertainment" to Color.parseColor("#B388FF"), "Music" to Color.parseColor("#FF9F5A"),
        )
        val R_ART = View.generateViewId()
        val R_TITLE = View.generateViewId()
        val R_SUB = View.generateViewId()
        val R_TIME = View.generateViewId()
    }
}
