package tv.onnowtv.vespernative.profile

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import tv.onnowtv.vespernative.data.Account
import tv.onnowtv.vespernative.data.ApiFailure
import tv.onnowtv.vespernative.data.VesperApi

/** Owned by ONE authenticated session. Old instances cannot pull under a new JWT. */
class ProfileCloudRepository(private val api: VesperApi, private val account: Account,
    private val boundToken: String, private val activeToken: () -> String?) {
    init { require(boundToken.isNotBlank()) }

    suspend fun pull(): CloudSnapshot? {
        ensureCurrentSession()
        val response = api.request(listOf("vesper", "sync", "pull"), boundToken)
        ensureCurrentSession() // Discard an old account's response after logout/switch.
        return CloudSnapshot.parse(account, response as? JSONObject ?: throw InvalidSnapshot())
    }

    private fun ensureCurrentSession() {
        val active = activeToken() ?: throw ApiFailure(401)
        if (active != boundToken) throw CancellationException("The account session changed.")
    }
}