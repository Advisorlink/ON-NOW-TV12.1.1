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
 * activation gate check, APK installs, image loads).
 *
 * v2.10.54 — Cloudflare migration.  The launcher now knows about
 * TWO production hostnames:
 *
 *   • Primary  : `onnowhub.com`        — Cloudflare-fronted,
 *                                         99.99% uptime SLA,
 *                                         edge-cached APK downloads.
 *   • Fallback : `onnowtv.duckdns.org` — DuckDNS (legacy), kept
 *                                         alive so existing-fleet
 *                                         launchers still resolve.
 *
 * On a `UnknownHostException` for EITHER hostname, OkHttp's custom
 * DNS resolver falls back to a hardcoded VPS IP (the same Contabo
 * box answers requests for both hostnames via the nginx
 * server_name).  SNI + certificate validation stay correct because
 * OkHttp's `dns()` override only changes the IP we connect to, not
 * the hostname OkHttp uses for SNI/cert checking.
 *
 * Outcome: connectivity to the launcher backend fails only if
 *  (a) all three of `onnowhub.com`, `onnowtv.duckdns.org` AND
 *      `62.84.181.66` are unreachable simultaneously — physically
 *      impossible unless the VPS itself is down, OR
 *  (b) the device has no internet at all.
 *
 * Update FALLBACK_IP if you ever migrate VPS providers.
 */
object ResilientHttp {

    private const val TAG = "ResilientHttp"

    /** Production hostnames we know how to fall back for.
     *  Both A-records point to FALLBACK_IP; both are valid SANs on
     *  the nginx TLS cert. */
    private val FALLBACK_HOSTS: Set<String> = setOf(
        "onnowhub.com",
        "www.onnowhub.com",
        "onnowtv.duckdns.org",
    )

    /** Production VPS IPv4 (Contabo, Germany).  Update on
     *  VPS migration. */
    private const val FALLBACK_IP = "62.84.181.66"

    private val fallbackDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                Dns.SYSTEM.lookup(hostname)
            } catch (uhe: UnknownHostException) {
                val match = FALLBACK_HOSTS.firstOrNull {
                    it.equals(hostname, ignoreCase = true)
                }
                if (match != null) {
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
    }

    /** Shared OkHttpClient.  Re-used by every caller so connection
     *  pooling and DNS cache are effective across the app. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(fallbackDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Helps when the first hostname resolves but the connection
        // itself fails (e.g. transient Cloudflare edge issue) —
        // OkHttp retries the request once before surfacing the error.
        .retryOnConnectionFailure(true)
        .build()
}
