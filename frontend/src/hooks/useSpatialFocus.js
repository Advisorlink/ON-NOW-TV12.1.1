import { useEffect } from 'react';

/**
 * Spatial D-pad focus manager for TV.
 *
 * Listens for ArrowUp / ArrowDown / ArrowLeft / ArrowRight at the
 * window level and moves focus to the geometrically nearest
 * element marked with `data-focusable="true"`.
 *
 * Also forwards Enter to a click() on the focused element so
 * keyboards / remotes that don't fire native click work.
 *
 * Designed to "just work" with native browser focus + scrollIntoView,
 * so any element in the tree only needs:
 *
 *   <button data-focusable="true" data-focus-style="tile" tabIndex={0}>
 */
export default function useSpatialFocus() {
    useEffect(() => {
        const focusables = () =>
            Array.from(
                document.querySelectorAll('[data-focusable="true"]')
            ).filter((el) => {
                const r = el.getBoundingClientRect();
                return (
                    !el.hasAttribute('disabled') &&
                    r.width > 0 &&
                    r.height > 0 &&
                    getComputedStyle(el).visibility !== 'hidden'
                );
            });

        const center = (rect) => ({
            x: rect.left + rect.width / 2,
            y: rect.top + rect.height / 2,
        });

        const findNext = (current, dir) => {
            const cur = current.getBoundingClientRect();
            const c = center(cur);
            const candidates = focusables().filter((el) => el !== current);

            let best = null;
            let bestScore = Infinity;

            for (const el of candidates) {
                const r = el.getBoundingClientRect();
                const ec = center(r);
                const dx = ec.x - c.x;
                const dy = ec.y - c.y;

                let inDirection = false;
                let primary = 0;
                let perpendicular = 0;
                const overlapTol = 8;

                if (dir === 'right') {
                    inDirection = r.left >= cur.right - overlapTol;
                    primary = r.left - cur.right;
                    perpendicular = Math.abs(dy);
                } else if (dir === 'left') {
                    inDirection = r.right <= cur.left + overlapTol;
                    primary = cur.left - r.right;
                    perpendicular = Math.abs(dy);
                } else if (dir === 'down') {
                    inDirection = r.top >= cur.bottom - overlapTol;
                    primary = r.top - cur.bottom;
                    perpendicular = Math.abs(dx);
                } else if (dir === 'up') {
                    inDirection = r.bottom <= cur.top + overlapTol;
                    primary = cur.top - r.bottom;
                    perpendicular = Math.abs(dx);
                }

                if (!inDirection) continue;
                if (primary < 0) primary = 0;

                // Heavy weight on perpendicular distance so we prefer items
                // roughly aligned with the current focused item.
                const score = primary + perpendicular * 2;
                if (score < bestScore) {
                    bestScore = score;
                    best = el;
                }
            }
            return best;
        };

        const focusEl = (el) => {
            if (!el) return;
            el.focus({ preventScroll: true });
            el.scrollIntoView({
                behavior: 'smooth',
                block: 'nearest',
                inline: 'center',
            });
        };

        const onKey = (e) => {
            const dirMap = {
                ArrowRight: 'right',
                ArrowLeft: 'left',
                ArrowUp: 'up',
                ArrowDown: 'down',
            };
            const dir = dirMap[e.key];

            if (dir) {
                const active =
                    document.activeElement &&
                    document.activeElement.matches('[data-focusable="true"]')
                        ? document.activeElement
                        : focusables()[0];
                if (!active) return;
                e.preventDefault();
                const next = findNext(active, dir);
                if (next) focusEl(next);
                return;
            }

            if (e.key === 'Enter' || e.key === ' ') {
                if (
                    document.activeElement &&
                    document.activeElement.matches('[data-focusable="true"]')
                ) {
                    e.preventDefault();
                    document.activeElement.click();
                }
            }
        };

        // Initial focus: prefer an element marked data-initial-focus,
        // otherwise the first focusable on the page.
        const init = () => {
            if (
                document.activeElement &&
                document.activeElement.matches('[data-focusable="true"]')
            )
                return;
            const preferred = document.querySelector(
                '[data-focusable="true"][data-initial-focus="true"]'
            );
            const target = preferred || focusables()[0];
            if (target) target.focus({ preventScroll: true });
        };

        const t = setTimeout(init, 250);
        window.addEventListener('keydown', onKey);
        return () => {
            window.removeEventListener('keydown', onKey);
            clearTimeout(t);
        };
    }, []);
}
