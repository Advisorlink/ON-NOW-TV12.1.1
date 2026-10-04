package tv.onnowtv.livetv.data

import java.io.File

private fun assertTrue(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private fun assertFalse(cond: Boolean, msg: String) = assertTrue(!cond, msg)

private fun writeChannel(dir: File, name: String, body: String) {
    dir.mkdirs()
    File(dir, name).writeText(body)
}

private fun readOrNull(file: File): String? = if (file.exists()) file.readText() else null

private fun testSuccessCarriesOldAndFresh(tmp: File) {
    val live = File(tmp, "live")
    val staging = File(tmp, "staging")
    val oldOnly = "old-only.jsonl.gz"
    val freshOnly = "fresh-only.jsonl.gz"

    writeChannel(live, oldOnly, "old")
    writeChannel(staging, freshOnly, "fresh")

    val ok = CacheDirectorySwap.promote(live, staging)
    assertTrue(ok, "promote should succeed")
    assertTrue(File(live, oldOnly).exists(), "old file should be carried over")
    assertTrue(File(live, freshOnly).exists(), "fresh file should exist")
}

private fun testLiveToOldRenameFailurePreservesCache(tmp: File) {
    val live = File(tmp, "live")
    val staging = File(tmp, "staging")
    val old = File(tmp, "live.old")
    writeChannel(live, "live-a.jsonl.gz", "A")
    writeChannel(staging, "staging-b.jsonl.gz", "B")

    val ok = CacheDirectorySwap.promote(live, staging) { from, to ->
        if (from == live && to == old) return@promote false
        from.renameTo(to)
    }

    assertFalse(ok, "promote should fail when live->old rename fails")
    assertTrue(File(live, "live-a.jsonl.gz").exists(), "live cache should remain intact")
    assertTrue(File(staging, "staging-b.jsonl.gz").exists(), "staging should remain for diagnostics")
}

private fun testStagingToLiveFailureRollsBack(tmp: File) {
    val live = File(tmp, "live")
    val staging = File(tmp, "staging")
    val old = File(tmp, "live.old")
    writeChannel(live, "live-a.jsonl.gz", "A")
    writeChannel(staging, "staging-b.jsonl.gz", "B")

    val ok = CacheDirectorySwap.promote(live, staging) { from, to ->
        when {
            from == staging && to == live -> false // fail primary swap
            else -> from.renameTo(to)
        }
    }

    assertFalse(ok, "promote should fail when staging->live rename fails")
    assertTrue(File(live, "live-a.jsonl.gz").exists(), "live should be restored from .old rollback")
    assertFalse(old.exists(), "old backup should be consumed by successful rollback")
}

private fun testRollbackFailureRetainsOldBackup(tmp: File) {
    val live = File(tmp, "live")
    val staging = File(tmp, "staging")
    val old = File(tmp, "live.old")
    writeChannel(live, "live-a.jsonl.gz", "A")
    writeChannel(staging, "staging-b.jsonl.gz", "B")

    val ok = CacheDirectorySwap.promote(live, staging) { from, to ->
        when {
            from == staging && to == live -> false // fail primary swap
            from == old && to == live -> false // fail rollback too
            else -> from.renameTo(to)
        }
    }

    assertFalse(ok, "promote should fail when swap and rollback both fail")
    assertTrue(old.exists(), "old backup must remain if rollback fails")
    assertFalse(live.exists(), "live may be absent when rollback cannot restore")
}

private fun testStaleBackupRecovery(tmp: File) {
    val live = File(tmp, "live")
    val staging = File(tmp, "staging")
    val old = File(tmp, "live.old")

    // stale backup exists, live missing
    writeChannel(old, "old-only.jsonl.gz", "OLD")
    writeChannel(staging, "new-only.jsonl.gz", "NEW")

    val ok = CacheDirectorySwap.promote(live, staging)
    assertTrue(ok, "promote should recover stale .old into live then swap")
    // old should be deleted on success
    assertFalse(old.exists(), "stale backup should be cleaned after successful promote")
    assertTrue(File(live, "old-only.jsonl.gz").exists(), "recovered old channel should carry forward")
    assertTrue(File(live, "new-only.jsonl.gz").exists(), "new channel should exist")
}

private fun testAbsentStagingNoMutation(tmp: File) {
    val live = File(tmp, "live")
    val staging = File(tmp, "missing-staging")
    writeChannel(live, "live-a.jsonl.gz", "A")
    val before = readOrNull(File(live, "live-a.jsonl.gz"))

    val ok = CacheDirectorySwap.promote(live, staging)
    assertFalse(ok, "promote should fail with absent staging dir")
    assertTrue(File(live, "live-a.jsonl.gz").exists(), "live cache should be untouched")
    assertTrue(readOrNull(File(live, "live-a.jsonl.gz")) == before, "live cache content should be unchanged")
}

fun main() {
    val root = createTempDir(prefix = "cache-swap-jvm-")
    val cases = listOf(
        "success carries old and fresh" to ::testSuccessCarriesOldAndFresh,
        "live->old rename failure preserves cache" to ::testLiveToOldRenameFailurePreservesCache,
        "staging->live failure rolls back" to ::testStagingToLiveFailureRollsBack,
        "rollback failure retains .old" to ::testRollbackFailureRetainsOldBackup,
        "stale backup recovery" to ::testStaleBackupRecovery,
        "absent staging no mutation" to ::testAbsentStagingNoMutation,
    )

    var failures = 0
    cases.forEachIndexed { i, (name, fn) ->
        val dir = File(root, "case-${i + 1}").apply { mkdirs() }
        try {
            fn(dir)
            println("PASS: $name")
        } catch (t: Throwable) {
            failures += 1
            println("FAIL: $name -> ${t.message}")
            t.printStackTrace()
        }
    }

    if (failures > 0) {
        throw IllegalStateException("$failures CacheDirectorySwap JVM tests failed")
    }
    println("All CacheDirectorySwap JVM tests passed")
}
