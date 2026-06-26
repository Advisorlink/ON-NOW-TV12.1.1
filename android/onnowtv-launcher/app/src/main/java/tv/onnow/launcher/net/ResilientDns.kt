package tv.onnow.launcher.net

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * ResilientDns
 * ────────────
 * Drop-in replacement for OkHttp's default DNS resolver.  Tries
 * the device's system resolver first (fast and zero-cost), and
 * only falls back to **DNS-over-HTTPS via Cloudflare 1.1.1.1**
 * when the system resolver returns `UnknownHostException` /
 * `NXDOMAIN`.
 *
 * Why this exists (v2.10.53-d):
 *   • Some TV boxes ship with a router DNS that doesn't carry
 *     DuckDNS subdomain records (or has them in a stale NXDOMAIN
 *     negative cache).  The user sees
 *     "Unable to resolve host 'onnowtv.duckdns.org'" on the
 *     Launcher device-registration screen and is hard-stuck.
 *   • DoH over `https://1.1.1.1/dns-query` works in those cases
 *     because Cloudflare's resolvers always carry the record and
 *     the request travels over HTTPS (so router DNS hijacks /
 *     NXDOMAIN poisoning don't apply).
 *   • The bootstrap client uses HARD-CODED Cloudflare IPs so the
 *     fallback itself doesn't depend on DNS.
 *
 * Returned addresses are cached in-process for 5 minutes so we
 * don't hit Cloudflare on every single HTTP call.
 */
class ResilientDns(
    /** Optional caller-supplied DoH endpoint override. */
    private val dohUrl: String = "https://1.1.1.1/dns-query",
) : Dns {

    private val systemDns: Dns = Dns.SYSTEM
    private val cache = ConcurrentHashMap<String, CachedResult>()

    private data class CachedResult(val addrs: List<InetAddress>, val expiresAt: Long)

    private val dohClient: DnsOverHttps by lazy {
        // Bootstrap OkHttpClient that ONLY talks to Cloudflare's
        // hard-coded IPs — never asks the system resolver for
        // 1.1.1.1 / 1.0.0.1 (they ARE the resolver after all).
        val bootstrap = OkHttpClient.Builder().build()
        DnsOverHttps.Builder()
            .client(bootstrap)
            .url(dohUrl.toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("2606:4700:4700::1001"),
            )
            .includeIPv6(false) // Most TV boxes are IPv4-only.
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // ── L1: in-process cache (5 min TTL) ──────────────────
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (it.expiresAt > now) return it.addrs }

        // ── L2: system DNS ────────────────────────────────────
        runCatching { systemDns.lookup(hostname) }
            .onSuccess { addrs ->
                cache[hostname] = CachedResult(addrs, now + CACHE_TTL_MS)
                return addrs
            }
            .onFailure { Log.w(TAG, "system DNS failed for $hostname: ${it.message}") }

        // ── L3: DNS-over-HTTPS via Cloudflare ─────────────────
        return try {
            val addrs = dohClient.lookup(hostname)
            if (addrs.isEmpty()) throw UnknownHostException("no DoH records for $hostname")
            Log.i(TAG, "resolved $hostname via DoH → ${addrs.first().hostAddress}")
            cache[hostname] = CachedResult(addrs, now + CACHE_TTL_MS)
            addrs
        } catch (uhe: UnknownHostException) {
            throw uhe
        } catch (t: Throwable) {
            throw UnknownHostException(
                "system DNS + DoH both failed for $hostname: ${t.message}",
            )
        }
    }

    companion object {
        private const val TAG = "ResilientDns"
        private const val CACHE_TTL_MS = 5L * 60L * 1000L  // 5 minutes
    }
}
