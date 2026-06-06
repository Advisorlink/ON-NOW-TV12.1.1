package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
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
 *   • Top    — COLLECTIONS.  The FIRST tile is always "+ Add
 *               Collection" — tapping it shows a dialog where the
 *               user types a name and picks "Auto cover" (AI) or
 *               "Upload your own" (file picker).  The new
 *               collection starts EMPTY; channels are added later
 *               by long-pressing OK on a channel inside the EPG.
 *               OK on a populated tile opens the EPG in
 *               collection-mode (sidebar hidden, middle column =
 *               just the collection's channels).  LONG-PRESS on a
 *               tile opens a rename / change-cover / delete menu.
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
            onAddCollection = { promptCreateCollection() },
            onPick = { c -> openCollection(c) },
            onLongPick = { c -> promptManageCollection(c) },
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

        collectionsAdapter.submit(collections)
        collectionsCount.text = collections.size.toString()
        // The Collections row ALWAYS shows at least the "+ Add
        // Collection" virtual tile so the row is never empty.  Hide
        // the empty-state placeholder unconditionally.
        collectionsEmpty.visibility = View.GONE
        collectionsList.visibility = View.VISIBLE

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

        // Land focus on the first collection tile (the "+ Add"
        // virtual tile is index 0) so the user can immediately
        // navigate with the d-pad.
        collectionsList.post {
            collectionsList.findViewHolderForAdapterPosition(0)
                ?.itemView?.requestFocus()
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
            putExtra(EpgActivity.EXTRA_INITIAL_COLLECTION_ID, c.id)
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

    // ───────────────────────────────── create / manage collection

    /** Step 1: prompt for the collection name + cover source. */
    private fun promptCreateCollection() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. Saturday Sports, Kids Picks"
            setSingleLine()
            setTextColor(android.graphics.Color.parseColor("#F5F8FF"))
            setHintTextColor(android.graphics.Color.parseColor("#5F6A85"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle("Name your collection")
            .setMessage("Pick a name, then choose how the cover artwork is set.")
            .setView(input)
            .setPositiveButton("Auto cover (AI)") { d, _ ->
                val name = input.text?.toString()?.trim().orEmpty().ifBlank { "My Collection" }
                d.dismiss()
                createCollection(name, useAi = true)
            }
            .setNeutralButton("Upload your own") { d, _ ->
                val name = input.text?.toString()?.trim().orEmpty().ifBlank { "My Collection" }
                d.dismiss()
                createCollectionWithCustomCover(name)
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    /** Step 2a: AI cover.  Persists the empty collection first so
     *  the user gets immediate feedback, then runs the generator
     *  in the background and writes the cover URL back when ready. */
    private fun createCollection(name: String, useAi: Boolean) {
        val record = LibraryCollection(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            coverHash = null,
            coverUrl = null,
            addedAt = System.currentTimeMillis(),
            channelIds = emptyList(),
        )
        CollectionsStore.add(this, record)
        refresh()
        if (!useAi) return

        collectionsAdapter.setBusy(record.id, true)
        lifecycleScope.launch {
            try {
                val gen = withContext(Dispatchers.IO) { CoversApi.generate(name) }
                CollectionsStore.update(
                    this@LibraryActivity,
                    record.copy(coverHash = gen.hash, coverUrl = gen.url),
                )
                collectionsAdapter.setBusy(record.id, false)
                refresh()
            } catch (t: Throwable) {
                collectionsAdapter.setBusy(record.id, false)
                android.widget.Toast.makeText(
                    this@LibraryActivity,
                    "Couldn't generate cover: ${t.message ?: "unknown error"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Step 2b: custom cover — persist the collection first so the
     *  picker callback has a stable id to attach the image to. */
    private fun createCollectionWithCustomCover(name: String) {
        val record = LibraryCollection(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            coverHash = null,
            coverUrl = null,
            addedAt = System.currentTimeMillis(),
            channelIds = emptyList(),
        )
        CollectionsStore.add(this, record)
        refresh()
        launchPickCustomCover(record)
    }

    /** Long-press menu: rename / change cover / delete. */
    private fun promptManageCollection(c: LibraryCollection) {
        val options = arrayOf("Rename", "Regenerate cover (AI)", "Upload custom cover", "Delete")
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(options) { d, idx ->
                d.dismiss()
                when (idx) {
                    0 -> promptRenameCollection(c)
                    1 -> regenerateAiCover(c)
                    2 -> launchPickCustomCover(c)
                    3 -> confirmDeleteCollection(c)
                }
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun promptRenameCollection(c: LibraryCollection) {
        val input = android.widget.EditText(this).apply {
            setText(c.name)
            setSelection(c.name.length)
            setSingleLine()
            setTextColor(android.graphics.Color.parseColor("#F5F8FF"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename \"${c.name}\"")
            .setView(input)
            .setPositiveButton("Save") { d, _ ->
                val newName = input.text?.toString()?.trim().orEmpty().ifBlank { c.name }
                CollectionsStore.update(this, c.copy(name = newName))
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun confirmDeleteCollection(c: LibraryCollection) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${c.name}\"?")
            .setMessage("The channels themselves are not deleted — only this collection.")
            .setPositiveButton("Delete") { d, _ ->
                CollectionsStore.remove(this, c.id)
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun regenerateAiCover(c: LibraryCollection) {
        collectionsAdapter.setBusy(c.id, true)
        lifecycleScope.launch {
            try {
                val gen = withContext(Dispatchers.IO) {
                    CoversApi.generate(c.name, forceSalt = CoversApi.freshSalt())
                }
                CollectionsStore.update(
                    this@LibraryActivity,
                    c.copy(coverHash = gen.hash, coverUrl = gen.url),
                )
                collectionsAdapter.setBusy(c.id, false)
                refresh()
            } catch (t: Throwable) {
                collectionsAdapter.setBusy(c.id, false)
                android.widget.Toast.makeText(
                    this@LibraryActivity,
                    "Cover generation failed: ${t.message ?: "unknown error"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ────────────────────────────────────── custom cover picker

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
                    dir.listFiles { f -> f.name.startsWith("${c.id}.") }
                        ?.forEach { runCatching { it.delete() } }
                    val mime = contentResolver.getType(uri)
                    val ext = when (mime) {
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        "image/gif" -> "gif"
                        else -> "jpg"
                    }
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
