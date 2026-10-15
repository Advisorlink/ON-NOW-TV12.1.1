package tv.onnowtv.vespernative

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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

class MainActivity : AppCompatActivity() {
    private lateinit var services: NativeServices
    private lateinit var container: FrameLayout
    private var repository: CatalogRepository? = null
    private val screens = mutableListOf<NativeScreen>()
    private var account: Account? = null
    private var activeProfile: CloudProfile? = null
    private var profileData: ProfileData? = null
    private var authJob: Job? = null
    private var invalidating = false

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
                repository = CatalogRepository(services.api) { services.auth.token }
                navigate("home")
            }
            if (profile.requiresPin) push(ProfilePinScreen(this, profile, snapshot, ::activate, ::pop))
            else activate(snapshot.openProfile(profile.id))
        }, { navigate("home") }, ::signOut, ::failed))
    }
    private fun navigate(route: String) {
        val repo = repository ?: return
        when (route) {
            "home", "movie", "series" -> replace(HomeScreen(this, repo, route.takeUnless { it == "home" }, ::open, ::navigate, ::failed, profileData != null))
            "search" -> push(SearchScreen(this, repo, ::open, ::pop, ::failed))
            "library" -> activeProfile?.let { profile -> profileData?.let { push(LibraryScreen(this, profile, it, ::open, ::pop)) } }
            "account" -> showAccount()
        }
    }
    private fun open(title: Title) { repository?.let { push(DetailScreen(this, title, it, ::pop, ::failed)) } }
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
        screens.removeAt(screens.lastIndex).dispose(); display(screens.last())
    }
    private fun showAccount() {
        val content = TvUi.column(this).apply { setPadding(32, 24, 32, 24) }
        content.addView(TvUi.heading(this, "account-name", account?.label.orEmpty()))
        val dialog = AlertDialog.Builder(this).setView(content).create()
        content.addView(TvUi.button(this, "account-close", "Back") { dialog.dismiss() })
        content.addView(TvUi.button(this, "account-profiles", "Switch profile") { dialog.dismiss(); showProfiles() })
        content.addView(TvUi.button(this, "account-signout", "Sign out") {
            dialog.dismiss()
            signOut()
        })
        dialog.setOnShowListener { TvUi.identify(dialog.window!!.decorView, "account-dialog") }
        dialog.show()
    }
    private fun signOut() {
        authJob?.cancel()
        screens.forEach(NativeScreen::dispose); screens.clear(); container.removeAllViews()
        container.addView(StatusBand(this, "signout-status").apply { show("Signing out…") },
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        repository = null; account = null; activeProfile = null; profileData = null
        authJob = lifecycleScope.launch { services.auth.signOut { login() } }
    }
    private fun confirmExit() {
        val content = TvUi.column(this).apply { setPadding(32, 24, 32, 24) }
        content.addView(TvUi.heading(this, "exit-title", "Exit Vesper?"))
        val dialog = AlertDialog.Builder(this).setView(content).create()
        content.addView(TvUi.button(this, "exit-cancel", "Stay") { dialog.dismiss() })
        content.addView(TvUi.button(this, "exit-confirm", "Exit") { dialog.dismiss(); finish() })
        dialog.setOnShowListener { TvUi.identify(dialog.window!!.decorView, "exit-dialog") }
        dialog.show()
    }
    override fun onDestroy() { screens.forEach(NativeScreen::dispose); screens.clear(); super.onDestroy() }
}