package tv.onnowtv.vespernative.ui

import android.app.Activity
import android.os.Looper
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
class V050DetailFlowTest {
    @Test fun actor_opens_mixed_filmography_down_opens_similar_and_back_restores_actor(){
        val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
            val body=when {
                r.path!!.contains("/credits/")->"""{"cast":[{"id":10,"name":"Actor Name","character":"Lead","profile":""}]}"""
                r.path!!.contains("/person/")->"""{"id":10,"name":"Actor Name","biography":"Biography","filmography":[{"id":100,"media_type":"movie","title":"Film One"},{"id":200,"media_type":"tv","name":"Show Two"}]}"""
                else->"""{"results":[{"id":300,"media_type":"movie","title":"Similar Movie"}]}"""
            };return MockResponse().setHeader("Content-Type","application/json").setBody(body)
        }};server.start()
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val opened=mutableListOf<Title>()
        val lane=DetailLane(activity,scope,TitleExtrasRepository(VesperApi(server.url("/").toString()),{null}),{}, {},{opened.add(it)},{})
        val root=FrameLayout(activity);root.addView(lane,FrameLayout.LayoutParams(-1,160));activity.setContentView(root)
        fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540)}
        fun wait(id:String):View {repeat(80){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));layout();find(root,id)?.let{return it};Thread.sleep(5)};error("Missing $id")}
        try {
            lane.load(Title("tmdb:42","movie","Parent"));val actor=wait("cast-actor-10");actor.requestFocus();actor.performClick()
            val film=wait("cast-filmography-title-movie:tmdb:100");assertNotNull(wait("cast-filmography-title-series:tmdb:200"))
            film.requestFocus();film.performClick();assertEquals("tmdb:100",opened.single().id)
            film.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN))
            wait("cast-similar-title-movie:tmdb:300").requestFocus()
            assertEquals("Just like this",(wait("detail-lane-heading") as TextView).text.toString())
            assertTrue(lane.back());wait("cast-actor-10");repeat(5){Shadows.shadowOf(Looper.getMainLooper()).idle();layout()}
            assertTrue(find(root,"cast-actor-10")!!.hasFocus());assertFalse(lane.back())
        }finally{scope.cancel();activity.finish();server.shutdown()}
    }
    @Test fun registry_network_colors_survive_native_brand_parsing(){
        val c=RuntimeEnvironment.getApplication();val repo=BrowseRepository(VesperApi("https://example.com")){null}
        val brands=repo.brands(c,"networks");assertTrue(brands.isNotEmpty());assertTrue(brands.any{it.background.contains("linear-gradient")})
        val netflix=brands.first{it.slug=="netflix"};assertTrue(netflix.background.contains("#"));assertTrue(netflix.wordmark.isNotBlank())
    }
    private fun find(v:View,id:String):View?{if(v.getTag(R.id.test_id)==id)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),id)?.let{return it};return null}
}