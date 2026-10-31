package tv.onnowtv.livetv.ui

import android.content.Intent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.PlayerActivity
import tv.onnowtv.livetv.data.CatchUpIndex
import tv.onnowtv.livetv.data.CatchUpResolver
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme

/** One path for every catch-up play: branded loading cover → quick check → the normal player (same cover). */
object CatchUpPlayback {
    private var busy = false

    fun start(
        activity: AppCompatActivity, host: FrameLayout, channel: Channel, programme: Programme, title: String, subtitle: String,
        all: List<Channel>, home: PlayerLoadingOverlay.Team? = null, away: PlayerLoadingOverlay.Team? = null, onMissing: () -> Unit = {},
    ) {
        if (busy) return
        busy = true
        val cover = host.findViewWithTag<PlayerLoadingOverlay>("player-loading-screen")
            ?: PlayerLoadingOverlay(activity).also { host.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        cover.bringToFront()
        val message = if (home != null && away != null) "LOADING YOUR GAME…" else "LOADING YOUR PROGRAMME…"
        cover.show("↺  CATCH UP", title, subtitle, home, away, channel.logoUrl, message)
        // Free the line (1-connection accounts) before the recording connects.
        tv.onnowtv.livetv.LivePreviewSession.release()
        activity.lifecycleScope.launch {
            val ready = try { CatchUpResolver.resolve(activity, channel, programme, all) } finally { busy = false }
            if (ready == null) {
                cover.dismiss()
                CatchUpIndex.forget(channel, programme)
                Toast.makeText(activity, "Your provider doesn't have this recording, so it's been removed from the list", Toast.LENGTH_LONG).show()
                onMissing()
                return@launch
            }
            activity.startActivity(Intent(activity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, ready.url)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SUBTITLE, "CATCH UP · $subtitle")
                putExtra(PlayerActivity.EXTRA_STOP_ON_EXIT, true)
                home?.let { putExtra(PlayerActivity.EXTRA_HOME_NAME, it.name); putExtra(PlayerActivity.EXTRA_HOME_LOGO, it.logo); it.color?.let { c -> putExtra(PlayerActivity.EXTRA_HOME_COLOR, String.format("#%06X", 0xFFFFFF and c)) } }
                away?.let { putExtra(PlayerActivity.EXTRA_AWAY_NAME, it.name); putExtra(PlayerActivity.EXTRA_AWAY_LOGO, it.logo); it.color?.let { c -> putExtra(PlayerActivity.EXTRA_AWAY_COLOR, String.format("#%06X", 0xFFFFFF and c)) } }
            })
            activity.overridePendingTransition(0, 0)
            cover.postDelayed({ cover.dismiss() }, 1_200L)
        }
    }
}
