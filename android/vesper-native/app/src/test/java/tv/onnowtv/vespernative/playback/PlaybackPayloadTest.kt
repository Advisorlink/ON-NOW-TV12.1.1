package tv.onnowtv.vespernative.playback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.data.Title
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PlaybackPayloadTest {
    private val context get()=RuntimeEnvironment.getApplication().applicationContext
    private fun stream(url:String,header:String,order:Int=0)=Stream.parse(JSONObject().put("url",url).put("name","Fixture source")
        .put("_quality","2160p").put("_size_gb",3.4).put("headers",JSONObject().put("Referer",header)),order)!!
    @Test fun selected_index_order_and_same_url_different_headers_are_preserved() {
        val first=stream("https://media.example/test.m3u8","first")
        val second=stream(first.url,"second",1)
        val third=stream("https://media.example/other.m3u8","third",2)
        val id=PlaybackPayload.save(context,Title("tt1","movie","Title",rating="8.1",runtime="97 min"),"Title",second,listOf(first,second,third))
        val payload=PlaybackPayload.read(context,id)!!; val queue=PlaybackPayload.streams(payload)
        assertEquals(3,queue.size); assertEquals(1,payload.getInt("selected"))
        assertEquals(listOf(first.url,second.url,third.url),queue.map(Stream::url))
        assertEquals(listOf("first","second","third"),queue.map { it.headers["Referer"] })
        assertEquals("2160p",queue[1].quality); assertEquals(3.4,queue[1].size!!,0.01)
        assertEquals("97 min",payload.getString("runtime"))
        File(context.cacheDir,"playback/$id.json").delete()
    }
    @Test fun invalid_or_corrupt_payload_does_not_read_arbitrary_paths() {
        assertNull(PlaybackPayload.read(context,"../../private")); assertNull(PlaybackPayload.read(context,null))
        assertNull(PlaybackPayload.read(context,UUID.randomUUID().toString()))
        val id=UUID.randomUUID().toString(); val file=File(context.cacheDir,"playback/$id.json")
        file.parentFile!!.mkdirs(); file.writeText("not-json"); assertNull(PlaybackPayload.read(context,id)); file.delete()
    }
    @Test fun oversized_queue_is_rejected_before_writing_payload() {
        val s=stream("https://media.example/a.m3u8","x")
        try { PlaybackPayload.save(context,Title("tt1","movie","x"),"x",s.copy(description="x".repeat(2_000_001)),emptyList()); fail("Oversized payload accepted") }
        catch(_:IllegalArgumentException) {}
    }
    @Test fun original_player_model_receives_real_metadata_not_invented_values() {
        val payload=JSONObject().put("title","Title").put("year","2026").put("rating","8.1").put("runtime","97 min")
            .put("poster","poster").put("backdrop","backdrop").put("overview","Plot").put("logo","logo")
        val state=PlayerVisualState(payload,"Fallback")
        assertEquals("Title",state.info.title); assertEquals("8.1",state.info.rating)
        assertEquals("Plot",state.info.synopsis); assertEquals("poster",state.info.poster)
        assertEquals("logo",state.logo.value); assertFalse(state.info.isEnglish); assertEquals(0L,state.bitrate.value)
        val before=state.activity.value; state.touch(); assertTrue(state.activity.value>before)
    }
}