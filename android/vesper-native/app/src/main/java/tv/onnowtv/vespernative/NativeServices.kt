package tv.onnowtv.vespernative

import android.content.Context
import tv.onnowtv.vespernative.auth.*
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.profile.ProfileCloudRepository

class NativeServices(val api: VesperApi, val auth: AuthRepository) {
    fun cloudProfiles(account: Account): ProfileCloudRepository = ProfileCloudRepository(
        api, account, auth.token ?: throw ApiFailure(401)) { auth.token }

    companion object {
        internal var factory: (Context) -> NativeServices = { context ->
            val api = VesperApi(BuildConfig.API_BASE_URL)
            NativeServices(api, AuthRepository(api, KeystoreSessionStore(context)))
        }
    }
}