package tv.onnow.launcher.remote

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * Embedded LAN web-remote server — the "WiFi direct" path.
 *
 * When the phone and the box share a network, the cloud pair response
 * hands the phone this server's `http://<lan-ip>:<port>` address and
 * the phone's browser redirects itself here.  The page (`/remote`) is
 * served straight off the box and every button press arrives over a
 * SAME-ORIGIN WebSocket (`/ws?code=`) — no internet round-trip at all,
 * so a D-pad press lands in ~5-20 ms.
 *
 * The HTML is a cached copy of the cloud `remote_page.html`
 * (refreshed by RemoteControlService on every session start) so design
 * updates never need an APK release.
 */
class LocalRemoteServer(
    port: Int,
    private val expectedCode: String,
    private val pageFile: File,
    private val cloudBaseUrl: String,
    private val onInput: (JSONObject) -> Unit,
) : NanoWSD(port) {

    companion object { private const val TAG = "LocalRemote" }

    private val clients = Collections.synchronizedSet(mutableSetOf<PhoneSocket>())
    @Volatile private var lastState: String? = null

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = PhoneSocket(handshake)

    override fun serveHttp(session: IHTTPSession): Response {
        return when (session.uri) {
            "/remote", "/" -> {
                val html = try {
                    if (pageFile.exists()) pageFile.readText() else null
                } catch (_: Throwable) { null }
                if (html != null) {
                    newFixedLengthResponse(Response.Status.OK, "text/html", html)
                } else {
                    newFixedLengthResponse(
                        Response.Status.REDIRECT, "text/plain", ""
                    ).apply { addHeader("Location", "$cloudBaseUrl/remote") }
                }
            }
            "/ping" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            // Branding/manifest assets referenced relatively by the
            // page — bounce LAN-mode phones to the cloud copies.
            "/remote-icon-192.png", "/remote-icon-512.png", "/remote.webmanifest" ->
                newFixedLengthResponse(
                    Response.Status.REDIRECT, "text/plain", ""
                ).apply { addHeader("Location", "$cloudBaseUrl${session.uri}") }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not_found")
        }
    }

    /** Push a state JSON (now-playing / keyboard) to every connected phone. */
    fun broadcastState(json: String) {
        lastState = json
        val snapshot = synchronized(clients) { clients.toList() }
        for (c in snapshot) {
            try { c.send(json) } catch (_: Throwable) { clients.remove(c) }
        }
    }

    fun hasClients(): Boolean = clients.isNotEmpty()

    inner class PhoneSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private val authed: Boolean =
            handshake.parameters["code"]?.firstOrNull() == expectedCode

        override fun onOpen() {
            if (!authed) {
                try {
                    close(WebSocketFrame.CloseCode.PolicyViolation, "bad_code", false)
                } catch (_: Throwable) {}
                return
            }
            clients.add(this)
            Log.i(TAG, "phone connected over LAN (${clients.size} client(s))")
            lastState?.let { try { send(it) } catch (_: Throwable) {} }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean,
        ) {
            clients.remove(this)
        }

        override fun onMessage(message: WebSocketFrame) {
            if (!authed) return
            try {
                val msg = JSONObject(message.textPayload)
                if (msg.optString("action") == "ping") {
                    val reply = JSONObject().put("type", "pong")
                    if (msg.has("t")) reply.put("t", msg.optDouble("t"))
                    send(reply.toString())
                    return
                }
                onInput(msg)
            } catch (t: Throwable) {
                Log.w(TAG, "bad ws message", t)
            }
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }
}
