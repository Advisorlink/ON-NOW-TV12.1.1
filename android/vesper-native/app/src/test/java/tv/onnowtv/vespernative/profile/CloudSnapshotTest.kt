package tv.onnowtv.vespernative.profile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.onnowtv.vespernative.data.Account

// CloudSnapshot/CloudProfile: key scoping, PIN policy, mutation safety, and legacy one-shot promotion.
class CloudSnapshotTest {
    private val account = Account(id = "acc-1", username = "ANNIE M", label = "Annie")
    private val suffix = ProfileKeys.accountSuffix(account.username)

    @Test
    fun reads_actual_localstorage_strings_and_preserves_opaque_future_data() {
        val profileJson = JSONArray().put(profile("p-1", "Living room", pin = "2468")).toString()
        val libraryJson = JSONObject().put("favorites", JSONObject().put("tt12", JSONObject().put("name", "A title"))).toString()
        val watchingJson = JSONArray().put(JSONObject().put("id", "tt12:1:2").put("positionMs", 90500)).toString()
        val future = "{\"future\":{\"label\":\"汉字 🎬\",\"v\":null},\"flag\":false}"
        val payload = JSONObject()
            .put(ProfileKeys.PROFILES + suffix, profileJson)
            .put("${ProfileKeys.LIBRARY}:p-1", libraryJson)
            .put("${ProfileKeys.CONTINUE_WATCHING}:p-1", watchingJson)
            .put("vesper-future-v9", future)
        val snapshot = CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", payload))!!
        assertEquals("Living room", snapshot.profiles.single().name)
        val unlocked = snapshot.openProfile("p-1", "2468")
        assertEquals("A title", unlocked.library().getJSONObject("favorites").getJSONObject("tt12").getString("name"))
        assertEquals(90500L, unlocked.continueWatching().getJSONObject(0).getLong("positionMs"))
        assertEquals(profileJson, snapshot.rawCopy().getString(ProfileKeys.PROFILES + suffix))
        assertEquals(future, snapshot.rawCopy().getString("vesper-future-v9"))
    }

    @Test
    fun parse_accepts_found_false_and_found_true_shapes() {
        val none = CloudSnapshot.parse(account, JSONObject("""{"found":false}"""))
        assertNull(none)

        val envelope = JSONObject().put("found", true).put("data", JSONObject()).put("updated_at", 1700000000000L)
        val snapshot = CloudSnapshot.parse(account, envelope)
        assertNotNull(snapshot)
        assertEquals(1700000000000L, snapshot!!.updatedAt)
    }

    @Test
    fun profile_suffix_matches_web_sanitization_rules_exactly() {
        assertEquals(":ANNIE_M", suffix)
        assertEquals(":A_B-C_D", ProfileKeys.accountSuffix("A  B-C@D"))
    }

    @Test
    fun scoped_profiles_key_is_authoritative_even_when_empty_and_no_cross_account_scan() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val otherScoped = ProfileKeys.PROFILES + ":OTHER_USER"
        val legacy = JSONArray().put(profile("legacy-1", "Legacy"))
        val other = JSONArray().put(profile("other-1", "Other"))

