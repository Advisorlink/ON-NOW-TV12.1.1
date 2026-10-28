package tv.onnowtv.livetv.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.CatchUpIndex
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.XtreamBundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tv.onnowtv.livetv.ui.SportsUi as UI

/**
 * "Watch replay" on finished Sports Hub games, from the same server-confirmed recordings as Catch Up:
 * one cached request, no per-game provider probing, and it plays through the same branded player path.
 */
object SportsReplayRow {
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Pair<List<CatchUpIndex.Recording>, CatchUpIndex.Recording?>>()
    fun create(ctx: Context, owner: LifecycleOwner, game: JSONObject, bundle: XtreamBundle?, tag: String,
               @Suppress("UNUSED_PARAMETER") open: (String) -> Unit, onAvailable: () -> Unit = {}): LinearLayout {
        val row = UI.identify(UI.col(ctx, UI.dp(ctx, 5f)), "$tag-container").apply { visibility = View.GONE }
        if (bundle == null || game.optString("state") != "post") return row
        var job: Job? = null
        val source = UI.identify(UI.text(ctx, "", 10f, UI.DIM), "$tag-channel").apply { maxLines = 2 }
        val button = UI.identify(UI.pill(ctx, "↻ Watch replay", true) {}, tag)
        row.addView(button, LinearLayout.LayoutParams(-2, -2)); row.addView(source)
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                job?.cancel()
                job = owner.lifecycleScope.launch {
                    owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (isActive) {
                            val all = CatchUpIndex.recordings(ctx, bundle)
                            val id = game.optString("id")
                            val hit = memo[id]?.takeIf { it.first === all }?.second ?: withContext(Dispatchers.Default) {
                                all.filter { GameChannelMatcher.isFixtureReplay(game, it.programme) }.minByOrNull { it.programme.startMs }
                            }.also { memo[id] = all to it }
                            if (hit != null) {
                                val day = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()).format(Date(hit.programme.startMs))
                                source.text = "${hit.channel.name} · $day"
                                button.contentDescription = "Watch replay · ${hit.channel.name}"
                                button.setOnClickListener { play(ctx, game, hit, all) }
                                row.visibility = View.VISIBLE; onAvailable()
                            } else row.visibility = View.GONE
                            delay(120_000)
                        }
                    }
                }
            }
            override fun onViewDetachedFromWindow(view: View) { job?.cancel(); job = null }
        })
        return row
    }

    private fun team(o: JSONObject?) = o?.optString("name")?.takeIf { it.isNotBlank() }?.let { name ->
        PlayerLoadingOverlay.Team(name, o.optString("logo").takeIf { it.isNotBlank() && it != "null" },
            o.optString("color").takeIf { it.isNotBlank() && it != "null" }?.let { c -> runCatching { android.graphics.Color.parseColor("#" + c.removePrefix("#")) }.getOrNull() })
    }

    fun play(ctx: Context, game: JSONObject, hit: CatchUpIndex.Recording, all: List<CatchUpIndex.Recording>) {
        var c: Context? = ctx
        while (c is android.content.ContextWrapper && c !is AppCompatActivity) c = c.baseContext
        val activity = c as? AppCompatActivity ?: return
        val home = team(game.optJSONObject("home")); val away = team(game.optJSONObject("away"))
        val title = if (home != null && away != null) "${home.name} vs ${away.name}" else hit.programme.title
        val day = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(Date(hit.programme.startMs)).uppercase()
        CatchUpPlayback.start(activity, activity.findViewById<FrameLayout>(android.R.id.content), hit.channel, hit.programme, title,
            "${hit.channel.name} · $day", all.map { it.channel }.distinctBy { it.id }, home, away)
    }
}
