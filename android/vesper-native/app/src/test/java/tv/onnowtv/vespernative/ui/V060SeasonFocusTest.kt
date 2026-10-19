package tv.onnowtv.vespernative.ui

import android.app.Activity
import android.os.Looper
import android.view.*
import android.widget.TextView
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.profile.LocalPreferences
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
class V060SeasonFocusTest {
    @Test fun details_focus_saved_season_then_back_returns_to_that_season(){
        val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody(
            if(r.path!!.contains("/tmdb/imdb/"))"""{"imdb_id":"tt42"}"""
            else if(r.path!!.contains("find-by-imdb"))"""{"tmdb_id":42}"""
            else if(r.path!!.contains("/meta/"))"""{"data":{"meta":{"id":"tt42","type":"series","name":"Below","description":"Series description","videos":[{"id":"tt42:1:1","season":1,"episode":1,"name":"Come Home","overview":"Episode one overview"},{"id":"tt42:2:1","season":2,"episode":1,"name":"The Return","overview":"Second season overview"}]}}}"""
            else if(r.path!!.contains("/credits/"))"""{"cast":[]}""" else """{"results":[]}""")};server.start()
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val prefs=LocalPreferences(activity,"season-user","main",null);prefs.rememberSeason("series:tt42",2)
        val screen=DetailScreen(activity,Title("tmdb:42","series","Below"),CatalogRepository(VesperApi(server.url("/").toString()),{null}),{}, {},preferences=prefs)
        activity.setContentView(screen.root);screen.shown()
        fun settle(){repeat(40){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));screen.root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));screen.root.layout(0,0,960,540);Thread.sleep(5)}}
        try{
            settle();val season=find(screen.root,"detail-season-2")!!;assertTrue("Saved season must receive initial focus",season.hasFocus())
            season.performClick();settle();assertEquals(View.VISIBLE,find(screen.root,"detail-episodes")!!.visibility)
            assertEquals("The Return",(find(screen.root,"detail-episode-name-tt42:2:1") as TextView).text.toString())
            assertEquals("Second season overview",(find(screen.root,"detail-episode-description-tt42:2:1") as TextView).text.toString())
            assertTrue(screen.back());settle();assertTrue(season.hasFocus());assertFalse(season.isSelected)
            assertEquals(2,prefs.lastSeason("series:tt42"));assertNull(LocalPreferences(activity,"season-user","kids",null).lastSeason("series:tt42"))
        }finally{screen.dispose();controller.pause().stop().destroy();server.shutdown()}
    }
    private fun find(v:View,key:String):View?{if(v.getTag(R.id.test_id)==key)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),key)?.let{return it};return null}
}