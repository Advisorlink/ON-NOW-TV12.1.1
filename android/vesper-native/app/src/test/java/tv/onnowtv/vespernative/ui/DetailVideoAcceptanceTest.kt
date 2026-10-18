package tv.onnowtv.vespernative.ui

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.*
import org.json.JSONObject
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.profile.LocalPreferences
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class DetailVideoAcceptanceTest {
    @Test @Config(sdk=[34],qualifiers="w1920dp-h1080dp-land-mdpi")
    fun movie_details_actions_and_cast_fit_raw1080p_box_and_manual_choice_bypasses_play()=checkMovie(1920,1080)
    @Test @Config(sdk=[34],qualifiers="w1280dp-h720dp-land-mdpi")
    fun long_movie_details_and_cast_fit_720p_box()=checkMovie(1280,720)
    private fun checkMovie(width:Int,height:Int) {
        val c=TvViewport.context(RuntimeEnvironment.getApplication())
        val name="The Extraordinarily Long Movie Title With Several Unbreakableeeeeeeeeeeeeeeeeeeeeee Words"
        val server=MockWebServer()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val json=when {
                    request.path!!.startsWith("/api/meta/")->JSONObject().put("data",JSONObject().put("meta",JSONObject().put("id","tt123").put("name",name).put("description","Very long synopsis. ".repeat(100)).put("releaseInfo","2026")))
                    request.path!!.startsWith("/api/tmdb/find-by-imdb/")->JSONObject().put("tmdb_id",123)
                    request.path!!.contains("/credits/")->JSONObject("""{"cast":[{"id":10,"name":"Actor Name","character":"A long character name","profile":""}]}""")
                    else->JSONObject()
                }
                return MockResponse().setHeader("Content-Type","application/json").setBody(json.toString())
            }
        }
        server.start(); var plays=0;var choices=0
        val screen=DetailScreen(c,Title("tt123","movie",name),CatalogRepository(VesperApi(server.url("/").toString()),{null}),{}, {},{_,_,_->plays++},
            chooseStream={_,_,_->choices++},preferences=LocalPreferences(c,"geometry","test",null))
        try {
            repeat(60) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));Thread.sleep(5)
            }
            screen.root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));screen.root.layout(0,0,width,height)
            val cast=find(screen.root,"detail-cast-lane")!!
            assertEquals(View.VISIBLE,cast.visibility)
            val castRect=rect(screen.root,cast)
            for(tag in listOf("detail-back","detail-home","detail-title","detail-metadata","detail-synopsis","detail-play","detail-choose-stream","detail-trailer","detail-library-readonly","detail-autoplay")) {
                val view=find(screen.root,tag)!!;val bounds=rect(screen.root,view)
                assertTrue("$tag clipped $bounds",bounds.left>=0 && bounds.right<=width && bounds.top>=0 && bounds.bottom<=height)
                assertTrue("$tag overlaps cast $bounds vs $castRect",bounds.bottom<=castRect.top)
            }
            assertFalse(find(screen.root,"detail-library-readonly")!!.isEnabled)
            find(screen.root,"detail-choose-stream")!!.performClick();assertEquals(1,choices);assertEquals(0,plays)
            find(screen.root,"detail-play")!!.performClick();assertEquals(1,plays)
        } finally { screen.dispose();server.shutdown() }
    }
    private fun rect(root:ViewGroup,view:View):Rect=Rect(0,0,view.width,view.height).also { root.offsetDescendantRectToMyCoords(view,it) }
    private fun find(v:View,key:String):View? {
        if(v.getTag(R.id.test_id)==key)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),key)?.let {return it}
        return null
    }
}