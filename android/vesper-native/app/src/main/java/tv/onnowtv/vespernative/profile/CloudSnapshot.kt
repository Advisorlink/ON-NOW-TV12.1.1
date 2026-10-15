package tv.onnowtv.vespernative.profile

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import tv.onnowtv.vespernative.data.Account
import tv.onnowtv.vespernative.data.objects
import tv.onnowtv.vespernative.data.text

/**
 * Lossless, read-only projection of the web client's flat localStorage snapshot.
 * It lives only in this authenticated session. There is NO save/push/disk path.
 * Caller receives defensive projections, never another account's suffixed data.
 */
class CloudSnapshot private constructor(private val owner: Account, data: JSONObject, val updatedAt: Long?) {
    private val raw = JSONObject(data.toString())
    private val legacyClaims = mutableMapOf<String, String>()
    private val records: Map<String, JSONObject> = parseProfiles()
    val profiles: List<CloudProfile> get() = records.values.map { record ->
        CloudProfile(record.text("id"), record.text("name").ifBlank { "Profile" },
            record.text("avatarId").ifBlank { "fn-popcorn-fg" }, pin(record).length == 4)
    }

    private fun parseProfiles(): Map<String, JSONObject> {
        val scoped = ProfileKeys.PROFILES + ProfileKeys.accountSuffix(owner.username)
        // A scoped empty/malformed value is authoritative, not a reason to read
        // another account's profiles. Legacy key belongs to this JWT-bound pull.
        val key = if (raw.has(scoped)) scoped else ProfileKeys.PROFILES
        if (!raw.has(key) || raw.isNull(key)) return emptyMap()
        val array = decode(raw.get(key)) as? JSONArray ?: throw InvalidSnapshot()
        val result = linkedMapOf<String, JSONObject>()
        for (record in array.objects()) {
            val id = record.text("id")
            if (id.isBlank() || id == "kids" || record.optBoolean("kids", false)) continue
            pin(record) // Invalid PIN shape must not accidentally unlock a profile.
            if (result.containsKey(id)) throw InvalidSnapshot()
            result[id] = record
        }
        return result
    }

    private fun pin(record: JSONObject): String {
        if (!record.has("pin") || record.isNull("pin")) return ""
        return record.opt("pin") as? String ?: throw InvalidSnapshot()
    }

    /** Exact existing policy: only a four-character stored PIN gates access;
     * equality is exact, without trim/normalization. UI digit gating comes later. */
    fun openProfile(profileId: String, enteredPin: String = ""): ProfileData {
        val record = records[profileId] ?: throw IllegalArgumentException("Profile is unavailable.")
        val savedPin = pin(record)
        if (savedPin.length == 4 && savedPin != enteredPin) throw ProfileLocked()
        return ProfileData(profileId) { base -> scopedValue(base, profileId) }
    }

    private fun scopedValue(base: String, profileId: String): Any? {
        val scoped = ProfileKeys.scoped(base, profileId)
        if (raw.has(scoped)) return raw.opt(scoped)
        val legacy = when {
            raw.has(base) -> base
            raw.has("$base:global") -> "$base:global"
            else -> return null
        }
        // Mirror the web's one-shot promotion in MEMORY only. A second profile
        // must not inherit the first viewer's library or Continue Watching.
        if (legacyClaims.getOrPut(base) { profileId } != profileId) return null
        return raw.opt(legacy)
    }

    internal fun rawCopy(): JSONObject = JSONObject(raw.toString())

    companion object {
        fun parse(account: Account, envelope: JSONObject): CloudSnapshot? {
            val found = envelope.opt("found") as? Boolean ?: throw InvalidSnapshot()
            if (!found) return null
            val data = envelope.opt("data") as? JSONObject ?: throw InvalidSnapshot()
            val updated = (envelope.opt("updated_at") as? Number)?.toLong()
            return CloudSnapshot(account, data, updated)
        }
        private fun decode(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is String -> try { JSONTokener(value).nextValue() } catch (_: Exception) { throw InvalidSnapshot() }
            is JSONObject -> JSONObject(value.toString())
            is JSONArray -> JSONArray(value.toString())
            else -> value
        }
    }
}

/** Accessor exists only after PIN validation; it does not retain or expose PINs. */
class ProfileData internal constructor(val profileId: String, private val read: (String) -> Any?) {
    fun library(): JSONObject = runCatching { JSONObject(read(ProfileKeys.LIBRARY)?.toString() ?: "{}") }.getOrDefault(JSONObject())
    fun continueWatching(): JSONArray = runCatching { JSONArray(read(ProfileKeys.CONTINUE_WATCHING)?.toString() ?: "[]") }.getOrDefault(JSONArray())
}