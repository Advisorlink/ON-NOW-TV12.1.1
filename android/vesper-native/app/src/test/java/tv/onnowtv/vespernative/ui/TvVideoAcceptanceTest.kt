package tv.onnowtv.vespernative.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.Title

/** Acceptance measurements for the two user videos; not GPU/pixel equality claims. */
@RunWith(RobolectricTestRunner::class)
class TvVideoAcceptanceTest {
    @Test @Config(sdk=[34],qualifiers="w1920dp-h1080dp-land-mdpi")
    fun generic_1080p_box_is_960_dp_with_six_full_grid_columns()=checkNormalized(1920,1080)
    @Test @Config(sdk=[34],qualifiers="w960dp-h540dp-land-xhdpi")
    fun normal_1080p_box_uses_identical_dimensions()=checkNormalized(1920,1080)
    @Test @Config(sdk=[34],qualifiers="w3840dp-h2160dp-land-mdpi")
    fun generic_4k_box_is_960_dp_not_thirteen_tiny_columns()=checkNormalized(3840,2160)
    @Test @Config(sdk=[34],qualifiers="w1280dp-h720dp-land-mdpi")
    fun generic_720p_box_preserves_composition()=checkNormalized(1280,720)

    private fun checkNormalized(width:Int,height:Int) {
        val base=RuntimeEnvironment.getApplication().applicationContext
        val c=TvViewport.context(base)
        assertEquals(width,c.resources.displayMetrics.widthPixels,2)
        assertEquals(height,c.resources.displayMetrics.heightPixels,2)
        assertEquals(960,c.resources.configuration.screenWidthDp,2)
        val count=TvViewport.browseColumns(c.resources.configuration.screenWidthDp)
        assertEquals(6,count)
        val grid=RecyclerView(c).apply { layoutManager=GridLayoutManager(c,count)
            setPadding(TvUi.dp(c,48),0,TvUi.dp(c,32),0)
            adapter=TitleAdapter("acceptance",154,{}).apply { replace((1..18).map { Title("tmdb:$it","movie","Title $it") }) }
        }
        layout(grid,width,height)
        val first=(0..5).map { grid.findViewHolderForAdapterPosition(it)!!.itemView }
        assertEquals(1,first.map { it.top }.distinct().size)
        assertTrue(first.last().right<=width-grid.paddingRight)
        assertTrue(grid.findViewHolderForAdapterPosition(6)!!.itemView.top>first[0].top)
        first.forEach {
            val margin=(it.layoutParams as RecyclerView.LayoutParams).marginEnd
            assertEquals((width-grid.paddingLeft-grid.paddingRight)/6-margin,it.width)
            assertEquals(it.width*3/2,it.height)
        }
        val hero=HeroView(c,{}); hero.bind(Title("long","movie","A Long Movie Title That Must Remain Inside the Television",description="Overview ".repeat(100)))
        layout(hero,width,height/2)
        hero.actions.forEach { v->
            val rect=android.graphics.Rect(0,0,v.width,v.height); hero.offsetDescendantRectToMyCoords(v,rect)
            assertTrue("hero action $rect outside ${width}x${height/2}",rect.left>=0 && rect.right<=width && rect.bottom<=height/2)
        }
    }
    @Test @Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
    fun shelf_focus_snaps_whole_row_and_preserves_parent_top_padding() {
        val c=RuntimeEnvironment.getApplication().applicationContext
        val rv=RecyclerView(c); val lm=PagedShelfLayout(c); rv.layoutManager=lm;rv.setPadding(0,13,0,0)
        rv.adapter=object:RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount()=4
            override fun onCreateViewHolder(parent:ViewGroup,type:Int)=object:RecyclerView.ViewHolder(FrameLayout(c).apply { layoutParams=RecyclerView.LayoutParams(-1,257); isFocusable=true }) {}
            override fun onBindViewHolder(holder:RecyclerView.ViewHolder,p:Int) {}
        }
        layout(rv,960,270); lm.scrollToPositionWithOffset(1,0);layout(rv,960,270)
        val row=rv.findViewHolderForAdapterPosition(1)!!.itemView
        assertEquals(rv.paddingTop,row.top);assertEquals(270,row.bottom)
        assertTrue(lm.onRequestChildFocus(rv,RecyclerView.State(),row,row))
        layout(rv,960,270);assertEquals(13,row.top)
    }
    private fun layout(view:View,w:Int,h:Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
    }
    private fun assertEquals(expected:Int,actual:Int,tolerance:Int) { assertTrue("expected $expected actual $actual",kotlin.math.abs(expected-actual)<=tolerance) }
}