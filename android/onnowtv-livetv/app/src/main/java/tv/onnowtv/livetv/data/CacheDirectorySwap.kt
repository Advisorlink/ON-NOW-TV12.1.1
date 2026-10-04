package tv.onnowtv.livetv.data

import java.io.File

/** Preserve the working guide on disk-full/rename failure. Pure JVM logic
 * so failure and rollback paths can be verified without an Android device. */
internal object CacheDirectorySwap {
    fun promote(live: File, staging: File, rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) }): Boolean {
        if (!staging.isDirectory) return false
        val old = File(live.parentFile, "${live.name}.old")
        if (!live.exists() && old.exists() && !rename(old, live)) return false
        if (old.exists() && !old.deleteRecursively()) return false
        // Copy, don't move, carry-over channels. Rollback must retain ALL
        // old guide files even if the second rename fails.
        live.listFiles { file -> file.name.endsWith(".jsonl.gz") }?.forEach { file ->
            val target = File(staging, file.name)
            if (!target.exists()) file.copyTo(target)
        }
        if (live.exists() && !rename(live, old)) return false
        if (!rename(staging, live)) {
            if (old.exists()) rename(old, live)
            return false // retain .old if even rollback could not be renamed
        }
        old.deleteRecursively()
        return true
    }
}