package tv.onnowtv.vespernative.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.vespernative.R

/** V2 TV focus: 1.07 tile / 1.04 pill; CSS ease-out (0,0,.58,1).
 * Only scale animates. Halo/z-order snap, so rapid reversals leave no ghost glow. */
class FocusMotion private constructor(private val view:View,private val tile:Boolean,private val animate:Boolean,
    radius:Int) : View.OnAttachStateChangeListener {
    private val bloom=FocusBloomDrawable(view.context,radius)
    private var changed:(Boolean)->Unit={}
    init {
        view.clipToOutline=false; view.foreground=bloom
        (view as? ViewGroup)?.apply { clipChildren=false; clipToPadding=false }
        view.addOnAttachStateChangeListener(this)
        view.onFocusChangeListener=View.OnFocusChangeListener { _,focused -> apply(focused,true); changed(focused) }
    }
    private fun apply(focused:Boolean,moving:Boolean) {
        view.animate().cancel()
        val scale=if(focused)if(tile)TILE_SCALE else PILL_SCALE else 1f
        bloom.focused=focused; view.translationZ=if(focused)TvUi.dp(view.context,4).toFloat() else 0f
        if(moving && animate && ValueAnimator.areAnimatorsEnabled()) {
            view.animate().scaleX(scale).scaleY(scale).setDuration(DURATION_MS).setStartDelay(0).setInterpolator(easeOut).start()
        } else { view.scaleX=scale; view.scaleY=scale }
    }
    override fun onViewAttachedToWindow(v:View) { apply(v.hasFocus(),false) }
    override fun onViewDetachedFromWindow(v:View) { apply(false,false) }
    companion object {
        const val TILE_SCALE=1.07f
        const val PILL_SCALE=1.04f
        const val DURATION_MS=120L
        private val easeOut=PathInterpolator(0f,0f,.58f,1f)
        fun bind(view:View,tile:Boolean=true,radius:Int=6,animate:Boolean=tile,changed:(Boolean)->Unit={}) {
            val motion=view.getTag(R.id.focus_motion) as? FocusMotion ?: FocusMotion(view,tile,animate,radius).also { view.setTag(R.id.focus_motion,it) }
            motion.changed=changed; motion.apply(view.hasFocus(),false)
        }
        fun reset(view:View) {
            (view.getTag(R.id.focus_motion) as? FocusMotion)?.let { it.apply(false,false); it.changed={} }
        }
        fun prepareRail(view:RecyclerView,inset:Int=16) {
            view.clipChildren=false; view.clipToPadding=false
            val px=TvUi.dp(view.context,inset)
            view.setPadding(maxOf(px,view.paddingLeft),maxOf(px,view.paddingTop),maxOf(px,view.paddingRight),maxOf(px,view.paddingBottom))
        }
    }
}