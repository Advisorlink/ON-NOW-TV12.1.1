package tv.onnowtv.vespernative.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.onnowtv.vespernative.data.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class V060WarmupTest {
    @Test fun prewarm_is_deduplicated_bounded_and_never_starts_native_extractor()=runBlocking {
        val server=MockWebServer();val metadataCalls=AtomicInteger();val backendCalls=AtomicInteger();val nativeCalls=AtomicInteger()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val body=if(request.path!!.contains("/trailer-stream/")){backendCalls.incrementAndGet();"""{"url":"https://cdn.example.com/preview.mp4"}"""}
                else{metadataCalls.incrementAndGet();"""{"data":{"key":"abcdefghijk"}}"""}
            return MockResponse().setHeader("Content-Type","application/json").setBody(body)
        }};server.start()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try{
            val repo=TrailerRepository(VesperApi(server.url("/").toString()),{null}){nativeCalls.incrementAndGet();null}
            val warm=TrailerWarmup(scope,repo);val first=Title("tmdb:1","movie","One");val next=Title("tmdb:2","movie","Two");val third=Title("tmdb:3","movie","Three")
            warm.prime(listOf(first,next,third));val a=warm.focus(first);val same=warm.focus(first);assertSame(a,same)
            withTimeout(5000){a.media.await();warm.focus(next).media.await();warm.focus(third).media.await()}
            assertEquals(0,nativeCalls.get());assertEquals(3,metadataCalls.get());assertEquals(3,backendCalls.get());assertEquals(3,warm.pendingCount)
            assertNotNull(repo.cached(first));assertNotNull(repo.cached(next));assertNotNull(repo.cached(third))
            val count=server.requestCount
            assertEquals("https://cdn.example.com/preview.mp4",withTimeout(1000){repo.candidates(first,a).first()}.video)
            assertEquals(count,server.requestCount)
            for(id in 4..12){warm.prime(listOf(Title("tmdb:$id","movie","$id")),true);assertTrue(warm.pendingCount<=3)}
            warm.clear();assertEquals(0,warm.pendingCount)
        }finally{scope.cancel();server.shutdown()}
    }
    @Test fun metadata_cache_avoids_repeating_id_and_trailer_lookups()=runBlocking {
        val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody(if(r.path!!.contains("find-by-imdb"))"""{"tmdb_id":42}""" else """{"data":{"key":"abcdefghijk"}}""")};server.start()
        try{val repo=TrailerRepository(VesperApi(server.url("/").toString()),{null});val title=Title("tt123","movie","Movie")
            assertEquals(listOf("abcdefghijk"),repo.keys(title));assertEquals(2,server.requestCount)
            assertEquals(listOf("abcdefghijk"),repo.keys(title));assertEquals(2,server.requestCount)
        }finally{server.shutdown()}
    }
    @Test fun failed_preload_is_not_reused_as_a_two_minute_failure_cache()=runBlocking {
        val server=MockWebServer();val attempts=AtomicInteger()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
            if(r.path!!.contains("/trailer-stream/"))return if(attempts.incrementAndGet()==1)MockResponse().setResponseCode(503).setBody("{}") else MockResponse().setBody("""{"url":"https://cdn.example.com/recovered.mp4"}""")
            return MockResponse().setBody("""{"data":{"key":"abcdefghijk"}}""")
        }};server.start();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try{
            val repo=TrailerRepository(VesperApi(server.url("/").toString()),{null}){null};val warm=TrailerWarmup(scope,repo);val title=Title("tmdb:42","movie","Movie")
            val failed=warm.focus(title);assertNull(withTimeout(3000){failed.media.await()})
            val retry=warm.focus(title);assertNotSame(failed,retry)
            assertEquals("https://cdn.example.com/recovered.mp4",withTimeout(3000){retry.media.await()}?.video)
            assertEquals(2,attempts.get());warm.clear()
        }finally{scope.cancel();server.shutdown()}
    }
}