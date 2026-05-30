// ON NOW TV Tunes — Karaoke (v2.8.74 redesign).
//
// Per the user-supplied designs (May 30 2026):
//
//   - Top hero: "Tonight, You're The Star" with a microphone-on-stage
//     full-bleed backdrop.
//   - Four tiles below: Sing Your Own · Party Mode · Up Next · Random
//     Challenge.  Each tile is a glass-morphism card with a glowing
//     blue/violet icon and a one-line description.
//   - Mounts inside the normal MusicLayout (so the sidebar, bottom
//     nav and audio engine are all available).
//
// Tapping a tile navigates to a dedicated sub-route:
//   /music/karaoke/sing       → KaraokeSingYourOwn
//   /music/karaoke/party      → KaraokePartyPicker
//   /music/karaoke/up-next    → KaraokeUpNext
//   /music/karaoke/challenge  → KaraokeChallenge
//
// Legacy `/music/karaoke/play/:trackId` still resolves via the redirect
// stub in `./Karaoke.legacy.jsx` so old deep links keep working.

import React from 'react';
import { Link } from 'react-router-dom';
import { Search, Users, ListOrdered, Dices } from 'lucide-react';

const TILES = [
    {
        id: 'sing',
        to: '/music/karaoke/sing',
        eyebrow: 'SOLO',
        title: 'Sing Your Own',
        body: 'Search any song you love and sing your heart out.',
        icon: Search,
    },
    {
        id: 'party',
        to: '/music/karaoke/party',
        eyebrow: 'GROUP',
        title: 'Party Mode',
        body: 'Group karaoke fun! Guests join, add names, and choose songs.',
        icon: Users,
    },
    {
        id: 'up-next',
        to: '/music/karaoke/up-next',
        eyebrow: 'QUEUE',
        title: 'Up Next',
        body: "See what's playing now and what's coming up in the queue.",
        icon: ListOrdered,
    },
    {
        id: 'challenge',
        to: '/music/karaoke/challenge',
        eyebrow: 'GAMES',
        title: 'Random Challenge',
        body: 'Try fun challenge modes like silent section, mystery lyrics or skip.',
        icon: Dices,
    },
];

export default function KaraokeHome() {
    return (
        <div className="tunes-karaoke-home" data-testid="karaoke-home">
            <section className="kk-hero" data-testid="karaoke-hero">
                <div className="kk-hero__bg" />
                <div className="kk-hero__scrim" />
                <div className="kk-hero__content">
                    <p className="kk-hero__eyebrow">PICK YOUR JAM · SING IT LOUD</p>
                    <h1 className="kk-hero__title">
                        Tonight, You&apos;re
                        <br />
                        <em>The Star</em>
                    </h1>
                    <p className="kk-hero__sub">
                        Choose a song. Grab the mic. Own the stage.
                        <br />
                        It&apos;s your time to shine.
                    </p>
                </div>
            </section>

            <div className="kk-tile-grid">
                {TILES.map((tile, idx) => {
                    const Icon = tile.icon;
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
                            <div className="kk-tile__inner">
                                <div className="kk-tile__icon">
                                    <Icon size={72} strokeWidth={1.4} />
                                </div>
                                <div className="kk-tile__copy">
                                    <p className="kk-tile__eyebrow">{tile.eyebrow}</p>
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
