package tv.onnowtv.livetv.data

/**
 * A single Collection (saved category) in the Live TV library.
 *
 * Stored in [CollectionsStore] keyed by [id].  The [coverHash]
 * points at a `/api/library/cover/{hash}.png` resource on the
 * backend — a deterministic SHA-derived id so reinstalls can
 * re-download the same artwork.
 */
data class Collection(
    val id: String,           // UUID generated on the device
    val categoryId: String,   // Xtream category id (back-link to bundle)
    val name: String,         // human-readable label
    val coverHash: String?,   // backend hash for the AI-generated cover
    val coverUrl: String?,    // resolved full URL ("https://…/api/library/cover/…")
    val addedAt: Long,        // epoch millis
)
