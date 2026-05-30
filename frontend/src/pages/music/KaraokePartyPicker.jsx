// ON NOW TV Tunes — Party Mode picker (v2.8.74).
//
// 3 tiles per the design:
//   - Friends Sing Along (casual joins)
//   - Challenge Party (challenges included by default)
//   - Random Party (system picks singer + song)

import React from 'react';
import { Link } from 'react-router-dom';
import { Users, Dices, Sparkles } from 'lucide-react';

const TILES = [
    {
        id: 'friends',
        to: '/music/karaoke/party/friends',
        title: 'Friends Sing Along',
        body: 'Casual mode where everyone joins and adds songs.',
        icon: Users,
    },
    {
        id: 'challenge',
        to: '/music/karaoke/party/friends?challenges=on',
        title: 'Challenge Party',
        body: 'A more game-style mode with challenges included.',
        icon: Sparkles,
    },
    {
        id: 'random',
        to: '/music/karaoke/party/friends?mode=random',
        title: 'Random Party',
        body: 'The system picks singers and songs from everyone\u2019s list.',
        icon: Dices,
    },
];

export default function KaraokePartyPicker() {
    return (
        <div className="kk-party-picker" data-testid="karaoke-party-picker">
            <section className="kk-sing__hero">
                <div className="kk-hero__bg" />
                <div className="kk-hero__scrim" />
                <div className="kk-sing__hero-copy">
                    <p className="kk-hero__eyebrow">PARTY MODE</p>
                    <h1 className="kk-sing__title">
                        Pick Your <em>Party</em>
                    </h1>
                    <p className="kk-sing__sub">
                        How rowdy are we tonight? Choose a vibe and we&apos;ll
                        spin up the party lobby for your guests to join.
                    </p>
                </div>
            </section>

            <div className="kk-party-tiles">
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
                            data-testid={`karaoke-party-tile-${tile.id}`}
                            style={{ animationDelay: `${idx * 70}ms` }}
                        >
                            <div className="kk-tile__inner">
                                <div className="kk-tile__icon">
                                    <Icon size={64} strokeWidth={1.4} />
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
