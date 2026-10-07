package tv.vesper.app

private fun expect(name: String, condition: Boolean) {
    if (!condition) throw IllegalStateException("FAILED: $name")
    println("PASS: $name")
}

fun main() {
    val slop = 10f
    val timeout = 900L

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(100f, 100f, 1_000L, pointers = 1, physicalTouch = true)
        expect("stationary finger accepted once", p.up(100f, 100f, 1_200L, pointers = 1))
        expect("accepted once only", !p.up(100f, 100f, 1_210L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(10f, 10f, 10L, pointers = 1, physicalTouch = false)
        expect("mouse/non-physical rejected", !p.up(10f, 10f, 15L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(0f, 0f, 10L, pointers = 1, physicalTouch = true)
        p.move(30f, 0f, pointers = 1)
        expect("move over slop cancels", !p.up(0f, 0f, 20L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(0f, 0f, 10L, pointers = 1, physicalTouch = true)
        p.move(30f, 0f, pointers = 1)
        p.move(0f, 0f, pointers = 1)
        expect("return to start after drag still canceled", !p.up(0f, 0f, 20L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(0f, 0f, 10L, pointers = 1, physicalTouch = true)
        p.move(0f, 0f, pointers = 2)
        expect("multi-pointer canceled", !p.up(0f, 0f, 20L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(0f, 0f, 20L, pointers = 1, physicalTouch = true)
        expect("negative elapsed rejected", !p.up(0f, 0f, 19L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(0f, 0f, 20L, pointers = 1, physicalTouch = true)
        expect("long press timeout respected", !p.up(0f, 0f, 20L + timeout + 1L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(0f, 0f, 20L, pointers = 1, physicalTouch = true)
        p.cancel()
        expect("cancel then up rejected", !p.up(0f, 0f, 30L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(0f, 0f, 20L, pointers = 1, physicalTouch = true)
        p.cancel()
        p.down(1f, 1f, 100L, pointers = 1, physicalTouch = true)
        expect("new down resets state", p.up(1f, 1f, 120L, pointers = 1))
    }

    run {
        val p = PlaybackTapPolicy(slop, timeout)
        p.down(0f, 0f, 20L, pointers = 1, physicalTouch = true)
        expect("stylus treated as physical (via policy flag)", p.up(0f, 0f, 25L, pointers = 1))
    }

    println("PASS: PlaybackTapPolicy JVM harness")
}
