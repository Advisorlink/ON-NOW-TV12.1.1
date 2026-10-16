package tv.onnowtv.vespernative.playback

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import tv.onnowtv.vespernative.ui.TvUi

/** First native playback path. No WebView, account token, cloud push or background decoder. */
class PlayerActivity:AppCompatActivity() {
    private var player:ExoPlayer?=null
    private lateinit var video:PlayerView
    private lateinit var overlay:LinearLayout
    private lateinit var state:TextView
    private lateinit var time:TextView
    private lateinit var toggle:TextView
    private lateinit var seek:SeekBar
    private val handler=Handler(Looper.getMainLooper())
    private var seeking=false; private var position=0L; private var completed=false; private var paused=false
    private var bufferingSince=0L
    private val stream by lazy { Stream.parse(JSONObject(intent.getStringExtra("stream") ?: "{}"),0) }
    private val key by lazy { intent.getStringExtra("resume-key").orEmpty() }
    private val prefs by lazy { getSharedPreferences("native-playback-local",MODE_PRIVATE) }
    private val hide=Runnable { overlay.visibility=View.GONE }
    private val tick=object:Runnable { override fun run() {
        player?.let { p ->
            val duration=p.duration.coerceAtLeast(0); if(!seeking && duration>0)seek.progress=((p.currentPosition*1000)/duration).toInt()
            time.text="${clock(p.currentPosition)}  /  ${clock(duration)}"
            if(bufferingSince>0 && android.os.SystemClock.elapsedRealtime()-bufferingSince>35_000) {
                bufferingSince=0; error("This stream is taking too long. Retry or choose another stream.")
            }
        }; handler.postDelayed(this,1000)
    } }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window,window.decorView).apply { systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(WindowInsetsCompat.Type.systemBars()) }
        if(stream==null || key.isBlank()) { finish(); return }
        position=savedInstanceState?.getLong("position") ?: prefs.getLong(key,0)
        val root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video=TvUi.identify(PlayerView(this),"player-video").apply { useController=false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) }
        root.addView(video,FrameLayout.LayoutParams(-1,-1))
        state=TvUi.text(this,"player-state","Opening stream…",18f).apply { gravity=Gravity.CENTER }
        root.addView(state,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))
        overlay=TvUi.column(this).apply { setPadding(TvUi.dp(this@PlayerActivity,32),TvUi.dp(this@PlayerActivity,20),TvUi.dp(this@PlayerActivity,32),TvUi.dp(this@PlayerActivity,20)); setBackgroundColor(Color.argb(230,3,6,15)) }
        overlay.addView(TvUi.heading(this,"player-title",intent.getStringExtra("title").orEmpty(),22f))
        overlay.addView(TvUi.text(this,"player-source",stream!!.name,12f,TvUi.accent)); overlay.addView(TvUi.gap(this,10))
        seek=TvUi.identify(SeekBar(this),"player-seek").apply { max=1000; contentDescription="Playback position" }
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s:SeekBar) { seeking=true; handler.removeCallbacks(hide) }
            override fun onStopTrackingTouch(s:SeekBar) { seeking=false; player?.let { if(it.isCurrentMediaItemSeekable)it.seekTo(it.duration.coerceAtLeast(0)*s.progress/1000) }; show() }
            override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean) { if(user && !seeking)player?.let { if(it.isCurrentMediaItemSeekable)it.seekTo(it.duration.coerceAtLeast(0)*p/1000) } }
        })
        overlay.addView(seek,LinearLayout.LayoutParams(-1,TvUi.dp(this,32)))
        time=TvUi.text(this,"player-time",size=12f); overlay.addView(time)
        val controls=TvUi.row(this)
        fun button(id:String,label:String,action:()->Unit):TextView=TvUi.button(this,id,label) { action(); show() }.also {
            it.textSize=12f; controls.addView(it,LinearLayout.LayoutParams(-2,TvUi.dp(this,42)).apply { marginEnd=TvUi.dp(this@PlayerActivity,6) })
        }
        button("player-back","← Back") { finish() }
        button("player-rewind","−10s") { player?.seekBack() }
        toggle=button("player-toggle","Pause") { player?.let { if(it.isPlaying)it.pause() else it.play() } }
        button("player-forward","+10s") { player?.seekForward() }
        button("player-stream","Swap stream") { finish() }
        button("player-audio","Audio") { tracks(C.TRACK_TYPE_AUDIO) }
        button("player-subtitles","Subtitles") { tracks(C.TRACK_TYPE_TEXT) }
        button("player-speed","Speed") { choose("Playback speed",listOf("0.75×","1×","1.25×","1.5×","2×")) { player?.setPlaybackSpeed(listOf(.75f,1f,1.25f,1.5f,2f)[it]) } }
        button("player-aspect","Aspect") { choose("Aspect ratio",listOf("Fit","Fill","Zoom")) { video.resizeMode=listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,AspectRatioFrameLayout.RESIZE_MODE_FILL,AspectRatioFrameLayout.RESIZE_MODE_ZOOM)[it] } }
        overlay.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(controls) })
        root.addView(overlay,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM)); setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { finish() } })
    }
    override fun onStart() { super.onStart(); if(stream!=null && key.isNotBlank())prepare() }
    private fun prepare() {
        if(player!=null)return
        val value=stream ?: return
        // Intentionally separate from the API's authenticated client. Provider headers
        // are restricted to the original origin; never forward account credentials.
        val origin=value.url.toHttpUrl()
        val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).addNetworkInterceptor { chain ->
                val request=chain.request(); val same=request.url.host==origin.host && request.url.scheme==origin.scheme && request.url.port==origin.port
                val builder=request.newBuilder()
                if(same)value.headers.forEach { (k,v)->builder.header(k,v) }
                else value.headers.keys.forEach { builder.removeHeader(it) }
                chain.proceed(builder.build())
            }.build()
        val p=ExoPlayer.Builder(this,DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player=p; video.player=p
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
        p.addListener(object:Player.Listener {
            override fun onPlaybackStateChanged(s:Int) {
                bufferingSince=if(s==Player.STATE_BUFFERING)android.os.SystemClock.elapsedRealtime() else 0
                state.text=if(s==Player.STATE_BUFFERING)"Buffering…" else ""
                state.visibility=if(s==Player.STATE_BUFFERING)View.VISIBLE else View.GONE
                seek.isEnabled=p.isCurrentMediaItemSeekable
                if(s==Player.STATE_ENDED) { completed=true; prefs.edit().remove(key).apply(); show() }
            }
            override fun onIsPlayingChanged(value:Boolean) { toggle.text=if(value)"Pause" else "Play" }
            override fun onPlayerError(e:PlaybackException) { error("Couldn't play this stream (${e.errorCodeName}).") }
        })
        p.setMediaItem(MediaItem.fromUri(value.url)); p.seekTo(position); p.prepare(); p.playWhenReady=!paused
        handler.post(tick); show(); toggle.requestFocus()
    }
    private fun show() { overlay.visibility=View.VISIBLE; handler.removeCallbacks(hide); if(player?.playWhenReady==true)handler.postDelayed(hide,6000) }
    private fun error(message:String) {
        release(); state.text=message; state.visibility=View.VISIBLE; show()
        val dialog=AlertDialog.Builder(this).setTitle("Stream unavailable").setMessage(message)
            .setPositiveButton("Retry") { _,_->prepare() }.setNegativeButton("Choose another stream") { _,_->finish() }.create()
        dialog.setOnShowListener { TvUi.identify(dialog.window!!.decorView,"player-error-dialog"); TvUi.identify(dialog.getButton(-1),"player-error-retry"); TvUi.identify(dialog.getButton(-2),"player-error-streams") }
        dialog.show()
    }
    private fun choose(title:String,labels:List<String>,select:(Int)->Unit) {
        handler.removeCallbacks(hide)
        val dialog=AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()) { _,i->select(i) }.create()
        dialog.setOnShowListener { TvUi.identify(dialog.window!!.decorView,"player-choice-dialog"); TvUi.identify(dialog.listView,"player-choice-list") }
        dialog.setOnDismissListener { show() }; dialog.show()
    }
    private fun tracks(type:Int) {
        val p=player ?: return
        val choices=p.currentTracks.groups.filter { it.type==type }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).map { index -> group to index }
        }
        val labels=choices.map { (g,i)->g.getTrackFormat(i).let { "${it.label ?: it.language ?: "Track ${i+1}"} ${it.channelCount.takeIf { n->n>0 }?.let { n->"· $n ch" }.orEmpty()}" } }
        if(choices.isEmpty()) { choose("${if(type==C.TRACK_TYPE_AUDIO)"Audio" else "Subtitles"}",listOf("No tracks available")) {}; return }
        choose(if(type==C.TRACK_TYPE_AUDIO)"Audio" else "Subtitles",if(type==C.TRACK_TYPE_TEXT)listOf("Off")+labels else labels) { index ->
            val builder=p.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            if(type==C.TRACK_TYPE_TEXT && index==0)builder.setTrackTypeDisabled(type,true) else {
                val (group,i)=choices[index-if(type==C.TRACK_TYPE_TEXT)1 else 0]
                builder.setTrackTypeDisabled(type,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,i))
            }
            p.trackSelectionParameters=builder.build()
        }
    }
    override fun dispatchKeyEvent(e:KeyEvent):Boolean {
        if(e.action==KeyEvent.ACTION_DOWN && player!=null) {
            when(e.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player?.let { if(it.isPlaying)it.pause() else it.play() }; show(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); show(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); show(); return true }
            }
            if(overlay.visibility!=View.VISIBLE && e.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) { show(); toggle.requestFocus(); return true }
            if(overlay.visibility!=View.VISIBLE && e.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if(e.keyCode==KeyEvent.KEYCODE_DPAD_LEFT)player?.seekBack() else player?.seekForward(); return true
            }
            handler.removeCallbacks(hide); if(player?.isPlaying==true)handler.postDelayed(hide,6000)
        }
        return super.dispatchKeyEvent(e)
    }
    private fun release() {
        handler.removeCallbacksAndMessages(null)
        player?.let { p -> position=p.currentPosition.coerceAtLeast(0); paused=!p.playWhenReady
            if(!completed && position>0)prefs.edit().putLong(key,position).apply()
            video.player=null; p.release()
        }; player=null; bufferingSince=0
    }
    override fun onSaveInstanceState(out:Bundle) { out.putLong("position",player?.currentPosition ?: position); super.onSaveInstanceState(out) }
    override fun onStop() { release(); super.onStop() }
    override fun onDestroy() { release(); super.onDestroy() }
    private fun clock(ms:Long):String { val seconds=ms.coerceAtLeast(0)/1000; return "%d:%02d".format(seconds/60,seconds%60) }
}