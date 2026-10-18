package tv.onnowtv.vespernative.data

import org.json.JSONObject

data class CastMember(val id:String,val name:String,val character:String,val image:String)
class TitleExtrasRepository(private val api:VesperApi,private val token:()->String?) {
    private val castCache=linkedMapOf<String,List<CastMember>>()
    suspend fun cast(title:Title):List<CastMember> {
        castCache[title.key]?.let { return it }
        val id=if(title.id.startsWith("tmdb:"))title.id.substringAfter(':') else
            (api.request(listOf("tmdb","find-by-imdb",title.id),token()) as JSONObject).text("tmdb_id")
        if(!id.matches(Regex("[0-9]+")))return emptyList()
        val result=api.request(listOf("tmdb","credits",if(title.type=="series")"tv" else "movie",id),token()) as JSONObject
        val members=result.optJSONArray("cast")?.objects().orEmpty().map {
            CastMember(it.text("id"),it.text("name"),it.text("character"),it.text("profile"))
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.take(20)
        if(castCache.size>=24)castCache.remove(castCache.keys.first())
        castCache[title.key]=members
        return members
    }
    suspend fun person(id:String):JSONObject=api.request(listOf("tmdb","person",id),token()) as JSONObject
}