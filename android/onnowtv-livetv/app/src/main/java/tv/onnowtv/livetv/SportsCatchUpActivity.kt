package tv.onnowtv.livetv

import android.content.Intent
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.onnowtv.livetv.data.CatchUpIndex
import tv.onnowtv.livetv.ui.CatchUpLoadingOverlay
import tv.onnowtv.livetv.data.CatchUpResolver
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.LiveSportsClassifier
import tv.onnowtv.livetv.data.ParentalLock
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.SportsEventPolicy
import tv.onnowtv.livetv.data.SportsReplayCatalogue
import tv.onnowtv.livetv.data.SportsReplayPolicy
import tv.onnowtv.livetv.data.SportsReplayRepository
import tv.onnowtv.livetv.data.XtreamRepository
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Sports Hub → Catch Up: pick a sport, then a day, then a past game shown as crest-vs-crest (no scores). */
class SportsCatchUpActivity : AppCompatActivity() {
    private data class Side(val name: String, val logo: String?, val color: String?)
    private data class Meta(val match: Boolean, val home: Side? = null, val away: Side? = null)
    private data class Game(val channel: Channel, val programme: Programme, val sport: String) {
        val key get() = "${channel.id}-${programme.startMs}"
    }

    private val cyan = Color.parseColor("#5DC8FF")
    private val dim = Color.parseColor("#9DA5B5")
    private val games = mutableListOf<Game>()
    private val meta = HashMap<String, Meta>()
    private var sport: String? = null
    private var day = 0L
    private var loading = true
    private var resolving = false
    private var archive: List<Channel> = emptyList()

    private lateinit var status: TextView
    private lateinit var focusLine: TextView
    private lateinit var section: LinearLayout
    private lateinit var sectionTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tilesList: RecyclerView
    private val tiles = Adapter<String>(::sportTile) { v, s -> bindTile(v, s) }
    private val days = Adapter<Long>(::dayChip) { v, d -> bindDay(v, d) }
    private val cards = Adapter<Game>(::gameCard) { v, g -> bindGame(v, g) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun metaKey(g: Game) = "${g.sport}|${g.programme.title}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val full = BundleHolder.current ?: run { finish(); return }
        if (LivePreviewSession.isAlive()) LivePreviewSession.release()
        setContentView(buildUi())
        val catNames = full.categories.associate { it.id to it.name }
        archive = SportsReplayCatalogue.channels(ParentalLock.visible(this, full)).map { CatchUpResolver.viewer(this, it) }
        val channels = archive
            .filter { isSportsChannel(catNames[it.categoryId].orEmpty() + " " + it.name) }
            .sortedBy { if (LiveSportsClassifier.programmeBucket(it.name) != null) 0 else 1 }
            .take(80)
        if (channels.isEmpty()) {
            loading = false
            status.text = "Your provider hasn't shared any recorded sports channels yet"
            loader.dismiss()
            return
        }
        load(channels)
    }

    @Deprecated("TV remote compatibility")
    override fun onBackPressed() {
        if (sport == null) { super.onBackPressed(); return }
        val previous = sport
        sport = null; render()
        scroll.smoothScrollTo(0, 0)
        tilesList.post { tilesList.findViewWithTag<View>("sports-catchup-tile-$previous")?.requestFocus() }
    }

