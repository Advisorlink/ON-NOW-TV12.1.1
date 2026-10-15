package tv.onnowtv.livetv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SportsArchiveMetadataTest {

    @Test
    fun archive_flag_normalizes_bool_numeric_and_string_variants() {
        assertEquals(true, SportsArchiveMetadata.flag(true))
        assertEquals(false, SportsArchiveMetadata.flag(false))
        assertEquals(true, SportsArchiveMetadata.flag(1))
        assertEquals(false, SportsArchiveMetadata.flag(0))
        assertEquals(true, SportsArchiveMetadata.flag("true"))
        assertEquals(false, SportsArchiveMetadata.flag("false"))
        assertEquals(true, SportsArchiveMetadata.flag("1"))
        assertEquals(false, SportsArchiveMetadata.flag("0"))
        assertNull(SportsArchiveMetadata.flag("unknown"))
        assertNull(SportsArchiveMetadata.flag(null))
    }

    @Test
    fun timestamp_seconds_and_millis_normalize_to_same_epoch_ms() {
        val sec = 1_700_000_000L
        val ms = sec * 1000
        assertEquals(ms, SportsArchiveMetadata.timestamp(sec))
        assertEquals(ms, SportsArchiveMetadata.timestamp(ms))
        assertEquals(ms, SportsArchiveMetadata.timestamp(sec.toString()))
        assertEquals(ms, SportsArchiveMetadata.timestamp(ms.toString()))
    }

    @Test
    fun parse_programmes_accepts_end_timestamp_fallback_and_rejects_invalid_windows() {
        val nowSec = System.currentTimeMillis() / 1000
        val encoded = Base64.getEncoder().encodeToString("Yankees vs Red Sox".toByteArray())
        val json = JSONObject(
            """
            {"epg_listings":[
              {"title":"$encoded","start_timestamp":$nowSec,"end_timestamp":${nowSec + 3600},"has_archive":"1"},
              {"title":"Invalid stop<=start","start_timestamp":$nowSec,"stop_timestamp":$nowSec,"has_archive":1},
              {"title":"Invalid missing start","stop_timestamp":${nowSec + 1200},"has_archive":1}
            ]}
            """.trimIndent(),
        )

        val rows = SportsReplayRepository.parseProgrammes(json)
        assertEquals(1, rows.size)
        assertEquals("Yankees vs Red Sox", rows.first().title)
        assertEquals(true, rows.first().archiveAvailable)
    }

    @Test
    fun xtream_repository_accepts_tv_archive_boolean_true() {
        val bundle = XtreamRepository.parseBundleJson(
            """
            {
              "provider": {"id":"p","name":"P","host":"h","port":"443","scheme":"https"},
              "categories": [],
              "channels": [
                {"stream_id":"11","name":"Sports 11","stream_url":"https://p/live/u/p/11.ts","tv_archive":true,"tv_archive_duration":"7"}
              ],
              "epg": {}
            }
            """.trimIndent(),
        )

        assertEquals(1, bundle.channels.size)
        assertTrue(bundle.channels.first().archiveEnabled)
        assertEquals(7, bundle.channels.first().archiveDays)
    }
}
