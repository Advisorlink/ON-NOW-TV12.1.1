package tv.onnowtv.vespernative.profile

import org.json.*
import tv.onnowtv.vespernative.data.objects
import tv.onnowtv.vespernative.data.text

data class HomeRow(val id:String,val label:String,val query:String="")
class HomeRowPreferences(raw:String) {
    private val json=runCatching{JSONObject(raw)}.getOrDefault(JSONObject())
    val custom=json.optJSONArray("custom")?.objects().orEmpty().mapNotNull {
        val id=it.text("id");val label=it.text("label");val query=it.text("query")
        if(id.startsWith("custom:") && query.isNotBlank() && label.isNotBlank())HomeRow(id,label.take(64),query.take(100))else null
    }.take(30).toMutableList()
    val rows get()=defaults+custom
    val order=(strings(json.optJSONArray("order")).filter{id->rows.any{it.id==id}}+rows.map{it.id}).distinct().toMutableList()
    val hidden=strings(json.optJSONArray("hidden")).filter{id->rows.any{it.id==id}}.toMutableSet()
    fun move(id:String,delta:Int){val from=order.indexOf(id);val to=from+delta;if(from>=0 && to in order.indices)java.util.Collections.swap(order,from,to)}
    fun toggle(id:String){if(!hidden.add(id))hidden.remove(id)}
    fun add(label:String){val id="custom:"+label.lowercase().replace(Regex("[^a-z0-9]+"),"-").trim('-');if(rows.none{it.id==id}){custom.add(HomeRow(id,label,label));order.add(id)}}
    fun remove(id:String){custom.removeAll{it.id==id};order.remove(id);hidden.remove(id)}
    fun toJson():String=JSONObject().put("order",JSONArray(order)).put("hidden",JSONArray(hidden.toList())).put("custom",JSONArray(custom.map{JSONObject().put("id",it.id).put("label",it.label).put("query",it.query)})).toString()
    companion object {
        val defaults=listOf(HomeRow("cw","Continue Watching"),HomeRow("foryou","Similar to what you love"),HomeRow("networks","Browse by Network"),HomeRow("studios","Studios"),HomeRow("movie-year","New movies"),HomeRow("series-year","New series"),HomeRow("movie-top","Popular movies"),HomeRow("series-top","Popular series"),HomeRow("upcoming","Upcoming movies"))
        private fun strings(array:JSONArray?)=(0 until (array?.length() ?: 0)).mapNotNull{array?.optString(it)?.takeIf(String::isNotBlank)}
    }
}