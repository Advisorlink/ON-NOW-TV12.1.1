// ON NOW TV Tunes — Party Mode picker (v2.8.76 redesign).
//
// 3 square tiles matching the user's PNG mockup design:
// dark-navy + starfield + neon-blue icon + bold white title.

import React from 'react';
import { Link } from 'react-router-dom';

const IconFriends = (props) => (
    <svg viewBox="0 0 96 96" fill="none" stroke="currentColor"
         strokeWidth="4.5" strokeLinecap="round" strokeLinejoin="round"
         {...props}>
        {/* music note above */}
        <path d="M48 8 L48 30" />
        <path d="M48 8 L62 14 L62 22 L48 16 Z" fill="currentColor" />
        {/* 3 people heads + shoulders */}
        <circle cx="48" cy="48" r="9" />
        <path d="M30 84 C30 70 40 64 48 64 C56 64 66 70 66 84" />
        <circle cx="22" cy="56" r="7" />
        <path d="M8 86 C8 76 14 70 22 70" />
        <circle cx="74" cy="56" r="7" />
        <path d="M88 86 C88 76 82 70 74 70" />
    </svg>
);

const IconChallengeParty = (props) => (
    <svg viewBox="0 0 96 96" fill="none" stroke="currentColor"
         strokeWidth="4.5" strokeLinecap="round" strokeLinejoin="round"
         {...props}>
        {/* trophy with sparkles */}
        <path d="M30 18 L66 18 L66 36 C66 48 58 56 48 56 C38 56 30 48 30 36 Z" />
        <path d="M30 24 L20 24 L20 32 C20 40 26 44 32 44" />
        <path d="M66 24 L76 24 L76 32 C76 40 70 44 64 44" />
        <line x1="42" y1="58" x2="42" y2="72" />
        <line x1="54" y1="58" x2="54" y2="72" />
        <line x1="34" y1="78" x2="62" y2="78" />
        {/* sparkles */}
        <path d="M16 64 L18 70 L24 72 L18 74 L16 80 L14 74 L8 72 L14 70 Z" fill="currentColor"/>
        <path d="M80 64 L82 70 L88 72 L82 74 L80 80 L78 74 L72 72 L78 70 Z" fill="currentColor"/>
    </svg>
);

const IconDice = (props) => (
    <svg viewBox="0 0 96 96" fill="none" stroke="currentColor"
         strokeWidth="4.5" strokeLinecap="round" strokeLinejoin="round"
         {...props}>
        <rect x="14" y="14" width="68" height="68" rx="14" />
        <circle cx="32" cy="32" r="4" fill="currentColor" />
        <circle cx="64" cy="32" r="4" fill="currentColor" />
        <circle cx="48" cy="48" r="4" fill="currentColor" />
        <circle cx="32" cy="64" r="4" fill="currentColor" />
        <circle cx="64" cy="64" r="4" fill="currentColor" />
    </svg>
);

const TILES = [
    {
        id: 'friends',
        to: '/music/karaoke/party/friends',
        title: 'Friends Sing Along',
        body: 'Casual mode where everyone joins and adds songs.',
        Icon: IconFriends,
    },
    {
        id: 'challenge',
        to: '/music/karaoke/party/friends?challenges=on',
        title: 'Challenge Party',
        body: 'A more game-style mode with challenges included.',
        Icon: IconChallengeParty,
    },
    {
        id: 'random',
        to: '/music/karaoke/party/friends?mode=random',
        title: 'Random Party',
        body: "System picks the singer and song from everyone's list.",
        Icon: IconDice,
    },
];

export default function KaraokePartyPicker() {
    return (
        <div className="kk-party-picker" data-testid="karaoke-party-picker">
            <section className="kk-hero kk-hero--compact">
                <div className="kk-hero__bg" />
                <div className="kk-hero__scrim" />
                <div className="kk-hero__content">
                    <p className="kk-hero__eyebrow">PARTY MODE</p>
                    <h1 className="kk-hero__title">
                        Pick Your <em>Party</em>
                    </h1>
                    <p className="kk-hero__sub">
                        How rowdy are we tonight? Choose a vibe.
                    </p>
                </div>
            </section>

            <div className="kk-tile-grid kk-tile-grid--3">
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
                            data-testid={`karaoke-party-tile-${tile.id}`}
                            style={{ animationDelay: `${idx * 70}ms` }}
                        >
                            <div className="kk-tile__stars" aria-hidden="true" />
                            <div className="kk-tile__inner">
                                <div className="kk-tile__icon">
                                    <Icon className="kk-tile__svg" />
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
