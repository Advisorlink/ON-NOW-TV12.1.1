package tv.onnowtv.vespernative.playback

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.onnowtv.vespernative.data.*

data class Stream(val url:String,val name:String,val description:String,val headers:Map<String,String>,
    val source:Int,val uncached:Boolean,val fhd:Boolean,val size:Double?,val order:Int,val quality:String="") {
    fun json()=JSONObject().put("url",url).put("name",name).put("description",description).put("headers",JSONObject(headers))
        .put("_size_gb",size).put("_pm_uncached",uncached).put("_quality",quality.ifBlank { if(fhd)"1080p" else "" }).put("_onnow_direct",source==0)
    companion object {
        fun parse(o:JSONObject,index:Int):Stream? {
            val url=o.text("url"); if(url.toHttpUrlOrNull()==null)return null
            val sourceText=listOf("_addon_id","_addon_name","_addon_source","name").joinToString(" ") { o.text(it) }.lowercase()
            val rank=when {
                o.optBoolean("_onnow_direct") || o.text("_addon_id")=="onnowtv-direct" -> 0
                "torrentio" in sourceText -> 1
                Regex("media[ _-]?fusion").containsMatchIn(sourceText) -> 2
                Regex("easy[ _-]?news").containsMatchIn(sourceText) -> 3
                else -> 4
            }
            val description=o.text("description").ifBlank { o.text("title") }
            val quality="${o.text("name")} $description ${o.text("_quality")}"
            val fhd=Regex("1080|fhd",RegexOption.IGNORE_CASE).containsMatchIn(quality) && !Regex("2160|4k|av1",RegexOption.IGNORE_CASE).containsMatchIn(quality)
            val rawHeaders=o.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request") ?: o.optJSONObject("headers") ?: JSONObject()
            val headers=rawHeaders.keys().asSequence().filter { key ->
                key.matches(Regex("[A-Za-z0-9-]+")) && key.lowercase() !in setOf("host","connection","content-length")
            }.mapNotNull { key -> rawHeaders.text(key).takeUnless { '\r' in it || '\n' in it }?.let { key to it } }.toMap()
            return Stream(url,o.text("_addon_name").ifBlank { o.text("name").ifBlank { "Stream" } },description,headers,rank,
                o.optBoolean("_pm_uncached"),fhd,(o.opt("_size_gb") as? Number)?.toDouble(),index,
                o.text("_quality").ifBlank { Regex("2160p|1080p|720p|480p|4K",RegexOption.IGNORE_CASE).find(quality)?.value.orEmpty() })
        }
        fun sorted(values:List<Stream>):List<Stream> = values.sortedWith(Comparator { a,b ->
            val blockA=if(a.uncached)5 else a.source; val blockB=if(b.uncached)5 else b.source
            when {
                blockA!=blockB -> blockA.compareTo(blockB)
                blockA==0 -> a.order.compareTo(b.order)
                a.fhd!=b.fhd -> if(a.fhd)-1 else 1
                band(a.size)!=band(b.size) -> band(a.size).compareTo(band(b.size))
                a.size!=b.size -> (a.size ?: Double.MAX_VALUE).compareTo(b.size ?: Double.MAX_VALUE)
                else -> a.order.compareTo(b.order)
            }
        })
        private fun band(size:Double?)=when { size==null->3; size<1->1; size>4->2; else->0 }
    }
}
class StreamRepository(private val api:VesperApi,private val token:()->String?) {
    suspend fun streams(type:String,id:String):List<Stream> {
        val response=api.request(listOf("streams",type,id),token()) as JSONObject
        return Stream.sorted(response.optJSONArray("streams")?.objects().orEmpty().mapIndexedNotNull { index,json -> Stream.parse(json,index) })
    }
}