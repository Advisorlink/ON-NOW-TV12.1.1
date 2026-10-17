package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnNextLayout
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.profile.*

class ProfilePickerScreen(c: Context, private val cloud: ProfileCloudRepository,
    private val pick: (CloudSnapshot, CloudProfile) -> Unit, browse: () -> Unit,
    signOut: () -> Unit, private val failed: (Throwable) -> Unit) : NativeScreen(c) {
    private val status = StatusBand(c, "profiles-status")
    private val emptyBrowse = TvUi.button(c, "profiles-browse", "Browse") { browse() }.apply { visibility = View.GONE }
    private val logout = TvUi.button(c, "profiles-signout", "Sign out", signOut)
    private val grid = TvUi.identify(RecyclerView(c), "profiles-grid").apply {
        layoutManager = GridLayoutManager(c, 4); itemAnimator = null
        FocusMotion.prepareRail(this,8)
    }
    private var job: Job? = null
    init {
        val content = TvUi.column(c).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(TvUi.dp(c, 48), TvUi.dp(c, 32), TvUi.dp(c, 48), TvUi.dp(c, 24))
        }
        content.addView(TvUi.brand(c, "profiles-brand", 16f))
        content.addView(TvUi.gap(c, 55))
        content.addView(TvUi.heading(c, "profiles-title", "Who's ready to watch?", 36f))
        content.addView(TvUi.gap(c, 8))
        content.addView(TvUi.text(c, "profiles-subtitle", "Choose a profile to continue", 9f, TvUi.muted))
        content.addView(TvUi.gap(c, 24))
        content.addView(status, LinearLayout.LayoutParams(-1, -2))
        content.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = TvUi.row(c)
        V2Ui.stylePill(logout,"logout"); V2Ui.stylePill(emptyBrowse,"grid")
        actions.addView(emptyBrowse); actions.addView(logout)
        content.addView(actions)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        status.onRetry = { load() }; load()
    }
    private fun load() {
        job?.cancel(); job = scope.launch {
            status.show("Loading your profiles…"); emptyBrowse.visibility = View.GONE
            try {
                val snapshot = cloud.pull()
                val profiles = snapshot?.profiles.orEmpty()
                if (profiles.isEmpty()) {
                    grid.adapter = null
                    status.show("No saved Vesper profiles.")
                    emptyBrowse.visibility = View.VISIBLE; emptyBrowse.requestFocus()
                } else {
                    val columns = minOf(profiles.size, 5)
                    (grid.layoutManager as GridLayoutManager).spanCount = columns
                    grid.layoutParams = LinearLayout.LayoutParams(TvUi.dp(context, columns * 126+16), 0, 1f)
                    grid.adapter = ProfilesAdapter(profiles) { pick(snapshot!!, it) }
                    status.clear()
                    grid.doOnNextLayout { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status.show("Couldn't load your profiles.", true); status.focusRetry(); failed(e) }
        }
    }
    override fun initialFocus(): View = grid.takeIf { (it.adapter?.itemCount ?: 0) > 0 } ?: emptyBrowse.takeIf { it.visibility == View.VISIBLE } ?: logout
}

private class ProfilesAdapter(private val profiles: List<CloudProfile>, private val pick: (CloudProfile) -> Unit) : RecyclerView.Adapter<ProfilesAdapter.Holder>() {
    private var assetNames: Set<String>? = null
    class Holder(c: Context) : RecyclerView.ViewHolder(TvUi.column(c)) {
        val image = ImageView(c)
        val name = TvUi.text(c, "unbound-profile-name", size = 11f).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val lock = TvUi.text(c, "unbound-profile-lock", size = 7f, color = TvUi.muted).apply { gravity = Gravity.CENTER }
        init { (itemView as LinearLayout).apply {
            gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; isClickable = true
            foreground = TvUi.outline(c, 12)
            setPadding(12, 12, 12, 12)
            layoutParams = RecyclerView.LayoutParams(-1, TvUi.dp(c, 150)).apply { setMargins(8, 8, 8, 8) }
            addView(image, LinearLayout.LayoutParams(TvUi.dp(c, 90), TvUi.dp(c, 90)))
            addView(TvUi.gap(c, 12))
            addView(name, LinearLayout.LayoutParams(-1, TvUi.dp(c,16))); addView(lock,LinearLayout.LayoutParams(-1,TvUi.dp(c,10)))
        } }
    }
    override fun getItemCount() = profiles.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context)
    override fun onBindViewHolder(holder: Holder, position: Int) {
        FocusMotion.bind(holder.itemView,radius=45,halo=holder.image)
        val profile = profiles[position]
        TvUi.identify(holder.itemView, "profile-${profile.id}")
        TvUi.identify(holder.image, "profile-avatar-${profile.id}")
        TvUi.identify(holder.name, "profile-name-${profile.id}")
        TvUi.identify(holder.lock, "profile-lock-${profile.id}")
        holder.name.text = profile.name; holder.lock.text = if (profile.requiresPin) "PIN protected" else ""
        holder.lock.visibility=if(profile.requiresPin)View.VISIBLE else View.GONE
        holder.itemView.contentDescription = profile.name + if (profile.requiresPin) ", PIN protected" else ""
        val assets = assetNames ?: holder.itemView.context.assets.list("avatars").orEmpty().toSet().also { assetNames = it }
        val file = "${profile.avatarId}.jpg".takeIf { it in assets } ?: "fn-popcorn-fg.jpg"
        holder.image.load("file:///android_asset/avatars/$file") { size(200, 200); transformations(CircleCropTransformation()) }
        holder.itemView.setOnClickListener { pick(profile) }
    }
    override fun onViewRecycled(holder: Holder) { holder.image.load(null); FocusMotion.reset(holder.itemView) }
}