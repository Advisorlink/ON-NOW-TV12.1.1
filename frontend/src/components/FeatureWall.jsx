import React, { useEffect, useRef, useState } from 'react';
import { Sparkles, X, Layers, Clapperboard, LayoutGrid, PlusCircle, Zap, Search, Settings2, Gift, Check, Lightbulb, EyeOff, Users, Sword, MoveHorizontal, Tv, Bookmark, Smartphone, Star, Play } from 'lucide-react';
import './featureWall.css';

const ICONS = { layers: Layers, clapperboard: Clapperboard, 'layout-grid': LayoutGrid, 'plus-circle': PlusCircle, zap: Zap, search: Search, 'settings-2': Settings2, gift: Gift, lightbulb: Lightbulb, 'eye-off': EyeOff, users: Users, sword: Sword, movement: MoveHorizontal, tv: Tv, bookmark: Bookmark, smartphone: Smartphone, star: Star, play: Play };

const centre = (el) => { const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, h: r.height }; };

/** Nearest tile in the arrow direction by on-screen position (tiles may span columns). */
function neighbourOf(tiles, from, key) {
    const c = centre(tiles[from]);
    const horizontal = key === 'ArrowLeft' || key === 'ArrowRight';
    let best = -1, bestScore = Infinity;
    tiles.forEach((t, i) => {
        if (i === from) return;
        const p = centre(t);
        const dx = p.x - c.x, dy = p.y - c.y;
        const ok = key === 'ArrowRight' ? dx > 1 && Math.abs(dy) < c.h / 2
            : key === 'ArrowLeft' ? dx < -1 && Math.abs(dy) < c.h / 2
            : key === 'ArrowDown' ? dy > 1 : dy < -1;
        if (!ok) return;
        const score = horizontal ? Math.abs(dx) : Math.abs(dy) * 4 + Math.abs(dx);
        if (score < bestScore) { bestScore = score; best = i; }
    });
    return best;
}

/** Full-screen "What's New" wall: one illustrated tile per feature.
 *  Remote: arrows move between tiles (focused tile grows and reveals
 *  its description), OK toggles the description, Back/Escape closes. */
export const FeatureWall = ({ release, version, onDismiss, onBack }) => {
    const root = useRef(null);
    const grid = useRef(null);
    const done = useRef(null);
    const [active, setActive] = useState(0);
    const [pinned, setPinned] = useState(null);
    const items = release.items;

    const focusTile = (i) => {
        const next = Math.max(0, Math.min(items.length - 1, i));
        setActive(next);
        grid.current?.children[next]?.focus({ preventScroll: false });
    };

    useEffect(() => {
        grid.current?.children[0]?.focus({ preventScroll: true });
        const containFocus = (e) => {
            if (root.current && !root.current.contains(e.target)) grid.current?.children[active]?.focus({ preventScroll: true });
        };
        document.addEventListener('focusin', containFocus);
        return () => document.removeEventListener('focusin', containFocus);
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    const onKeyDown = (e) => {
        if (['Escape', 'Backspace', 'GoBack', 'BrowserBack'].includes(e.key)) { e.preventDefault(); e.stopPropagation(); onBack(); return; }
        const onDone = document.activeElement === done.current;
        if (e.key.startsWith('Arrow')) {
            e.preventDefault(); e.stopPropagation();
            if (onDone) { if (e.key === 'ArrowUp') focusTile(items.length - 1); return; }
            const target = neighbourOf([...(grid.current?.children || [])], active, e.key);
            if (e.key === 'ArrowDown' && target < 0) { done.current?.focus({ preventScroll: false }); return; }
            if (target >= 0) focusTile(target);
            return;
        }
        if (e.key === 'Enter' || e.key === ' ') {
            if (onDone) return; // native click handles the button
            e.preventDefault(); e.stopPropagation();
            setPinned((p) => (p === active ? null : active));
            return;
        }
        if (e.key === 'Tab') { e.preventDefault(); e.stopPropagation(); (onDone ? grid.current?.children[active] : done.current)?.focus({ preventScroll: true }); }
    };

    return (
        <div data-testid="whats-new-modal" className="wall-overlay" onClick={onDismiss}>
            <section ref={root} role="dialog" aria-modal="true" aria-labelledby="release-title" data-testid="whats-new-panel" data-focus-trap="true" className="wall" onKeyDown={onKeyDown} onClick={(e) => e.stopPropagation()}>
                <header className="wall-head">
                    <div>
                        <div data-testid="whats-new-version" className="wall-eyebrow"><Sparkles size={15} /> WHAT’S NEW <span>v{version}</span><em>{release.date}</em></div>
                        <h2 id="release-title" data-testid="whats-new-heading" className="wall-title">{release.headline}</h2>
                    </div>
                    <button data-testid="whats-new-close" className="wall-close" onClick={onDismiss} aria-label="Close updates" tabIndex={-1}><X size={18} /></button>
                </header>
                <div ref={grid} data-testid="whats-new-grid" className="wall-grid" style={{ '--count': items.length }}>
                    {items.map((item, i) => {
                        const Icon = ICONS[item.icon] || Check;
                        const open = pinned === i || active === i;
                        return (
                            <button
                                key={item.title}
                                type="button"
                                data-testid={`whats-new-item-${i}`}
                                data-active={active === i ? 'true' : 'false'}
                                data-open={open ? 'true' : 'false'}
                                data-wide={item.wide ? 'true' : 'false'}
                                className="wall-tile"
                                style={{ '--i': i, backgroundImage: `url(${item.art})` }}
                                tabIndex={0}
                                onFocus={() => setActive(i)}
                                onMouseEnter={() => setActive(i)}
                                onClick={() => setPinned((p) => (p === i ? null : i))}
                            >
                                <span className="wall-tile-shade" />
                                <span className="wall-tile-icon"><Icon size={16} strokeWidth={2.2} /></span>
                                <span className="wall-tile-copy">
                                    <strong>{item.title}</strong>
                                    <small>{item.detail}</small>
                                </span>
                            </button>
                        );
                    })}
                </div>
                <footer className="wall-foot">
                    <span className="wall-hint" data-testid="whats-new-hint">Move around to explore · OK to read more · Back to close</span>
                    <button ref={done} data-testid="whats-new-done" className="wall-done" onClick={onDismiss}>Got it</button>
                </footer>
            </section>
        </div>
    );
};
