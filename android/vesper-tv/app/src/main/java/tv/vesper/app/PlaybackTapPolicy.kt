package tv.vesper.app

import kotlin.math.hypot

/** Pure gesture policy; tested without Android or a mocked player. */
internal class PlaybackTapPolicy(private val slop: Float, private val maxDurationMs: Long = 900L) {
    private var x = 0f
    private var y = 0f
    private var began = 0L
    private var valid = false

    fun down(px: Float, py: Float, time: Long, pointers: Int, physicalTouch: Boolean) {
        x = px; y = py; began = time
        valid = physicalTouch && pointers == 1
    }
    fun move(px: Float, py: Float, pointers: Int) {
        if (pointers != 1 || hypot(px - x, py - y) > slop) valid = false
    }
    fun cancel() { valid = false }
    fun up(px: Float, py: Float, time: Long, pointers: Int): Boolean {
        move(px, py, pointers)
        val accepted = valid && time >= began && time - began <= maxDurationMs
        valid = false
        return accepted
    }
}