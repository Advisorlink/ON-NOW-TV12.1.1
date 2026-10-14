package tv.onnowtv.livetv.data

import java.util.concurrent.ConcurrentHashMap

/** The SAME hydrated guide used by What's On. One immutable bundle identity, no Activity references. */
object SharedSportsGuide {
    private data class Session(val owner: XtreamBundle, val rows: ConcurrentHashMap<String, List<Programme>>)
    @Volatile private var session: Session? = null

    @Synchronized fun attach(bundle: XtreamBundle): ConcurrentHashMap<String, List<Programme>> {
        session?.takeIf { it.owner === bundle }?.let { return it.rows }
        return ConcurrentHashMap(bundle.epg).also { session = Session(bundle, it) }
    }
    fun rows(bundle: XtreamBundle): Map<String, List<Programme>> = session?.takeIf { it.owner === bundle }?.rows ?: bundle.epg
    /** Pass an explicit snapshot/reference into derived viewer-specific bundles used by replay checks. */
    fun enriched(bundle: XtreamBundle): XtreamBundle = bundle.copy(epg = rows(bundle))
}