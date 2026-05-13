import React, { useEffect, useRef, useState } from 'react';

/**
 * Mounts its children only when the placeholder enters (or comes
 * within ~600px of) the viewport.  Renders a same-height skeleton
 * placeholder so layout stays stable.
 *
 * This is a critical perf win for HK1: 5+ shelves * 20+ posters each
 * means 100+ off-screen <img> tags decoding on mount.  Deferring all
 * but the first 1–2 shelves cuts initial paint by ~3x on cheap boxes.
 */
export default function Lazy({
    minHeight = 300,
    /* Mount a shelf when within ~3 viewport heights below the
       current scroll position.  Generous so the user always
       sees real posters when arriving at a new shelf during D-pad
       navigation — never a placeholder. */
    rootMargin = '2400px 0px',
    eager = false,
    children,
}) {
    const ref = useRef(null);
    const [shown, setShown] = useState(eager);

    useEffect(() => {
        if (shown) return;
        const el = ref.current;
        if (!el) return;
        // Fallback if IntersectionObserver isn't available (very old
        // WebView).  Mount after a small delay so we never hard-block.
        if (typeof IntersectionObserver === 'undefined') {
            const t = setTimeout(() => setShown(true), 200);
            return () => clearTimeout(t);
        }
        const io = new IntersectionObserver(
            (entries) => {
                for (const e of entries) {
                    if (e.isIntersecting) {
                        setShown(true);
                        io.disconnect();
                        return;
                    }
                }
            },
            { rootMargin }
        );
        io.observe(el);
        // Belt-and-braces fallback: even if IO never fires (e.g.
        // a slow Mali GPU paints the placeholder offscreen and the
        // box-internal viewport doesn't match the document
        // viewport our IO is watching), mount after 1.5 s of
        // existing in the tree so D-pad Down can always reach
        // the next shelf.
        const safety = setTimeout(() => setShown(true), 1500);
        return () => {
            io.disconnect();
            clearTimeout(safety);
        };
    }, [shown, rootMargin]);

    if (shown) return children;
    return (
        <div
            ref={ref}
            aria-hidden="true"
            style={{
                width: '100%',
                minHeight,
                contain: 'layout paint',
            }}
        />
    );
}
