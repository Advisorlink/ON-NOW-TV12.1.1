package tv.onnow.launcher.net

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * ResilientHttp
 * ─────────────
 * Single shared OkHttpClient for every backend call the launcher
 * makes (config poll, ack-notification, register, activation,
 * activation gate check).
 *
 * Carries one piece of resilience that the device-registration
 * flow was missing before:
 *
 *   • If `Dns.SYSTEM.lookup(hostname)` throws UnknownHostException
 *     for our production host, we fall back to a HARDCODED
 *     production IP.  This keeps the launcher functional when
 *     DuckDNS's authoritative nameservers go down (which they
 *     periodically do — they returned SERVFAIL globally on
 *     26 Jun 2026 and bricked every box that relied on
 *     `onnowtv.duckdns.org` resolving), or when the TV box's
 *     router DNS doesn't carry the duckdns subdomain.
 *
 *   • OkHttp's DNS-override path keeps SNI + certificate
 *     validation correct (cert is still validated against
 *     `onnowtv.duckdns.org`, just the IP we connect to is the
 *     hardcoded one) — so we do NOT need a custom
 *     HostnameVerifier and the connection is no less secure than
 *     a standard HTTPS call.
 *
 * Update FALLBACK_IP if you ever migrate VPS providers.
 */
object ResilientHttp {

    private const val TAG = "ResilientHttp"

    /** Production hostname.  Used to gate the fallback so we don't
     *  accidentally point unrelated requests at our VPS. */
    private const val FALLBACK_HOST = "onnowtv.duckdns.org"

    /** Production VPS IPv4.  Update on VPS migration. */
    private const val FALLBACK_IP = "62.84.181.66"

    private val fallbackDns = Dns { hostname ->
        try {
            Dns.SYSTEM.lookup(hostname)
        } catch (uhe: UnknownHostException) {
            if (hostname.equals(FALLBACK_HOST, ignoreCase = true)) {
                Log.w(
                    TAG,
                    "System DNS failed for $hostname — falling back to hardcoded IP $FALLBACK_IP",
                )
                listOf(InetAddress.getByName(FALLBACK_IP))
            } else {
                throw uhe
            }
        }
    }

    /** Shared OkHttpClient.  Re-used by every caller so connection
     *  pooling and DNS cache are effective across the app. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(fallbackDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}
