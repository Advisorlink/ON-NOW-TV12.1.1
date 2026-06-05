package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.LibraryCollection
import tv.onnowtv.livetv.data.CollectionsStore
import tv.onnowtv.livetv.data.CoversApi
import tv.onnowtv.livetv.data.FavouritesStore
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.ui.CollectionTileAdapter
import tv.onnowtv.livetv.ui.FavouriteTileAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "My Library" hub for the native Live TV app.
 *
 * Two horizontal rows:
 *   • Top    — COLLECTIONS (saved categories with AI-generated 16:9
 *               cover art).  OK opens the category in [EpgActivity];
 *               LONG-PRESS opens the regenerate-cover dialog.
 *   • Bottom — FAVOURITES (saved channels).  OK launches full-screen
 *               via the shared [LivePreviewSession].
 *
 * Both rows pull live data from the singleton [BundleHolder] so the
 * "NOW: <title>" subline and the per-collection channel count are
 * always in sync with the latest Xtream bundle.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var clock: TextView
    private lateinit var collectionsList: RecyclerView
    private lateinit var collectionsEmpty: View
    private lateinit var collectionsCount: TextView
    private lateinit var favouritesList: RecyclerView
    private lateinit var favouritesEmpty: View
    private lateinit var favouritesCount: TextView

    private lateinit var collectionsAdapter: CollectionTileAdapter
    private lateinit var favouritesAdapter: FavouriteTileAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        clock = findViewById(R.id.lib_clock)
        collectionsList = findViewById(R.id.lib_collections_list)
        collectionsEmpty = findViewById(R.id.lib_collections_empty)
        collectionsCount = findViewById(R.id.lib_collections_count)
        favouritesList = findViewById(R.id.lib_favourites_list)
        favouritesEmpty = findViewById(R.id.lib_favourites_empty)
        favouritesCount = findViewById(R.id.lib_favourites_count)

        collectionsAdapter = CollectionTileAdapter(
            onPick = { c -> openCollection(c) },
            onLongPick = { c -> promptRegenerateCover(c) },
        )
        collectionsList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        collectionsList.adapter = collectionsAdapter
        collectionsList.itemAnimator = null

        favouritesAdapter = FavouriteTileAdapter(
            nowTitleFor = { ch -> nowProgrammeTitle(ch) },
            onPick = { ch -> openFavourite(ch) },
        )
        favouritesList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        favouritesList.adapter = favouritesAdapter
        favouritesList.itemAnimator = null

        startClock()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────── data

    private fun refresh() {
        val collections = CollectionsStore.load(this)
        val favouriteIds = FavouritesStore.load(this)
        val bundle = BundleHolder.current

        // Collections row + per-tile channel counts.
        val counts = if (bundle != null) {
            collections.associate { c ->
                c.categoryId to bundle.channels.count { it.categoryId == c.categoryId }
            }
        } else emptyMap()
        collectionsAdapter.channelCounts = counts
        collectionsAdapter.submit(collections)
        collectionsCount.text = collections.size.toString()
        collectionsEmpty.visibility = if (collections.isEmpty()) View.VISIBLE else View.GONE
        collectionsList.visibility = if (collections.isEmpty()) View.INVISIBLE else View.VISIBLE

        // Favourites row.
        val favChannels: List<Channel> = bundle
            ?.channels
            ?.filter { it.id in favouriteIds }
            ?.sortedBy { it.lcn?.toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()
        favouritesAdapter.submit(favChannels)
        favouritesCount.text = favChannels.size.toString()
        favouritesEmpty.visibility = if (favChannels.isEmpty()) View.VISIBLE else View.GONE
        favouritesList.visibility = if (favChannels.isEmpty()) View.INVISIBLE else View.VISIBLE

        // Land focus on the first collection (or first favourite if
        // there are no collections yet) so the user can immediately
        // navigate with the d-pad.
        if (collections.isNotEmpty()) {
            collectionsList.post {
                collectionsList.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }
        } else if (favChannels.isNotEmpty()) {
            favouritesList.post {
                favouritesList.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }
        }
    }

    private fun nowProgrammeTitle(ch: Channel): String? {
        val bundle = BundleHolder.current ?: return null
        val sid = ch.epgChannelId ?: return null
        val now = System.currentTimeMillis()
        val list: List<Programme> = bundle.epg[sid] ?: return null
        val match = list.firstOrNull { it.isLiveAt(now) }
        return match?.title
    }

    // ───────────────────────────────────────────────────── navigation

    private fun openCollection(c: LibraryCollection) {
        val intent = Intent(this, EpgActivity::class.java).apply {
            // Reuse the existing "open with this category selected" path.
            putExtra(EpgActivity.EXTRA_INITIAL_CATEGORY_ID, c.categoryId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun openFavourite(ch: Channel) {
        // Start the shared session on this channel + jump full-screen.
        LivePreviewSession.setChannel(this, ch)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, ch.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, ch.id)
            putExtra(PlayerActivity.EXTRA_USE_SHARED_PLAYER, true)
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────────── regenerate-cover

    private fun promptRegenerateCover(c: LibraryCollection) {
        val dlg = tv.onnowtv.livetv.ui.LibraryDialog(this)
        dlg.showIdle(
            titleText = "Regenerate cover for \"${c.name}\"?",
            bodyText = "We'll create a fresh realistic broadcaster-style banner — usually 10–20 seconds.\n\n" +
                "Or pick \"Re-style ALL\" to refresh every cover in your library in parallel.  " +
                "Press BACK to cancel.",
            primaryLabel = "Regenerate this",
            secondaryLabel = "Re-style ALL",
            onPrimary = {
                dlg.showBusy("Regenerating — usually 10–20 seconds.")
                regenerate(c, dlg)
            },
            onSecondary = {
                dlg.dismiss()
                regenerateAll()
            },
        )
    }

    private fun regenerate(c: LibraryCollection, dlg: tv.onnowtv.livetv.ui.LibraryDialog) {
        collectionsAdapter.setBusy(c.id, true)
        lifecycleScope.launch {
            try {
                val gen = withContext(Dispatchers.IO) {
                    CoversApi.generate(c.name, forceSalt = CoversApi.freshSalt())
                }
                val updated = c.copy(coverHash = gen.hash, coverUrl = gen.url)
                CollectionsStore.update(this@LibraryActivity, updated)
                dlg.snapToComplete()
                refresh()
            } catch (t: Throwable) {
                collectionsAdapter.setBusy(c.id, false)
                dlg.showError(t.message ?: "Unknown error")
            }
        }
    }

    /**
     * Bulk re-style — fires every Collection's regeneration **in
     * parallel** (Nano Banana easily handles 4-8 concurrent calls)
     * so the whole shelf refreshes in roughly one cover's worth of
     * wall-clock time instead of N × ~15 s.
     */
    private fun regenerateAll() {
        val all = CollectionsStore.load(this)
        if (all.isEmpty()) return
        val dlg = tv.onnowtv.livetv.ui.LibraryDialog(this)
        dlg.showIdle(
            titleText = "Re-style every cover",
            bodyText = "Generate a fresh banner for all ${all.size} collections in parallel — " +
                "usually about 15–25 seconds total.",
            primaryLabel = "Re-style ALL",
            secondaryLabel = "Cancel",
            onPrimary = {
                dlg.showBusy("Re-styling ${all.size} covers in parallel…")
                for (c in all) collectionsAdapter.setBusy(c.id, true)
                lifecycleScope.launch {
                    val jobs = all.map { c ->
                        kotlinx.coroutines.async(Dispatchers.IO) {
                            try {
                                val gen = CoversApi.generate(c.name, forceSalt = CoversApi.freshSalt())
                                CollectionsStore.update(this@LibraryActivity,
                                    c.copy(coverHash = gen.hash, coverUrl = gen.url))
                            } catch (_: Throwable) {
                                /* swallow individual failures so the
                                 * batch keeps progressing */
                            }
                        }
                    }
                    jobs.awaitAll()
                    for (c in all) collectionsAdapter.setBusy(c.id, false)
                    dlg.snapToComplete()
                    refresh()
                }
            },
        )
    }

    // ────────────────────────────────────────────────────── clock

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                clock.text = clockFmt.format(Date()).uppercase(Locale.UK)
                clockHandler.postDelayed(this, 30_000)
            }
        }
        tick.run()
    }
}
