// ON NOW TV Tunes — Karaoke home (v2.8.76 redesign).
//
// Per the user-supplied PNG mockups (May 30 2026):
//   - Square dark-navy tiles with a subtle starfield + soft blue
//     border (NO multi-color borders, NO long stretched cards).
//   - Neon-blue line-art icons with drop-shadow glow.
//   - Bold white title; light-gray 2-3-line description.
//
// The user supplied PNGs for the "Sing Your Own" and "Party Mode"
// tiles directly (see /public/karaoke-icons/).  We use those exact
// images as the tile artwork so the home matches the mockups
// pixel-for-pixel.  The "Up Next" and "Random Challenge" tiles use
// matching inline SVG icons styled with the same neon-blue glow.

import React from 'react';
import { Link } from 'react-router-dom';

// =============================================================
// Inline SVG icons — drawn to match the neon mockup style
// (thick rounded strokes, no fill, currentColor + drop-shadow).
// =============================================================

const IconUpNext = (props) => (
    <svg viewBox="0 0 96 96" fill="none" stroke="currentColor"
         strokeWidth="5" strokeLinecap="round" strokeLinejoin="round"
         {...props}>
        {/* numbered queue: small numerals + 3 stacked lines */}
        <text x="10" y="32" fontFamily="Geist, system-ui, sans-serif"
              fontSize="18" fontWeight="700" fill="currentColor" stroke="none">1</text>
        <text x="10" y="58" fontFamily="Geist, system-ui, sans-serif"
              fontSize="18" fontWeight="700" fill="currentColor" stroke="none">2</text>
        <text x="10" y="84" fontFamily="Geist, system-ui, sans-serif"
              fontSize="18" fontWeight="700" fill="currentColor" stroke="none">3</text>
        <line x1="34" y1="28"  x2="86" y2="28"  />
        <line x1="34" y1="54"  x2="86" y2="54"  />
        <line x1="34" y1="80"  x2="72" y2="80"  />
    </svg>
);

const IconChallenge = (props) => (
    <svg viewBox="0 0 96 96" fill="none" stroke="currentColor"
         strokeWidth="4.5" strokeLinecap="round" strokeLinejoin="round"
         {...props}>
        {/* large sparkle */}
        <path d="M48 14 L54 38 L78 44 L54 50 L48 74 L42 50 L18 44 L42 38 Z" />
        {/* small sparkle top-right */}
        <path d="M78 16 L80 22 L86 24 L80 26 L78 32 L76 26 L70 24 L76 22 Z" />
        {/* small sparkle bottom-left */}
        <path d="M20 70 L22 76 L28 78 L22 80 L20 86 L18 80 L12 78 L18 76 Z" />
    </svg>
);

const TILES = [
    {
        id: 'sing',
        to: '/music/karaoke/sing',
        title: 'Sing Your Own',
        body: 'Search any song you love and sing your heart out.',
        art: '/karaoke-icons/sing-your-own-icon.png',
    },
    {
        id: 'party',
        to: '/music/karaoke/party',
        title: 'Party Mode',
        body: 'Group karaoke fun! Guests join, add names, and choose songs.',
        art: '/karaoke-icons/party-mode-icon.png',
    },
    {
        id: 'up-next',
        to: '/music/karaoke/up-next',
        title: 'Up Next',
        body: "See what's playing now and what's coming up in the queue.",
        Icon: IconUpNext,
    },
    {
        id: 'challenge',
        to: '/music/karaoke/challenge',
        title: 'Random Challenge',
        body: 'Try fun challenge modes like silent section, mystery lyrics or skip.',
        Icon: IconChallenge,
    },
];

export default function KaraokeHome() {
    return (
        <div className="tunes-karaoke-home" data-testid="karaoke-home">
            <section className="kk-hero" data-testid="karaoke-hero">
                <div className="kk-hero__bg" />
                <div className="kk-hero__scrim" />
                <div className="kk-hero__content">
                    <p className="kk-hero__eyebrow">PICK YOUR JAM &middot; SING IT LOUD</p>
                    <h1 className="kk-hero__title">
                        Tonight, You&apos;re
                        <br />
                        <em>The Star</em>
                    </h1>
                    <p className="kk-hero__sub">
                        Choose a song. Grab the mic. Own the stage.
                    </p>
                </div>
            </section>

            <div className="kk-tile-grid">
                {TILES.map((tile, idx) => {
                    const Icon = tile.Icon;
                    return (
                        <Link
                            key={tile.id}
                            to={tile.to}
                            className="kk-tile"
                            data-focusable="true"
                            data-focus-style="tile"
                            tabIndex={0}
                            data-testid={`karaoke-tile-${tile.id}`}
                            style={{ animationDelay: `${idx * 60}ms` }}
                        >
                            <div className="kk-tile__stars" aria-hidden="true" />
                            <div className="kk-tile__inner">
                                <div className="kk-tile__icon">
                                    {tile.art
                                        ? <img src={tile.art} alt="" draggable="false" />
                                        : <Icon className="kk-tile__svg" />
                                    }
                                </div>
                                <div className="kk-tile__copy">
                                    <h2 className="kk-tile__title">{tile.title}</h2>
                                    <p className="kk-tile__body">{tile.body}</p>
                                </div>
                            </div>
                        </Link>
                    );
                })}
            </div>
        </div>
    );
}
