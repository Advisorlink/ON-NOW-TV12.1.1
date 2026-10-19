package tv.onnowtv.vespernative.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    private val keyCache=linkedMapOf<String,Pair<Long,List<String>>>()
    private val rejected=linkedMapOf<String,MutableSet<TrailerMedia>>()
    fun reject(title:Title,media:TrailerMedia) {
        cache.remove(title.key)
        if(rejected.size>=16)rejected.remove(rejected.keys.first())
        rejected.getOrPut(title.key) { mutableSetOf() }.add(media)
    }
    fun remember(title:Title,media:TrailerMedia) {
        if(cache.size>=16)cache.remove(cache.keys.first())
        cache[title.key]=System.currentTimeMillis() to media
    }
    fun cached(title:Title):TrailerMedia?=cache[title.key]?.takeIf{System.currentTimeMillis()-it.first<120_000}?.second
    suspend fun keys(title:Title):List<String> {
        keyCache[title.key]?.takeIf{System.currentTimeMillis()-it.first<3_600_000}?.let{return it.second}
        val type=if(title.type=="series")"tv" else "movie"
        val id=if(title.id.startsWith("tmdb:"))title.id.substringAfter(':') else
            (api.request(listOf("tmdb","find-by-imdb",title.id),token()) as JSONObject).text("tmdb_id")
        if(!id.matches(Regex("[0-9]+")))return emptyList()
        val data=(api.request(listOf("tmdb","trailer",type,id),token()) as JSONObject).optJSONObject("data") ?: return emptyList()
        val keys=(data.optJSONArray("candidates")?.objects()?.map{it.text("key")}.orEmpty()+data.text("key"))
            .filter{it.matches(Regex("[A-Za-z0-9_-]{11}"))}.distinct().take(3)
        if(keys.isNotEmpty()){if(keyCache.size>=64)keyCache.remove(keyCache.keys.first());keyCache[title.key]=System.currentTimeMillis() to keys}
        return keys
    }
    suspend fun backend(key:String):TrailerMedia?=try{withTimeoutOrNull(8_000){
        TrailerMedia.parse(api.request(listOf("trailer-stream",key),token(),mapOf("combined" to "1")) as JSONObject,key)
    }}catch(e:CancellationException){throw e}catch(_:Exception){null}
    suspend fun resolve(title:Title):TrailerMedia?=candidates(title).firstOrNull()?.also { remember(title,it) }
    fun candidates(title:Title,prepared:PreparedTrailer?=null):Flow<TrailerMedia> = channelFlow {
        cached(title)?.let{send(it)}
        withTimeoutOrNull(30_000) {
            val keys=prepared?.keys?.await().orEmpty().ifEmpty{keys(title)}
            coroutineScope {
                launch {
                    for(key in keys.take(2)) {
                        val result=try { withTimeoutOrNull(7_000) { extract(key) } }
                        catch(e:CancellationException) { throw e } catch(_:Exception) { null }
                        result?.takeIf { it !in rejected[title.key].orEmpty() }?.let { send(it) }
                    }
                }
                launch {
                    val ready=prepared?.media?.await()?.takeIf{it !in rejected[title.key].orEmpty()}
                    if(ready!=null)send(ready)
                    for(key in keys.take(2).drop(if(ready!=null)1 else 0)) {
                        val result=backend(key)
                        result?.takeIf { it !in rejected[title.key].orEmpty() }?.let { send(it) }
                    }
                }
            }
        }
    }.distinctUntilChanged()
}