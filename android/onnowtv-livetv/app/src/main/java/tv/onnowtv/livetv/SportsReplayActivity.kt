package tv.onnowtv.livetv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.AuthStore
import tv.onnowtv.livetv.data.SportsReplayRepository
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp

/** Isolated catch-up player. The existing live PlayerActivity and its tuning behavior are untouched. */
class SportsReplayActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context, game: JSONObject, channelId: String) = ctx.startActivity(Intent(ctx, SportsReplayActivity::class.java).apply {
            putExtra("game", game.toString()); putExtra("channel", channelId) // No stream URL or credentials in the Intent.
        })
    }
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var retry: TextView
    private lateinit var header: LinearLayout
    private var player: ExoPlayer? = null
    private var fetch: Job? = null
    private var resumeMs = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply { id = View.generateViewId(); tag = "sports-replay-player"; keepScreenOn = true; setShowNextButton(false); setShowPreviousButton(false) }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val top = UI.col(this, dp(this, 9f)).apply { setPadding(dp(this@SportsReplayActivity, 22f), dp(this@SportsReplayActivity, 18f), dp(this@SportsReplayActivity, 22f), dp(this@SportsReplayActivity, 14f)); setBackgroundColor(0xD90A0F1A.toInt()) }
        header = top
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> header.visibility = if (player?.playbackState == Player.STATE_READY) visibility else View.VISIBLE })
        val controls = UI.row(this, dp(this, 10f)); controls.addView(UI.wordmark(this, true), LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(UI.identify(UI.pill(this, "↺ Restart") { resumeMs = 0; begin() }, "replay-restart"))
        controls.addView(UI.identify(UI.pill(this, "‹ Back") { finish() }, "replay-back")); top.addView(controls)
        status = UI.identify(UI.text(this, "Opening replay…", 13f, UI.DIM), "replay-status"); top.addView(status)
        retry = UI.identify(UI.pill(this, "Retry") { begin() }, "replay-retry").apply { visibility = View.GONE }; top.addView(retry, LinearLayout.LayoutParams(-2, -2))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); setContentView(root)
        resumeMs = savedInstanceState?.getLong("position", 0L) ?: 0L
    }
    override fun onStart() { super.onStart(); begin() }
    override fun onStop() { fetch?.cancel(); resumeMs = player?.currentPosition ?: resumeMs; playerView.player = null; player?.release(); player = null; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", player?.currentPosition ?: resumeMs); super.onSaveInstanceState(outState) }
    private fun begin() {
        fetch?.cancel(); playerView.player = null; player?.release(); player = null; retry.visibility = View.GONE; header.visibility = View.VISIBLE
        val game = try { JSONObject(intent.getStringExtra("game").orEmpty()) } catch (_: Exception) { fail("Game details unavailable"); return }
        val bundle = BundleHolder.current ?: run { fail("Open the guide again before playing this replay"); return }
        val channel = bundle.channels.firstOrNull { it.id == intent.getStringExtra("channel") }
            ?: run { fail("This replay channel is no longer in your guide"); return }
        val accountChannel = channel.copy(streamUrl = AuthStore.rewriteStreamUrl(this, channel.streamUrl))
        val match = GameChannelMatcher.find(game, bundle.copy(channels = listOf(accountChannel))).firstOrNull { it.confirmed }
            ?: run { fail("The fixture is no longer confirmed in your guide"); return }
        status.text = "Opening replay · ${match.channel.name}…"
        fetch = lifecycleScope.launch {
            val result = SportsReplayRepository.verify(game, match)
            val verified = result.verified ?: run { fail(result.message); return@launch }
            val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(SportsReplayRepository.USER_AGENT).setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
            val source = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this@SportsReplayActivity).setDataSourceFactory(http)
            player = ExoPlayer.Builder(this@SportsReplayActivity).setMediaSourceFactory(source).build().also { p ->
                playerView.player = p
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { match.programme?.let { SportsReplayRepository.invalidate(match.channel, it) }; fail("Your provider could not play this recording. Please retry.") }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            status.text = "REPLAY · ${match.channel.name}" + if (p.isCurrentMediaItemSeekable) "" else " · Seeking unavailable from provider"
                            playerView.setShowFastForwardButton(p.isCurrentMediaItemSeekable); playerView.setShowRewindButton(p.isCurrentMediaItemSeekable)
                            if (resumeMs > 0 && p.isCurrentMediaItemSeekable) p.seekTo(resumeMs)
                            resumeMs = 0
                        }
                    }
                })
                p.setMediaItem(MediaItem.Builder().setUri(verified.url).setMimeType(verified.mimeType).build()); p.prepare(); p.playWhenReady = true
            }
            playerView.requestFocus()
        }
    }
    private fun fail(message: String) { header.visibility = View.VISIBLE; status.text = message; retry.visibility = View.VISIBLE; retry.requestFocus() }
}