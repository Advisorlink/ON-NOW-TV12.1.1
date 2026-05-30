// ON NOW TV Tunes — Karaoke Party API client (v2.8.74).
//
// Thin fetch wrapper for the backend /api/karaoke/party endpoints.
// Persists the local member id + active party code in localStorage
// so the user can reload mid-party without losing context.

const BASE = `${process.env.REACT_APP_BACKEND_URL || ''}/api/karaoke`;

const LS_PARTY_CODE = 'tunes-karaoke-party-code';
const LS_MEMBER_ID  = 'tunes-karaoke-member-id';

async function http(method, path, body) {
    const r = await fetch(`${BASE}${path}`, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
    });
    if (!r.ok) {
        let detail = '';
        try { detail = (await r.json()).detail || ''; } catch { /* ignore */ }
        throw new Error(`HTTP ${r.status}${detail ? `: ${detail}` : ''}`);
    }
    return r.json();
}

export const karaokeAPI = {
    create: (hostName, hostAvatar = '') =>
        http('POST', '/party', { host_name: hostName, host_avatar: hostAvatar }),
    get: (code) => http('GET', `/party/${encodeURIComponent(code)}`),
    join: (code, name, avatar = '') =>
        http('POST', `/party/${encodeURIComponent(code)}/join`, { name, avatar }),
    addSong: (code, memberId, track) =>
        http('POST', `/party/${encodeURIComponent(code)}/song`, {
            member_id: memberId,
            track_id: String(track.id),
            title: track.title,
            artist: track.artist?.name || track.artist || '',
            cover: track.album?.cover_medium || track.album?.cover || track.artwork || '',
        }),
    removeSong: (code, memberId, songId) =>
        http('DELETE', `/party/${encodeURIComponent(code)}/song/${songId}?member_id=${encodeURIComponent(memberId)}`),
    setMode: (code, mode) =>
        http('POST', `/party/${encodeURIComponent(code)}/mode`, { mode }),
    advance: (code) =>
        http('POST', `/party/${encodeURIComponent(code)}/advance`),
    setChallenge: (code, challenge) =>
        http('POST', `/party/${encodeURIComponent(code)}/challenge`, { challenge }),
    poll: (code, since = 0) => {
        const url = `${BASE}/party/${encodeURIComponent(code)}/poll?since=${since}`;
        return fetch(url).then((r) => r.json());
    },
};

export function readPartySession() {
    return {
        code: localStorage.getItem(LS_PARTY_CODE) || null,
        memberId: localStorage.getItem(LS_MEMBER_ID) || null,
    };
}

export function writePartySession({ code, memberId }) {
    if (code) localStorage.setItem(LS_PARTY_CODE, code);
    if (memberId) localStorage.setItem(LS_MEMBER_ID, memberId);
}

export function clearPartySession() {
    localStorage.removeItem(LS_PARTY_CODE);
    localStorage.removeItem(LS_MEMBER_ID);
}

// Catalog of challenges available in the picker.  Mirrors the
// backend's accepted IDs.
export const CHALLENGES = [
    {
        id: 'silent-spotlight',
        title: 'Silent Spotlight',
        body: 'The music mutes for a section. Keep singing!',
        icon: 'mic',
    },
    {
        id: 'blank-beat',
        title: 'Blank Beat',
        body: 'Some lyrics are hidden. Can you still nail it?',
        icon: 'question',
    },
    {
        id: 'genre-flip',
        title: 'Genre Flip',
        body: 'The track changes style mid-song. Stay sharp!',
        icon: 'note',
    },
    {
        id: 'sip-and-sing',
        title: 'Sip & Sing',
        body: 'Add sips and dares for the ultimate party mode!',
        icon: 'cup',
    },
];

export function challengeMeta(id) {
    return CHALLENGES.find((c) => c.id === id) || null;
}
