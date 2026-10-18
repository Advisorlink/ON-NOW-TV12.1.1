package tv.onnowtv.vespernative.playback

import kotlinx.coroutines.*
import org.json.JSONObject
import tv.onnowtv.vespernative.data.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class TrailerMedia(val video:String,val audio:String="",val key:String="") {
    companion object {
        fun valid(url:String):Boolean=url.toHttpUrlOrNull()?.let {
            it.scheme=="https" && it.username.isEmpty() && it.password.isEmpty() &&
                it.host!="youtu.be" && it.host!="youtube.com" && !it.host.endsWith(".youtube.com")
        }==true
        fun parse(json:JSONObject,key:String):TrailerMedia? {
            val paired=json.optBoolean("is_hd_pair")
            val video=if(paired)json.text("video_url") else json.text("progressive_url").ifBlank {
                if(json.text("video_url").isBlank())json.text("url") else ""
            }
            val audio=if(paired)json.text("audio_url") else ""
            return if(valid(video) && (!paired || valid(audio)))TrailerMedia(video,audio,key) else null
        }
    }
}

/** Same order as trailerEngine: TMDB candidates -> on-device NewPipe -> backend.
 * URLs stay in bounded memory only. This repository has no cloud-write route.
 */
class TrailerRepository(private val api:VesperApi,private val token:()->String?,
    private val extract:suspend(String)->TrailerMedia?=DeviceTrailerExtractor::extract) {
    private val cache=linkedMapOf<String,Pair<Long,TrailerMedia>>()
    suspend fun resolve(title:Title):TrailerMedia? {
        cache[title.key]?.takeIf { System.currentTimeMillis()-it.first<600_000 }?.let { return it.second }
        return withTimeoutOrNull(40_000) {
            val type=if(title.type=="series")"tv" else "movie"
            val id=if(title.id.startsWith("tmdb:"))title.id.substringAfter(':') else {
                val found=api.request(listOf("tmdb","find-by-imdb",title.id),token()) as JSONObject
                found.text("tmdb_id")
            }
            if(!id.matches(Regex("[0-9]+")))return@withTimeoutOrNull null
            val data=(api.request(listOf("tmdb","trailer",type,id),token()) as JSONObject).optJSONObject("data") ?: return@withTimeoutOrNull null
            val keys=(data.optJSONArray("candidates")?.objects()?.map { it.text("key") }.orEmpty()+data.text("key"))
                .filter { it.matches(Regex("[A-Za-z0-9_-]{11}")) }.distinct().take(3)
            var result:TrailerMedia?=null
            for(key in keys) {
                currentCoroutineContext().ensureActive()
                result=withTimeoutOrNull(12_000) { extract(key) }
                if(result!=null)break
            }
            if(result==null)for(key in keys.take(2)) {
                try {
                    val json=api.request(listOf("trailer-stream",key),token(),mapOf("combined" to "1")) as JSONObject
                    result=TrailerMedia.parse(json,key)
                    if(result!=null)break
                } catch(e:CancellationException) { throw e } catch(_:Exception) { /* Try the next actual candidate. */ }
            }
            currentCoroutineContext().ensureActive()
            result?.also {
                if(cache.size>=16)cache.remove(cache.keys.first())
                cache[title.key]=System.currentTimeMillis() to it
            }
        }
    }
}