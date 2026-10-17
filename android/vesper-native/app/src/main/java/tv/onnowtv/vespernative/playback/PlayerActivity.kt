package tv.onnowtv.vespernative.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import tv.onnowtv.vespernative.ui.*
import tv.onnowtv.vespernative.playback.v2.TrackOption
import tv.onnowtv.vespernative.playback.v2.StreamOption

/** Working Media3 transport, now driven by the actual original V2 native UI. */
class PlayerActivity:AppCompatActivity() {
    private var player:ExoPlayer?=null
    private lateinit var video:PlayerView
    private lateinit var visuals:PlayerVisualState
    private val handler=Handler(Looper.getMainLooper())
    private var position=0L; private var completed=false; private var paused=false; private var bufferingSince=0L
    private var selected=0
    private val payload by lazy { PlaybackPayload.read(this,intent.getStringExtra("payload")) }
    private val queue by lazy { PlaybackPayload.streams(payload).ifEmpty {
        runCatching { Stream.parse(JSONObject(intent.getStringExtra("stream") ?: "{}"),0) }.getOrNull()?.let(::listOf).orEmpty()
    } }
    private val stream get()=queue.getOrNull(selected)
    private val key by lazy { intent.getStringExtra("resume-key").orEmpty() }
    private val prefs by lazy { getSharedPreferences("native-playback-local",MODE_PRIVATE) }
    private val trackMap=mutableMapOf<String,Pair<Tracks.Group,Int>>()
    private val meter by lazy { DefaultBandwidthMeter.getSingletonInstance(this) }
    private val bandwidthListener=BandwidthMeter.EventListener { elapsed,bytes,_->
        if(::visuals.isInitialized && elapsed>0)visuals.bitrate.value=bytes*8/elapsed
    }
    private val tick=object:Runnable { override fun run() {
        player?.let { p ->
            visuals.position.value=p.currentPosition.coerceAtLeast(0); visuals.duration.value=p.duration.coerceAtLeast(0)
            visuals.buffered.value=(p.bufferedPosition-p.currentPosition).coerceAtLeast(0); visuals.percent.value=p.bufferedPercentage
            if(bufferingSince>0 && android.os.SystemClock.elapsedRealtime()-bufferingSince>35_000) {
                bufferingSince=0; error("This stream is taking too long. Retry or choose another stream.")
            }
        }; handler.postDelayed(this,500)
    } }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window,window.decorView).apply { systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(WindowInsetsCompat.Type.systemBars()) }
        selected=(savedInstanceState?.getInt("selected") ?: payload?.optInt("selected",0) ?: 0).coerceIn(0,(queue.size-1).coerceAtLeast(0))
        if(stream==null || key.isBlank()) { finish(); return }
        position=savedInstanceState?.getLong("position") ?: prefs.getLong(key,0)
        paused=savedInstanceState?.getBoolean("paused") ?: false
        visuals=PlayerVisualState(payload,intent.getStringExtra("title").orEmpty())
        val root=FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        video=TvUi.identify(PlayerView(this),"player-video").apply { useController=false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) }
        root.addView(video,FrameLayout.LayoutParams(-1,-1))
        val overlay=visuals.view(this,
            playPause={ player?.let {
                if(it.isPlaying)it.pause() else {
                    if(it.playbackState==Player.STATE_ENDED) { it.seekTo(0); completed=false }
                    it.play()
                }
            } },
            seek={ value->player?.let { if(it.isCurrentMediaItemSeekable)it.seekTo(value.coerceIn(0,it.duration.coerceAtLeast(0))) } },
            audioPick={ pickTrack(C.TRACK_TYPE_AUDIO,it) },subtitlePick={ pickTrack(C.TRACK_TYPE_TEXT,it) },
            streamPick=::swap,aspectPick=::aspect,close={ finish() })
        TvUi.identify(overlay,"player-original-v2-overlay"); root.addView(overlay,FrameLayout.LayoutParams(-1,-1)); setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() {
            if(visuals.controlsVisible) { visuals.hide.value=System.currentTimeMillis(); visuals.controlsVisible=false } else finish()
        } })
        updateStreams()
    }
    override fun onStart() { super.onStart(); if(stream!=null && key.isNotBlank())prepare() }
    private fun prepare() {
        if(player!=null)return
        val value=stream ?: return
        visuals.loading.value=true; visuals.error.value=null
        // Deliberately independent of the authenticated API client. A provider's
        // credentials are origin-bound; the account JWT is never sent to media.
        val origin=value.url.toHttpUrl()
        val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).addNetworkInterceptor { chain ->
                val request=chain.request(); val same=request.url.host==origin.host && request.url.scheme==origin.scheme && request.url.port==origin.port
                val builder=request.newBuilder()
                if(same)value.headers.forEach { (k,v)->builder.header(k,v) } else value.headers.keys.forEach { builder.removeHeader(it) }
                chain.proceed(builder.build())
            }.build()
        val p=ExoPlayer.Builder(this,DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
            .setBandwidthMeter(meter)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player=p; video.player=p
        meter.addEventListener(handler,bandwidthListener)
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
        p.addListener(object:Player.Listener {
            override fun onPlaybackStateChanged(state:Int) {
                bufferingSince=if(state==Player.STATE_BUFFERING)android.os.SystemClock.elapsedRealtime() else 0
                visuals.loading.value=state==Player.STATE_BUFFERING || state==Player.STATE_IDLE
                if(state==Player.STATE_ENDED) { completed=true; prefs.edit().remove(key).apply(); visuals.touch() }
            }
            override fun onIsPlayingChanged(value:Boolean) { visuals.playing.value=value }
            override fun onTracksChanged(tracks:Tracks) { updateTracks(tracks) }
            override fun onPlayerError(e:PlaybackException) { error("Couldn't play this stream (${e.errorCodeName}).") }
        })
        p.setMediaItem(MediaItem.fromUri(value.url)); p.seekTo(position); p.prepare(); p.playWhenReady=!paused
        handler.post(tick); visuals.touch()
    }
    private fun updateStreams() {
        visuals.streams.value=queue.mapIndexed { i,s-> StreamOption(idx=i,label=s.description.ifBlank { s.name },selected=selected==i,addonSource=s.name,
            quality=s.quality.ifBlank { if(s.fhd)"1080p" else "" },sizeChip=s.size?.let { "%.1f GB".format(it) }.orEmpty()) }
        stream?.let { visuals.liveInfo.value=visuals.info.copy(addonSource=it.name,quality=it.quality.ifBlank { if(it.fhd)"1080p" else "" },sizeGb=it.size?.toFloat() ?: 0f) }
    }
    private fun swap(index:Int) {
        if(index !in queue.indices || index==selected)return
        release(); selected=index; completed=false; updateStreams(); prepare()
    }
    private fun updateTracks(tracks:Tracks) {
        trackMap.clear()
        fun options(type:Int):List<TrackOption> = tracks.groups.flatMapIndexed { groupIndex,g ->
            if(g.type!=type)emptyList() else (0 until g.length).filter(g::isTrackSupported).map { i ->
                val id="$groupIndex:$i"; trackMap[id]=g to i
                val f=g.getTrackFormat(i)
                val label=f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "Track ${i+1}"
                val details=listOfNotNull(f.sampleMimeType?.substringAfter('/'),f.channelCount.takeIf { it>0 }?.let { "$it ch" }).joinToString(" · ")
                TrackOption(id,listOf(label,details).filter(String::isNotBlank).joinToString(" · "),g.isTrackSelected(i))
            }
        }
        visuals.audio.value=options(C.TRACK_TYPE_AUDIO); visuals.subtitles.value=options(C.TRACK_TYPE_TEXT)
    }
    private fun pickTrack(type:Int,id:String) {
        val p=player ?: return; val builder=p.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if(id=="off" || id=="off_permanent") {
            builder.setTrackTypeDisabled(type,true)
            if(id=="off_permanent")prefs.edit().putBoolean("${key.substringBefore(':')}:subtitles-off",true).apply()
        } else {
            val (group,index)=trackMap[id] ?: return
            if(group.type!=type)return
            builder.setTrackTypeDisabled(type,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index))
        }
        p.trackSelectionParameters=builder.build()
    }
    private fun aspect(token:String) {
        visuals.aspect.value=token; video.scaleX=if(token=="zoom")1.15f else 1f; video.scaleY=video.scaleX
        video.resizeMode=when(token) { "fill","zoom"->AspectRatioFrameLayout.RESIZE_MODE_ZOOM; "stretch"->AspectRatioFrameLayout.RESIZE_MODE_FILL; else->AspectRatioFrameLayout.RESIZE_MODE_FIT }
    }
    private fun error(message:String) {
        release(); visuals.error.value=message; visuals.loading.value=true
        V2Dialog.show(this,"player-error-dialog","Stream unavailable",message,listOf(
            V2Dialog.Action("player-error-retry","Retry","play",true) { prepare() },
            V2Dialog.Action("player-error-streams","Choose another stream","back") { finish() }))
    }
    override fun dispatchKeyEvent(e:KeyEvent):Boolean {
        if(e.action==KeyEvent.ACTION_DOWN && player!=null && ::visuals.isInitialized) {
            when(e.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player?.let { if(it.isPlaying)it.pause() else it.play() }; visuals.touch(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); visuals.touch(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); visuals.touch(); return true }
            }
            if(e.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)) {
                val wasVisible=visuals.controlsVisible; visuals.touch()
                if(!wasVisible)return true // first key reveals the original dock without accidental activation
            }
        }
        return super.dispatchKeyEvent(e)
    }
    private fun release() {
        handler.removeCallbacksAndMessages(null)
        meter.removeEventListener(bandwidthListener)
        player?.let { p -> position=p.currentPosition.coerceAtLeast(0); paused=!p.playWhenReady
            if(!completed && position>0)prefs.edit().putLong(key,position).apply()
            video.player=null; p.release()
        }; player=null; bufferingSince=0
    }
    override fun onSaveInstanceState(out:Bundle) { out.putLong("position",player?.currentPosition ?: position); out.putInt("selected",selected); out.putBoolean("paused",player?.let { !it.playWhenReady } ?: paused); super.onSaveInstanceState(out) }
    override fun onStop() { release(); super.onStop() }
    override fun onDestroy() { release(); super.onDestroy() }
}