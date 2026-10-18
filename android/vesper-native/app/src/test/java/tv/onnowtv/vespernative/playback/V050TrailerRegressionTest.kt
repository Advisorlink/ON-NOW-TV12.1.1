package tv.onnowtv.vespernative.playback

import android.app.Activity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.data.*
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class V050TrailerRegressionTest {
    @Test fun texture_surface_is_visible_before_a_player_or_first_frame_exists(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val host=FrameLayout(activity);activity.setContentView(host)
        val surface=createTrailerSurface(activity,host,false);host.addView(surface)
        assertEquals(View.VISIBLE,surface.visibility);assertTrue(surface.videoSurfaceView is TextureView)
        assertFalse((surface.videoSurfaceView as TextureView).isOpaque);assertNull(surface.player);assertFalse(surface.useController)
        assertTrue(createTrailerSurface(activity,host,true).useController)
        activity.finish()
    }
    @Test fun slow_device_extractor_cannot_starve_a_working_backend_source()=runBlocking {
        val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody(
            if(r.path!!.contains("/trailer-stream/"))"""{"progressive_url":"https://cdn.example.com/muxed.mp4"}""" else """{"data":{"key":"abcdefghijk","candidates":[{"key":"abcdefghijk"}]}}""")}
        server.start();val cancelled=AtomicBoolean(false)
        try {
            val repo=TrailerRepository(VesperApi(server.url("/").toString()),{null}){try{delay(60000);null}finally{cancelled.set(true)}}
            val result=withTimeout(3000){repo.resolve(Title("tmdb:42","movie","Real contract fixture"))}
            assertEquals("https://cdn.example.com/muxed.mp4",result?.video);assertTrue(cancelled.get())
        }finally{server.shutdown()}
    }
    @Test fun a_failed_cached_source_is_not_reused_forever()=runBlocking {
        val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody(
            if(r.path!!.contains("/trailer-stream/"))"""{"url":"https://cdn.example.com/fallback.mp4"}""" else """{"data":{"key":"abcdefghijk"}}""")};server.start()
        try {
            val title=Title("tmdb:42","movie","Test");val broken=TrailerMedia("https://cdn.example.com/broken.mp4",key="abcdefghijk")
            val repo=TrailerRepository(VesperApi(server.url("/").toString()),{null}){broken}
            repo.remember(title,broken);repo.reject(title,broken)
            assertEquals("https://cdn.example.com/fallback.mp4",withTimeout(3000){repo.resolve(title)}?.video)
        }finally{server.shutdown()}
    }
}