package tv.onnowtv.vespernative.profile

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import java.io.ByteArrayInputStream
import tv.onnowtv.vespernative.data.Episode

@RunWith(RobolectricTestRunner::class)
class V050SettingsRegressionTest {
    @Test fun home_row_defaults_order_and_local_profile_isolation(){
        val c=RuntimeEnvironment.getApplication().applicationContext
        val prefs=LocalPreferences(c,"row-test","main",null);val other=LocalPreferences(c,"row-test","kids",null)
        val rows=prefs.homeRows
        assertEquals(listOf("cw","foryou","networks","studios","movie-year","series-year","movie-top","series-top","upcoming"),rows.order)
        rows.move("movie-year",-1);rows.toggle("networks");rows.add("Comedy");prefs.saveRows(rows)
        val reopened=LocalPreferences(c,"row-test","main",null).homeRows
        assertEquals("movie-year",reopened.order[3]);assertTrue("networks" in reopened.hidden);assertEquals("Comedy",reopened.custom.single().query)
        assertFalse("networks" in other.homeRows.hidden);assertTrue(other.homeRows.custom.isEmpty())
    }
    @Test fun malformed_rows_are_safely_normalized_for_backup(){
        val p=LocalPreferences(RuntimeEnvironment.getApplication(),"backup-bad","main",null);p.homeRowsJson="not json"
        assertEquals(9,JSONObject(p.backup()).getJSONObject("homeRows").getJSONArray("order").length())
    }
    @Test fun local_restore_is_atomic_and_has_no_cloud_or_credentials_fields(){
        val c=RuntimeEnvironment.getApplication();val source=LocalPreferences(c,"backup-a","main",null);source.theme="gold";source.autoplay=false
        val data=source.backup();assertFalse(data.contains("token"));assertFalse(data.contains("password"));assertFalse(data.contains("library"))
        val target=LocalPreferences(c,"backup-b","main",null);target.restoreBackup(data);assertEquals("gold",target.theme);assertFalse(target.autoplay)
        val invalid=JSONObject(data).put("theme","mint").put("autoplayTV","not boolean").toString()
        assertThrows(Exception::class.java){target.restoreBackup(invalid)};assertEquals("gold",target.theme)
        assertThrows(Exception::class.java){target.restoreBackup(JSONObject(data).put("theme","unrecognized").toString())}
    }
    @Test fun oversized_local_file_is_rejected_before_json_parsing(){
        assertThrows(IllegalArgumentException::class.java){LocalBackup.read(ByteArrayInputStream(ByteArray(65537)))}
        assertEquals("{}",LocalBackup.read(ByteArrayInputStream("{}".toByteArray())))
    }
    @Test fun real_cinemeta_episode_names_take_precedence_over_missing_or_numeric_titles(){
        val e=Episode.parse(JSONObject("""{"id":"tt123:1:1","season":1,"episode":1,"name":"Come Home","title":"1","overview":"A real episode.","thumbnail":"https://example.com/still.jpg"}"""))!!
        assertEquals("Come Home",e.name);assertEquals("A real episode.",e.overview);assertTrue(e.thumbnail.isNotBlank())
        assertEquals("Episode 2",Episode.parse(JSONObject("""{"id":"tt123:1:2","season":1,"episode":2}"""))!!.name)
    }
}