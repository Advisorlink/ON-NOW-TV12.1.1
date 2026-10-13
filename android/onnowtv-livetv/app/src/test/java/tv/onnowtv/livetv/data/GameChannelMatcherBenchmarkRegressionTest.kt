package tv.onnowtv.livetv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.text.Normalizer
import kotlin.math.abs
import kotlin.system.measureNanoTime

class GameChannelMatcherBenchmarkRegressionTest {

    data class Signature(
        val channelId: String,
        val score: Int,
        val reason: String,
        val confirmed: Boolean,
        val programmeTitle: String?,
        val programmeStart: Long?,
        val programmeStop: Long?,
    )

    private object LegacyMatcher {
        private val marks = Regex("\\p{M}+")
        private val punctuation = Regex("[^a-z0-9]+")
        private val spaces = Regex(" +")
        private val excluded = Regex("\\b(highlights?|recap|preview|postgame|post game|news|classic|review|rewind)\\b")

        private fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
            .lowercase().replace(punctuation, " ").trim().replace(spaces, " ")

        private fun aliases(t: JSONObject?): Set<String> {
            val names = listOf("name", "short", "abbr").map { normalize(SportsPresentation.value(t, it)) }.toMutableList()
            val words = normalize(SportsPresentation.value(t, "name")).split(' ').filter { it.isNotBlank() }
            if (words.size >= 2) names.add(words.takeLast(2).joinToString(" "))
            words.lastOrNull()?.takeIf { it.length >= 4 }?.let(names::add)
            return names.filter { it.length >= 2 && it !in setOf("fc", "cf", "sc", "united", "city", "town", "club", "the") }.toSet()
        }

        private fun mentions(title: String, names: Set<String>) = names.any { " $title ".contains(" $it ") }

        private fun isFixtureProgramme(game: JSONObject, programme: Programme): Boolean {
            val start = game.optLong("startMs")
            if (start <= 0 || programme.stopMs <= programme.startMs) return false
            val title = normalize(programme.title)
            if (excluded.containsMatchIn(title)) return false
            if (abs(programme.startMs - start) > 90 * 60_000L || programme.stopMs <= start) return false
            val home = aliases(game.optJSONObject("home"))
            val away = aliases(game.optJSONObject("away"))
            return mentions(title, home - away) && mentions(title, away - home)
        }

        fun find(game: JSONObject, bundle: XtreamBundle, nowMs: Long): List<GameChannelMatcher.Match> {
            val casters = game.optJSONArray("broadcasts")?.let { a ->
                (0 until a.length()).map { normalize(a.optString(it)) }.filter { it.length >= 3 }
            }.orEmpty()
            return bundle.channels.mapNotNull { ch ->
                val exact = (SportsGuideOverlay.get(ch) + bundle.epg[ch.epgChannelId ?: ch.id].orEmpty())
                    .distinctBy { it.startMs to it.stopMs }
                    .filter { isFixtureProgramme(game, it) }
                    .minByOrNull { abs(it.startMs - game.optLong("startMs")) }
                if (exact != null) GameChannelMatcher.Match(ch, 100 + if (exact.isLiveAt(nowMs)) 10 else 0, "Guide confirmed · ${exact.title}", exact, true)
                else if (casters.any { " ${GameChannelMatcher.normalize(ch.name)} ".contains(" $it ") }) GameChannelMatcher.Match(ch, 25, "Possible broadcast · not confirmed in guide")
                else null
            }.sortedWith(compareByDescending<GameChannelMatcher.Match> { it.confirmed }.thenByDescending { it.score }.thenBy { it.channel.name }).take(12)
        }
    }

