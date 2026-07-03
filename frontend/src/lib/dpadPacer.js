/**
 * dpadPacer — frame-aware pacing for held D-pad keys.
 *
 * The OS auto-repeats arrow keys at ~30 Hz.  Each spatial move costs
 * focus() + style recalc + layout + scroll — a cheap TV box cannot
 * paint that fast, so keydown events BACKLOG and the cursor keeps
 * moving after the user releases the key ("stuck / runaway" feel).
 *
 * Rules:
 *  • Discrete presses (e.repeat === false) are ALWAYS processed
 *    instantly — 1:1 tap feel is untouchable.
 *  • Held-key repeats are processed at most once per painted frame
 *    AND no faster than MIN_REPEAT_MS.  Excess repeats are DROPPED,
 *    never queued — releasing the key stops movement immediately.
 *  • The frame gate self-adapts: a fast PC paces at MIN_REPEAT_MS,
 *    a slow box paces at whatever rate it can actually render.
 *
 * One shared pacer for every D-pad surface (spatial engine, Home
 * row-walker, …) so mixed handlers can't double-move in one frame.
 */

const MIN_REPEAT_MS = 70;

let lastMoveTs = 0;
let framePending = false;

function mark() {
    lastMoveTs = performance.now();
    framePending = true;
    requestAnimationFrame(() => {
        // One more frame so the move's paint actually committed
        // before we allow the next repeat on slow boxes.
        framePending = false;
    });
}

/**
 * Returns true when this keydown should produce a move.
 * Callers must still preventDefault() on dropped repeats so the
 * browser doesn't fall back to native scrolling.
 */
export function paceDpad(e) {
    if (!e || e.repeat !== true) {
        mark();
        return true;
    }
    if (framePending) return false;
    if (performance.now() - lastMoveTs < MIN_REPEAT_MS) return false;
    mark();
    return true;
}
