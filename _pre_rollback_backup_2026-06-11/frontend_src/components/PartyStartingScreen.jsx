import React from 'react';
import Host from '@/lib/host';

/**
 * Full-screen takeover that hides the entire Player UI (video, top
 * bar, controls, preview, etc.) until a Watch Together party has
 * fully synced and the server flips status to `playing`.
 *
 * v2.6.73 redesign per user spec:
 *   "I don't want the loading screen to be shown at all.  Just the
 *    popcorn / host screens stay all the way through to when the
 *    movie starts."
 *
 * So this screen is now JUST the artwork (popcorn for guests, host-
 * loading.png for hosts) with a tiny status chip in the top-right.
 * No headlines, no pulsing rings, no member rail — pure cinematic
 * artwork that stays put until playback actually starts.
 *
 * Props
 * -----
 *  • visible        — when false the component renders nothing
 *  • role           — `host` | `guest`
 *  • partyCode      — short 6-char code (used in the corner chip)
 */
export default function PartyStartingScreen({
    visible,
    role,
    partyCode,
}) {
    if (!visible) return null;
    const isHost = role === 'host';
    /* Hand-designed artwork — `host-loading.png` for the host,
       `popcorn-loading.jpg` for the guest.  Both shipped in
       `frontend/public/party/`.  Use `Host.publicAsset()` so the
       URL resolves under both `file://` (sideloaded APK) and HTTP
       (web preview). */
    const src = Host.publicAsset(
        isHost ? 'party/host-loading.png' : 'party/popcorn-loading.jpg'
    );

    return (
        <div
            data-testid="party-starting-screen"
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 70,
                background: '#020610',
                overflow: 'hidden',
                color: '#E6EAF2',
            }}
        >
            <img
                src={src}
                alt=""
                aria-hidden="true"
                style={{
                    position: 'absolute',
                    inset: 0,
                    width: '100%',
                    height: '100%',
                    objectFit: 'cover',
                    objectPosition: 'center',
                }}
            />

            {/* Top-right party-code chip — discreet so the artwork
                stays the hero.  Same colour treatment for host and
                guest; the artwork itself differentiates them. */}
            {partyCode && (
                <div
                    className="vesper-mono"
                    style={{
                        position: 'absolute',
                        top: 'clamp(18px, 2vh, 30px)',
                        right: 'clamp(18px, 2vw, 30px)',
                        padding: '8px 16px',
                        borderRadius: 999,
                        background: 'rgba(2,6,16,0.55)',
                        backdropFilter: 'blur(8px)',
                        WebkitBackdropFilter: 'blur(8px)',
                        border: '1px solid rgba(93,200,255,0.45)',
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: '#5DC8FF',
                        fontWeight: 700,
                        textTransform: 'uppercase',
                        boxShadow: '0 6px 18px rgba(0,0,0,0.45)',
                    }}
                >
                    <span style={{ marginRight: 8 }}>●</span>
                    Party · {partyCode} · {isHost ? 'HOST' : 'GUEST'}
                </div>
            )}
        </div>
    );
}
