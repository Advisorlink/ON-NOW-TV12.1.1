package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.ui.SportsUi as UI

/** "Where to watch": small logos of your matching channels, else the TV network names from the schedule. */
object SportsChannelStrip {
    private val cache = object : LinkedHashMap<String, List<GameChannelMatcher.Match>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<GameChannelMatcher.Match>>?) = size > 120
    }

    fun wants(g: JSONObject) = g.optString("state") == "pre" || g.optString("state") == "in"

    fun networks(g: JSONObject): List<String> =
        g.optJSONArray("broadcasts")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() && it != "null" } }.orEmpty()

    /** Fills [strip] with up to [max] logos; returns false when there is nothing to show. */
    fun fill(ctx: Context, strip: LinearLayout, g: JSONObject, matches: List<GameChannelMatcher.Match>, max: Int = 3, size: Float = 22f): Boolean {
        strip.removeAllViews()
        val channels = matches.map { it.channel }.distinctBy { it.id }
        channels.take(max).forEach { ch ->
            strip.addView(UI.identify(android.widget.ImageView(ctx), "watch-on-${Data.key(g)}-${ch.id}").apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; contentDescription = ch.name
                background = UI.bg(0xF2FFFFFF.toInt(), UI.dp(ctx, 5f).toFloat()); clipToOutline = true
                val p = UI.dp(ctx, 2f); setPadding(p, p, p, p)
                if (!ch.logoUrl.isNullOrBlank()) load(ch.logoUrl) { crossfade(false); error(R.drawable.ic_nav_tv) } else setImageResource(R.drawable.ic_nav_tv)
            }, LinearLayout.LayoutParams(UI.dp(ctx, size * 1.5f), UI.dp(ctx, size)))
        }
        if (channels.size > max) strip.addView(UI.text(ctx, "+${channels.size - max}", 10f, Color.WHITE, bold = true))
        if (channels.isEmpty()) networks(g).take(2).forEach { n ->
            strip.addView(UI.singleLine(UI.text(ctx, n.uppercase(), 9f, Color.WHITE, bold = true)).apply {
                gravity = Gravity.CENTER; maxWidth = UI.dp(ctx, 72f)
                setPadding(UI.dp(ctx, 6f), UI.dp(ctx, 2f), UI.dp(ctx, 6f), UI.dp(ctx, 2f))
                background = UI.bg(0x33FFFFFF, UI.dp(ctx, 5f).toFloat(), UI.dp(ctx, 1f), 0x55FFFFFF)
            })
        }
        strip.visibility = if (strip.childCount > 0) View.VISIBLE else View.GONE
        return strip.childCount > 0
    }

    /** Empty strip that fills itself off the UI thread (guide matching is too heavy for the HK1 main thread). */
    fun create(act: AppCompatActivity, g: JSONObject, max: Int = 3, size: Float = 22f): LinearLayout {
        val strip = UI.identify(UI.row(act, UI.dp(act, 4f)), "watch-on-${Data.key(g)}").apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        load(act, strip, g, max, size)
        return strip
    }

    fun load(act: AppCompatActivity, strip: LinearLayout, g: JSONObject, max: Int = 3, size: Float = 22f) {
        val key = Data.key(g); strip.tag = key
        if (!wants(g)) { strip.removeAllViews(); strip.visibility = View.GONE; return }
        cache[key]?.let { fill(act, strip, g, it, max, size); return }
        fill(act, strip, g, emptyList(), max, size)
        val bundle = BundleHolder.current ?: return
        act.lifecycleScope.launch {
            val found = withContext(Dispatchers.Default) { runCatching { GameChannelMatcher.find(g, bundle) }.getOrDefault(emptyList()) }
            cache[key] = found
            if (strip.tag == key) fill(act, strip, g, found, max, size)
        }
    }
}
