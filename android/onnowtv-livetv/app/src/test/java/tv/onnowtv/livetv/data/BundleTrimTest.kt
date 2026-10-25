package tv.onnowtv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BundleTrimTest {

    @Test
    fun trims_epg_to_window_and_keeps_channels() {
        val now = 1_000_000L
        val json = """{"provider":{"id":"p","name":"n","host":"h","port":"1","scheme":"https"},
            "categories":[{"id":"1","name":"Sport","channel_count":2}],
            "channels":[{"stream_id":"29","name":"SKY","logo":null,"category_id":"1","stream_url":"https://x/live/u/p/29.ts","tv_archive":1,"tv_archive_duration":5}],
            "epg":{"29":[
              {"title":"old","description":"d","start":${now - 20_000},"stop":${now - 15_000},"live":false},
              {"title":"now","description":"${"x".repeat(500)}","start":${now - 100},"stop":${now + 100},"live":true},
              {"title":"far","description":null,"start":${now + 90_000},"stop":${now + 95_000},"live":false}],
              "30":[{"title":"gone","start":1,"stop":2}]},
            "generated_at":1792915407}"""
        val bundle = XtreamRepository.parseBundleJson(XtreamRepository.trimBundle(StringReader(json), now))
        assertEquals(1, bundle.channels.size)
        assertEquals(true, bundle.channels[0].archiveEnabled)
        assertEquals(1792915407L, bundle.generatedAt)
        assertEquals(listOf("now"), bundle.epg["29"]?.map { it.title })
        assertEquals(100, bundle.epg["29"]?.first()?.description?.length)
        assertTrue("30" !in bundle.epg)
    }

    @Test
    fun trims_real_backend_bundle_when_available() {
        val f = File("/tmp/bundle.json")
        if (!f.exists()) return
        val text = f.bufferedReader().use { XtreamRepository.trimBundle(it, 1792915407L) }
        val bundle = XtreamRepository.parseBundleJson(text)
        println("trimmed ${f.length() / 1048576} MB -> ${text.length / 1048576} MB; ${bundle.channels.size} channels, ${bundle.epg.values.sumOf { it.size }} programmes")
        assertTrue(bundle.channels.size > 1000)
        assertTrue(text.length < 20 * 1024 * 1024)
    }
}
