package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*

class DetailScreen(c: Context, private val seed: Title, private val repository: CatalogRepository,
    back: () -> Unit, private val failed: (Throwable) -> Unit) : NativeScreen(c) {
    private val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .24f }
    private val title = TvUi.heading(c, "detail-title", seed.name, 32f)
    private val metadata = TvUi.text(c, "detail-metadata", color = TvUi.accent)
    private val summary = TvUi.text(c, "detail-synopsis", size = 15f, color = TvUi.muted)
    private val backButton = TvUi.icon(c, "detail-back", "Back", R.drawable.ic_back, back)
    private val status = StatusBand(c, "detail-status")
    private val episodes = TvUi.identify(RecyclerView(c), "detail-episodes").apply { layoutManager = LinearLayoutManager(c); itemAnimator = null }
    private var job: Job? = null
    init {
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        val content = TvUi.column(c).apply { setPadding(TvUi.dp(c, 44), TvUi.dp(c, 22), TvUi.dp(c, 44), TvUi.dp(c, 18)) }
        val header = TvUi.row(c)
        header.addView(backButton, LinearLayout.LayoutParams(TvUi.dp(c, 42), TvUi.dp(c, 42)))
        header.addView(TvUi.text(c, "detail-type", if (seed.type == "series") "  TV SHOW" else "  MOVIE", 13f, TvUi.accent))
        content.addView(header); content.addView(TvUi.gap(c, 14))
        content.addView(title); content.addView(metadata); content.addView(TvUi.gap(c, 12))
        val scroll = TvUi.identify(ScrollView(c), "detail-synopsis-scroll").apply { isFocusable = true; addView(summary) }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(status)
        content.addView(episodes, LinearLayout.LayoutParams(-1, 0, if (seed.type == "series") 1.2f else 0f))
        if (seed.type != "series") episodes.visibility = View.GONE
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        bind(seed); status.onRetry = { load() }; load()
    }
    private fun bind(value: Title) {
        title.text = value.name; metadata.text = listOf(value.subtitle, value.genres.joinToString(" · ")).filter(String::isNotBlank).joinToString(" · ")
        summary.text = value.description.ifBlank { "No synopsis available." }
        image.load(value.backdrop.takeIf(String::isNotBlank)) { size(1280, 720); crossfade(false) }
        episodes.adapter = EpisodeAdapter(value.episodes)
    }
    private fun load() {
        job?.cancel(); job = scope.launch {
            status.show("Loading title details…")
            try { bind(repository.detail(seed)); status.clear()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status.show("Couldn't load all title details.", true); failed(e) }
        }
    }
    override fun initialFocus(): View = backButton
}

private class EpisodeAdapter(private val episodes: List<Episode>) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
    override fun getItemCount() = episodes.size
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = Holder(
        TvUi.text(parent.context, "unbound-episode", size = 14f).apply {
            isFocusable = true; background = TvUi.focus(parent.context, false)
            setPadding(16, 14, 16, 14); layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val episode = episodes[position]
        TvUi.identify(holder.label, "detail-episode-${episode.id}")
        holder.label.text = "S${episode.season} · E${episode.number}  ${episode.name}"
        holder.label.contentDescription = holder.label.text
    }
}