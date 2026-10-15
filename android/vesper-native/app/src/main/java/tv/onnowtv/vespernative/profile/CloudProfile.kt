package tv.onnowtv.vespernative.profile

/** Public picker projection: deliberately contains no PIN or snapshot payload. */
data class CloudProfile(val id: String, val name: String, val avatarId: String, val requiresPin: Boolean)

class ProfileLocked : IllegalArgumentException("Incorrect PIN. Try again.")
class InvalidSnapshot : IllegalArgumentException("Saved profile data could not be read.")

object ProfileKeys {
    const val PROFILES = "onnowtv-profiles-v1"
    const val LIBRARY = "vesper-library"
    const val CONTINUE_WATCHING = "onnowtv-continue-watching-v1"
    fun accountSuffix(username: String) = ":" + username.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    fun scoped(base: String, profileId: String) = "$base:$profileId"
}