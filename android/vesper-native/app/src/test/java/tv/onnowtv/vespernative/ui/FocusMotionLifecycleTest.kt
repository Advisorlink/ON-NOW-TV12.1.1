package tv.onnowtv.vespernative.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import tv.onnowtv.vespernative.data.Title

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
class FocusMotionLifecycleTest {
    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(260)) }
    private fun scene(test:(FrameLayout,List<TextView>)->Unit) {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity=controller.get(); val host=FrameLayout(activity)
        activity.setContentView(host)
        val tiles=(0..2).map { i -> TextView(activity).apply {
            isFocusable=true; isFocusableInTouchMode=true
            host.addView(this,FrameLayout.LayoutParams(120,180).apply { leftMargin=50+i*160; topMargin=50 })
            FocusMotion.bind(this)
        } }
        host.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY))
        host.layout(0,0,960,540)
        try { test(host,tiles) } finally { controller.pause().stop().destroy() }
    }
    @Test fun rapid_reversals_keep_one_glow_and_finish_without_layout_changes()=scene { _,tiles ->
        val bounds=tiles.map { listOf(it.left,it.top,it.width,it.height) }
        repeat(15) { n ->
            val target=tiles[n%3]; assertTrue(target.requestFocus())
            assertEquals(1,tiles.count { (it.foreground as FocusBloomDrawable).focused })
            assertTrue((target.foreground as FocusBloomDrawable).focused)
        }
        settle()
        tiles.forEachIndexed { i,tile ->
            assertEquals(if(i==2)1.07f else 1f,tile.scaleX,.001f)
            assertEquals(if(i==2)1.07f else 1f,tile.scaleY,.001f)
            assertEquals(bounds[i],listOf(tile.left,tile.top,tile.width,tile.height))
        }
        settle(); assertTrue(tiles[2].hasFocus())
    }
    @Test fun detach_cancels_running_animation_and_removes_halo()=scene { host,tiles ->
        val tile=tiles[0]; assertTrue(tile.requestFocus()); host.removeView(tile); settle()
        assertEquals(1f,tile.scaleX,.001f); assertEquals(1f,tile.scaleY,.001f)
        assertEquals(0f,tile.translationZ,.001f); assertFalse((tile.foreground as FocusBloomDrawable).focused)
    }
    @Test fun rebind_has_one_current_domain_callback_and_recycle_resets()=scene { _,tiles ->
        val tile=tiles[1]; var obsolete=0; var current=0
        FocusMotion.bind(tile) { if(it)obsolete++ }
        FocusMotion.bind(tile) { if(it)current++ }
        assertTrue(tile.requestFocus()); settle()
        assertEquals(0,obsolete); assertEquals(1,current)
        FocusMotion.reset(tile); settle()
        assertFalse((tile.foreground as FocusBloomDrawable).focused); assertEquals(1f,tile.scaleX,.001f)
    }
    @Test fun disabled_system_animation_scale_uses_instant_focus_feedback()=scene { _,tiles ->
        val setter=ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType)
        val getter=ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
        val previous=getter.invoke(null) as Float
        try {
            setter.invoke(null,0f); assertFalse(ValueAnimator.areAnimatorsEnabled())
            assertTrue(tiles[1].requestFocus()); assertEquals(1.07f,tiles[1].scaleX,.001f)
            assertTrue(tiles[2].requestFocus()); assertEquals(1f,tiles[1].scaleX,.001f)
        } finally { setter.invoke(null,previous) }
    }
    @Test fun long_poster_labels_are_inside_artwork_and_recycling_removes_scale()=scene { host,_ ->
        val adapter=TitleAdapter("measured",154,{})
        val value=Title("tt-test","movie","ExtremelyLongUnbreakableTitleToTestStablePosterBounds",year="2026",rating="8.8")
        adapter.replace(listOf(value)); val holder=adapter.onCreateViewHolder(host,0)
        adapter.onBindViewHolder(holder,0); val card=holder.itemView
        card.measure(View.MeasureSpec.makeMeasureSpec(102,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(154,View.MeasureSpec.EXACTLY)); card.layout(0,0,102,154)
        for(view in listOf(holder.name,holder.sub)) {
            val rect=android.graphics.Rect(0,0,view.width,view.height)
            (card as android.view.ViewGroup).offsetDescendantRectToMyCoords(view,rect)
            assertTrue("Poster label exceeds artwork",rect.left>=0 && rect.right<=102 && rect.top>=0 && rect.bottom<=154)
        }
        card.onFocusChangeListener.onFocusChange(card,true); adapter.onViewRecycled(holder); settle()
        assertEquals(1f,card.scaleX,.001f); assertFalse((card.foreground as FocusBloomDrawable).focused)
    }
}