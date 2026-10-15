package tv.onnowtv.vespernative.auth

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import tv.onnowtv.vespernative.BuildConfig
import tv.onnowtv.vespernative.data.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class AuthRepository(private val api: VesperApi, private val sessions: SessionStore) {
    var token: String? = null
        private set

    suspend fun signIn(username: String, password: String): Account {
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter your username and password." }
        val response = api.request(listOf("auth", "login"), body = JSONObject()
            .put("username", username.trim()).put("password", password)
            .put("client_id", BuildConfig.APPLICATION_ID).toString()) as JSONObject
        val candidate = response.text("access_token")
        require(candidate.isNotBlank()) { "Invalid sign-in response. Please try again." }
        val account = parseAccount(response.getJSONObject("account"))
        sessions.save(candidate)
        token = candidate
        return account
    }

    suspend fun restore(): Account? {
        val saved = sessions.read() ?: return null
        try {
            val response = api.request(listOf("auth", "me"), saved) as JSONObject
            val account = parseAccount(response.getJSONObject("account"))
            token = saved
            return account
        } catch (e: ApiFailure) {
            if (e.status == 401 || e.status == 403) { clear(); return null }
            throw e // Network/server outage is NOT an expired session.
        }
    }

    private fun parseAccount(json: JSONObject): Account {
        if (json.text("status") == "disabled") throw ApiFailure(403)
        val expires = json.text("expires_at")
        val instant = runCatching { Instant.parse(expires) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(expires).toInstant(ZoneOffset.UTC) }.getOrNull()
        if (instant != null && instant.isBefore(Instant.now())) throw ApiFailure(403)
        return Account.parse(json)
    }

    suspend fun clear() { token = null; sessions.clear() }

    suspend fun signOut(onLocalCleared: () -> Unit = {}) {
        val previous = token
        clear() // Local sign-out is immediate even if the server is offline.
        onLocalCleared()
        if (previous != null) try {
            api.request(listOf("auth", "logout"), previous, body = "{}")
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { /* stateless existing backend logout */ }
    }
}