package tv.vesper.app

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ON NOW TV Direct: the panel URL (London gateway) only answers with a 302 to a tokenised
 * relay URL (France) that serves the bytes. Every ExoPlayer open — start, MKV index read
 * at the end of the file, every seek — paid both TLS handshakes plus the redirect. Resolve
 * the relay once and send every open straight there; re-resolve after [TTL_MS] or on demand.
 */
@UnstableApi
class VodRelayResolver(client: OkHttpClient, private val userAgent: String) : ResolvingDataSource.Resolver {
    private val probe = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    @Volatile private var source: Uri? = null
    @Volatile private var relay: Uri? = null
    @Volatile private var resolvedAt = 0L

    fun invalidate() { relay = null }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme?.startsWith("http") != true) return dataSpec
        val cached = relay
        if (cached != null && source == uri && System.currentTimeMillis() - resolvedAt < TTL_MS) return dataSpec.withUri(cached)
        val target = runCatching {
            probe.newCall(
                Request.Builder().url(uri.toString()).header("User-Agent", userAgent).header("Range", "bytes=0-0").build(),
            ).execute().use { r -> if (r.code in 301..308) r.header("Location") else null }
        }.onFailure { Log.w(TAG, "relay resolve failed: ${it.message}") }.getOrNull()
        if (target.isNullOrBlank()) return dataSpec
        val resolved = Uri.parse(java.net.URI(uri.toString()).resolve(target).toString())
        source = uri; relay = resolved; resolvedAt = System.currentTimeMillis()
        Log.i(TAG, "Direct relay resolved → ${resolved.host}")
        return dataSpec.withUri(resolved)
    }

    companion object {
        private const val TAG = "VodRelayResolver"
        private const val TTL_MS = 10 * 60_000L
    }
}
