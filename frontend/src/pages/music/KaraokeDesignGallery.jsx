// ON NOW TV Tunes — Karaoke Design Gallery (v2.8.76).
//
// Shows multiple visual style variants side-by-side so the user can
// pick which one they want applied across the karaoke flow.  Each
// variant is just a different CSS class on identical JSX so the
// underlying behavior is unchanged — only the look swaps.

import React from 'react';
import { Search, Users, ListOrdered, Dices, Mic, Music, Sparkles, GlassWater } from 'lucide-react';

const TILE_VARIANTS = [
    {
        id: 'v1-glass',
        name: 'Variant 1 · Glass Cards',
        sub: 'Frosted glass on photo backdrop, thin glowing border, premium feel.',
        cls: 'dg-tile dg-v1',
    },
    {
        id: 'v2-poster',
        name: 'Variant 2 · Vivid Poster',
        sub: 'Bold solid-color blocks like a music streaming app. Maximum colour.',
        cls: 'dg-tile dg-v2',
    },
    {
        id: 'v3-photo',
        name: 'Variant 3 · Photo Cards',
        sub: 'Real concert photos behind each tile with subtle scrim. Cinematic.',
        cls: 'dg-tile dg-v3',
    },
    {
        id: 'v4-neon',
        name: 'Variant 4 · Neon Arcade',
        sub: 'Thick neon outline + glowing text, retro arcade game vibes.',
        cls: 'dg-tile dg-v4',
    },
    {
        id: 'v5-painted',
        name: 'Variant 5 · Hand-Painted',
        sub: 'Brushed-paint textures, playful illustration energy, fun and warm.',
        cls: 'dg-tile dg-v5',
    },
    {
        id: 'v6-3d',
        name: 'Variant 6 · 3D Pop',
        sub: 'Heavy drop shadow + chunky border, tactile physical-button feel.',
        cls: 'dg-tile dg-v6',
    },
];

const TILES = [
    { eyebrow: 'SOLO',  title: 'Sing Your Own',     body: 'Search any song and sing your heart out.',     icon: Search,       theme: 'pink' },
    { eyebrow: 'GROUP', title: 'Party Mode',        body: 'Group karaoke fun — guests join and pick songs.', icon: Users,    theme: 'blue' },
    { eyebrow: 'QUEUE', title: 'Up Next',           body: "See what's coming up in the queue.",            icon: ListOrdered, theme: 'purple' },
    { eyebrow: 'GAMES', title: 'Random Challenge',  body: 'Mystery lyrics, silent section, and more!',     icon: Dices,       theme: 'coral' },
];

const BTN_VARIANTS = [
    { id: 'b1-pill',     name: 'Button 1 · Solid Pill',     cls: 'dg-btn dg-b1' },
    { id: 'b2-ghost',    name: 'Button 2 · Outline / Ghost', cls: 'dg-btn dg-b2' },
    { id: 'b3-gradient', name: 'Button 3 · Gradient Sweep',  cls: 'dg-btn dg-b3' },
    { id: 'b4-neon',     name: 'Button 4 · Neon Glow',       cls: 'dg-btn dg-b4' },
    { id: 'b5-3d',       name: 'Button 5 · 3D Tactile',      cls: 'dg-btn dg-b5' },
    { id: 'b6-glass',    name: 'Button 6 · Glass Blur',      cls: 'dg-btn dg-b6' },
];

export default function KaraokeDesignGallery() {
    return (
        <div className="dg-gallery" data-testid="karaoke-design-gallery">
            <header className="dg-hero">
                <p className="dg-eyebrow">DESIGN GALLERY · PICK YOUR STYLE</p>
                <h1>Karaoke Tile &amp; Button Options</h1>
                <p className="dg-sub">
                    6 tile styles + 6 button styles below.  Each row shows the
                    same 4-tile karaoke home rendered in a different look.
                    Tell me which variant numbers you want and I&apos;ll apply
                    them everywhere.
                </p>
            </header>

            {TILE_VARIANTS.map((variant) => (
                <section
                    key={variant.id}
                    className={`dg-variant dg-variant--${variant.id}`}
                    data-testid={`dg-variant-${variant.id}`}
                >
                    <header className="dg-variant__head">
                        <h2>{variant.name}</h2>
                        <p>{variant.sub}</p>
                    </header>
                    <div className="dg-grid">
                        {TILES.map((tile) => {
                            const Icon = tile.icon;
                            return (
                                <div
                                    key={tile.title}
                                    className={`${variant.cls} dg-theme--${tile.theme}`}
                                >
                                    <div className="dg-tile__bg" />
                                    <div className="dg-tile__scrim" />
                                    <div className="dg-tile__inner">
                                        <div className="dg-tile__icon">
                                            <Icon size={48} strokeWidth={1.6} />
                                        </div>
                                        <p className="dg-tile__eyebrow">{tile.eyebrow}</p>
                                        <h3 className="dg-tile__title">{tile.title}</h3>
                                        <p className="dg-tile__body">{tile.body}</p>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </section>
            ))}

            <section className="dg-variant dg-variant--buttons">
                <header className="dg-variant__head">
                    <h2>Primary Button Styles</h2>
                    <p>Big "Start Singing", "Join the Party", "Play" CTAs.</p>
                </header>
                <div className="dg-btn-grid">
                    {BTN_VARIANTS.map((b) => (
                        <div key={b.id} className="dg-btn-cell">
                            <p className="dg-btn-cell__label">{b.name}</p>
                            <button type="button" className={b.cls} data-testid={`dg-${b.id}`}>
                                <Mic size={18} />
                                Start Singing
                            </button>
                        </div>
                    ))}
                </div>
            </section>

            <section className="dg-variant dg-variant--examples">
                <header className="dg-variant__head">
                    <h2>Challenge Card Styles (for Add a Challenge screen)</h2>
                    <p>Examples of the 4 small &quot;example challenge&quot; cards in each style.</p>
                </header>
                <div className="dg-grid dg-grid--challenges">
                    {[
                        { title: 'Silent Spotlight', body: 'The music mutes for a section. Keep singing!', icon: Mic, cls: 'dg-cc-v1' },
                        { title: 'Blank Beat',       body: 'Some lyrics are hidden. Can you nail it?',     icon: null, cls: 'dg-cc-v2' },
                        { title: 'Genre Flip',       body: 'The track changes style mid-song.',            icon: Music, cls: 'dg-cc-v3' },
                        { title: 'Sip &amp; Sing',   body: 'Add sips and dares to the mix.',               icon: GlassWater, cls: 'dg-cc-v4' },
                    ].map((c, i) => {
                        const Icon = c.icon;
                        return (
                            <div key={i} className={`dg-challenge-card ${c.cls}`}>
                                <div className="dg-challenge-card__icon">
                                    {Icon ? <Icon size={42} strokeWidth={1.5} /> : <span className="dg-dashed">???</span>}
                                </div>
                                <h3 dangerouslySetInnerHTML={{ __html: c.title }} />
                                <p>{c.body}</p>
                            </div>
                        );
                    })}
                </div>
            </section>
        </div>
    );
}
