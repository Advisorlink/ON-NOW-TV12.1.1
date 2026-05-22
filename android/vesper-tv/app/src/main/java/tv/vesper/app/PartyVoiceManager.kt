package tv.vesper.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v2.7.60 — Native Watch Together voice manager for ExoPlayerActivity.
 *
 * Owns:
 *   • An OkHttp WebSocket to /api/watch-party/ws/{code}
 *   • MediaRecorder for 10 s mic captures
 *   • Multipart POST upload to /api/stt/transcribe
 *   • Broadcast of `voice_message` over the WS
 *   • Inbound bubble state (a StateFlow Compose collects)
 *
 * Lifecycle: instantiated in `ExoPlayerActivity.onCreate`, released in
 * `onDestroy`.  All state flows are safe to read from Compose.
 */
class PartyVoiceManager(
    private val ctx: Context,
    private val partyCode: String,
    private val partyWsUrl: String,
    private val backendBase: String,         // e.g. https://rebrand-app-5.preview…
    initialMemberId: String,
    private val selfDisplayName: String,
    private val selfAvatarId: String,
    private val selfAvatarEmoji: String,
    initialMembersJson: String?,
) {
    companion object { private const val TAG = "PartyVoice" }

    // v2.7.69 — selfMemberId is mutable because the server may
    // re-assign us a new one in the "joined" payload (especially
    // if the React Detail page lost its session before launching
    // the native player).  If we don't track the assigned id, the
    // server echoes our own reactions back as if from another
    // member and emojis "go crazy" with duplicates.
    @Volatile
    private var selfMemberId: String = initialMemberId

    /** State an external observer (Compose overlay) collects. */
    data class Member(
        val id: String,
        val name: String,
        val avatar: String,
        val avatarEmoji: String,
    )
    data class VoiceBubble(
        val id: String,
        val text: String,
        val senderName: String,
        val senderAvatarEmoji: String,
        val mine: Boolean,
        val createdAt: Long,
    )
    /** v2.7.67 — floating emoji reaction (ArrowUp/Down/Left/Right hold). */
    data class Reaction(
        val id: String,
        val emoji: String,
        val lane: Int,        // 0..7 — horizontal column for the float animation
        val senderName: String,
        val createdAt: Long,
    )
    enum class RecState { Idle, Recording, Transcribing, Blocked, Error }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _members      = MutableStateFlow(parseMembers(initialMembersJson))
    private val _bubbles      = MutableStateFlow<List<VoiceBubble>>(emptyList())
    private val _reactions    = MutableStateFlow<List<Reaction>>(emptyList())
    private val _recState     = MutableStateFlow(RecState.Idle)
    private val _wsConnected  = MutableStateFlow(false)
    // v2.7.73 — host's current playback position, sourced from the
    // server's `state` broadcasts.  The guest's "Catch up" button
    // seeks the local player to this value so the room re-syncs.
    private val _hostPositionMs = MutableStateFlow(0L)
    // v2.7.64 — surface the actual error so the UI can show *why*
    // transcription failed instead of a generic "TRY AGAIN".  Kept as
    // a short human-readable string (≤ 80 chars), cleared back to ""
    // when state returns to Idle.
    private val _lastError    = MutableStateFlow("")

    val members: StateFlow<List<Member>>          = _members.asStateFlow()
    val bubbles: StateFlow<List<VoiceBubble>>     = _bubbles.asStateFlow()
    val reactions: StateFlow<List<Reaction>>      = _reactions.asStateFlow()
    val recState: StateFlow<RecState>             = _recState.asStateFlow()
    val wsConnected: StateFlow<Boolean>           = _wsConnected.asStateFlow()
    val hostPositionMs: StateFlow<Long>           = _hostPositionMs.asStateFlow()
    val lastError: StateFlow<String>              = _lastError.asStateFlow()
    val selfMemberIdValue: String
        get() = selfMemberId

    // ── WebSocket ─────────────────────────────────────────────────
    private var ws: WebSocket? = null

    fun connect() {
        try {
            val req = Request.Builder().url(partyWsUrl).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _wsConnected.value = true
                    // Send a 'hello' so the server registers us.
                    val hello = JSONObject().apply {
                        put("type", "hello")
                        put("role", "guest")  // any role works for voice
                        put("member_id", selfMemberId)
                        put("name", selfDisplayName)
                        put("avatar", selfAvatarId)
                    }
                    try { webSocket.send(hello.toString()) } catch (_: Exception) {}
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try { handleWsMessage(text) } catch (e: Exception) {
                        Log.w(TAG, "ws parse failed", e)
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _wsConnected.value = false
                    Log.w(TAG, "ws failure: ${t.message}")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _wsConnected.value = false
                }
            })
            // v2.7.68 — Pre-warm the HTTPS connection to /api/stt so
            // the first transcribe POST doesn't pay the TLS-handshake
            // + DNS-resolve cost (~600 ms on the HK1 box).  We hit
            // /api/ with a HEAD request and discard the response.
            scope.launch {
                try {
                    val baseFromWs = when {
                        partyWsUrl.startsWith("wss://") ->
                            "https://" + partyWsUrl.removePrefix("wss://").substringBefore("/")
                        partyWsUrl.startsWith("ws://") ->
                            "http://"  + partyWsUrl.removePrefix("ws://").substringBefore("/")
                        else -> ""
                    }
                    if (baseFromWs.isNotBlank()) {
                        val warmReq = Request.Builder()
                            .url("$baseFromWs/api/")
                            .head()
                            .build()
                        client.newCall(warmReq).execute().close()
                        Log.i(TAG, "STT connection pre-warmed: $baseFromWs")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "STT prewarm failed (non-fatal)", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ws connect failed", e)
        }
    }

    private fun handleWsMessage(raw: String) {
        val msg = JSONObject(raw)
        when (msg.optString("type")) {
            "joined" -> {
                // v2.7.69 — adopt the server-assigned member_id so
                // our self-echo filter actually filters our own
                // broadcasts.  Previously we stored the assignment
                // and threw it away, which caused every reaction we
                // sent to come back as if from a stranger and the
                // emoji panel duplicated each tap.
                val mid = msg.optString("member_id", "")
                if (mid.isNotBlank() && mid != selfMemberId) {
                    Log.i(TAG, "selfMemberId updated by server: $selfMemberId -> $mid")
                    selfMemberId = mid
                }
            }
            "members" -> {
                _members.value = parseMembers(msg.toString())
            }
            "voice_message" -> {
                val text = msg.optString("text", "")
                if (text.isBlank()) return
                val member = msg.optJSONObject("member")
                val senderId = member?.optString("id", "") ?: ""
                if (senderId == selfMemberId) return  // already echoed locally
                val bubble = VoiceBubble(
                    id = "v-${System.currentTimeMillis()}-${(0..999).random()}",
                    text = text.take(160),
                    senderName = member?.optString("name", "") ?: "",
                    senderAvatarEmoji = member?.optString("avatar_emoji", "") ?: "",
                    mine = false,
                    createdAt = System.currentTimeMillis(),
                )
                pushBubble(bubble)
            }
            "reaction" -> {
                // v2.7.67 — incoming emoji from any party member.
                val emoji = msg.optString("emoji", "")
                if (emoji.isBlank()) return
                val member = msg.optJSONObject("member")
                val senderId = member?.optString("id", "") ?: ""
                // Primary self-echo filter: server-assigned member id
                // matches ours (kept in sync via the "joined" handler).
                if (senderId.isNotBlank() && senderId == selfMemberId) return
                // v2.7.69 — backstop filter.  Some server builds omit
                // `member.id` on broadcast or send a placeholder.  If
                // we just locally fired this exact emoji within the
                // last 1.5 s, assume the inbound is our own echo.
                val now = System.currentTimeMillis()
                if (now - lastLocalEmojiAt < 1500L && lastLocalEmoji == emoji) return
                pushReaction(
                    emoji = emoji,
                    senderName = member?.optString("name", "") ?: "",
                )
            }
            "state" -> {
                // v2.7.73 — track the host's authoritative playback
                // position so the guest's "Catch up" button can seek
                // the local player to it.
                val pos = msg.optLong("position_ms", -1L)
                if (pos >= 0L) _hostPositionMs.value = pos
            }
            else -> { /* state / reaction / pong — not our concern */ }
        }
    }

    private fun parseMembers(json: String?): List<Member> {
        if (json.isNullOrBlank()) {
            return listOf(Member(selfMemberId, selfDisplayName, selfAvatarId, selfAvatarEmoji))
        }
        return try {
            val arr = if (json.trim().startsWith("[")) {
                JSONArray(json)
            } else {
                JSONObject(json).optJSONArray("members") ?: JSONArray()
            }
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Member(
                    id     = o.optString("id", ""),
                    name   = o.optString("name", ""),
                    avatar = o.optString("avatar", "a1"),
                    avatarEmoji = o.optString("avatar_emoji", ""),
                )
            }.filter { it.id.isNotBlank() }
                .ifEmpty {
                    listOf(Member(selfMemberId, selfDisplayName, selfAvatarId, selfAvatarEmoji))
                }
        } catch (e: Exception) {
            Log.w(TAG, "parseMembers failed", e)
            listOf(Member(selfMemberId, selfDisplayName, selfAvatarId, selfAvatarEmoji))
        }
    }

    private fun pushBubble(b: VoiceBubble) {
        _bubbles.value = _bubbles.value + b
        scope.launch {
            kotlinx.coroutines.delay(8200L)
            _bubbles.value = _bubbles.value.filterNot { it.id == b.id }
        }
    }

    // v2.7.67 — Reactions: short-lived floating emoji from any party member.
    private val reactionLaneCounter = java.util.concurrent.atomic.AtomicInteger(0)
    // v2.7.69 — backstop self-echo dedupe.  Remembers the most
    // recent emoji we broadcast and its timestamp, so server echoes
    // missing a `member.id` can still be filtered out.
    @Volatile private var lastLocalEmoji: String = ""
    @Volatile private var lastLocalEmojiAt: Long = 0L
    private fun pushReaction(emoji: String, senderName: String) {
        val id = "r-${System.currentTimeMillis()}-${(0..9999).random()}"
        val lane = (reactionLaneCounter.getAndIncrement() % 7)
        val r = Reaction(
            id = id,
            emoji = emoji,
            lane = lane,
            senderName = senderName,
            createdAt = System.currentTimeMillis(),
        )
        _reactions.value = _reactions.value + r
        scope.launch {
            // v2.7.69 — keep reactions alive for the full 7 s float
            // animation + a small buffer so they don't pop off mid-fade.
            kotlinx.coroutines.delay(7500L)
            _reactions.value = _reactions.value.filterNot { it.id == id }
        }
    }

    /** Called by ExoPlayerActivity when the user taps a D-pad arrow. */
    fun sendReaction(emoji: String) {
        if (emoji.isBlank()) return
        // v2.7.69 — record for the backstop self-echo dedupe in
        // case the server doesn't tag broadcasts with our member id.
        lastLocalEmoji = emoji
        lastLocalEmojiAt = System.currentTimeMillis()
        val out = JSONObject().apply {
            put("type", "reaction")
            put("emoji", emoji)
            put("avatar_emoji", selfAvatarEmoji)
        }
        try { ws?.send(out.toString()) } catch (_: Exception) {}
        // Local echo so the sender sees their own emoji instantly.
        pushReaction(emoji = emoji, senderName = selfDisplayName.ifBlank { "You" })
    }

    // ── MediaRecorder ─────────────────────────────────────────────
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordJob: Job? = null
    private var recordStartedAt: Long = 0

    fun startRecording() {
        if (_recState.value != RecState.Idle) return
        // v2.7.66 — explicit RECORD_AUDIO check.  Without this the
        // MediaRecorder.start() below throws an opaque IllegalStateException
        // on Android 6+ devices that haven't been granted the permission,
        // which is exactly what was happening on the HK1 box: the manifest
        // declared the permission but no activity ever asked the user to
        // grant it.  We now surface a clear "MIC PERMISSION" pill so the
        // root cause is visible on the TV.
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "startRecording aborted: RECORD_AUDIO not granted")
            scope.launch {
                _lastError.value = "MIC PERMISSION"
                _recState.value = RecState.Blocked
                kotlinx.coroutines.delay(10_000L)
                _recState.value = RecState.Idle
                _lastError.value = ""
            }
            return
        }
        try {
            val outFile = File.createTempFile("voice-", ".m4a", ctx.cacheDir)
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // v2.7.70 — Quality restored.  v2.7.68 dropped this to
            // 8 kHz / 16 kbps to halve upload size, but that mangled
            // Whisper's accuracy because the model is *trained* on
            // 16 kHz audio.  The user reported it "making up what it
            // hears".  Back to broadcast-quality voice: 24 kHz /
            // 48 kbps mono AAC — still small (~60 KB for 10 s, well
            // under 100 KB even on slow uploads) and crystal clear.
            rec.setAudioSamplingRate(24000)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(48_000)
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordFile = outFile
            recordStartedAt = System.currentTimeMillis()
            _recState.value = RecState.Recording
            // 10s ceiling
            recordJob = scope.launch {
                kotlinx.coroutines.delay(10_000L)
                if (_recState.value == RecState.Recording) {
                    stopRecording()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "startRecording failed", e)
            cleanupRecorder()
            val msgRaw = e.message.orEmpty()
            val isPerm = msgRaw.contains("permission", true)
            val short = if (isPerm) "MIC PERMISSION"
                        else "MIC INIT: ${e.javaClass.simpleName}".take(40)
            scope.launch {
                _lastError.value = short
                _recState.value = if (isPerm) RecState.Blocked else RecState.Error
                kotlinx.coroutines.delay(10_000L)
                _recState.value = RecState.Idle
                _lastError.value = ""
            }
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        val file = recordFile
        val elapsed = System.currentTimeMillis() - recordStartedAt
        recordJob?.cancel()
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
        recorder = null
        recordFile = null
        if (file == null) {
            _recState.value = RecState.Idle
            return
        }
        if (elapsed < 400 || file.length() < 800) {
            Log.w(TAG, "STT dropped: recording too short (elapsed=${elapsed}ms size=${file.length()}B)")
            try { file.delete() } catch (_: Exception) {}
            scope.launch {
                _lastError.value = "TOO SHORT"
                _recState.value = RecState.Error
                kotlinx.coroutines.delay(1800L)
                _recState.value = RecState.Idle
                _lastError.value = ""
            }
            return
        }
        // Upload + transcribe in background
        scope.launch { uploadAndBroadcast(file) }
    }

    private fun cleanupRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { recordFile?.delete() } catch (_: Exception) {}
        recordFile = null
        recordJob?.cancel()
        recordJob = null
    }

    private suspend fun uploadAndBroadcast(file: File) {
        withContext(Dispatchers.Main) {
            _recState.value = RecState.Transcribing
            _lastError.value = ""
        }
        // v2.7.64 — Build the transcribe URL explicitly with safety net.
        // If we somehow got an empty backendBase (intent extra missing,
        // unexpected ws URL format), derive https origin from partyWsUrl
        // directly so we never POST to a relative URL.
        val postUrl: String = run {
            val base = backendBase.trim().trimEnd('/')
            if (base.startsWith("https://") || base.startsWith("http://")) {
                "$base/api/stt/transcribe"
            } else {
                // Salvage: parse partyWsUrl ourselves.
                val ws = partyWsUrl.trim()
                val origin = when {
                    ws.startsWith("wss://") -> "https://" + ws.removePrefix("wss://").substringBefore("/")
                    ws.startsWith("ws://")  -> "http://"  + ws.removePrefix("ws://").substringBefore("/")
                    else -> ""
                }
                if (origin.isBlank()) "" else "$origin/api/stt/transcribe"
            }
        }
        Log.i(TAG, "STT upload starting: file=${file.length()}B postUrl=$postUrl backendBase=$backendBase wsUrl=$partyWsUrl")
        if (postUrl.isBlank()) {
            Log.e(TAG, "STT abort: no transcribe URL could be derived (backendBase='$backendBase', partyWsUrl='$partyWsUrl')")
            try { file.delete() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                _lastError.value = "NO BACKEND URL"
                _recState.value = RecState.Error
                kotlinx.coroutines.delay(10_000L)
                _recState.value = RecState.Idle
                _lastError.value = ""
            }
            return
        }
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    "voice.m4a",
                    file.asRequestBody("audio/mp4".toMediaType()),
                )
                .build()
            val req = Request.Builder()
                .url(postUrl)
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            val code = resp.code
            val raw = try { resp.body?.string().orEmpty() } catch (_: Exception) { "" }
            resp.close()
            try { file.delete() } catch (_: Exception) {}
            Log.i(TAG, "STT response: code=$code bodyLen=${raw.length} bodyHead=${raw.take(200)}")

            if (code !in 200..299) {
                val short = "HTTP $code"
                withContext(Dispatchers.Main) {
                    _lastError.value = short
                    _recState.value = RecState.Error
                    kotlinx.coroutines.delay(10_000L)
                    _recState.value = RecState.Idle
                    _lastError.value = ""
                }
                return
            }
            val text = try {
                JSONObject(raw).optString("text", "").trim()
            } catch (e: Exception) {
                Log.w(TAG, "STT body parse failed", e)
                ""
            }
            if (text.isBlank()) {
                withContext(Dispatchers.Main) {
                    _lastError.value = "NO SPEECH"
                    _recState.value = RecState.Error
                    kotlinx.coroutines.delay(10_000L)
                    _recState.value = RecState.Idle
                    _lastError.value = ""
                }
                return
            }
            // Broadcast on WS
            val out = JSONObject().apply {
                put("type", "voice_message")
                put("text", text)
                put("avatar_emoji", selfAvatarEmoji)
            }
            try { ws?.send(out.toString()) } catch (_: Exception) {}
            // Local echo
            val mine = VoiceBubble(
                id = "v-self-${System.currentTimeMillis()}",
                text = text.take(160),
                senderName = selfDisplayName.ifBlank { "You" },
                senderAvatarEmoji = selfAvatarEmoji,
                mine = true,
                createdAt = System.currentTimeMillis(),
            )
            withContext(Dispatchers.Main) {
                pushBubble(mine)
                _recState.value = RecState.Idle
                _lastError.value = ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "STT upload/transcribe threw: ${e.javaClass.simpleName}: ${e.message}", e)
            try { file.delete() } catch (_: Exception) {}
            val short = "${e.javaClass.simpleName}: ${(e.message ?: "").take(60)}"
            withContext(Dispatchers.Main) {
                _lastError.value = short
                _recState.value = RecState.Error
                kotlinx.coroutines.delay(10_000L)
                _recState.value = RecState.Idle
                _lastError.value = ""
            }
        }
    }

    fun release() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        cleanupRecorder()
        try { scope.cancel() } catch (_: Exception) {}
    }
}
