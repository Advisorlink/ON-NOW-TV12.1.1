package tv.onnowtv.vespernative.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Opening bytes of nearby trailers are fetched while browsing, so the first frame is local. */
object TrailerCache {
    @Volatile private var cache:SimpleCache?=null
    private val client by lazy { OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8,3,TimeUnit.MINUTES)).build() }
    private val slots=Semaphore(2)
    private fun get(c:Context):SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(c.cacheDir,"trailer-media"),LeastRecentlyUsedCacheEvictor(160L shl 20),StandaloneDatabaseProvider(c)).also { cache=it }
    }
    fun factory(c:Context):CacheDataSource.Factory = CacheDataSource.Factory().setCache(get(c.applicationContext))
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client).setUserAgent(androidx.media3.common.MediaLibraryInfo.VERSION_SLASHY)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    suspend fun preload(c:Context,media:TrailerMedia) = withContext(Dispatchers.IO) { slots.withPermit {
        listOf(media.video to 1_500_000L,media.audio to 350_000L).filter { it.first.startsWith("http") }.forEach { (url,bytes) ->
            ensureActive()
            val writer=CacheWriter(factory(c).createDataSource(),DataSpec.Builder().setUri(url).setPosition(0).setLength(bytes).build(),null,null)
            val cancel=coroutineContext[Job]?.invokeOnCompletion { writer.cancel() }
            try { runCatching { writer.cache() } } finally { cancel?.dispose() }
        }
    } }
}
