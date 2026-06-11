import React from 'react';

/**
 * Fun, friendly empty state shown to a child when their search
 * doesn't match anything kid-safe.  Two flavours:
 *   variant="blocked" — they searched something we deliberately
 *                       blocked (matched adult content above the
 *                       parent's cert ceiling).  Reassuring tone.
 *   variant="empty"   — no results at all.  Encouraging tone.
 *
 * Designed to be obvious and a little playful so the kid doesn't
 * just feel "the app is broken".  Big emoji, soft pastel card,
 * an obvious "try these" prompt with safe suggestions.
 */
const SUGGESTIONS = ['Bluey', 'Mario', 'Frozen', 'Paw Patrol', 'Moana', 'Shrek'];

export default function KidsBlockedMessage({ query, onPick }) {
    return (
        <section
            data-testid="kids-blocked-message"
            className="relative"
            style={{
                marginTop: 24,
                padding: '36px 32px 32px',
                borderRadius: 24,
                background:
                    'linear-gradient(135deg, rgba(255,212,59,0.16) 0%, rgba(255,107,203,0.16) 50%, rgba(120,89,255,0.18) 100%)',
                border: '1px solid rgba(255,212,59,0.32)',
                maxWidth: 720,
            }}
        >
            <div
                aria-hidden
                style={{
                    position: 'absolute',
                    top: -28,
                    left: 28,
                    width: 72,
                    height: 72,
                    borderRadius: '50%',
                    background:
                        'linear-gradient(135deg, #FFD43B 0%, #FF6BCB 100%)',
                    display: 'grid',
                    placeItems: 'center',
                    boxShadow: '0 14px 32px rgba(255,107,203,0.45)',
                    fontSize: 38,
                }}
            >
                🙈
            </div>

            <div
                className="vesper-eyebrow"
                style={{
                    fontSize: 11,
                    color: '#FFD43B',
                    marginTop: 18,
                }}
            >
                Oops! Kid-safe zone
            </div>
            <h2
                className="vesper-display"
                style={{
                    fontSize: 30,
                    letterSpacing: '-0.025em',
                    lineHeight: 1.1,
                    color: '#fff',
                    marginTop: 8,
                }}
            >
                We can&apos;t show you that one
            </h2>
            <p
                style={{
                    color: 'var(--vesper-text-2)',
                    fontSize: 15,
                    lineHeight: 1.55,
                    marginTop: 12,
                    maxWidth: 540,
                }}
            >
                {query
                    ? `“${query}” isn't on the kid-safe list. Ask a grown-up if you really want to watch it.`
                    : "That word didn't match anything kid-safe."}{' '}
                Here are some fun things to try instead:
            </p>

            <div
                className="flex flex-wrap gap-3"
                style={{ marginTop: 18 }}
            >
                {SUGGESTIONS.map((s) => (
                    <button
                        key={s}
                        data-testid={`kids-suggest-${s.toLowerCase().replace(/\s+/g, '-')}`}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => onPick?.(s)}
                        className="rounded-full"
                        style={{
                            padding: '9px 18px',
                            background: 'rgba(255,255,255,0.10)',
                            border: '1px solid rgba(255,212,59,0.32)',
                            color: '#fff',
                            fontSize: 14,
                            fontWeight: 600,
                        }}
                    >
                        {s}
                    </button>
                ))}
            </div>
        </section>
    );
}
