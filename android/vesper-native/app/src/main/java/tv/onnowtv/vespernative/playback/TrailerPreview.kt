package tv.onnowtv.vespernative.playback

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.*
import android.widget.FrameLayout
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
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
    val isPlaying get()=player?.playbackState==Player.STATE_READY
    val fullscreenVisible get()=dialog?.isShowing==true

    fun focus(title:Title,target:FrameLayout,controls:Boolean=false,onState:(String)->Unit={}) {
        stop(); host=target; val token=++generation
        job=scope.launch {
            delay(280)
            onState("Loading trailer…")
            try {
                val media=repository.resolve(title)
                ensureActive()
                if(token!=generation || !target.isAttachedToWindow)return@launch
                if(media==null) { onState("Trailer unavailable"); return@launch }
                val surface=LayoutInflater.from(c).inflate(R.layout.trailer_surface,target,false) as PlayerView
                TvUi.identify(surface,"inline-trailer-video")
                surface.useController=controls; surface.isFocusable=controls
                val p=ExoPlayer.Builder(c).build(); player=p; view=surface
                surface.player=p; surface.visibility=View.INVISIBLE; target.addView(surface,0,FrameLayout.LayoutParams(-1,-1))
                val sources=DefaultMediaSourceFactory(c)
                val video=sources.createMediaSource(MediaItem.fromUri(media.video))
                p.setMediaSource(if(media.audio.isNotBlank())MergingMediaSource(video,sources.createMediaSource(MediaItem.fromUri(media.audio))) else video)
                p.addListener(object:Player.Listener {
                    override fun onRenderedFirstFrame() { if(token==generation) { surface.visibility=View.VISIBLE; onState(""); if(controls)surface.requestFocus() } }
                    override fun onPlayerError(error:PlaybackException) { if(token==generation) { release(); onState("Trailer unavailable") } }
                })
                p.prepare(); p.playWhenReady=true
            } catch(e:CancellationException) { throw e } catch(_:Exception) { if(token==generation)onState("Trailer unavailable") }
        }
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
    fun stop() { generation++; job?.cancel(); job=null; release(); host=null }
}