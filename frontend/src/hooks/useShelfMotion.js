import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { getPerf } from '@/lib/perfLab';

const DURATION = 180;
const OPEN_DURATION = 240;
const EASING = 'cubic-bezier(0.22, 1, 0.36, 1)';

/** One owner for a shelf's horizontal position. Commit geometry once, then
 * animate only translations (FLIP), never widths or scrollLeft per frame.
 * The wide preview stays parked; adjacent posters glide beneath it. A new
 * press samples the current visual position, so reversing never queues moves.
 */
export default function useShelfMotion(scroller, count, wide) {
    const [selection, setSelection] = useState(() => ({ index: count > 1 ? 1 : 0 }));
    const before = useRef(null);
    const animations = useRef(new Map());
    const hasFocused = useRef(false);
    const prevWide = useRef(wide);

    // Record every cell's current visual position right before a width
    // change the caller is about to commit (the 'pause' slide-open).
    const snapshot = useCallback(() => {
        const rail = scroller.current;
        if (!rail || !hasFocused.current) return;
        const cells = Array.from(rail.querySelectorAll(':scope > [data-shelf-cell]'));
        before.current = new Map(cells.map((el) => [el, el.getBoundingClientRect().left]));
    }, [scroller]);

    const cancelMotion = () => {
        animations.current.forEach((animation) => animation.cancel());
        animations.current.clear();
    };

    const onFocus = (event) => {
        const rail = scroller.current;
        const tile = event.target.closest?.('[data-preview="true"]');
        const cell = tile?.closest('[data-shelf-cell]');
        if (!rail || !cell || cell.parentElement !== rail) return;
        // Mouse/programmatic focus uses the same single ring as the D-pad.
        document.querySelectorAll('[data-focused="true"]').forEach((el) => {
            if (el !== tile) el.removeAttribute('data-focused');
        });
        tile.setAttribute('data-focused', 'true');
        const cells = Array.from(rail.querySelectorAll(':scope > [data-shelf-cell]'));
        // Read all old visual positions BEFORE React changes the two widths.
        // getBoundingClientRect includes an interrupted compositor translation.
        before.current = hasFocused.current
            ? new Map(cells.map((el) => [el, el.getBoundingClientRect().left]))
            : null;
        hasFocused.current = true;
        setSelection({ index: cells.indexOf(cell) });
    };

    useLayoutEffect(() => {
        const opening = wide && !prevWide.current;
        prevWide.current = wide;
        const rail = scroller.current;
        if (!rail || !hasFocused.current) return;
        const cells = Array.from(rail.querySelectorAll(':scope > [data-shelf-cell]'));
        const index = Math.min(selection.index, cells.length - 1);
        if (index < 0) return;
        cancelMotion();
        const style = getComputedStyle(rail);
        const padding = parseFloat(style.paddingLeft) || 0;
        const gap = parseFloat(style.columnGap) || 0;
        const narrow = cells.find((_, i) => !wide || i !== index);
        const pitch = (narrow?.offsetWidth || cells[index].offsetWidth) + gap;
        const bounds = rail.getBoundingClientRect();
        // On narrow screens the wide card uses slot 1, so it remains visible.
        const slot = bounds.width >= padding + pitch + cells[index].offsetWidth + padding ? pitch : 0;
        const target = Math.max(0, cells[index].offsetLeft - padding - (index ? slot : 0));
        const geometry = cells.map((el) => ({ el, left: el.offsetLeft, width: el.offsetWidth }));
        rail.scrollLeft = target;
        const scroll = rail.scrollLeft;
        const previous = before.current;
        before.current = null;
        if (!previous) return;
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        // The slide-open always animates (it is the point of 'pause' mode);
        // ordinary row glides follow the Performance-lab choice.
        if (!opening && getPerf('shelfMotion') === 'instant') return;
        const duration = opening ? OPEN_DURATION : DURATION;
        if (opening) {
            const tile = cells[index].querySelector('[data-preview="true"]');
            const narrowWidth = narrow?.offsetWidth || 0;
            if (tile?.animate && narrowWidth && cells[index].offsetWidth > narrowWidth) {
                const hidden = Math.round((1 - narrowWidth / cells[index].offsetWidth) * 100);
                const reveal = tile.animate([
                    { clipPath: `inset(0 ${hidden}% 0 0 round 12px)` },
                    { clipPath: 'inset(0 0 0 0 round 12px)' },
                ], { duration, easing: EASING });
                animations.current.set(tile, reveal);
                reveal.onfinish = () => {
                    if (animations.current.get(tile) === reveal) animations.current.delete(tile);
                };
            }
        }
        geometry.forEach(({ el, left, width }, i) => {
            const end = bounds.left + left - scroll;
            const start = previous.get(el);
            // Keep the preview anchored. Animate only visible neighbours, not
            // every offscreen tile (and not the focus scale on the button).
            if ((wide && i === index) || start == null || !el.animate) return;
            if (Math.max(start, end) + width < bounds.left || Math.min(start, end) > bounds.right) return;
            const dx = start - end;
            if (Math.abs(dx) < 1) return;
            const animation = el.animate([
                { transform: `translate3d(${dx}px, 0, 0)` },
                { transform: 'translate3d(0, 0, 0)' },
            ], { duration, easing: EASING });
            animations.current.set(el, animation);
            animation.onfinish = () => {
                if (animations.current.get(el) === animation) animations.current.delete(el);
            };
        });
    }, [selection, wide, count, scroller]);

    useEffect(() => {
        const rail = scroller.current;
        const cancel = () => cancelMotion();
        rail?.addEventListener('pointerdown', cancel, { passive: true });
        rail?.addEventListener('wheel', cancel, { passive: true });
        window.addEventListener('resize', cancel);
        return () => {
            cancel();
            rail?.removeEventListener('pointerdown', cancel);
            rail?.removeEventListener('wheel', cancel);
            window.removeEventListener('resize', cancel);
        };
    }, [scroller]);

    return { wideIndex: Math.min(selection.index, count - 1), onFocus, snapshot };
}