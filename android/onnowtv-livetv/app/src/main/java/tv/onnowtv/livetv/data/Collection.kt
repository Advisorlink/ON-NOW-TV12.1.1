package tv.onnowtv.livetv.data

/**
 * A single saved category (a "Collection") in the Live TV library.
 *
 * Stored in [CollectionsStore] keyed by [id].  The [coverHash]
 * points at a `/api/library/cover/{hash}.png` resource on the
 * backend — a deterministic SHA-derived id so reinstalls can
 * re-download the same artwork.
 *
 * **Why not just call it "Collection"?**  Because `Collection`
 * shadows `kotlin.collections.Collection<T>` and confuses Kotlin's
 * unresolved-reference + extension-lookup machinery in callers
 * that also call `.awaitAll()` (extension on
 * `kotlin.collections.Collection<Deferred<T>>`).  Prefix-naming
 * sidesteps the entire problem.
 */
data class LibraryCollection(
    val id: String,                    // UUID generated on the device
    val name: String,                  // human-readable label
    val coverHash: String?,            // backend hash for the AI-generated cover
    val coverUrl: String?,             // resolved full URL ("https://…/api/library/cover/…") OR "file://…"
    val addedAt: Long,                 // epoch millis
    val channelIds: List<String> = emptyList(),  // user-picked Channel.id values
    val categoryId: String = "",       // legacy: pre-v2 Collections wrapped a single Xtream category
)
