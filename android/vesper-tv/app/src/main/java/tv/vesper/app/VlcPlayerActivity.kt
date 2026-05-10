package tv.vesper.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Native libVLC-based player Activity.
 *
 * Embeds the full libVLC engine (FFmpeg + every codec under the sun)
 * so we match Stremio's playback parity inside the same APK — no
 * external player handoff, no codec gaps.
 *
 * Launched from JavaScript via OnNowTV.playInternal(url, title, [sub]).
 *
 * Controls:
 *   - Tap / D-pad OK: toggle play/pause
 *   - Auto-hide after 4s of inactivity
 *   - Bottom seek bar with current / total time
 *   - Top: back button + title
 *   - D-pad left/right: skip ±10s when controls are visible
 *   - BACK: exit player
 */
class VlcPlayerActivity : AppCompatActivity() {

    private lateinit var libVlc: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout

    private lateinit var rootControls: View
    private lateinit var backBtn: ImageButton
    private lateinit var playBtn: ImageButton
    private lateinit var titleTv: TextView
    private lateinit var positionTv: TextView
    private lateinit var durationTv: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var loadingView: View

    private var controlsVisible = true
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateTimeline()
            tickHandler.postDelayed(this, 500)
        }
    }

    private var streamUrl: String? = null
    private var streamTitle: String? = null
    private var subUrl: String? = null
    private var isSeeking = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        volumeControlStream = AudioManager.STREAM_MUSIC

        streamUrl = intent.getStringExtra(EXTRA_URL)
        streamTitle = intent.getStringExtra(EXTRA_TITLE)
        subUrl = intent.getStringExtra(EXTRA_SUB_URL)

        if (streamUrl.isNullOrBlank()) {
            finish()
            return
        }

        setContentView(R.layout.activity_vlc_player)
        videoLayout = findViewById(R.id.video_layout)
        rootControls = findViewById(R.id.controls_root)
        backBtn = findViewById(R.id.btn_back)
        playBtn = findViewById(R.id.btn_play_pause)
        titleTv = findViewById(R.id.tv_title)
        positionTv = findViewById(R.id.tv_position)
        durationTv = findViewById(R.id.tv_duration)
        seekBar = findViewById(R.id.seek_bar)
        loadingView = findViewById(R.id.loading_view)

        titleTv.text = streamTitle ?: "Now playing"

        backBtn.setOnClickListener { finish() }
        playBtn.setOnClickListener {
            togglePlayPause()
            scheduleHide()
        }

        videoLayout.setOnClickListener {
            if (controlsVisible) {
                togglePlayPause()
            }
            showControls()
            scheduleHide()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                bar: SeekBar?, progress: Int, fromUser: Boolean
            ) {
                if (fromUser) positionTv.text = formatMillis(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {
                isSeeking = true
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                isSeeking = false
                mediaPlayer.time = bar?.progress?.toLong() ?: 0L
                scheduleHide()
            }
        })

        // Hide system bars for full immersion
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        initVlc()
        startPlayback()
        scheduleHide()
        tickHandler.post(tickRunnable)
    }

    private fun initVlc() {
        val args = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--network-caching=1500",
            "--http-reconnect",
            "--avcodec-hw=any", // hardware decode where possible
            "-vvv"              // verbose log — helpful for debugging codec issues
        )
        libVlc = LibVLC(this, args)
        mediaPlayer = MediaPlayer(libVlc)
        mediaPlayer.attachViews(videoLayout, null, false, false)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    loadingView.visibility = View.GONE
                    playBtn.setImageResource(R.drawable.ic_pause)
                }
                MediaPlayer.Event.Paused -> {
                    playBtn.setImageResource(R.drawable.ic_play)
                }
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering < 100f) {
                        loadingView.visibility = View.VISIBLE
                    } else {
                        loadingView.visibility = View.GONE
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC encountered an error")
                    loadingView.visibility = View.GONE
                }
                MediaPlayer.Event.EndReached -> {
                    finish()
                }
            }
        }
    }

    private fun startPlayback() {
        val media = Media(libVlc, Uri.parse(streamUrl))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1500")
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()

        // Attach a remote subtitle track if we were given one
        subUrl?.takeIf { it.isNotBlank() }?.let { url ->
            // libVLC needs the call to happen after playback has started.
            // Retry a few times with backoff.
            val handler = Handler(Looper.getMainLooper())
            val maxAttempts = 8
            val attempt = intArrayOf(0)
            lateinit var tryAdd: Runnable
            tryAdd = Runnable {
                @Suppress("DEPRECATION")
                val ok = mediaPlayer.addSlave(
                    IMedia.Slave.Type.Subtitle,
                    Uri.parse(url),
                    true
                )
                if (!ok && attempt[0] < maxAttempts) {
                    attempt[0]++
                    handler.postDelayed(tryAdd, 750L)
                }
            }
            handler.postDelayed(tryAdd, 1200L)
        }
    }

    private fun togglePlayPause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        } else {
            mediaPlayer.play()
        }
    }

    private fun updateTimeline() {
        if (isSeeking) return
        val length = mediaPlayer.length
        val time = mediaPlayer.time
        if (length > 0) {
            seekBar.max = length.toInt()
            seekBar.progress = time.toInt()
            durationTv.text = formatMillis(length)
            positionTv.text = formatMillis(time)
        }
    }

    private fun showControls() {
        rootControls.animate()
            .alpha(1f)
            .setDuration(180)
            .withStartAction { rootControls.visibility = View.VISIBLE }
            .start()
        controlsVisible = true
    }

    private fun hideControls() {
        rootControls.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction { rootControls.visibility = View.GONE }
            .start()
        controlsVisible = false
    }

    private fun scheduleHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 4_000)
    }

    private fun seekBy(deltaMs: Long) {
        val target = (mediaPlayer.time + deltaMs).coerceAtLeast(0L)
        mediaPlayer.time = target
        updateTimeline()
        showControls()
        scheduleHide()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Show controls on any remote keypress, then handle the action
        if (!controlsVisible) {
            showControls()
            scheduleHide()
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); true }
                else -> true
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause(); scheduleHide(); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); true }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        if (this::mediaPlayer.isInitialized) mediaPlayer.pause()
    }

    override fun onDestroy() {
        tickHandler.removeCallbacks(tickRunnable)
        hideHandler.removeCallbacks(hideRunnable)
        if (this::mediaPlayer.isInitialized) {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
        }
        if (this::libVlc.isInitialized) libVlc.release()
        super.onDestroy()
    }

    private fun formatMillis(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    companion object {
        private const val TAG = "VlcPlayerActivity"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUB_URL = "subUrl"
    }
}
