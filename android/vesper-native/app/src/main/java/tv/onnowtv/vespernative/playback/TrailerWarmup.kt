package tv.onnowtv.vespernative.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.onnowtv.vespernative.data.Title
import java.util.concurrent.atomic.AtomicBoolean

data class PreparedTrailer(val keys:Deferred<List<String>>,val media:Deferred<TrailerMedia?>,val createdAt:Long=System.currentTimeMillis(),val reusable:AtomicBoolean=AtomicBoolean(true))

/** Screen-owned, bounded and silent: metadata/URLs only, never a background decoder
 * or NewPipe task. The single native extraction worker stays available for focus.
 */
class TrailerWarmup(private val scope:CoroutineScope,private val repo:TrailerRepository) {
    private val slots=Semaphore(2)
    private val pending=linkedMapOf<String,PreparedTrailer>()
    private var active:String?=null
    val pendingCount get()=pending.size
    fun prime(titles:List<Title>,foreground:Boolean=false){
        val items=titles.distinctBy(Title::key).take(3)
        if(foreground)active=items.firstOrNull()?.key
        if(!foreground && active!=null && items.none{it.key==active})return
        val keep=(items.map{it.key}+listOfNotNull(active)).toSet()
        pending.keys.filter{it !in keep}.forEach{key->pending.remove(key)?.let{it.keys.cancel();it.media.cancel()}}
        items.forEach(::request)
    }
    fun focus(title:Title):PreparedTrailer{active=title.key;return request(title)}
    private fun request(title:Title):PreparedTrailer {
        pending[title.key]?.takeIf{it.reusable.get() && !it.keys.isCancelled && !it.media.isCancelled && System.currentTimeMillis()-it.createdAt<120_000}?.let{return it}
        invalidate(title)
        while(pending.size>=3){val key=pending.keys.firstOrNull{it!=active} ?: break;pending.remove(key)?.let{it.keys.cancel();it.media.cancel()}}
        val reusable=AtomicBoolean(true)
        val keys=scope.async {(try{withTimeoutOrNull(10_000){slots.withPermit{repo.keys(title)}}.orEmpty()}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}).also{if(it.isEmpty())reusable.set(false)}}
        val media=scope.async {
            (repo.cached(title) ?: keys.await().firstOrNull()?.let{key->slots.withPermit{repo.backend(key)}}?.also{repo.remember(title,it)}).also{if(it==null)reusable.set(false)}
        }
        return PreparedTrailer(keys,media,reusable=reusable).also{pending[title.key]=it}
    }
    fun clear(){pending.values.forEach{it.keys.cancel();it.media.cancel()};pending.clear();active=null}
    fun invalidate(title:Title){pending.remove(title.key)?.let{it.keys.cancel();it.media.cancel()}}
}