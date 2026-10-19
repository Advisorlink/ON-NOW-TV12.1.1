package tv.onnowtv.vespernative.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
class V060ShelfReadinessTest {
    @Test fun delayed_first_page_never_exposes_more_container_as_first_poster(){
        val gate=CountDownLatch(1);val server=MockWebServer()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            gate.await(5,TimeUnit.SECONDS)
            return MockResponse().setHeader("Content-Type","application/json").setBody("""{"data":{"metas":[{"id":"tt1","type":"movie","name":"Loaded poster"}]}}""")
        }};server.start()
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var heroUpdates=0
        val adapter=ShelfAdapter(listOf(Catalog("fixture","movie","year","New movies",false)),CatalogRepository(VesperApi(server.url("/").toString()),{null}),scope,254,{}, {heroUpdates++},{},View.NO_ID)
        val parent=FrameLayout(activity);val holder=adapter.onCreateViewHolder(parent,0)
        parent.addView(holder.itemView)
        val nav=TvUi.button(activity,"readiness-nav","Navigation"){};parent.addView(nav,FrameLayout.LayoutParams(120,40))
        activity.setContentView(parent);nav.requestFocus()
        try{
            adapter.onBindViewHolder(holder,0)
            assertEquals("A loading row must not pretend its More container is a title",0,holder.recycler.adapter!!.itemCount)
            gate.countDown()
            repeat(40){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));parent.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));parent.layout(0,0,960,540);Thread.sleep(5)}
            val first=holder.recycler.findViewHolderForAdapterPosition(0)!!.itemView
            assertEquals("Background loading must not replace the focused hero",0,heroUpdates)
            assertTrue((first.getTag(R.id.test_id) as String).contains("-title-movie:tt1"))
            assertTrue(first.isFocusable);assertTrue(first.requestFocus());assertTrue(first.hasFocus());assertTrue(heroUpdates>0)
        }finally{gate.countDown();adapter.onViewRecycled(holder);scope.cancel();controller.pause().stop().destroy();server.shutdown()}
    }
}