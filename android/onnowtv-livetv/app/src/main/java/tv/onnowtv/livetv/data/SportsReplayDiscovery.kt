package tv.onnowtv.livetv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8

/** One account-scoped, resumable foreground index, not a scan restarted for every sport. */
object SportsReplayDiscovery {
    class State(val scope: String, val channels: List<Channel>) {
        var cursor = 0
        var errors = 0
        var probes = 0
        var failedAt = 0L
        var phase = "Reading archive listings"
        val candidates = linkedMapOf<String, SportsReplayCatalogue.Entry>()
        val checked = mutableSetOf<String>()
        val verified = linkedMapOf<String, SportsReplayCatalogue.Entry>()
        fun availableSports(): List<String> = LiveSportsClassifier.DISPLAY_ORDER.filter { sport -> verified.values.any { it.bucket == sport } }
        fun pending(sport: String? = null): List<SportsReplayCatalogue.Entry> = candidates.values.filter { it.key !in checked && (sport == null || it.bucket == sport) }
        fun hasMore(sport: String? = null): Boolean = cursor < channels.size || pending(sport).isNotEmpty()
    }
    private var saved: State? = null
    fun acquire(channels: List<Channel>, force: Boolean = false): State {
        val scope = channels.sortedBy { it.id }.joinToString("\n") { "${it.id}|${it.streamUrl}|${it.archiveEnabled}|${it.archiveDays}" }.encodeUtf8().sha256().hex()
        val state = saved?.takeIf { !force && it.scope == scope } ?: State(scope, channels).also { saved = it }
        prune(state)
        return state
    }
    fun prune(state: State) {
        val now = System.currentTimeMillis()
        val expiredCandidates = state.candidates.filterValues { it.programme.startMs < now - 7 * 86_400_000L || !SportsReplayPolicy.checkProgramme(it.channel, it.programme, now).eligible }.keys
        expiredCandidates.forEach { state.candidates.remove(it); state.checked.remove(it); state.verified.remove(it) }
        val expired = state.verified.filterValues { !SportsReplayPolicy.checkProgramme(it.channel, it.programme, now).eligible || !SportsReplayRepository.isVerified(it.channel, it.programme) }.keys
        expired.forEach { state.verified.remove(it); state.checked.remove(it) }
        if (now - state.failedAt > 60_000) { state.checked.retainAll(state.verified.keys); state.failedAt = now }
    }
    suspend fun seed(state: State, guide: Map<String, List<Programme>>, channels: List<Channel> = state.channels.take(3)) {
        // This is an optional cache optimisation, never a whole-guide gate before the screen can load.
        val entries = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            channels.take(3).flatMap { channel ->
                currentCoroutineContext().ensureActive()
                val rows = guide[channel.epgChannelId ?: channel.id].orEmpty().asSequence().take(96)
                    .filter { it.startMs >= now - 7 * 86_400_000L && it.stopMs <= now }.take(16).toList()
                SportsReplayCatalogue.entries(channel, rows)
            }
        }
        add(state, entries)
    }
    private fun add(state: State, entries: List<SportsReplayCatalogue.Entry>) {
        entries.forEach { entry ->
            // Bounded retained candidates per sport, newest first; never let one sport starve another.
            if (entry.key in state.candidates) state.candidates[entry.key] = entry
            else {
                val sameSport = state.candidates.values.filter { it.bucket == entry.bucket }
                if (sameSport.size < 120) state.candidates[entry.key] = entry
                else {
                    val oldest = sameSport.minByOrNull { it.programme.startMs }
                    if (oldest != null && oldest.programme.startMs < entry.programme.startMs) {
                        state.candidates.remove(oldest.key); state.checked.remove(oldest.key); state.verified.remove(oldest.key)
                        state.candidates[entry.key] = entry
                    }
                }
            }
        }
    }
    fun orderedChannels(bundle: XtreamBundle, rewrite: (Channel) -> Channel): List<Channel> {
        val groups = SportsReplayCatalogue.channels(bundle).map(rewrite).groupBy { channel ->
            // Never walk programme history to decide which channel to read first.
            LiveSportsClassifier.programmeBucket(channel.name) ?: "other"
        }.mapValues { (_, list) -> ArrayDeque(list) }.toMutableMap()
        val ordered = mutableListOf<Channel>()
        val order = (LiveSportsClassifier.DISPLAY_ORDER + groups.keys).distinct()
        while (groups.values.any { it.isNotEmpty() }) order.forEach { sport -> groups[sport]?.removeFirstOrNull()?.let(ordered::add) }
        return ordered
    }
    suspend fun scan(state: State, selected: () -> String?, current: () -> Boolean, force: Boolean = false, guide: Map<String, List<Programme>> = emptyMap(), update: () -> Unit) = coroutineScope {
        val counts = mutableMapOf<String, Int>()
        var budget = 0
        suspend fun verifyNext(): Boolean {
            val pending = state.pending()
            val next = pending.minWithOrNull(compareBy<SportsReplayCatalogue.Entry> {
                when { state.verified.values.none { v -> v.bucket == it.bucket } -> 0; it.bucket == selected() -> 1; else -> 2 }
            }.thenBy { counts[it.bucket] ?: 0 }.thenByDescending { it.programme.startMs }) ?: return false
            if (force) SportsReplayRepository.invalidate(next.channel, next.programme)
            state.phase = "Checking ${LiveSportsClassifier.labelOf(next.bucket)} recordings"
            update()
            val result = SportsReplayRepository.verifyProgramme(next.channel, next.programme)
            currentCoroutineContext().ensureActive(); if (!current()) return false
            state.checked.add(next.key); state.probes++; budget++; counts[next.bucket] = (counts[next.bucket] ?: 0) + 1
            if (result.verified != null) state.verified[next.key] = next
            update(); return true
        }
        // Use any already-loaded historical guide first; this can show the first category immediately.
        if (state.pending().isNotEmpty() && current()) verifyNext()
        while (state.cursor < state.channels.size && current()) {
            val wave = state.channels.drop(state.cursor).take(3)
            state.phase = "Reading archive listings"; update()
            seed(state, guide, wave)
            val results = wave.map { channel -> async { channel to SportsReplayRepository.archiveProgrammes(channel, force) } }.awaitAll()
            currentCoroutineContext().ensureActive(); if (!current()) return@coroutineScope
            results.forEach { (channel, rows) ->
                if (rows == null) state.errors++ else {
                    rows.filter { it.archiveAvailable == false }.forEach { p ->
                        val key = "${channel.id}-${p.startMs}"; state.candidates.remove(key); state.verified.remove(key); state.checked.add(key)
                    }
                    val entries = withContext(Dispatchers.Default) { SportsReplayCatalogue.entries(channel, rows) }
                    add(state, entries)
                }
            }
            state.cursor += wave.size; update()
            repeat(3) { if (budget < 60 && current()) verifyNext() }
            if (budget >= 60) return@coroutineScope
        }
        // Still one media probe at a time. Subsequent pages continue the index, never rescan channels.
        while (budget < 60 && current() && verifyNext()) { currentCoroutineContext().ensureActive() }
    }
}