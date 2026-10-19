package tv.onnowtv.vespernative.ui

import android.graphics.Rect
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.profile.LocalPreferences

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
class V060TypographyTest {
    @Test fun long_short_and_unbroken_titles_never_change_font_size_or_overlap_actions(){
        val c=RuntimeEnvironment.getApplication();val hero=HeroView(c,{})
        val originalFace=(find(hero,"hero-title") as TextView).typeface
        val names=listOf("Up","Animals","The Assassination of Jesse James by the Coward Robert Ford","Supercalifragilisticexpialidocious".repeat(4),"A TV Show with a deliberately very long two-line heading")
        var size:Float?=null
        for(height in listOf(286,270,225))for(name in names){
            hero.bind(Title("title","movie",name,description="A long overview which must never paint behind the Play, More info or My Library controls. ".repeat(30),year="2026",runtime="135 min",rating="8.5",genres=listOf("Drama","Thriller")))
            layout(hero,960,height)
            val heading=find(hero,"hero-title") as TextView
            if(size==null)size=heading.textSize else assertEquals(size!!,heading.textSize,.01f)
            // ARM64 Robolectric LEGACY reports weight=0. Font continuity is still
            // testable; actual variable-font rasterisation requires Android hardware.
            assertSame(originalFace,heading.typeface)
            val block=find(hero,"hero-text-block")!!;val play=find(hero,"hero-play")!!
            assertTrue(rect(hero,block).bottom<rect(hero,play).top)
            assertTrue(rect(hero,block).top>=0)
            for(id in listOf("hero-title","hero-metadata","hero-description")){
                val text=find(hero,id) as TextView
                if(text.visibility==View.VISIBLE){assertTrue("$id escapes text block",rect(hero,text).bottom<=rect(hero,block).bottom);assertTrue("$id glyph layout clipped",text.layout.height<=text.height+1)}
            }
            hero.actions.forEach{assertTrue(rect(hero,it).bottom<=height)}
        }
    }
    @Test fun reference_theme_colors_apply_to_eyebrows_and_first_metadata_not_counts(){
        val c=RuntimeEnvironment.getApplication();val prefs=LocalPreferences(c,"color-check","main",null)
        try{for(theme in LocalPreferences.themes){
            prefs.theme=theme.id;prefs.applyTheme()
            val hero=HeroView(c,{});hero.bindFeatured(Title("id","movie","Animals",year="2026",genres=listOf("Thriller")))
            assertEquals(TvUi.accent,(find(hero,"hero-eyebrow") as TextView).currentTextColor)
            val metadata=(find(hero,"hero-metadata") as TextView).text as Spanned
            val span=metadata.getSpans(0,metadata.length,ForegroundColorSpan::class.java).single()
            assertEquals(TvUi.accent,span.foregroundColor);assertEquals("2026",metadata.subSequence(metadata.getSpanStart(span),metadata.getSpanEnd(span)).toString())
            assertEquals(TvUi.muted,V2Ui.caption(c,"count","18 titles").currentTextColor)
        }}finally{prefs.theme="electric";prefs.applyTheme()}
    }
    private fun layout(v:View,w:Int,h:Int){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
    private fun rect(root:ViewGroup,v:View)=Rect(0,0,v.width,v.height).also{root.offsetDescendantRectToMyCoords(v,it)}
    private fun find(v:View,key:String):View?{if(v.getTag(R.id.test_id)==key)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),key)?.let{return it};return null}
}