        val envelope = JSONObject().put("found", true).put("data", JSONObject()
            .put(scopedKey, JSONArray())
            .put(otherScoped, other)
            .put(ProfileKeys.PROFILES, legacy)
        )
        val snapshot = CloudSnapshot.parse(account, envelope)!!
        assertTrue(snapshot.profiles.isEmpty())
    }

    @Test
    fun missing_current_scoped_uses_only_unsuffixed_legacy_not_other_account_scopes() {
        val otherScoped = ProfileKeys.PROFILES + ":SOMEONE_ELSE"
        val envelope = JSONObject().put("found", true).put("data", JSONObject()
            .put(otherScoped, JSONArray().put(profile("other", "Other")))
        )
        val snapshot = CloudSnapshot.parse(account, envelope)!!
        assertTrue(snapshot.profiles.isEmpty())
    }

    @Test(expected = InvalidSnapshot::class)
    fun malformed_scoped_profiles_fails_closed_without_legacy_fallback() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val envelope = JSONObject().put("found", true).put("data", JSONObject()
            .put(scopedKey, "{}")
            .put(ProfileKeys.PROFILES, JSONArray().put(profile("p-legacy", "Legacy")))
        )
        CloudSnapshot.parse(account, envelope)
    }

    @Test(expected = InvalidSnapshot::class)
    fun duplicate_profile_ids_fail_closed() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val dupes = JSONArray().put(profile("p-1", "A")).put(profile("p-1", "B"))
        CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject().put(scopedKey, dupes)))
    }

    @Test
    fun excludes_kids_by_id_and_kids_flag() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val profiles = JSONArray()
            .put(profile("kids", "Kids"))
            .put(profile("p-kids-flag", "Child", kids = true))
            .put(profile("p-1", "Adult"))
        val snapshot = CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject().put(scopedKey, profiles)))!!
        assertEquals(listOf("p-1"), snapshot.profiles.map { it.id })
    }

    @Test
    fun pin_gating_exact_length4_exact_match_and_no_pin_allowed() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val profiles = JSONArray()
            .put(profile("p-pin", "PIN", pin = "1234"))
            .put(profile("p-long", "Long", pin = "12345"))
            .put(profile("p-none", "None", pin = ""))
        val snapshot = CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject().put(scopedKey, profiles)))!!

        assertTrue(snapshot.profiles.first { it.id == "p-pin" }.requiresPin)
        assertTrue(snapshot.profiles.first { it.id == "p-long" }.requiresPin.not())

        val wrong = runCatching { snapshot.openProfile("p-pin", "0000") }.exceptionOrNull()
        assertTrue(wrong is ProfileLocked)
        val blank = runCatching { snapshot.openProfile("p-pin", "") }.exceptionOrNull()
        assertTrue(blank is ProfileLocked)
        val spaced = runCatching { snapshot.openProfile("p-pin", " 1234") }.exceptionOrNull()
        assertTrue(spaced is ProfileLocked)

        assertEquals("p-pin", snapshot.openProfile("p-pin", "1234").profileId)
        assertEquals("p-long", snapshot.openProfile("p-long", "nope").profileId)
        assertEquals("p-none", snapshot.openProfile("p-none", "").profileId)
    }

    @Test(expected = InvalidSnapshot::class)
    fun malformed_numeric_pin_never_unlocks() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val invalid = JSONArray().put(JSONObject(profile("p-1", "A").toString()).put("pin", 1234))
        CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject().put(scopedKey, invalid)))
    }

    @Test(expected = InvalidSnapshot::class)
    fun malformed_array_pin_never_unlocks() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val invalid = JSONArray().put(JSONObject(profile("p-1", "A").toString()).put("pin", JSONArray().put("1")))
        CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject().put(scopedKey, invalid)))
    }

    @Test
    fun cloud_profile_to_string_does_not_expose_pin_value() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val snapshot = CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject()
            .put(scopedKey, JSONArray().put(profile("p-1", "Alpha", pin = "9876")))
        ))!!
        val asText = snapshot.profiles.single().toString()
        assertFalse(asText.contains("9876"))
        assertFalse(asText.contains("pin=9876", ignoreCase = true))
    }

    @Test
    fun unknown_profile_or_kids_profile_cannot_be_opened() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val snapshot = CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject()
            .put(scopedKey, JSONArray().put(profile("p-1", "Adult")))
        ))!!
        assertTrue(runCatching { snapshot.openProfile("missing") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { snapshot.openProfile("kids") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun preserves_unknown_nested_library_fields_and_defensive_copies() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val sourceEnvelope = JSONObject().put("found", true).put("data", JSONObject()
            .put(scopedKey, JSONArray().put(profile("p-1", "Adult")))
            .put("${ProfileKeys.LIBRARY}:p-1", JSONObject()
                .put("favorites", JSONObject())
                .put("notifyList", JSONObject().put("tt1", JSONObject().put("x", 1)))
                .put("unknownNested", JSONObject().put("a", JSONArray().put(1).put(2)))
            )
            .put("${ProfileKeys.CONTINUE_WATCHING}:p-1", JSONArray().put(JSONObject().put("id", "tt1:s1e1")))
        )

        val snapshot = CloudSnapshot.parse(account, sourceEnvelope)!!
        sourceEnvelope.getJSONObject("data").put("${ProfileKeys.LIBRARY}:p-1", JSONObject().put("favorites", JSONObject().put("clobbered", true)))

        val opened = snapshot.openProfile("p-1")
        val libA = opened.library()
        assertTrue(libA.has("unknownNested"))
        assertEquals(2, libA.getJSONObject("unknownNested").getJSONArray("a").length())

        libA.put("mutated", true)
        val libB = opened.library()
        assertFalse(libB.has("mutated"))

        val cwA = opened.continueWatching()
        cwA.put(JSONObject().put("id", "mutated"))
        val cwB = opened.continueWatching()
        assertEquals(1, cwB.length())

        val raw = snapshot.rawCopy()
        raw.put("newkey", "x")
        assertFalse(snapshot.rawCopy().has("newkey"))
    }

    @Test
    fun legacy_and_global_promotion_is_one_owner_per_base_only() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val snapshot = CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject()
            .put(scopedKey, JSONArray().put(profile("p-1", "P1")).put(profile("p-2", "P2")))
            .put(ProfileKeys.LIBRARY, JSONObject().put("from", "legacy"))
            .put("${ProfileKeys.CONTINUE_WATCHING}:global", JSONArray().put(JSONObject().put("id", "tt1")))
        ))!!

        val p1 = snapshot.openProfile("p-1")
        val p2 = snapshot.openProfile("p-2")

        assertEquals("legacy", p1.library().optString("from"))
        assertEquals(0, p2.library().length())

        assertEquals(1, p1.continueWatching().length())
        assertEquals(0, p2.continueWatching().length())
    }

    @Test
    fun existing_scoped_empty_data_never_borrows_legacy_or_other_scope() {
        val scopedKey = ProfileKeys.PROFILES + suffix
        val snapshot = CloudSnapshot.parse(account, JSONObject().put("found", true).put("data", JSONObject()
            .put(scopedKey, JSONArray().put(profile("p-1", "P1")).put(profile("p-2", "P2")))
            .put("${ProfileKeys.LIBRARY}:p-1", JSONObject())
            .put("${ProfileKeys.CONTINUE_WATCHING}:p-1", JSONArray())
            .put(ProfileKeys.LIBRARY, JSONObject().put("legacy", true))
            .put("${ProfileKeys.CONTINUE_WATCHING}:global", JSONArray().put(JSONObject().put("id", "tt1")))
            .put("${ProfileKeys.LIBRARY}:p-2", JSONObject().put("mine", true))
        ))!!

        val p1 = snapshot.openProfile("p-1")
        val p2 = snapshot.openProfile("p-2")

        assertEquals(0, p1.library().length())
        assertEquals(0, p1.continueWatching().length())
        assertTrue(p2.library().optBoolean("mine", false))
    }

    private fun profile(id: String, name: String, kids: Boolean = false, pin: String? = null): JSONObject {
        return JSONObject().put("id", id).put("name", name).put("avatarId", "fn-popcorn-fg").put("kids", kids)
            .also { if (pin != null) it.put("pin", pin) }
    }
}
