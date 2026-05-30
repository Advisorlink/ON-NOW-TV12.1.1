// ON NOW TV Tunes — Add a Challenge picker (v2.8.74).
//
// Per the supplied "Add a Challenge" design:
//   - Big "Add a Challenge" title + dice illustration top-right
//   - 3 main option tiles: Random Challenge / Pick a Challenge / No Challenge - Skip
//   - 4 example tiles below (Silent Spotlight, Blank Beat, Genre Flip, Sip & Sing)
//
// Used in two contexts:
//   1. From the karaoke home (standalone — picks a challenge to set
//      on the current party).
//   2. Pre-song flow before the karaoke player auto-starts (the
//      `?return=stage` query param sends the user back to the stage
//      after picking).

import React, { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
    Dices, ListChecks, SkipForward,
    Mic, Music, GlassWater,
} from 'lucide-react';
import { CHALLENGES, karaokeAPI, readPartySession } from '../../lib/karaoke-party-api';

const EXAMPLE_ICONS = {
    'silent-spotlight': Mic,
    'blank-beat':       null,            // rendered as dashed "???" text
    'genre-flip':       Music,
    'sip-and-sing':     GlassWater,
};

export default function KaraokeChallenge() {
    const navigate = useNavigate();
    const [params] = useSearchParams();
    const returnTo = params.get('return') || null;
    const [busy, setBusy] = useState(null);

    const apply = async (challenge) => {
        setBusy(challenge || 'skip');
        const sess = readPartySession();
        if (sess.code) {
            try { await karaokeAPI.setChallenge(sess.code, challenge); }
            catch { /* swallow — the picker shouldn't error out */ }
        }
        if (returnTo === 'stage') navigate('/music/karaoke/party/stage');
        else navigate('/music/karaoke');
    };

    return (
        <div className="kk-challenge" data-testid="karaoke-challenge">
            <header className="kk-challenge__head">
                <div>
                    <p className="kk-hero__eyebrow">BEFORE THE SONG STARTS</p>
                    <h1 className="kk-sing__title">Add a Challenge</h1>
                    <p className="kk-challenge__sub">
                        Add a fun twist to your performance and make it
                        unforgettable. You choose the challenge!
                    </p>
                </div>
                <div className="kk-challenge__dice-glow" aria-hidden="true">
                    <Dices size={150} strokeWidth={1.2} />
                </div>
            </header>

            <div className="kk-challenge__primary">
                <ChallengeOptionTile
                    eyebrow=""
                    title="Random Challenge"
                    body="Let us surprise you."
                    icon={Dices}
                    onClick={() => apply('random')}
                    busy={busy === 'random'}
                    testid="karaoke-challenge-random"
                />
                <ChallengeOptionTile
                    eyebrow=""
                    title="Pick a Challenge"
                    body="Choose the challenge you want to play."
                    icon={ListChecks}
                    onClick={() => {
                        document.getElementById('kk-challenge-examples')?.scrollIntoView({
                            behavior: 'smooth', block: 'start',
                        });
                    }}
                    testid="karaoke-challenge-pick"
                />
                <ChallengeOptionTile
                    eyebrow=""
                    title="No Challenge / Skip"
                    body="Play the song normally. No twists, just you."
                    icon={SkipForward}
                    onClick={() => apply(null)}
                    busy={busy === 'skip'}
                    testid="karaoke-challenge-skip"
                />
            </div>

            <p
                className="kk-hero__eyebrow kk-challenge__examples-eyebrow"
                id="kk-challenge-examples"
            >
                CHALLENGE EXAMPLES
            </p>
            <div className="kk-challenge__examples">
                {CHALLENGES.map((ch) => {
                    const Icon = EXAMPLE_ICONS[ch.id];
                    return (
                        <button
                            key={ch.id}
                            type="button"
                            className="kk-challenge-card"
                            onClick={() => apply(ch.id)}
                            disabled={busy === ch.id}
                            data-focusable="true"
                            data-focus-style="tile"
                            tabIndex={0}
                            data-testid={`karaoke-challenge-${ch.id}`}
                        >
                            <div className="kk-challenge-card__icon">
                                {Icon
                                    ? <Icon size={42} strokeWidth={1.4} />
                                    : <span className="kk-dashed">???</span>}
                            </div>
                            <h3>{ch.title}</h3>
                            <p>{ch.body}</p>
                        </button>
                    );
                })}
            </div>
        </div>
    );
}

function ChallengeOptionTile({ title, body, icon: Icon, onClick, busy, testid }) {
    return (
        <button
            type="button"
            className="kk-challenge-primary"
            onClick={onClick}
            disabled={busy}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={testid}
        >
            <div className="kk-challenge-primary__icon">
                <Icon size={56} strokeWidth={1.4} />
            </div>
            <h2>{title}</h2>
            <p>{body}</p>
        </button>
    );
}
