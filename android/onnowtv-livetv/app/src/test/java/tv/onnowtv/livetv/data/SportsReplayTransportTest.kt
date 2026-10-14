package tv.onnowtv.livetv.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SportsReplayTransportTest {

    @Test
    fun default_factory_uses_okhttp_datasource_and_redirects_enabled_without_weakened_tls() {
        val factory = SportsReplayTransport.dataSource()
        val dataSource = factory.createDataSource()

        assertTrue(dataSource is OkHttpDataSource)
        assertTrue(SportsReplayTransport.client.followRedirects)
        assertTrue(SportsReplayTransport.client.followSslRedirects)
        assertEquals(0L, SportsReplayTransport.client.callTimeoutMillis.toLong())
        assertTrue(SportsReplayTransport.client.certificatePinner.pins.isEmpty())
        assertTrue(SportsReplayTransport.client.hostnameVerifier.javaClass.name.contains("OkHostnameVerifier"))
    }

    @Test
    fun datasource_reads_identical_bounded_bytes_across_cross_host_http_302_redirect() {
        val origin = MockWebServer()
        val target = MockWebServer()
        origin.start()
        target.start()
        try {
            val payload = "abcdefghijklmnopqrstuvwxyz0123456789".toByteArray()
            target.enqueue(MockResponse().setResponseCode(200).setBody(payload.inputStream().readBytes().toString(Charsets.UTF_8)))
            origin.enqueue(
                MockResponse().setResponseCode(302)
                    .addHeader("Location", target.url("/archive/recording.m3u8").newBuilder().host("127.0.0.1").build().toString()),
            )

            val source = SportsReplayTransport.dataSource().createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(origin.url("/redirect/start").toString()))
                .setPosition(4)
                .setLength(12)
                .build()

            val bytes = readAll(source, dataSpec)
            assertArrayEquals(payload.copyOfRange(4, 16), bytes)
            assertEquals(1, origin.requestCount)
            assertEquals(1, target.requestCount)
        } finally {
            origin.shutdown()
            target.shutdown()
        }
    }

    @Test
    fun datasource_rejects_non_2xx_response() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        server.start()
        try {
            val source = SportsReplayTransport.dataSource().createDataSource()
            val spec = DataSpec.Builder().setUri(Uri.parse(server.url("/missing.ts").toString())).build()
            try {
                source.open(spec)
                fail("Expected non-2xx response to throw")
            } catch (_: HttpDataSource.InvalidResponseCodeException) {
                // expected
            } finally {
                runCatching { source.close() }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun player_configuration_prefers_english_disables_text_and_keeps_audio_video_enabled() {
        val context = RuntimeEnvironment.getApplication()
        val player = SportsReplayTransport.player(context)
        try {
            val params = player.trackSelectionParameters
            assertTrue(params.preferredAudioLanguages.contains("eng") || params.preferredAudioLanguages.contains("en"))
            assertTrue(params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            assertFalse(params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
            assertFalse(params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
        } finally {
            player.release()
        }
    }

    private fun readAll(source: androidx.media3.datasource.DataSource, spec: DataSpec): ByteArray {
        source.open(spec)
        val out = ArrayList<Byte>()
        val buffer = ByteArray(256)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            repeat(read) { out.add(buffer[it]) }
        }
        source.close()
        return out.toByteArray()
    }
}