    // ---------------------------------------------------------------- UI
    private lateinit var loader: CatchUpLoadingOverlay

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0A0F1A")) }
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#33123B4D"), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(-1, dp(260)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(28), dp(48), dp(36)) }
        col.addView(eyebrow("↺  SPORTS HUB · CATCH UP", cyan))
        col.addView(text("Sports Catch Up", 34f, Color.WHITE, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply { tag = "sports-catchup-heading" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        status = text("CHECKING YOUR RECORDINGS…", 11f, cyan, Typeface.MONOSPACE).apply { letterSpacing = .14f; tag = "sports-catchup-status" }
        col.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        focusLine = text("Choose a sport to see the games you missed", 13f, Color.parseColor("#C7CFDB"), Typeface.DEFAULT).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; tag = "sports-catchup-focus-line"
        }
        col.addView(focusLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        tilesList = RecyclerView(this).apply {
            tag = "sports-catchup-tiles"; clipToPadding = false; clipChildren = false; itemAnimator = null; isNestedScrollingEnabled = false
            setPadding(dp(6), dp(6), dp(6), dp(6)); layoutManager = GridLayoutManager(context, 4); adapter = tiles
        }
        col.addView(tilesList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        sectionTitle = eyebrow("", cyan).apply { textSize = 12f }
        section.addView(sectionTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        section.addView(RecyclerView(this).apply {
            tag = "sports-catchup-days"; clipToPadding = false; itemAnimator = null
            setPadding(dp(6), dp(6), dp(6), dp(6)); layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false); adapter = days
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
        emptyHint = text("", 13f, dim, Typeface.DEFAULT).apply { tag = "sports-catchup-empty" }
        section.addView(emptyHint)
        section.addView(RecyclerView(this).apply {
            tag = "sports-catchup-games"; clipToPadding = false; clipChildren = false; itemAnimator = null; isNestedScrollingEnabled = false
            setPadding(dp(6), dp(6), dp(6), dp(6)); layoutManager = GridLayoutManager(context, 3); adapter = cards
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(section)

        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(col) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        loader = CatchUpLoadingOverlay(this, "↺  SPORTS HUB · CATCH UP")
        root.addView(loader, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun text(t: String, size: Float, color: Int, face: Typeface) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); typeface = face
    }
    private fun eyebrow(t: String, color: Int) = text(t, 10f, color, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .24f }
    private fun shape(fill: Int, stroke: Int, strokeDp: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }
    private fun states(normal: android.graphics.drawable.Drawable, focused: android.graphics.drawable.Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_activated), focused)
        addState(intArrayOf(), normal)
    }
    private fun focusScale(v: View, onFocus: (() -> Unit)? = null) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).translationZ(if (has) dp(4).toFloat() else 0f).setDuration(120).start()
            if (has) onFocus?.invoke()
        }
    }

    // ------------------------------------------------------------- tiles
    private fun sportTile(parent: ViewGroup): View = FrameLayout(this).apply {
        layoutParams = RecyclerView.LayoutParams(-1, dp(118)).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) }
        isFocusable = true; isClickable = true; clipToOutline = true
        background = states(shape(Color.parseColor("#141C2A"), 0, 0, 16), shape(Color.parseColor("#141C2A"), Color.WHITE, 3, 16))
        setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(ImageView(context).apply { id = R_ART; scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        addView(View(context).apply { id = R_SHADE }, FrameLayout.LayoutParams(-1, -1))
        addView(ImageView(context).apply { id = R_ICON; alpha = .9f }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(12), dp(12))
            addView(text("", 21f, Color.WHITE, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply { id = R_TITLE; setShadowLayer(8f, 0f, 2f, Color.BLACK); maxLines = 1 })
            addView(text("", 10f, Color.WHITE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { id = R_SUB; letterSpacing = .16f; alpha = .9f })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun bindTile(v: View, s: String) {
        v.tag = "sports-catchup-tile-$s"
        v.isActivated = s == sport
        val accent = LiveSportsClassifier.colorOf(s)
        val list = games.filter { it.sport == s }
        val matches = list.count { meta[metaKey(it)]?.match == true }
        v.findViewById<TextView>(R_TITLE).text = LiveSportsClassifier.labelOf(s)
        v.findViewById<TextView>(R_SUB).text = if (matches > 0) "$matches GAMES · ${list.size} SHOWS" else "${list.size} SHOWS"
        v.findViewById<View>(R_SHADE).background = GradientDrawable(GradientDrawable.Orientation.BL_TR,
            intArrayOf((accent and 0x00FFFFFF) or 0xEE000000.toInt(), (accent and 0x00FFFFFF) or 0x55000000, Color.TRANSPARENT))
        v.findViewById<ImageView>(R_ICON).load(LiveSportsClassifier.iconOf(s))
        v.findViewById<ImageView>(R_ART).load(SPORT_ART[s] ?: SPORT_ART.getValue("other")) { crossfade(true) }
        v.setOnClickListener { selectSport(s) }
        focusScale(v) { focusLine.text = "${LiveSportsClassifier.labelOf(s)} · ${list.size} recordings from your channels" }
    }

    private fun dayChip(parent: ViewGroup): View = text("", 12f, Color.WHITE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
        layoutParams = RecyclerView.LayoutParams(-2, dp(36)).apply { marginEnd = dp(10) }
        gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0); letterSpacing = .1f
        isFocusable = true; isClickable = true
        background = states(shape(Color.parseColor("#141C2A"), Color.parseColor("#1AFFFFFF"), 1, 18), shape(Color.parseColor("#123B4D"), cyan, 2, 18))
    }

    private fun bindDay(v: View, d: Long) {
        v.tag = "sports-catchup-day-$d"
        v.isActivated = d == day
        (v as TextView).text = "${dayLabel(d)}  ·  ${games.count { it.sport == sport && startOfDay(it.programme.startMs) == d }}"
        v.setOnClickListener { day = d; render() }
        focusScale(v)
    }

    // ------------------------------------------------------------- cards
    private fun gameCard(parent: ViewGroup): View = FrameLayout(this).apply {
        layoutParams = RecyclerView.LayoutParams(-1, dp(168)).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) }
        isFocusable = true; isClickable = true; clipToOutline = true
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(ImageView(context).apply { id = R_ART; scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        addView(View(context).apply { id = R_SHADE }, FrameLayout.LayoutParams(-1, -1))
        addView(LinearLayout(context).apply {
            id = R_TEAMS; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(10), dp(14), dp(10), 0)
            addView(teamColumn(R_HOME_LOGO, R_HOME_NAME, R_HOME_INIT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("VS", 13f, Color.WHITE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
                gravity = Gravity.CENTER; letterSpacing = .2f; setPadding(dp(8), dp(4), dp(8), dp(4))
                background = shape(Color.parseColor("#66000000"), Color.parseColor("#33FFFFFF"), 1, 12)
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(22) })
            addView(teamColumn(R_AWAY_LOGO, R_AWAY_NAME, R_AWAY_INIT), LinearLayout.LayoutParams(0, -2, 1f))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        addView(text("", 16f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            id = R_TITLE; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setShadowLayer(8f, 0f, 2f, Color.BLACK); setPadding(dp(14), 0, dp(14), 0)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        addView(text("", 9f, cyan, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
            id = R_SUB; letterSpacing = .12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(12), 0, dp(12), dp(10)); gravity = Gravity.CENTER_HORIZONTAL
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun teamColumn(logoId: Int, nameId: Int, initId: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(context).apply {
            addView(text("", 18f, Color.WHITE, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply { id = initId; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(context).apply { id = logoId; scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(62), dp(62)))
        addView(text("", 13f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            id = nameId; gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun bindGame(v: View, g: Game) {
        v.tag = "sports-catchup-game-${g.key}"
        val m = meta[metaKey(g)]
        val accent = LiveSportsClassifier.colorOf(g.sport)
        val ring = shape(Color.TRANSPARENT, Color.parseColor("#1AFFFFFF"), 1, 14)
        v.background = states(ring, shape(Color.TRANSPARENT, cyan, 3, 14))
        v.findViewById<TextView>(R_SUB).text = "${g.channel.name.uppercase()} · ${dayLabel(startOfDay(g.programme.startMs))} ${time(g.programme.startMs)} · ${minutes(g.programme)} MIN"
        val art = v.findViewById<ImageView>(R_ART)
        val shade = v.findViewById<View>(R_SHADE)
        val title = v.findViewById<TextView>(R_TITLE)
        val teams = v.findViewById<View>(R_TEAMS)
        if (m?.match == true && m.home != null && m.away != null) {
            art.setImageDrawable(null)
            val home = UIColor(m.home.color, accent); val away = UIColor(m.away.color, Color.parseColor("#2A3547"))
            shade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                ColorUtils.blendARGB(Color.parseColor("#0E1422"), home, .55f), Color.parseColor("#0E1422"),
                ColorUtils.blendARGB(Color.parseColor("#0E1422"), away, .55f))).apply { cornerRadius = dp(12).toFloat() }
            teams.visibility = View.VISIBLE; title.visibility = View.GONE
            bindSide(v, m.home, R_HOME_LOGO, R_HOME_NAME, R_HOME_INIT)
            bindSide(v, m.away, R_AWAY_LOGO, R_AWAY_NAME, R_AWAY_INIT)
        } else {
            teams.visibility = View.GONE; title.visibility = View.VISIBLE
            title.text = noScore(g.programme.title)
            art.load(SPORT_ART[g.sport] ?: SPORT_ART.getValue("other")) { crossfade(true) }
            shade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                (accent and 0x00FFFFFF) or 0x66000000, Color.parseColor("#E6060A12")))
        }
        v.setOnClickListener { play(g) }
        focusScale(v) { focusLine.text = describe(g) + " · " + SimpleDateFormat("EEEE d MMMM · h:mm a", Locale.getDefault()).format(Date(g.programme.startMs)) }
    }

    private fun bindSide(v: View, s: Side, logoId: Int, nameId: Int, initId: Int) {
        v.findViewById<TextView>(nameId).text = s.name
        val init = v.findViewById<TextView>(initId)
        val logo = v.findViewById<ImageView>(logoId)
        init.text = s.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        init.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(UIColor(s.color, Color.parseColor("#2A3547"))); setStroke(dp(2), Color.parseColor("#55FFFFFF")) }
        logo.setImageDrawable(null)
        if (!s.logo.isNullOrBlank()) logo.load(s.logo) { crossfade(true); listener(onSuccess = { _, _ -> init.visibility = View.INVISIBLE }, onError = { _, _ -> init.visibility = View.VISIBLE }) }
        init.visibility = if (s.logo.isNullOrBlank()) View.VISIBLE else View.INVISIBLE
    }

    @Suppress("FunctionName")
    private fun UIColor(hex: String?, fallback: Int) = runCatching { Color.parseColor(if (hex!!.startsWith("#")) hex else "#$hex") }.getOrDefault(fallback)

    private fun describe(g: Game): String {
        val m = meta[metaKey(g)]
        return if (m?.match == true && m.home != null && m.away != null) "${m.home.name} vs ${m.away.name}" else noScore(g.programme.title)
    }

    private fun noScore(t: String) = SCORE.replace(" $t ", " v ").trim()

    // ------------------------------------------------------------- data
    private fun isSportsChannel(text: String): Boolean {
        val t = text.lowercase()
        return SPORTS_WORDS.any { it in t } || LiveSportsClassifier.programmeBucket(text) != null
    }

    private fun sportFor(c: Channel, p: Programme): String? {
        val t = p.title.lowercase()
        if (JUNK.containsMatchIn(t)) return null
        return SportsEventPolicy.bucket(p, c)
            ?: LiveSportsClassifier.programmeBucket(p.title)
            ?: LiveSportsClassifier.programmeBucket(c.name)
            ?: LiveSportsClassifier.classify(p.title, c.name)
            ?: LiveSportsClassifier.OTHER_SPORT
    }

    /** Waits for the verified index (real recordings only) and the team details, then shows everything at once. */
    private fun load(channels: List<Channel>) = lifecycleScope.launch {
        val index = CatchUpIndex.build(this@SportsCatchUpActivity, archive)
        val found = channels.flatMap { c -> index[c.id].orEmpty().mapNotNull { p -> sportFor(c, p)?.let { Game(c, p, it) } } }
        fetchMeta(found)
        games += found
        loading = false
        render()
        loader.dismiss()
        tilesList.post { tilesList.getChildAt(0)?.requestFocus() }
    }

    private suspend fun fetchMeta(list: List<Game>) {
        val pending = list.map { metaKey(it) to it }.distinctBy { it.first }.filter { it.first !in meta }
        pending.chunked(100).forEach { chunk ->
            val body = JSONObject().put("items", JSONArray(chunk.map { (_, g) -> JSONObject().put("title", g.programme.title).put("sport", g.sport) }))
            val rows = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL("${XtreamRepository.BACKEND_BASE.trimEnd('/')}/api/sportshub/catchup-matches").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 6_000; conn.readTimeout = 20_000
                    conn.setRequestProperty("Content-Type", "application/json")
                    try {
                        conn.outputStream.use { it.write(body.toString().toByteArray()) }
                        if (conn.responseCode !in 200..299) null else JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("items")
                    } finally { conn.disconnect() }
                }.getOrNull()
            } ?: return@forEach
            chunk.forEachIndexed { i, (key, _) ->
                val o = rows.optJSONObject(i) ?: return@forEachIndexed
                meta[key] = Meta(o.optBoolean("match"), side(o.optJSONObject("home")), side(o.optJSONObject("away")))
            }
        }
    }

    private fun side(o: JSONObject?): Side? = o?.let {
        Side(it.optString("name"), it.optString("logo").takeIf { s -> s.isNotBlank() && s != "null" }, it.optString("color").takeIf { s -> s.isNotBlank() && s != "null" })
    }

    private fun selectSport(s: String) {
        sport = s
        day = games.filter { it.sport == s }.maxOfOrNull { startOfDay(it.programme.startMs) } ?: startOfDay(System.currentTimeMillis())
        render()
        section.post { scroll.smoothScrollTo(0, section.top - dp(12)); days.notifyDataSetChanged() }
    }

    private fun render() {
        val present = LiveSportsClassifier.DISPLAY_ORDER.filter { s -> games.any { it.sport == s } }
        tiles.submit(present)
        status.text = when {
            loading -> "CHECKING YOUR RECORDINGS…"
            games.isEmpty() -> "NO RECORDED SPORT FOUND ON YOUR CHANNELS RIGHT NOW"
            else -> "${present.size} SPORTS · ${games.size} RECORDINGS"
        }
        val s = sport
        section.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) return
        val mine = games.filter { it.sport == s }
        val dayList = mine.map { startOfDay(it.programme.startMs) }.distinct().sortedDescending()
        if (day !in dayList) dayList.firstOrNull()?.let { day = it }
        days.submit(dayList)
        val shown = mine.filter { startOfDay(it.programme.startMs) == day }
            .sortedWith(compareByDescending<Game> { meta[metaKey(it)]?.match == true }.thenByDescending { it.programme.startMs })
        cards.submit(shown)
        sectionTitle.text = "CATCH UP ${LiveSportsClassifier.labelOf(s).uppercase()}"
        sectionTitle.setTextColor(LiveSportsClassifier.colorOf(s).let { if (ColorUtils.calculateLuminance(it) < .12) cyan else it })
        emptyHint.text = if (shown.isEmpty()) "No recordings for this day" else ""
        emptyHint.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun play(g: Game) {
        if (resolving) return
        resolving = true
        lifecycleScope.launch {
            val ready = CatchUpResolver.resolve(this@SportsCatchUpActivity, g.channel, g.programme, archive)
            resolving = false
            if (ready == null) {
                Toast.makeText(this@SportsCatchUpActivity, "Your provider doesn't have this recording, so it's been removed from the list", Toast.LENGTH_LONG).show()
                CatchUpIndex.forget(g.channel, g.programme)
                games.removeAll { it.key == g.key }
                render()
                return@launch
            }
            startActivity(Intent(this@SportsCatchUpActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, ready.url)
                putExtra(PlayerActivity.EXTRA_TITLE, describe(g))
                putExtra(PlayerActivity.EXTRA_SUBTITLE, "CATCH UP · ${ready.channel.name} · ${dayLabel(startOfDay(g.programme.startMs))} ${time(g.programme.startMs)}")
                putExtra(PlayerActivity.EXTRA_STOP_ON_EXIT, true)
            })
        }
    }

    // ------------------------------------------------------------- time
    private fun startOfDay(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun dayLabel(d: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val yesterday = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        return when (d) { today -> "TODAY"; yesterday -> "YESTERDAY"; else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(d)).uppercase() }
    }
    private fun time(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).uppercase()
    private fun minutes(p: Programme) = ((p.stopMs - p.startMs) / 60_000L).coerceAtLeast(1)

    private class Adapter<T>(private val create: (ViewGroup) -> View, private val bind: (View, T) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<T> = emptyList()
        fun submit(list: List<T>) { items = list; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(create(parent)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = bind(holder.itemView, items[position])
    }

    private companion object {
        val SCORE = Regex("\\s\\d{1,3}\\s*[-–]\\s*\\d{1,3}\\s")
        val JUNK = Regex("\\b(teleshopping|infomercial|paid programming|paid program|station close|off air|close down|programming resumes|no programme|to be announced|tba|tbc)\\b")
        val SPORTS_WORDS = listOf("sport", "espn", "bein", "kayo", "fox league", "fox footy", "fox cricket", "racing", "tab", "nfl", "nba", "nhl", "mlb",
            "afl", "nrl", "ufc", "wwe", "golf", "tennis", "cricket", "f1", "motor", "dazn", "optus sport", "stan sport", "premier league", "laliga", "serie a", "bundesliga", "football", "soccer", "rugby", "boxing")
        private fun px(id: String) = "https://images.pexels.com/photos/$id/pexels-photo-$id.jpeg?auto=compress&cs=tinysrgb&w=640"
        private fun us(id: String) = "https://images.unsplash.com/photo-$id?w=640&q=70&auto=format&fit=crop"
        val SPORT_ART = mapOf(
            "soccer" to px("30651230"),
            "f1" to px("29252129"),
            "motorsport" to us("1761092993029-70ab4bc9983d"),
            "golf" to us("1592919505780-303950717480"),
            "cricket" to us("1730739463889-34c7279277a9"),
            "tennis" to us("1545151414-8a948e1ea54f"),
            "rugby" to us("1480099225005-2513c8947aec"),
            "nrl" to px("35102692"),
            "afl" to us("1663832886113-3d55ed62d1ca"),
            "nfl" to us("1663563624897-de8972d7ce93"),
            "nba" to us("1626003573503-2e088d82c647"),
            "nhl" to us("1580748141549-71748dbe0bdc"),
            "mlb" to us("1623947453126-3652fc16b2b0"),
            "mma" to us("1509563268479-0f004cf3f58b"),
            "cycling" to us("1567415848125-e3e549f875b6"),
            "athletics" to px("19787364"),
            "wwe" to px("36472317"),
            "other" to us("1705593973313-75de7bf95b56"),
        )
        val R_ART = View.generateViewId()
        val R_SHADE = View.generateViewId()
        val R_ICON = View.generateViewId()
        val R_TITLE = View.generateViewId()
        val R_SUB = View.generateViewId()
        val R_TEAMS = View.generateViewId()
        val R_HOME_LOGO = View.generateViewId()
        val R_HOME_NAME = View.generateViewId()
        val R_HOME_INIT = View.generateViewId()
        val R_AWAY_LOGO = View.generateViewId()
        val R_AWAY_NAME = View.generateViewId()
        val R_AWAY_INIT = View.generateViewId()
    }
}
