package tv.onnowtv.vespernative

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.ui.*
import tv.onnowtv.vespernative.profile.*
import tv.onnowtv.vespernative.playback.*

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(TvViewport.context(base))
    }
    private lateinit var services: NativeServices
    private lateinit var container: FrameLayout
    private var repository: CatalogRepository? = null
    private val screens = mutableListOf<NativeScreen>()
    private var account: Account? = null
    private var activeProfile: CloudProfile? = null
    private var profileData: ProfileData? = null
    private var preferences: LocalPreferences? = null
    private var currentRoute = ""
    private var authJob: Job? = null
    private var invalidating = false
    private var pendingBackup:String?=null
    private var importScope:String?=null
    private val exportSettings=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")){uri->
        val data=pendingBackup;pendingBackup=null
        if(uri!=null && data!=null)try{contentResolver.openOutputStream(uri,"wt")!!.use{it.write(data.toByteArray())};Toast.makeText(this,"Local settings exported.",Toast.LENGTH_SHORT).show()}
        catch(_:Exception){Toast.makeText(this,"Couldn't export local settings.",Toast.LENGTH_LONG).show()}
    }
    private val importSettings=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()){uri->
        val prefs=preferences;val expected=importScope;importScope=null
        if(uri!=null && prefs!=null && prefs.scopeKey==expected)try{
            val data=contentResolver.openInputStream(uri)!!.use(LocalBackup::read);prefs.restoreBackup(data);prefs.applyTheme();navigate("home");navigate("settings")
            Toast.makeText(this,"Local settings restored. Cloud data unchanged.",Toast.LENGTH_SHORT).show()
        }catch(_:Exception){Toast.makeText(this,"Invalid settings backup. Nothing was changed.",Toast.LENGTH_LONG).show()}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        services = NativeServices.factory(this)
        container = FrameLayout(this).apply { setBackgroundColor(TvUi.background) }
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(screens.lastOrNull()?.back()==true)return
                if (screens.size > 1) pop() else confirmExit()
            }
        })
        restore()
    }
    private fun restore() {
        authJob?.cancel()
        val status = StatusBand(this, "session-status").apply {
            show("Opening Vesper…"); onRetry = { restore() }
        }
        container.removeAllViews()
        container.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        authJob = lifecycleScope.launch {
            try { services.auth.restore()?.let { signedIn(it) } ?: login()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status.show("Can't connect to ON NOW TV. Your session is saved.", true); status.focusRetry() }
        }
    }
    private fun login() {
        invalidating = false
        val login = LoginScreen(this) { username, password ->
            val current = screens.lastOrNull() as? LoginScreen ?: return@LoginScreen
            current.busy(true)
            authJob = lifecycleScope.launch {
                try { signedIn(services.auth.signIn(username, password))
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    current.failed(when {
                        e is ApiFailure && e.status == 401 -> "Invalid username or password."
                        e is ApiFailure -> e.message ?: "Unable to sign in."
                        else -> "Unable to sign in. Please try again."
                    })
                }
            }
        }
        replace(login)
    }
    private fun signedIn(value: Account) {
        account = value
        repository = CatalogRepository(services.api) { services.auth.token }
        showProfiles()
    }
    private fun showProfiles() {
        val owner = account ?: return
        activeProfile = null; profileData = null
        replace(ProfilePickerScreen(this, services.cloudProfiles(owner), { snapshot, profile ->
            fun activate(data: ProfileData) {
                activeProfile = profile; profileData = data
                preferences = LocalPreferences(this, owner.id, profile.id, data).also { it.applyTheme() }
                repository = CatalogRepository(services.api) { services.auth.token }
                navigate("home")
            }
            if (profile.requiresPin) push(ProfilePinScreen(this, profile, snapshot, ::activate, ::pop))
            else activate(snapshot.openProfile(profile.id))
        }, { preferences = LocalPreferences(this, owner.id, "browse", null).also { it.applyTheme() }; navigate("home") }, ::signOut, ::failed))
    }
    private fun navigate(route: String) {
        val repo = repository ?: return
        if (route == "home" && screens.size == 1 && screens.lastOrNull() is HomeScreen) return
        when (route) {
            "home" -> { currentRoute = route; replace(HomeScreen(this, repo, null, ::open, ::navigate, ::failed, profileData != null,
                ::openBrand, activeProfile, profileData, { openTitle(it, true) }, preferences?.side == true,preferences,{ openTitle(it,false) })) }
            "movie", "series" -> { currentRoute = route; push(BrowseGridScreen(this, repo, route, ::open, ::navigate, ::failed,navigation=chrome(route))) }
            "boxsets" -> { currentRoute = route; push(DirectoryScreen(this, repo, ::openBrand, ::navigate, ::failed,chrome(route))) }
            "anime" -> { currentRoute = route; push(AnimeScreen(this, repo, ::open, ::navigate, ::failed,{ openTitle(it,true) },chrome(route))) }
            "search" -> push(SearchScreen(this, repo, ::open, ::pop, ::failed,chrome(route),preferences?.scopeKey))
            "library" -> if (activeProfile != null && profileData != null) push(LibraryScreen(this, activeProfile!!, profileData!!, ::open, ::pop,chrome(route)))
                else showProfiles()
            "settings" -> preferences?.let { prefs -> push(SettingsScreen(this,prefs,{navigate("home")},{navigate("home");navigate("settings")},::showProfiles,::signOut,chrome(route),
                exportBackup={data->pendingBackup=data;try{exportSettings.launch("vesper-local-settings.json")}catch(_:Exception){pendingBackup=null;Toast.makeText(this,"No file picker is available on this device.",Toast.LENGTH_LONG).show()}},
                importBackup={importScope=prefs.scopeKey;try{importSettings.launch(arrayOf("application/json","text/plain"))}catch(_:Exception){importScope=null;Toast.makeText(this,"No file picker is available on this device.",Toast.LENGTH_LONG).show()}})) }
            "account" -> showAccount()
            "calendar", "watch-together" -> V2Dialog.show(this,"native-not-migrated",if(route=="calendar")"Calendar" else "Watch Together","This screen has not been migrated to the native app yet.",listOf(V2Dialog.Action("native-not-migrated-close","Close"){}))
        }
    }
    private fun chrome(route:String)=NavigationBar(this,route,::navigate,activeProfile?.avatarId,
        autoplay=preferences?.let { { it.autoplay } },toggleAutoplay=preferences?.let { { it.autoplay=!it.autoplay } })
    // V2 PosterTile: a movie tap is an explicit autoplay (loading screen → player, no picker).
    private fun open(title: Title) = openTitle(title, title.type=="movie")
    private fun openTitle(title: Title, autoplay: Boolean) { repository?.let {
        push(DetailScreen(this, title, it, ::pop, ::failed, ::streams, { navigate("home") }, autoplay,
            chooseStream={item,id,label->streams(item,id,label,false)},preferences=preferences,openTitle={related->openTitle(related,false)},
            prefetchStreams={item,id->streamRepository()?.let { repo->StreamPrefetch.get(repo,item.type,id) } },autoplayNow=::autoplay))
    } }
    private var streamRepo:StreamRepository?=null
    private fun streamRepository()=streamRepo ?: repository?.let { StreamRepository(services.api) { services.auth.token }.also { streamRepo=it } }
    private var pendingAutoplay:Triple<Title,String,String>?=null
    private val autoplayResult=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val pending=pendingAutoplay; pendingAutoplay=null
        if(result.resultCode==PlayerActivity.RESULT_CHOOSE_STREAM && pending!=null)streams(pending.first,pending.second,pending.third,false)
    }
    /** Autoplay never shows the stream picker: it opens the player's loading screen directly. */
    private fun autoplay(title:Title,id:String,label:String) {
        val scope=preferences?.scopeKey ?: return; val repo=streamRepository() ?: return
        if(pendingAutoplay!=null)return
        StreamPrefetch.get(repo,title.type,id)
        try {
            val payload=PlaybackPayload.save(this,title,label,null,emptyList())
            pendingAutoplay=Triple(title,id,label)
            autoplayResult.launch(android.content.Intent(this,PlayerActivity::class.java).putExtra("payload",payload).putExtra("title",label)
                .putExtra("resume-key","$scope:${title.type}:$id").putExtra("resolve-key",StreamPrefetch.key(title.type,id)))
        } catch(_:Exception) { pendingAutoplay=null; streams(title,id,label,false) }
    }
    private fun openBrand(brand: Brand) { repository?.let {
        if(brand.kind=="collections")push(CollectionScreen(this,it,brand,{title->openTitle(title,false)},{title->openTitle(title,true)},::pop,{navigate("home")},preferences,chrome("boxsets")))
        else push(BrowseGridScreen(this,it,"series",::open,::navigate,::failed,brand,chrome("home")))
    } }
    private fun streams(title: Title, id: String, label: String) {
        if(preferences?.shouldAutoplay(title.type)==true)autoplay(title,id,label) else streams(title,id,label,false)
    }
    private fun streams(title: Title, id: String, label: String,allowAutoplay:Boolean) {
        val scope = preferences?.scopeKey ?: return
        var available=emptyList<Stream>()
        push(StreamScreen(this, label, title.type, id, streamRepository() ?: return, ::pop, { stream ->
            try {
                val payload=PlaybackPayload.save(this,title,label,stream,available)
                startActivity(android.content.Intent(this, PlayerActivity::class.java)
                    .putExtra("payload",payload).putExtra("title", label).putExtra("resume-key", "$scope:${title.type}:$id"))
            } catch(_:Exception) { Toast.makeText(this,"Couldn't open this stream. Please choose another.",Toast.LENGTH_LONG).show() }
        }, ::failed,allowAutoplay && preferences?.shouldAutoplay(title.type)==true,{ available=it },title))
    }
    private fun failed(error: Throwable) {
        if (error is ApiFailure && (error.status == 401 || error.status == 403) && !invalidating) {
            invalidating = true
            lifecycleScope.launch {
                services.auth.clear(); repository = null; account = null
                activeProfile = null; profileData = null; login()
            }
        }
    }
    private fun replace(screen: NativeScreen) {
        screens.forEach(NativeScreen::dispose); screens.clear(); screens.add(screen); display(screen)
    }
    private fun push(screen: NativeScreen) { screens.lastOrNull()?.hidden(); screens.add(screen); display(screen) }
    private fun display(screen: NativeScreen) { container.removeAllViews(); container.addView(screen.root, FrameLayout.LayoutParams(-1, -1)); screen.shown() }
    private fun pop() {
        if (screens.size <= 1) return
        screens.removeAt(screens.lastIndex).dispose(); currentRoute = ""; display(screens.last())
    }
    private fun showAccount() {
        V2Dialog.show(this,"account-dialog",account?.label.orEmpty(),activeProfile?.name.orEmpty(),listOf(
            V2Dialog.Action("account-close","Back","back") {},
            V2Dialog.Action("account-profiles","Switch profile","user",true) { showProfiles() },
            V2Dialog.Action("account-signout","Sign out","logout") { signOut() }),titleKey="account-name")
    }
    private fun signOut() {
        authJob?.cancel()
        screens.forEach(NativeScreen::dispose); screens.clear(); container.removeAllViews()
        container.addView(StatusBand(this, "signout-status").apply { show("Signing out…") },
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        repository = null; account = null; activeProfile = null; profileData = null; streamRepo = null; StreamPrefetch.clear()
        authJob = lifecycleScope.launch { services.auth.signOut { login() } }
    }
    private fun confirmExit() {
        ExitDialog.show(this)
    }
    override fun onDestroy() { screens.forEach(NativeScreen::dispose); screens.clear(); super.onDestroy() }
    override fun onStop() { screens.forEach(NativeScreen::backgrounded); super.onStop() }
}