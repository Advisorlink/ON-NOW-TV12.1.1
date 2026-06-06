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
            bodyText = "Tweak the name below first if you want the generator to lean a particular way " +
                "(e.g. \"Sky Sports KO\" → \"Sky Sports KO boxing\").\n\n" +
                "Pick \"Re-style ALL\" to refresh every cover in your library in parallel, " +
                "or \"Add your own\" to pull a custom image from a USB stick / internal storage.  " +
                "Press BACK to cancel.",
            primaryLabel = "Regenerate this",
            secondaryLabel = "Re-style ALL",
            tertiaryLabel = "Add your own",
            nameHint = c.name,
            onPrimary = {
                val typed = dlg.editedName.ifBlank { c.name }
                dlg.showBusy("Regenerating cover for \"$typed\" — usually 10–20 seconds.")
                regenerate(c, dlg, overrideName = typed)
            },
            onSecondary = {
                dlg.dismiss()
                regenerateAll()
            },
            onTertiary = {
                dlg.dismiss()
                launchPickCustomCover(c)
            },
        )
    }

    // ────────────────────────────────────── custom cover picker

    /** Set when the user taps "Add your own" — the activity-result
     *  callback uses it to know which collection to update. */
    private var pendingCustomCoverFor: LibraryCollection? = null

    private val pickCoverLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingCustomCoverFor
        pendingCustomCoverFor = null
        if (uri == null || target == null) return@registerForActivityResult
        importCustomCover(target, uri)
    }

    private fun launchPickCustomCover(c: LibraryCollection) {
        pendingCustomCoverFor = c
        // Image MIME types only.  Android TV's storage picker
        // exposes USB OTG sticks + internal storage out of the box.
        pickCoverLauncher.launch(arrayOf("image/*"))
    }

    /** Copy the picked image into the app's private files dir
     *  (so we own a stable path even after the user unmounts the
     *  USB) then update the collection record so the tile re-paints
     *  with the local copy. */
    private fun importCustomCover(c: LibraryCollection, uri: android.net.Uri) {
        collectionsAdapter.setBusy(c.id, true)
        lifecycleScope.launch {
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    val dir = java.io.File(filesDir, "library_covers").apply { mkdirs() }
                    // Wipe any previous custom cover for this collection.
                    dir.listFiles { f -> f.name.startsWith("${c.id}.") }
                        ?.forEach { runCatching { it.delete() } }
                    val mime = contentResolver.getType(uri)
                    val ext = when (mime) {
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        "image/gif" -> "gif"
                        else -> "jpg"
                    }
                    // Timestamped filename so Coil's file-uri cache
                    // (keyed by absolute path + lastModified) treats
                    // each re-import as a fresh image.
                    val stamp = System.currentTimeMillis()
                    val out = java.io.File(dir, "${c.id}.$stamp.$ext")
                    contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    } ?: error("Could not read the picked file")
                    out.absolutePath
                }
                val coverUrl = "file://$savedPath"
                val updated = c.copy(
                    coverHash = "custom:${System.currentTimeMillis()}",
                    coverUrl = coverUrl,
                )
                CollectionsStore.update(this@LibraryActivity, updated)
                collectionsAdapter.setBusy(c.id, false)
                refresh()
                android.widget.Toast.makeText(
                    this@LibraryActivity,
                    "Cover updated for \"${c.name}\"",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (t: Throwable) {
                collectionsAdapter.setBusy(c.id, false)
                android.widget.Toast.makeText(
                    this@LibraryActivity,
                    "Couldn't import that image: ${t.message ?: "unknown error"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun regenerate(
        c: LibraryCollection,
        dlg: tv.onnowtv.livetv.ui.LibraryDialog,
        overrideName: String? = null,
    ) {
        val displayName = overrideName?.takeIf { it.isNotBlank() } ?: c.name
        collectionsAdapter.setBusy(c.id, true)
        lifecycleScope.launch {
            try {
                val gen = withContext(Dispatchers.IO) {
                    CoversApi.generate(displayName, forceSalt = CoversApi.freshSalt())
                }
                val updated = c.copy(
                    name = displayName,
                    coverHash = gen.hash,
                    coverUrl = gen.url,
                )
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
     * parallel** (GPT-Image-1 easily handles 4-8 concurrent calls)
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
                    // The async-on-Collection-map call below needs a
                    // CoroutineScope receiver, but the `.map { }`
                    // lambda doesn't carry one.  Capture `this` and
                    // call `async` through the captured scope so the
                    // extension resolves correctly.
                    val scope = this
                    val jobs = all.map { c ->
                        scope.async(Dispatchers.IO) {
                            try {
                                val gen = CoversApi.generate(
                                    c.name, forceSalt = CoversApi.freshSalt())
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
