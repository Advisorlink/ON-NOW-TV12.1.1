import React, { useEffect, useRef } from 'react';
import { Sparkles, X, Layers, Clapperboard, LayoutGrid, PlusCircle, Zap, Search, Settings2, Gift, Check, Lightbulb, EyeOff, Users, Sword, MoveHorizontal } from 'lucide-react';
import './releaseNotes.css';

const ICONS = { layers: Layers, clapperboard: Clapperboard, 'layout-grid': LayoutGrid, 'plus-circle': PlusCircle, zap: Zap, search: Search, 'settings-2': Settings2, gift: Gift, lightbulb: Lightbulb, 'eye-off': EyeOff, users: Users, sword: Sword, movement: MoveHorizontal };

export const ReleaseNotesPanel = ({ release, version, onDismiss, onBack }) => {
    const root = useRef(null);
    const done = useRef(null);
    useEffect(() => {
        done.current?.focus({ preventScroll: true });
        // A page's late initial-focus/data loader cannot steal modal focus.
        const containFocus = (e) => {
            if (root.current && !root.current.contains(e.target)) done.current?.focus({ preventScroll: true });
        };
        document.addEventListener('focusin', containFocus);
        return () => document.removeEventListener('focusin', containFocus);
    }, []);

    const onKeyDown = (e) => {
        if (['Escape', 'Backspace', 'GoBack'].includes(e.key)) {
            e.preventDefault(); e.stopPropagation(); onBack();
        } else if (e.key.startsWith('Arrow') || e.key === 'Tab') {
            e.preventDefault(); e.stopPropagation();
            const close = root.current.querySelector('[data-testid="whats-new-close"]');
            (document.activeElement === done.current ? close : done.current)?.focus({ preventScroll: true });
        }
    };

    return (
        <div data-testid="whats-new-modal" className="release-overlay" onClick={onDismiss}>
            <section ref={root} role="dialog" aria-modal="true" aria-labelledby="release-title" data-testid="whats-new-panel" data-focus-trap="true" className="release-panel" onKeyDown={onKeyDown} onClick={(e) => e.stopPropagation()}>
                <header className="release-header">
                    <div data-testid="whats-new-version" className="release-eyebrow"><Sparkles size={16} /> WHAT’S NEW <span>v{version}</span></div>
                    <h2 id="release-title" data-testid="whats-new-heading">{release.headline}</h2>
                    <button data-testid="whats-new-close" data-focusable="true" data-focus-style="quiet" className="release-close" onClick={onDismiss} aria-label="Close updates"><X size={18} /></button>
                </header>
                <div data-testid="whats-new-grid" className="release-grid">
                    {release.items.map((item, i) => {
                        const Icon = ICONS[item.icon] || Check;
                        return (
                            <article key={item.title} data-testid={`whats-new-item-${i}`} className="release-item">
                                <div className="release-item-heading"><Icon size={18} aria-hidden="true" /><h3 data-testid={`whats-new-item-title-${i}`}>{item.title}</h3></div>
                                <p data-testid={`whats-new-item-detail-${i}`}>{item.detail}</p>
                            </article>
                        );
                    })}
                </div>
                <footer className="release-footer"><button ref={done} data-testid="whats-new-got-it" data-focusable="true" data-focus-style="pill" data-initial-focus="true" onClick={onDismiss}>Let’s go <Check size={18} /></button></footer>
            </section>
        </div>
    );
};