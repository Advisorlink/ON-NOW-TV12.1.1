package tv.onnowtv.vespernative.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.playback.*
import tv.onnowtv.vespernative.profile.LocalPreferences
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
class V2AutoplayTest {
    private val context get()=RuntimeEnvironment.getApplication().applicationContext
    private fun waitFor(condition:()->Boolean) { repeat(100) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); if(condition())return; Thread.sleep(5) }; fail("Timed out") }
    private fun screen(body:String,auto:Boolean,loaded:()->Unit={},play:(Stream)->Unit,test:(StreamScreen)->Unit) {
        val server=MockWebServer(); server.enqueue(MockResponse().setBody(body)); server.start()
        val repository=StreamRepository(VesperApi(server.url("/").toString())) { "fixture-token" }
        val screen=StreamScreen(context,"Title","movie","tt1",repository,{},play,{throw AssertionError(it)},auto,{loaded()})
        try { test(screen) } finally { screen.dispose(); server.shutdown() }
    }
    @Test fun auto_starts_best_ready_source_once_after_queue_callback() {
        var available=false; val chosen=mutableListOf<Stream>()
        screen("""{"streams":[{"url":"https://cdn.example/uncached","_onnow_direct":true,"_pm_uncached":true},{"url":"https://cdn.example/ready","name":"Torrentio 1080p"}]}""",true,
            loaded={available=true},play={assertTrue(available); chosen.add(it)}) { view ->
            waitFor { chosen.isNotEmpty() }; assertEquals("https://cdn.example/ready",chosen.single().url)
            view.shown(); view.hidden(); view.shown(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(1,chosen.size)
        }
    }
    @Test fun uncached_sources_never_auto_start_and_manual_mode_stays_manual() {
        for(uncached in listOf(true,false)) {
            var ready=false; var plays=0
            screen("""{"streams":[{"url":"https://cdn.example/one","_pm_uncached":$uncached}]}""",uncached,loaded={ready=true},play={plays++}) { view ->
                waitFor { ready }; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)); assertEquals(0,plays)
                view.root.measure(View.MeasureSpec.makeMeasureSpec(960,1073741824),View.MeasureSpec.makeMeasureSpec(540,1073741824)); view.root.layout(0,0,960,540)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                find(view.root,"stream-0")!!.performClick(); assertEquals(1,plays)
            }
        }
    }
    @Test fun master_tv_flags_are_scoped_and_follow_original_gating() {
        val first=LocalPreferences(context,"autoplay-account","one",null)
        val other=LocalPreferences(context,"autoplay-account","two",null)
        assertTrue(first.shouldAutoplay("movie")); assertTrue(first.shouldAutoplay("series"))
        first.autoplayTV=false; assertTrue(first.shouldAutoplay("movie")); assertFalse(first.shouldAutoplay("series"))
        first.autoplayTV=true; first.autoplay=false
        assertFalse(first.shouldAutoplay("movie")); assertFalse(first.shouldAutoplay("series")); assertTrue(other.shouldAutoplay("series"))
    }
    @Test fun navigation_refreshes_autoplay_indicator_on_return_from_settings() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val host=FrameLayout(controller.get()); controller.get().setContentView(host)
        var enabled=true
        val nav=NavigationBar(controller.get(),"home",{},autoplay={enabled},toggleAutoplay={enabled=!enabled})
        host.addView(nav); val button=find(nav,"nav-autoplay")!!
        assertEquals("Autoplay on",button.contentDescription)
        enabled=false; host.removeView(nav); host.addView(nav)
        assertEquals("Autoplay off",button.contentDescription)
        button.performClick(); assertTrue(enabled); assertEquals("Autoplay on",button.contentDescription)
        controller.pause().stop().destroy()
    }
    @Test fun home_more_info_bypasses_automatic_play_callback() {
        val server=MockWebServer(); server.enqueue(MockResponse().setResponseCode(500)); server.start()
        var automatic=0; var manual=0
        val home=HomeScreen(context,CatalogRepository(VesperApi(server.url("/").toString())) { null },null,{automatic++},{},{},moreInfo={manual++})
        val hero=find(home.root,"hero-billboard") as HeroView
        hero.bind(Title("tt1","movie","Title")); hero.info.performClick()
        assertEquals(1,manual); assertEquals(0,automatic)
        home.dispose(); server.shutdown()
    }
    private fun find(v:View,id:String):View? {
        if(v.getTag(R.id.test_id)==id)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),id)?.let { return it }
        return null
    }
}