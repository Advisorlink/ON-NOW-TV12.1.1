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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.*
import tv.onnowtv.livetv.ui.SportsLoadingBand
import tv.onnowtv.livetv.ui.SportsWindow
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp

/** One replay, one player. No credential-bearing URLs in intents, logs or persistent state. */
class SportsReplayActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context, game: JSONObject, channelId: String) = ctx.startActivity(Intent(ctx, SportsReplayActivity::class.java).apply {
            putExtra("game", game.toString()); putExtra("channel", channelId)
        })
        fun openProgramme(ctx: Context, entry: SportsReplayCatalogue.Entry) = ctx.startActivity(Intent(ctx, SportsReplayActivity::class.java).apply {
            putExtra("channel", entry.channel.id); putExtra("programme-start", entry.programme.startMs)
        })
    }
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var retry: TextView
    private lateinit var header: LinearLayout
    private lateinit var loading: SportsLoadingBand
    private var player: ExoPlayer? = null
    private var fetch: Job? = null
    private var stall: Job? = null
    private var resumeMs = 0L
    private var activeChannel: Channel? = null
    private var activeProgramme: Programme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); SportsWindow.apply(this)
        LivePreviewSession.release()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = UI.identify(PlayerView(this), "sports-replay-player").apply {
            keepScreenOn = true; setShowNextButton(false); setShowPreviousButton(false)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        header = UI.col(this, dp(this, 10f)).apply {
            setPadding(dp(context, 16f), dp(context, 16f), dp(context, 16f), dp(context, 14f))
            setBackgroundColor(0xED0A0F1A.toInt())
        }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            header.visibility = if (player?.playbackState == Player.STATE_READY) visibility else View.VISIBLE
        })
        val controls = UI.row(this, dp(this, 8f))
        controls.addView(UI.identify(UI.text(this, "V2 REPLAY", 23f, bold = true), "replay-title"), LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(UI.identify(UI.pill(this, "↺ Restart") { resumeMs = 0; begin() }, "replay-restart"))
        controls.addView(UI.identify(UI.pill(this, "‹ Back") { finish() }, "replay-back")); header.addView(controls)
        status = UI.identify(UI.text(this, "Opening replay…", 16f, UI.DIM), "replay-status"); header.addView(status)
        loading = SportsLoadingBand(this, "replay"); header.addView(loading)
        retry = UI.identify(UI.pill(this, "Retry") { begin() }, "replay-retry").apply { visibility = View.GONE }
        header.addView(retry, LinearLayout.LayoutParams(-2, -2))
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); setContentView(root)
        resumeMs = savedInstanceState?.getLong("position", 0L) ?: 0L
    }
    override fun onStart() { super.onStart(); begin() }
    private fun releasePlayer() {
        stall?.cancel(); playerView.player = null; player?.release(); player = null
    }
    override fun finish() { fetch?.cancel(); if (::playerView.isInitialized) releasePlayer(); super.finish() }
    override fun onStop() { fetch?.cancel(); resumeMs = player?.currentPosition ?: resumeMs; releasePlayer(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", player?.currentPosition ?: resumeMs); super.onSaveInstanceState(outState) }

    private fun begin() {
        fetch?.cancel(); releasePlayer(); retry.visibility = View.GONE; header.visibility = View.VISIBLE
        loading.show("Opening your recording…")
        val bundle = BundleHolder.current ?: run { fail("Open the guide again before playing this replay"); return }
        val original = bundle.channels.firstOrNull { it.id == intent.getStringExtra("channel") }
            ?: run { fail("This replay channel is no longer in your guide"); return }
        val channel = original.copy(streamUrl = AuthStore.rewriteStreamUrl(this, original.streamUrl))
        status.text = channel.name
        fetch = lifecycleScope.launch {
            val programme: Programme? = if (intent.hasExtra("programme-start")) {
                SportsReplayRepository.archiveProgrammes(channel)?.firstOrNull {
                    it.startMs == intent.getLongExtra("programme-start", -1) && it.archiveAvailable == true
                }
            } else withContext(Dispatchers.Default) {
                val game = runCatching { JSONObject(intent.getStringExtra("game").orEmpty()) }.getOrNull()
                game?.let { fixture -> GameChannelMatcher.find(fixture, tv.onnowtv.livetv.data.SharedSportsGuide.enriched(bundle).copy(channels = listOf(channel)))
                    .firstOrNull { it.confirmed && SportsReplayPolicy.check(fixture, it).eligible }?.programme }
            }
            if (BundleHolder.current !== bundle) { fail("The guide account changed. Reopen this replay."); return@launch }
            if (programme == null) { fail("This recording is no longer available in your guide"); return@launch }
            activeChannel = channel; activeProgramme = programme
            status.text = "${programme.title}\n${channel.name}"
            val result = SportsReplayRepository.verifyProgramme(channel, programme)
            val verified = result.verified ?: run { fail(result.message); return@launch }
            if (BundleHolder.current !== bundle) { fail("The guide account changed. Reopen this replay."); return@launch }
            startPlayer(verified, channel)
        }
    }
    private fun startPlayer(verified: SportsReplayRepository.Verified, channel: Channel) {
        val p = SportsReplayTransport.player(this); player = p; playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                activeProgramme?.let { SportsReplayRepository.invalidate(channel, it) }
                // Error code is safe; exception causes may contain credentials, so never display/log them.
                fail("Recording could not play (${error.errorCodeName}). Please retry.")
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> buffering()
                    Player.STATE_READY -> {
                        stall?.cancel(); loading.show(null)
                        playerView.setShowFastForwardButton(p.isCurrentMediaItemSeekable)
                        playerView.setShowRewindButton(p.isCurrentMediaItemSeekable)
                        if (resumeMs > 0 && p.isCurrentMediaItemSeekable) p.seekTo(resumeMs)
                        resumeMs = 0
                        status.text = "REPLAY · ${channel.name}" + if (p.isCurrentMediaItemSeekable) "" else " · Seeking unavailable from provider"
                    }
                    Player.STATE_ENDED -> { stall?.cancel(); loading.show(null); header.visibility = View.VISIBLE; status.text = "Replay finished · ${channel.name}" }
                }
            }
        })
        p.setMediaItem(MediaItem.Builder().setUri(verified.url).setMimeType(verified.mimeType).build())
        p.prepare(); p.playWhenReady = true; buffering(); playerView.requestFocus()
    }
    private fun buffering() {
        header.visibility = View.VISIBLE; loading.show("Buffering recording…")
        stall?.cancel(); stall = lifecycleScope.launch { delay(35_000); fail("The recording is taking too long to load. Please retry.") }
    }
    private fun fail(message: String) {
        releasePlayer(); header.visibility = View.VISIBLE; loading.show(null)
        status.text = message; retry.visibility = View.VISIBLE; retry.requestFocus()
    }
}