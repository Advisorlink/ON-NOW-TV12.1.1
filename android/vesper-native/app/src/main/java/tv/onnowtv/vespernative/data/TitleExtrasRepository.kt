package tv.onnowtv.vespernative.data

import org.json.JSONObject
import tv.onnowtv.vespernative.BuildConfig

data class CastMember(val id:String,val name:String,val character:String,val image:String)
class TitleExtrasRepository(private val api:VesperApi,private val token:()->String?) {
    private val castCache=linkedMapOf<String,List<CastMember>>()
    private val people=linkedMapOf<String,JSONObject>()
    private val recommendations=linkedMapOf<String,List<Title>>()
    private suspend fun tmdbId(title:Title)=if(title.id.startsWith("tmdb:"))title.id.substringAfter(':') else
        (api.request(listOf("tmdb","find-by-imdb",title.id),token()) as JSONObject).text("tmdb_id")
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
    suspend fun person(id:String):JSONObject {
        people[id]?.let { return it }
        val result=api.request(listOf("tmdb","person",id),token()) as JSONObject
        if(people.size>=24)people.remove(people.keys.first())
        people[id]=result;return result
    }
    suspend fun films(id:String):List<Title> = person(id).optJSONArray("filmography")?.objects().orEmpty().mapNotNull {
        rawTitle(it,if(it.text("media_type")=="tv")"series" else "movie")
    }.distinctBy(Title::key)
    suspend fun similar(title:Title):List<Title> {
        recommendations[title.key]?.let { return it }
        val id=tmdbId(title);if(!id.matches(Regex("[0-9]+")))return emptyList()
        val response=api.request(listOf("tmdb","recommendations",if(title.type=="series")"tv" else "movie",id),token()) as JSONObject
        val result=response.optJSONArray("results")?.objects().orEmpty().mapNotNull {
            rawTitle(it,if(it.text("media_type").ifBlank{if(title.type=="series")"tv" else "movie"}=="tv")"series" else "movie")
        }.distinctBy(Title::key)
        if(recommendations.size>=24)recommendations.remove(recommendations.keys.first())
        recommendations[title.key]=result;return result
    }
    private fun rawTitle(item:JSONObject,type:String):Title? {
        // Person/recommendations return raw TMDB fields, unlike the normalized
        // collection/network endpoints. Reusing that parser silently dropped every title.
        val id=item.text("tmdb_id").ifBlank{item.text("id")};val name=item.text("title").ifBlank{item.text("name")}
        if(id.isBlank() || name.isBlank())return null
        fun image(key:String,size:String):String {
            val path=item.text(key);if(!path.matches(Regex("/[A-Za-z0-9._-]+")))return ""
            return "${BuildConfig.TMDB_IMAGE_BASE_URL}/$size$path"
        }
        return Title("tmdb:$id",type,name,item.text("poster").ifBlank{image("poster_path","w342")},
            item.text("backdrop").ifBlank{image("backdrop_path","w1280")},item.text("overview"),
            item.text("year").ifBlank{item.text("release_date").ifBlank{item.text("first_air_date")}}.take(4),
            item.text("rating").ifBlank{item.text("vote_average")}.takeIf{it!="0" && it!="0.0"}.orEmpty())
    }
}