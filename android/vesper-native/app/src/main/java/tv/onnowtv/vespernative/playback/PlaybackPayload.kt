package tv.onnowtv.vespernative.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.data.objects
import java.io.File
import java.util.UUID

/** Avoid large Binder intents: URLs/headers stay in a private, bounded cache. */
object PlaybackPayload {
    fun save(c:Context,title:Title,label:String,selected:Stream,streams:List<Stream>):String {
        val directory=File(c.cacheDir,"playback").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86_400_000 }?.forEach(File::delete)
        val queue=(streams+selected).distinctBy { it.url to it.headers }
        val payload=JSONObject().put("title",label).put("year",title.year).put("backdrop",title.backdrop)
            .put("poster",title.poster).put("logo",title.logo).put("rating",title.rating).put("runtime",title.runtime).put("type",title.type)
            .put("overview",title.description).put("genres",title.genres.joinToString(" · "))
            .put("streams",JSONArray(queue.map(Stream::json))).put("selected",queue.indexOfFirst { it.url==selected.url && it.headers==selected.headers })
        val text=payload.toString(); require(text.toByteArray().size<2_000_000) { "Stream selection is too large." }
        val id=UUID.randomUUID().toString(); File(directory,"$id.json").writeText(text); return id
    }
    fun read(c:Context,id:String?):JSONObject? {
        if(id==null || !id.matches(Regex("[a-f0-9-]{36}")))return null
        return runCatching { val file=File(c.cacheDir,"playback/$id.json"); require(file.length()<2_000_000); JSONObject(file.readText()) }.getOrNull()
    }
    fun streams(value:JSONObject?):List<Stream> = value?.optJSONArray("streams")?.objects().orEmpty().mapIndexedNotNull { i,s->Stream.parse(s,i) }
}