    @Test
    fun benchmark_and_regression_old_vs_new_matcher_parity() {
        val now = System.currentTimeMillis()
        val kickoff = now - 45 * 60_000L
        val game = JSONObject()
            .put("id", "401908030")
            .put("state", "in")
            .put("sport", "baseball")
            .put("league", "mlb")
            .put("startMs", kickoff)
            .put("broadcasts", JSONArray().put("espn").put("tnt"))
            .put("home", JSONObject().put("name", "New York Yankees").put("short", "Yankees").put("abbr", "NYY"))
            .put("away", JSONObject().put("name", "Boston Red Sox").put("short", "Red Sox").put("abbr", "BOS"))

        val channels = (0 until 5_000).map { i ->
            Channel(
                id = "c-$i",
                name = when (i) {
                    25 -> "ESPN Main"
                    26 -> "TNT Sports 1"
                    else -> "Channel $i"
                },
                lcn = i.toString(),
                logoUrl = null,
                categoryId = null,
                streamUrl = "https://provider.example/live/user/pass/$i.ts",
                epgChannelId = "c-$i",
                archiveEnabled = true,
                archiveDays = 7,
            )
        }

        val sharedOutsideWindow = (0 until 96).map { slot ->
            val start = kickoff + (24L * 3_600_000L) + slot * 30L * 60_000L
            Programme("Sports desk segment $slot", null, start, start + 30L * 60_000L, archiveAvailable = true)
        }

        val epg = HashMap<String, List<Programme>>(channels.size)
        channels.forEach { epg[it.id] = sharedOutsideWindow }

        val exactChannel = channels[9]
        val exactStart = kickoff - 5 * 60_000L
        epg[exactChannel.id] = listOf(
            Programme("Boston Red Sox vs New York Yankees", null, exactStart, kickoff + 120 * 60_000L, archiveAvailable = true),
            Programme("Boston Red Sox vs New York Yankees highlights", null, kickoff - 10 * 60_000L, kickoff + 90 * 60_000L, archiveAvailable = true),
        )

        val overlayWinner = channels[11]
        val overlayWindowStart = kickoff - 2 * 60_000L
        val duplicateWindow = Programme("BOS @ NYY", null, overlayWindowStart, kickoff + 120 * 60_000L, archiveAvailable = true)
        epg[overlayWinner.id] = listOf(
            Programme("Preview show", null, kickoff - 80 * 60_000L, kickoff - 10 * 60_000L, archiveAvailable = true),
            duplicateWindow,
        )
        SportsGuideOverlay.put(
            overlayWinner,
            listOf(
                Programme("Boston Red Sox at New York Yankees", null, overlayWindowStart, kickoff + 120 * 60_000L, archiveAvailable = false),
            ),
            ttlMs = 60_000,
        )

        val excludedOnly = channels[17]
        epg[excludedOnly.id] = listOf(
            Programme("Yankees vs Red Sox highlights", null, kickoff - 4 * 60_000L, kickoff + 100 * 60_000L, archiveAvailable = true),
            Programme("Postgame recap", null, kickoff + 100 * 60_000L, kickoff + 160 * 60_000L, archiveAvailable = true),
        )

        val bundle = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = channels,
            epg = epg,
            generatedAt = now,
        )

        repeat(2) {
            LegacyMatcher.find(game, bundle, now)
            GameChannelMatcher.find(game, bundle, now)
        }

        val oldDurations = mutableListOf<Long>()
        val newDurations = mutableListOf<Long>()
        repeat(5) {
            oldDurations += measureNanoTime { LegacyMatcher.find(game, bundle, now) }
            newDurations += measureNanoTime { GameChannelMatcher.find(game, bundle, now) }
        }

        val oldResult = LegacyMatcher.find(game, bundle, now).map { it.signature() }
        val newResult = GameChannelMatcher.find(game, bundle, now).map { it.signature() }
        assertEquals(oldResult, newResult)

        val timing = JSONObject()
            .put("dataset", JSONObject().put("channels", 5000).put("programmesPerChannel", 96).put("mostlyOutsideKickoffWindow", true))
            .put("warmed_runs", 5)
            .put("old_median_ms", medianMs(oldDurations))
            .put("new_median_ms", medianMs(newDurations))
            .put("parity", true)
            .put("note", "JVM benchmark only; not physical TV timing")

        // Gradle's module working directory is writable on CI and developer machines too.
        val outDir = Paths.get("build", "reports", "sports-navigation")
        Files.createDirectories(outDir)
        Files.write(
            outDir.resolve("game_channel_matcher_timing_latest.json"),
            timing.toString(2).toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun GameChannelMatcher.Match.signature(): Signature = Signature(
        channelId = channel.id,
        score = score,
        reason = reason,
        confirmed = confirmed,
        programmeTitle = programme?.title,
        programmeStart = programme?.startMs,
        programmeStop = programme?.stopMs,
    )

    private fun medianMs(valuesNs: List<Long>): Long {
        val sorted = valuesNs.sorted()
        return sorted[sorted.size / 2] / 1_000_000
    }
}
