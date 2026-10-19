package tv.onnowtv.vespernative.playback

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.*
import android.widget.FrameLayout
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.ui.TvUi

/** One decoder per screen; a native texture follows the wide card, never its own focus target. */
class TrailerPreview(private val c:Context,private val scope:CoroutineScope,private val repository:TrailerRepository) {
    private var job:Job?=null
    private var player:ExoPlayer?=null
    private var view:PlayerView?=null
    private var dialog:Dialog?=null
    private var host:FrameLayout?=null
    private var generation=0
    private var watchdog:Job?=null
    private val warmup=TrailerWarmup(scope,repository)
    private val preloads=mutableMapOf<String,Job>()
    fun warm(titles:List<Title>,foreground:Boolean=false){
        warmup.prime(titles,foreground).forEach { prepared -> scope.launch {
            val media=runCatching { prepared.media.await() }.getOrNull() ?: return@launch
            if(media.video in preloads)return@launch
            if(preloads.size>24)preloads.keys.take(12).forEach(preloads::remove)
            preloads[media.video]=scope.launch { TrailerCache.preload(c,media) }
        } }
    }
    val isPlaying get()=player?.playbackState==Player.STATE_READY
    val fullscreenVisible get()=dialog?.isShowing==true

    fun focus(title:Title,target:FrameLayout,controls:Boolean=false,onState:(String)->Unit={}) = start(title,target,controls,2,onState)
    private fun start(title:Title,target:FrameLayout,controls:Boolean,retries:Int,onState:(String)->Unit) {
        stop(); host=target; val token=++generation
        val prepared=warmup.focus(title)
        job=scope.launch {
            delay(280)
            onState("Loading trailer…")
            try {
                val accepted=repository.candidates(title,prepared).firstOrNull { media ->
                ensureActive(); if(token!=generation || !target.isAttachedToWindow)return@firstOrNull false
                release()
                val surface=createTrailerSurface(c,target,controls)
                val p=ExoPlayer.Builder(c).setLoadControl(DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000,8_000,400,800)
                    .setBackBuffer(0,false).setPrioritizeTimeOverSizeThresholds(true).build()).build()
                player=p; view=surface
                // A hidden TextureView never creates its surface. Keep it visible BEFORE
                // prepare; a transparent shutter preserves the poster until a frame arrives.
                surface.player=p; target.addView(surface,0,FrameLayout.LayoutParams(-1,-1))
                val sources=DefaultMediaSourceFactory(TrailerCache.factory(c))
                val video=sources.createMediaSource(MediaItem.fromUri(media.video))
                p.setMediaSource(if(media.audio.isNotBlank())MergingMediaSource(video,sources.createMediaSource(MediaItem.fromUri(media.audio))) else video)
                val firstFrame=CompletableDeferred<Boolean>()
                var firstRendered=false
                val listener=object:Player.Listener {
                    override fun onRenderedFirstFrame() { if(token==generation) { firstRendered=true; firstFrame.complete(true); onState(""); if(controls)surface.requestFocus() } }
                    override fun onPlayerError(error:PlaybackException) {
                        android.util.Log.w("VesperTrailer","playback ${error.errorCodeName}")
                        firstFrame.complete(false)
                        if(firstRendered && token==generation)recover(title,media,target,controls,retries,onState)
                    }
                }
                p.addListener(listener)
                p.prepare(); p.playWhenReady=true
                val played=withTimeoutOrNull(10_000) { firstFrame.await() }==true
                if(!played) { android.util.Log.w("VesperTrailer","No first frame; trying another source");repository.reject(title,media); release(); onState("Trying another trailer…") }
                else {
                    repository.remember(title,media)
                    watchdog=scope.launch {
                        var stalled=0
                        while(isActive && token==generation) {
                            delay(1_000)
                            stalled=if(p.playWhenReady && p.playbackState==Player.STATE_BUFFERING)stalled+1 else 0
                            if(stalled>=8) { recover(title,media,target,controls,retries,onState); break }
                        }
                    }
                }
                played
                }
                if(accepted==null && token==generation) { release(); onState("Trailer unavailable") }
            } catch(e:CancellationException) { throw e } catch(_:Exception) { if(token==generation)onState("Trailer unavailable") }
        }
    }
    private fun recover(title:Title,media:TrailerMedia,target:FrameLayout,controls:Boolean,retries:Int,onState:(String)->Unit) {
        repository.reject(title,media)
        warmup.invalidate(title)
        val expected=generation
        if(retries>0)scope.launch { if(expected==generation && target.isAttachedToWindow)start(title,target,controls,retries-1,onState) }
        else { stop(); onState("Trailer unavailable") }
    }
    fun fullscreen():Boolean {
        val p=player ?: return false; val surface=view ?: return false
        val parent=surface.parent as? FrameLayout ?: return false
        val d=Dialog(c,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val full=TvUi.identify(FrameLayout(c),"trailer-fullscreen").apply { setBackgroundColor(Color.BLACK) }
        parent.removeView(surface); full.addView(surface,FrameLayout.LayoutParams(-1,-1))
        surface.useController=true; surface.isFocusable=true
        d.setContentView(full); dialog=d
        d.setOnDismissListener {
            full.removeView(surface); surface.useController=false; surface.isFocusable=false
            if(view===surface && host===parent && parent.isAttachedToWindow)parent.addView(surface,0,FrameLayout.LayoutParams(-1,-1))
            dialog=null
        }
        d.show(); d.window?.setLayout(-1,-1); surface.requestFocus(); p.playWhenReady=true
        return true
    }
    private fun release() {
        val old=view; view=null
        dialog?.dismiss(); dialog=null
        old?.player=null; (old?.parent as? android.view.ViewGroup)?.removeView(old)
        player?.release(); player=null
    }
    fun stop(cancelWarm:Boolean=false) { generation++; job?.cancel(); job=null; watchdog?.cancel(); watchdog=null; release(); host=null;if(cancelWarm){warmup.clear();preloads.values.forEach(Job::cancel);preloads.clear()} }
}

internal fun createTrailerSurface(c:Context,target:FrameLayout,controls:Boolean):PlayerView =
    (LayoutInflater.from(c).inflate(R.layout.trailer_surface,target,false) as PlayerView).apply {
        TvUi.identify(this,"inline-trailer-video");useController=controls;isFocusable=controls
        setShutterBackgroundColor(Color.TRANSPARENT)
        (videoSurfaceView as? TextureView)?.isOpaque=false
        visibility=View.VISIBLE
    }