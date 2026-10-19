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
            setPadding(TvUi.dp(c, 48), TvUi.dp(c, 36), TvUi.dp(c, 48), TvUi.dp(c, 30))
        }
        // V2 ProfileSelect: radial accent wash at 50%/30% over the base background.
        root.background=android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(TvUi.background),
            android.graphics.drawable.GradientDrawable().apply { gradientType=android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                colors=intArrayOf(android.graphics.Color.argb(46,android.graphics.Color.red(TvUi.accent),android.graphics.Color.green(TvUi.accent),android.graphics.Color.blue(TvUi.accent)),android.graphics.Color.TRANSPARENT)
                gradientRadius=c.resources.displayMetrics.widthPixels*.42f;setGradientCenter(.5f,.3f) }))
        content.addView(TvUi.row(c).apply {
            addView(TvUi.heading(c,"profiles-brand-wordmark","ON NOW T",25f).apply { fontVariationSettings="'wght' 700";letterSpacing=-.03f })
            addView(TvUi.heading(c,"profiles-brand-v2","V2",27f).apply { fontVariationSettings="'wght' 800";letterSpacing=-.04f;setTextColor(TvUi.bright)
                setShadowLayer(TvUi.dp(c,9).toFloat(),0f,0f,android.graphics.Color.argb(179,android.graphics.Color.red(TvUi.accent),android.graphics.Color.green(TvUi.accent),android.graphics.Color.blue(TvUi.accent))) })
        }.also { TvUi.identify(it,"profiles-brand") })
        val middle = TvUi.column(c).apply { gravity = Gravity.CENTER }
        middle.addView(TvUi.heading(c, "profiles-title", "Who's ready to watch?", 48f).apply { letterSpacing=-.03f })
        middle.addView(TvUi.gap(c, 6))
        middle.addView(TvUi.text(c, "profiles-subtitle", "Choose a profile to continue", 12f, android.graphics.Color.rgb(155,168,194)))
        middle.addView(TvUi.gap(c, 32))
        middle.addView(status, LinearLayout.LayoutParams(-1, -2))
        middle.addView(grid, LinearLayout.LayoutParams(-1, -2))
        val actions = TvUi.row(c)
        V2Ui.stylePill(logout,"logout"); V2Ui.stylePill(emptyBrowse,"grid")
        listOf(logout,emptyBrowse).forEach { it.textSize=10.5f;it.setTextColor(android.graphics.Color.rgb(155,168,194)) }
        actions.addView(emptyBrowse,LinearLayout.LayoutParams(-2,TvUi.dp(c,33)).apply { marginEnd=TvUi.dp(c,9) }); actions.addView(logout,LinearLayout.LayoutParams(-2,TvUi.dp(c,33)))
        middle.addView(TvUi.gap(c, 30)); middle.addView(actions)
        content.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))
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
                    grid.layoutParams = LinearLayout.LayoutParams(TvUi.dp(context, columns * 143+16), if(profiles.size>columns) TvUi.dp(context,190) else -2)
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
        val name = TvUi.heading(c, "unbound-profile-name", "", 14.25f).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing=-.01f }
        val lock = TvUi.text(c, "unbound-profile-lock", size = 7.5f, color = android.graphics.Color.rgb(91,101,128)).apply { gravity = Gravity.CENTER; letterSpacing=.24f
            typeface=androidx.core.content.res.ResourcesCompat.getFont(c,tv.onnowtv.vespernative.R.font.jetbrains_mono) }
        init { (itemView as LinearLayout).apply {
            gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; isClickable = true
            foreground = TvUi.outline(c, 12)
            setPadding(12, 12, 12, 12)
            layoutParams = RecyclerView.LayoutParams(-1, TvUi.dp(c, 166)).apply { setMargins(TvUi.dp(c,8), TvUi.dp(c,8), TvUi.dp(c,8), TvUi.dp(c,8)) }
            addView(image, LinearLayout.LayoutParams(TvUi.dp(c, 98), TvUi.dp(c, 98)))
            addView(TvUi.gap(c, 12))
            addView(name, LinearLayout.LayoutParams(-1, TvUi.dp(c,20))); addView(TvUi.gap(c,3)); addView(lock,LinearLayout.LayoutParams(-1,TvUi.dp(c,11)))
        } }
    }
    override fun getItemCount() = profiles.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context)
    override fun onBindViewHolder(holder: Holder, position: Int) {
        FocusMotion.bind(holder.itemView,radius=49,halo=holder.image)
        val profile = profiles[position]
        TvUi.identify(holder.itemView, "profile-${profile.id}")
        TvUi.identify(holder.image, "profile-avatar-${profile.id}")
        TvUi.identify(holder.name, "profile-name-${profile.id}")
        TvUi.identify(holder.lock, "profile-lock-${profile.id}")
        holder.name.text = profile.name; holder.lock.text = if (profile.requiresPin) "PIN PROTECTED" else ""
        holder.lock.visibility=if(profile.requiresPin)View.VISIBLE else View.GONE
        holder.itemView.contentDescription = profile.name + if (profile.requiresPin) ", PIN protected" else ""
        val assets = assetNames ?: holder.itemView.context.assets.list("avatars").orEmpty().toSet().also { assetNames = it }
        val file = "${profile.avatarId}.jpg".takeIf { it in assets } ?: "fn-popcorn-fg.jpg"
        holder.image.load("file:///android_asset/avatars/$file") { size(200, 200); transformations(CircleCropTransformation()) }
        holder.itemView.setOnClickListener { pick(profile) }
    }
    override fun onViewRecycled(holder: Holder) { holder.image.load(null); FocusMotion.reset(holder.itemView) }
}