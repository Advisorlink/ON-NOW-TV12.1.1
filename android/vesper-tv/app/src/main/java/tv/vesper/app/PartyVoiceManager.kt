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
    private val selfMemberId: String,
    private val selfDisplayName: String,
    private val selfAvatarId: String,
    private val selfAvatarEmoji: String,
    initialMembersJson: String?,
) {
    companion object { private const val TAG = "PartyVoice" }

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
    enum class RecState { Idle, Recording, Transcribing, Blocked, Error }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _members      = MutableStateFlow(parseMembers(initialMembersJson))
    private val _bubbles      = MutableStateFlow<List<VoiceBubble>>(emptyList())
    private val _recState     = MutableStateFlow(RecState.Idle)
    private val _wsConnected  = MutableStateFlow(false)

    val members: StateFlow<List<Member>>          = _members.asStateFlow()
    val bubbles: StateFlow<List<VoiceBubble>>     = _bubbles.asStateFlow()
    val recState: StateFlow<RecState>             = _recState.asStateFlow()
    val wsConnected: StateFlow<Boolean>           = _wsConnected.asStateFlow()
    val selfMemberIdValue: String                 = selfMemberId

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
        } catch (e: Exception) {
            Log.w(TAG, "ws connect failed", e)
        }
    }

    private fun handleWsMessage(raw: String) {
        val msg = JSONObject(raw)
        when (msg.optString("type")) {
            "joined" -> {
                // Update self id if server gave us one
                val mid = msg.optString("member_id", "")
                // Member roster handling — keep what we have for now.
                val _unused = mid  // suppressed-unused warning
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

    // ── MediaRecorder ─────────────────────────────────────────────
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordJob: Job? = null
    private var recordStartedAt: Long = 0

    fun startRecording() {
        if (_recState.value != RecState.Idle) return
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
            rec.setAudioSamplingRate(16000)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(32_000)
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
            _recState.value = if (e.message?.contains("permission", true) == true)
                RecState.Blocked else RecState.Error
            scope.launch {
                kotlinx.coroutines.delay(2200L)
                _recState.value = RecState.Idle
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
            try { file.delete() } catch (_: Exception) {}
            _recState.value = RecState.Idle
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
        withContext(Dispatchers.Main) { _recState.value = RecState.Transcribing }
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
                .url("$backendBase/api/stt/transcribe")
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            val text = if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                (json.optString("text", "") ?: "").trim()
            } else ""
            resp.close()
            try { file.delete() } catch (_: Exception) {}

            if (text.isBlank()) {
                withContext(Dispatchers.Main) { _recState.value = RecState.Idle }
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload/transcribe failed", e)
            try { file.delete() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                _recState.value = RecState.Error
                kotlinx.coroutines.delay(2200L)
                _recState.value = RecState.Idle
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
