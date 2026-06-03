package tv.vesper.native_app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.vesper.native_app.data.CatalogItem
import tv.vesper.native_app.data.CatalogRepository
import tv.vesper.native_app.data.NavItem
import tv.vesper.native_app.data.Shelf
import tv.vesper.native_app.ui.ShelvesAdapter
import tv.vesper.native_app.ui.SideNavAdapter

/**
 * Home — the only activity in v0.1.  Side nav on the left, vertical
 * RecyclerView of horizontal poster rails on the right.  Pulls real
 * shelves from the production Vesper backend (same `/api/addons`
 * endpoints the React UI uses) so the user sees identical content
 * to their current app — just rendered through Android's native
 * focus engine for the smooth nav they signed off on for V2 Live TV.
 *
 * Future phases (NOT in v0.1):
 *   - Tap a poster → DetailActivity
 *   - Side-nav routes → Search / Library / Settings activities
 *   - Hero PLAY → PlayerActivity (ExoPlayer)
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var sideNav: RecyclerView
    private lateinit var shelvesList: RecyclerView
    private lateinit var shelvesAdapter: ShelvesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        sideNav      = findViewById(R.id.side_nav)
        shelvesList  = findViewById(R.id.shelves_list)

        setupSideNav()
        setupShelves()
        loadCatalog()

        // First focus into the shelves list so the user's D-pad
        // starts on the content, not the side nav (mirrors Vesper).
        shelvesList.post {
            shelvesList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun setupSideNav() {
        val nav = listOf(
            NavItem("home",     getString(R.string.nav_home),     R.drawable.ic_home),
            NavItem("tv",       getString(R.string.nav_tv),       R.drawable.ic_tv),
            NavItem("movies",   getString(R.string.nav_movies),   R.drawable.ic_movie),
            NavItem("search",   getString(R.string.nav_search),   R.drawable.ic_search),
            NavItem("library",  getString(R.string.nav_library),  R.drawable.ic_library),
            NavItem("settings", getString(R.string.nav_settings), R.drawable.ic_settings),
        )
        val adapter = SideNavAdapter(nav) { picked ->
            // v0.1: only Home is implemented.  Other routes ship in
            // later phases; show a toast for now so the focus engine
            // still feels responsive.
            if (picked.id != "home") {
                Toast.makeText(
                    this,
                    "${picked.label} coming next",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        sideNav.layoutManager = LinearLayoutManager(this)
        sideNav.adapter = adapter
        sideNav.itemAnimator = null
    }

    private fun setupShelves() {
        shelvesAdapter = ShelvesAdapter(
            onItemActivate = { item ->
                Toast.makeText(
                    this,
                    "Opening ${item.title} (Detail screen in next phase)",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onHeroPlay = { item ->
                Toast.makeText(
                    this,
                    "Play ${item.title} (Player in next phase)",
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
        shelvesList.layoutManager = LinearLayoutManager(this)
        shelvesList.adapter = shelvesAdapter
        shelvesList.itemAnimator = null
    }

    /**
     * Pull shelves off the main thread.  We call back to the UI
     * thread for each one so the page paints progressively (the
     * first rail appears before slow addons finish).
     */
    private fun loadCatalog() {
        lifecycleScope.launch(Dispatchers.IO) {
            var heroSet = false
            CatalogRepository.fetchShelves(
                onShelf = { shelf ->
                    runOnUiThreadSafe {
                        // First non-empty shelf gives us the hero.
                        if (!heroSet) {
                            shelf.items.firstOrNull { !it.backdrop.isNullOrBlank() }
                                ?.let {
                                    shelvesAdapter.setHero(it)
                                    heroSet = true
                                }
                        }
                        shelvesAdapter.addShelf(shelf)
                    }
                },
                onDone = {
                    // If no addon had a backdrop, fall back to the
                    // first poster as the hero so the page isn't
                    // headerless.
                    if (!heroSet) {
                        runOnUiThreadSafe {
                            firstAnyItem()?.let { shelvesAdapter.setHero(it) }
                        }
                    }
                },
            )
        }
    }

    private fun firstAnyItem(): CatalogItem? {
        // ShelvesAdapter doesn't currently expose its rows; cheap
        // workaround for v0.1 — null is fine, just means no hero.
        return null
    }

    private inline fun runOnUiThreadSafe(crossinline block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }
}
