package tv.onnowtv.livetv.data

/** Xtream implementations disagree on primitive types. Unknown is never a recording guarantee. */
object SportsArchiveMetadata {
    fun flag(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> when (value.toDouble()) { 1.0 -> true; 0.0 -> false; else -> null }
        is String -> when (value.trim().lowercase()) { "1", "true", "yes", "y" -> true; "0", "false", "no", "n" -> false; else -> null }
        else -> null
    }
    fun timestamp(value: Any?): Long? {
        val raw = when (value) { is Number -> value.toLong(); is String -> value.trim().toLongOrNull(); else -> null } ?: return null
        val seconds = if (raw >= 100_000_000_000L) raw / 1000 else raw
        return seconds.takeIf { it in 946684800L..4102444800L }?.times(1000)
    }
    fun retentionDays(value: Any?): Int? {
        val number = when (value) { is Number -> value.toDouble(); is String -> value.trim().toDoubleOrNull(); else -> null } ?: return null
        return number.takeIf { it.isFinite() && it in 0.0..366.0 && it == it.toInt().toDouble() }?.toInt()
    }
}