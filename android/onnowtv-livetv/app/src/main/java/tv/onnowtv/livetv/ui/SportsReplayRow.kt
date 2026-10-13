package tv.onnowtv.livetv.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.onnowtv.livetv.data.AuthStore
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.SportsReplayPolicy
import tv.onnowtv.livetv.data.SportsReplayRepository
import tv.onnowtv.livetv.data.XtreamBundle
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Work belongs to the attached card AND the foreground activity, never to a global scope. */
object SportsReplayRow {
    fun create(ctx: Context, owner: LifecycleOwner, game: JSONObject, bundle: XtreamBundle?, tag: String,
               open: (String) -> Unit, onAvailable: () -> Unit = {}): LinearLayout {
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
                        var previous: GameChannelMatcher.Match? = null
                        try {
                            delay(300) // Fast D-pad scrolling must not fan out provider requests.
                            // Same viewer URL rewrite as live playback, but never scan the guide on the UI thread.
                            val accountBundle = withContext(Dispatchers.Default) {
                                bundle.copy(channels = bundle.channels.map { channel ->
                                    if (channel.archiveEnabled) channel.copy(streamUrl = AuthStore.rewriteStreamUrl(ctx, channel.streamUrl)) else channel
                                })
                            }
                            while (isActive) {
                                val cached = previous
                                if (cached == null || !SportsReplayPolicy.check(game, cached).eligible ||
                                    cached.programme?.let { SportsReplayRepository.isVerified(cached.channel, it) } != true) {
                                    row.visibility = View.GONE
                                }
                                val match = SportsReplayRepository.discover(game, accountBundle)
                                previous = match
                                if (match != null) {
                                    source.text = listOfNotNull(match.channel.lcn, match.channel.name).joinToString(" · ")
                                    button.contentDescription = "Watch replay · ${match.channel.name}"
                                    button.setOnClickListener { open(match.channel.id) }
                                    row.visibility = View.VISIBLE; onAvailable()
                                } else row.visibility = View.GONE
                                delay(60_000)
                            }
                        } finally { row.visibility = View.GONE }
                    }
                }
            }
            override fun onViewDetachedFromWindow(view: View) { job?.cancel(); job = null; row.visibility = View.GONE }
        })
        return row
    }
}