package tv.onnowtv.vespernative.ui

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.playback.*
import tv.onnowtv.vespernative.profile.LocalPreferences
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
class V070AutoplayAndSeriesTest {
    private fun server(streams:String)=MockWebServer().apply { dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody(
        if(r.path!!.contains("/streams/"))streams
        else if(r.path!!.contains("/meta/"))"""{"data":{"meta":{"id":"tt42","type":"series","name":"Below","description":"Series description","videos":[{"id":"tt42:1:1","season":1,"episode":1,"name":"Come Home","overview":"Episode one overview"},{"id":"tt42:1:2","season":1,"episode":2,"name":"Crown","overview":"Two"}]}}}"""
        else if(r.path!!.contains("/credits/"))"""{"cast":[]}""" else """{"results":[]}""")};start() }
    private fun idle(n:Int=40){repeat(n){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));Thread.sleep(5)}}
    private fun player(streams:String):Pair<org.robolectric.android.controller.ActivityController<PlayerActivity>,MockWebServer> {
        val s=server(streams);val c=RuntimeEnvironment.getApplication()
        StreamPrefetch.clear();StreamPrefetch.get(StreamRepository(VesperApi(s.url("/").toString())){null},"movie","tt9")
        val payload=PlaybackPayload.save(c,Title("tt9","movie","Nine",backdrop="https://img.example/b.jpg"),"Nine",null,emptyList())
        val intent=Intent(c,PlayerActivity::class.java).putExtra("payload",payload).putExtra("title","Nine").putExtra("resume-key","u:movie:tt9").putExtra("resolve-key",StreamPrefetch.key("movie","tt9"))
        return Robolectric.buildActivity(PlayerActivity::class.java,intent).create().start().resume() to s
    }
    @Test fun autoplay_opens_player_loading_screen_and_starts_first_playable_stream_without_picker() {
        val (controller,s)=player("""{"streams":[{"url":"https://cdn.example/a.mkv","name":"PM","description":"1080p uncached","_pm_uncached":true},{"url":"https://cdn.example/b.mkv","name":"OnNow","description":"1080p"}]}""")
        try { val a=controller.get();assertFalse("Player screen opens immediately",a.isFinishing);idle(80)
            val q=a.javaClass.getDeclaredField("queue").apply{isAccessible=true}.get(a) as List<*>
            val p=a.javaClass.getDeclaredField("player").apply{isAccessible=true}.get(a);assertNotNull("First playable stream starts (queue=${q.size}, finishing=${a.isFinishing}, deferred=${StreamPrefetch.peek("movie:tt9")})",p)
            assertEquals("Uncached sources are skipped",0,a.javaClass.getDeclaredField("selected").apply{isAccessible=true}.getInt(a))
            assertFalse((q[0] as Stream).uncached)
        } finally { controller.pause().stop().destroy();s.shutdown() }
    }
    @Test fun autoplay_without_playable_stream_falls_back_to_stream_list() {
        val (controller,s)=player("""{"streams":[]}""")
        try { idle();val a=controller.get();assertTrue(a.isFinishing);assertEquals(PlayerActivity.RESULT_CHOOSE_STREAM,Shadows.shadowOf(a).resultCode) }
        finally { controller.pause().stop().destroy();s.shutdown() }
    }
    @Test fun movie_tile_autoplay_skips_picker_and_swap_shows_count() {
        val s=server("{}");val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        var auto=0;var picker=0;val streams=CompletableDeferred<List<Stream>>()
        val screen=DetailScreen(a,Title("tt7","movie","Seven"),CatalogRepository(VesperApi(s.url("/").toString()),{null}),{},{},{_,_,_->picker++},autoOpen=true,
            chooseStream={_,_,_->picker++},preferences=LocalPreferences(a,"auto","main",null),prefetchStreams={_,_->streams},autoplayNow={_,_,_->auto++})
        a.setContentView(screen.root);screen.shown()
        try { idle();assertEquals(1,auto);assertEquals(0,picker);assertNull(find(screen.root,"detail-autoplay"))
            assertEquals("Autoplay",(find(screen.root,"detail-play") as TextView).text.toString())
            streams.complete(List(3){Stream("https://x/$it","n","d",emptyMap(),1,false,true,1.0,it)});idle(5)
            assertEquals("Swap stream  (3)",(find(screen.root,"detail-choose-stream") as TextView).text.toString())
        } finally { screen.dispose();controller.pause().stop().destroy();s.shutdown() }
    }
    @Test fun season_view_scrolls_whole_page_and_controls_are_not_clipped_by_gutter() {
        val s=server("{}");val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val screen=DetailScreen(a,Title("tt42","series","Below"),CatalogRepository(VesperApi(s.url("/").toString()),{null}),{},{},preferences=LocalPreferences(a,"series","main",null))
        a.setContentView(screen.root);screen.shown()
        fun settle(){repeat(30){idle(1);screen.root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));screen.root.layout(0,0,960,540)}}
        try { settle();find(screen.root,"detail-season-1")!!.performClick();settle()
            assertEquals(View.VISIBLE,find(screen.root,"detail-seasons-heading")!!.let{(it.parent as View).visibility})
            assertEquals("1 SEASON · 2 EPISODES THIS SEASON",(find(screen.root,"detail-season-count") as TextView).text.toString())
            val page=find(screen.root,"detail-page") as DetailScroll;val episodes=find(screen.root,"detail-episodes")!!
            assertTrue("Episodes live inside the scrolling page",generateSequence(episodes.parent){it.parent}.any{it===page})
            val scroller=find(screen.root,"detail-autoplay")!!.parent.parent as View
            assertEquals(0,scroller.left);assertEquals(960,scroller.width);assertFalse((scroller as ViewGroup).clipToPadding)
            assertEquals(173,find(screen.root,"detail-episode-art-tt42:1:1")!!.parent.let{(it as View).width})
        } finally { screen.dispose();controller.pause().stop().destroy();s.shutdown() }
    }
    private fun find(v:View,key:String):View?{if(v.getTag(R.id.test_id)==key)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),key)?.let{return it};return null}
}
