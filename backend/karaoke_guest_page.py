"""
ON NOW TV Tunes — Self-contained mobile guest join HTML page.

Served directly by the FastAPI backend at:
    GET /api/karaoke/join/{code}

Why: the React /karaoke/join/{code} route works WHEN the React frontend
is deployed.  But the user's setup loads React from inside the APK
(WebViewAssetLoader) — the public `onnowtv.duckdns.org` host serves
ONLY the backend, not the React bundle.  So a QR code that points
into the React app breaks for everyone except the user themselves.

This page solves that:
  • One file, vanilla JS, no React, no build step.
  • Only depends on /api/karaoke + /api/music/search which are
    already deployed on the live backend.
  • Looks identical to the React version (same dark theme + electric
    blue accents).
  • Persists `member_id` in localStorage so refresh/return works.
  • Long-polls the party endpoint for live queue updates.

The frontend QR component now generates URLs that point at
this backend endpoint, so the QR works the instant the host
opens the lobby — no frontend deploy needed.
"""
from __future__ import annotations

from fastapi.responses import HTMLResponse


GUEST_JOIN_HTML = r"""<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no, viewport-fit=cover">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="theme-color" content="#050211">
    <title>Join Karaoke — __CODE__</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Geist:wght@400;600;700;800&family=Geist+Mono:wght@600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-0:#050211; --bg-1:#0a041a; --bg-2:#160a28;
            --surface:rgba(10,14,40,0.6); --border:rgba(124,167,255,0.28);
            --blue:#4ea7ff; --blue-2:#7cc4ff; --purple:#a96bff; --pink:#ff7ab8;
            --text:#fff; --text-2:rgba(255,255,255,0.75); --text-3:rgba(255,255,255,0.5);
            --glow:rgba(78,167,255,0.6);
        }
        * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
        html, body {
            margin:0; padding:0; background:var(--bg-0); color:var(--text);
            font-family:'Geist', system-ui, sans-serif; min-height:100dvh;
            overscroll-behavior-y:contain;
        }
        body {
            background:
                radial-gradient(60% 50% at 50% 0%, rgba(78,167,255,0.18), transparent 70%),
                radial-gradient(50% 40% at 100% 100%, rgba(169,107,255,0.16), transparent 70%),
                linear-gradient(180deg, #0a041a 0%, #050211 100%);
            padding: 24px 18px calc(120px + env(safe-area-inset-bottom));
        }
        .center { display:flex; flex-direction:column; align-items:center; text-align:center; gap:12px; }
        .mic-glow {
            width:120px; height:120px; border-radius:50%;
            background:radial-gradient(ellipse, rgba(78,167,255,0.45), transparent 70%);
            display:grid; place-items:center; color:var(--blue);
            filter:drop-shadow(0 0 28px var(--glow));
            margin:8px auto 14px;
        }
        .mic-glow svg { width:74px; height:74px; }
        .eyebrow {
            color:var(--blue-2); font-size:11px; letter-spacing:0.3em;
            font-weight:600; margin:0;
        }
        .pcode {
            font-family:'Geist Mono', monospace; font-weight:800;
            color:var(--blue); text-shadow:0 0 22px var(--glow);
            font-size:36px; letter-spacing:0.04em; margin:8px 0 8px;
        }
        .help { color:var(--text-2); font-size:15px; line-height:1.5; margin:0 0 22px; max-width:320px; }
        .card {
            background:var(--surface); backdrop-filter:blur(22px);
            border:1.5px solid var(--border); border-radius:18px;
            padding:18px; margin:14px 0;
        }
        label {
            display:block; color:var(--text-3); font-size:11px;
            letter-spacing:0.18em; text-transform:uppercase;
            font-weight:600; margin:0 0 8px;
        }
        input[type=text] {
            width:100%; background:rgba(255,255,255,0.04);
            border:1.5px solid var(--border); border-radius:14px;
            padding:16px; font-size:17px; color:#fff;
            font-family:inherit; outline:none;
            transition:border-color 220ms, box-shadow 220ms;
        }
        input[type=text]:focus {
            border-color:var(--blue);
            box-shadow:0 0 0 3px rgba(78,167,255,0.25);
        }
        input[type=text]::placeholder { color:var(--text-3); }
        button.cta {
            width:100%; padding:18px; font-size:17px; font-weight:700;
            border-radius:14px; border:1.5px solid var(--blue);
            background:transparent; color:var(--blue-2);
            box-shadow:0 0 0 1px var(--blue) inset, 0 0 24px rgba(78,167,255,0.3);
            display:flex; align-items:center; justify-content:center; gap:10px;
            cursor:pointer; font-family:inherit; margin-top:14px;
            transition:transform 180ms, box-shadow 220ms;
        }
        button.cta:hover, button.cta:active {
            background:rgba(78,167,255,0.16);
            box-shadow:0 0 0 2px var(--blue) inset, 0 0 40px rgba(78,167,255,0.55);
            transform:translateY(-1px);
        }
        button.cta:disabled { opacity:0.4; cursor:not-allowed; transform:none; }
        .error {
            color:var(--pink); background:rgba(255,122,184,0.1);
            border:1px solid var(--pink); padding:10px 14px;
            border-radius:10px; font-size:14px; margin:10px 0;
        }
        .topbar {
            display:flex; align-items:center; justify-content:space-between;
            gap:12px; padding-bottom:14px;
            border-bottom:1px solid rgba(255,255,255,0.08); margin-bottom:14px;
        }
        .topbar .pcode { font-size:18px; margin:0; }
        .topbar small { color:var(--text-3); font-size:12px; }
        .topbar small strong { color:#fff; }
        .iconbtn {
            width:40px; height:40px; border-radius:12px;
            background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.12);
            color:#fff; display:grid; place-items:center; cursor:pointer;
        }
        .search {
            position:relative; background:rgba(255,255,255,0.04);
            border:1.5px solid var(--border); border-radius:14px;
            padding:14px 14px 14px 48px;
        }
        .search svg {
            position:absolute; left:16px; top:50%; transform:translateY(-50%);
            color:var(--text-3); width:20px; height:20px;
        }
        .search input {
            width:100%; background:transparent; border:0; outline:0;
            color:#fff; font-size:16px; font-family:inherit;
        }
        .shelf { margin-top:18px; }
        .shelf-head {
            display:flex; align-items:center; gap:10px;
            color:var(--blue-2); font-size:12px; letter-spacing:0.22em;
            font-weight:600; margin:6px 0 10px;
        }
        .row {
            display:flex; align-items:center; gap:12px;
            background:rgba(255,255,255,0.04); border:1px solid rgba(255,255,255,0.08);
            border-radius:14px; padding:10px; margin-bottom:10px;
            width:100%; text-align:left; cursor:pointer;
            color:inherit; font-family:inherit;
        }
        .row:active { background:rgba(78,167,255,0.16); }
        .row img {
            width:52px; height:52px; border-radius:10px; object-fit:cover;
            flex-shrink:0; background:#1a0a2a;
        }
        .row .info { flex:1; min-width:0; }
        .row .info p { margin:0; font-weight:600; font-size:15px;
            white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .row .info small { color:var(--text-3); font-size:12px; }
        .row .badge {
            width:36px; height:36px; border-radius:12px;
            background:rgba(78,167,255,0.18); border:1px solid var(--blue);
            color:var(--blue); display:grid; place-items:center; flex-shrink:0;
        }
        .row .remove { color:var(--pink); font-size:13px; }
        .row.other { cursor:default; opacity:0.78; }
        .empty { color:var(--text-3); font-style:italic; padding:14px 4px; margin:0; font-size:14px; }
        .toast {
            position:fixed; bottom:30px; left:50%; transform:translateX(-50%);
            background:rgba(34,214,113,0.95); color:#04140b;
            padding:12px 22px; border-radius:30px; font-weight:700;
            font-size:14px; box-shadow:0 8px 24px rgba(34,214,113,0.5);
            opacity:0; pointer-events:none; transition:opacity 220ms, transform 220ms;
            z-index:50; display:flex; align-items:center; gap:8px;
        }
        .toast.show { opacity:1; transform:translateX(-50%) translateY(0); }
        .spin { animation:spin 1s linear infinite; }
        @keyframes spin { to { transform:rotate(360deg); } }
        h1.title {
            font-family:'Geist', system-ui; font-size:34px; font-weight:800;
            margin:0 0 6px; letter-spacing:-0.02em;
        }
        .joined-pills {
            display:flex; gap:8px; overflow-x:auto; padding-bottom:6px;
            margin:0 -4px 12px;
        }
        .pill {
            flex-shrink:0; padding:8px 14px; border-radius:30px;
            background:rgba(78,167,255,0.12); border:1px solid var(--border);
            font-size:13px; white-space:nowrap;
        }
        .pill.host { background:rgba(169,107,255,0.18); border-color:var(--purple); }
        .pill .you { font-weight:700; color:var(--blue-2); }
    </style>
</head>
<body>

<!-- PHASE: ENTER NAME -->
<section id="phase-enter" style="display:none;">
    <div class="center">
        <div class="mic-glow">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
                <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
                <line x1="12" y1="19" x2="12" y2="23"/>
                <line x1="8" y1="23" x2="16" y2="23"/>
            </svg>
        </div>
        <p class="eyebrow">JOIN THE PARTY</p>
        <p class="pcode" id="enter-code"></p>
        <p class="help">Type your name and start queuing songs to sing tonight.</p>
    </div>

    <div class="card">
        <label>Your name</label>
        <input id="name-input" type="text" maxlength="40" autofocus placeholder="e.g. Jamie">
        <p id="enter-err" class="error" style="display:none;"></p>
        <button id="join-btn" class="cta" disabled>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>
            Join the Party
        </button>
    </div>
</section>

<!-- PHASE: SONG PICKER -->
<section id="phase-songs" style="display:none;">
    <div class="topbar">
        <div>
            <p class="pcode" id="songs-code"></p>
            <small>Singing as <strong id="songs-who"></strong></small>
        </div>
        <button id="leave-btn" class="iconbtn" aria-label="Leave party">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
        </button>
    </div>

    <div class="joined-pills" id="joined-pills"></div>

    <div class="search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input id="search-input" type="text" placeholder="Search any song to queue…">
    </div>

    <div id="results-shelf" class="shelf"></div>
    <div id="mine-shelf" class="shelf"></div>
    <div id="others-shelf" class="shelf"></div>
</section>

<!-- PHASE: ERROR -->
<section id="phase-error" class="center" style="display:none; padding-top:80px;">
    <p class="eyebrow">PARTY NOT FOUND</p>
    <h1 class="title">Couldn't load this party</h1>
    <p class="help" id="error-detail">The party may have ended or expired.</p>
    <button class="cta" onclick="location.reload()" style="max-width:280px;">
        Try Again
    </button>
</section>

<div id="toast" class="toast">
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
    <span id="toast-text">Added!</span>
</div>

<script>
(function () {
    const CODE = "__CODE__".toUpperCase();
    const LS_NAME    = 'tunes-karaoke-guest-name';
    const LS_PARTY   = 'tunes-karaoke-party-code';
    const LS_MEMBER  = 'tunes-karaoke-member-id';
    const $ = (id) => document.getElementById(id);
    const show = (id) => {
        ['phase-enter','phase-songs','phase-error'].forEach((p) => {
            $(p).style.display = (p === id) ? 'block' : 'none';
        });
    };
    const toast = (msg) => {
        $('toast-text').textContent = msg;
        $('toast').classList.add('show');
        setTimeout(() => $('toast').classList.remove('show'), 1800);
    };

    let party = null;
    let memberId = localStorage.getItem(LS_MEMBER);
    let savedCode = localStorage.getItem(LS_PARTY);
    let pollSince = 0;
    let polling = false;
    $('enter-code').textContent = CODE;
    $('songs-code').textContent = CODE;
    $('name-input').value = localStorage.getItem(LS_NAME) || '';

    async function api(path, init) {
        const url = '/api/karaoke' + path;
        const r = await fetch(url, init);
        if (!r.ok) {
            let detail = '';
            try { detail = (await r.json()).detail || ''; } catch (e) {}
            throw new Error('HTTP ' + r.status + (detail ? ': ' + detail : ''));
        }
        return r.json();
    }

    async function search(q) {
        const url = '/api/music/search?q=' + encodeURIComponent(q);
        const r = await fetch(url);
        if (!r.ok) throw new Error('search failed');
        const j = await r.json();
        return (j.data && j.data.tracks) || j.tracks || [];
    }

    function renderJoinedPills() {
        const el = $('joined-pills'); el.innerHTML = '';
        party.members.forEach((m) => {
            const p = document.createElement('span');
            p.className = 'pill' + (m.is_host ? ' host' : '');
            const isMe = m.id === memberId;
            p.innerHTML = (isMe ? '<span class="you">★ </span>' : '') + escapeHtml(m.name);
            el.appendChild(p);
        });
    }

    function renderMine() {
        const mine = party.queue.filter((q) => q.member_id === memberId);
        const wrap = $('mine-shelf');
        if (mine.length === 0) {
            wrap.innerHTML = '<div class="shelf-head">YOUR QUEUE</div><p class="empty">Add some songs! They\'ll show up in the TV queue.</p>';
            return;
        }
        let html = '<div class="shelf-head">YOUR QUEUE (' + mine.length + ')</div>';
        mine.forEach((q) => {
            html += '<button class="row" data-remove="' + q.id + '">' +
                '<img src="' + escapeAttr(q.cover || '') + '" alt="">' +
                '<div class="info"><p>' + escapeHtml(q.title) + '</p><small>' + escapeHtml(q.artist) + '</small></div>' +
                '<span class="remove">Remove</span></button>';
        });
        wrap.innerHTML = html;
        wrap.querySelectorAll('[data-remove]').forEach((btn) => {
            btn.addEventListener('click', () => removeSong(btn.getAttribute('data-remove')));
        });
    }

    function renderOthers() {
        const others = party.queue.filter((q) => q.member_id !== memberId);
        const wrap = $('others-shelf');
        if (others.length === 0) { wrap.innerHTML = ''; return; }
        let html = '<div class="shelf-head">EVERYONE ELSE (' + others.length + ')</div>';
        others.forEach((q) => {
            html += '<div class="row other"><img src="' + escapeAttr(q.cover || '') + '" alt="">' +
                '<div class="info"><p>' + escapeHtml(q.title) + '</p>' +
                '<small>' + escapeHtml(q.artist) + ' · ' + escapeHtml(q.member_name) + '</small></div></div>';
        });
        wrap.innerHTML = html;
    }

    function renderTopbar() {
        const me = party.members.find((m) => m.id === memberId);
        $('songs-who').textContent = me ? me.name : 'Guest';
    }

    function escapeHtml(s) { return String(s).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
    function escapeAttr(s) { return escapeHtml(s); }

    async function load() {
        try {
            const r = await api('/party/' + CODE);
            party = r.party;
            pollSince = party.updated_at;
            const stillMember = savedCode === CODE && memberId && party.members.some((m) => m.id === memberId);
            if (stillMember) goSongs(); else show('phase-enter');
        } catch (e) {
            $('error-detail').textContent = (e && e.message) || 'Unknown error';
            show('phase-error');
        }
    }

    function goSongs() {
        show('phase-songs');
        renderTopbar();
        renderJoinedPills();
        renderMine();
        renderOthers();
        startPolling();
    }

    async function startPolling() {
        if (polling) return; polling = true;
        while (polling) {
            try {
                const r = await api('/party/' + CODE + '/poll?since=' + pollSince);
                if (r.party && !r.unchanged) {
                    party = r.party;
                    pollSince = party.updated_at;
                    renderJoinedPills();
                    renderMine();
                    renderOthers();
                }
            } catch (e) {
                await new Promise((res) => setTimeout(res, 3000));
            }
        }
    }

    $('name-input').addEventListener('input', () => {
        $('join-btn').disabled = $('name-input').value.trim().length === 0;
        $('enter-err').style.display = 'none';
    });

    $('join-btn').addEventListener('click', async () => {
        const name = $('name-input').value.trim();
        if (!name) return;
        $('join-btn').disabled = true;
        try {
            const r = await api('/party/' + CODE + '/join', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name }),
            });
            memberId = r.member_id;
            party = r.party;
            pollSince = party.updated_at;
            localStorage.setItem(LS_MEMBER, memberId);
            localStorage.setItem(LS_PARTY, CODE);
            localStorage.setItem(LS_NAME, name);
            goSongs();
        } catch (e) {
            $('enter-err').textContent = (e && e.message) || 'Could not join';
            $('enter-err').style.display = 'block';
            $('join-btn').disabled = false;
        }
    });

    $('leave-btn').addEventListener('click', () => {
        localStorage.removeItem(LS_MEMBER);
        localStorage.removeItem(LS_PARTY);
        memberId = null; savedCode = null;
        polling = false;
        show('phase-enter');
    });

    let searchTimer = null;
    $('search-input').addEventListener('input', (e) => {
        clearTimeout(searchTimer);
        const q = e.target.value.trim();
        if (!q) { $('results-shelf').innerHTML = ''; return; }
        searchTimer = setTimeout(async () => {
            $('results-shelf').innerHTML = '<div class="shelf-head">SEARCHING…</div>';
            try {
                const tracks = await search(q);
                if (!tracks.length) {
                    $('results-shelf').innerHTML = '<div class="shelf-head">RESULTS</div><p class="empty">Nothing matched.</p>';
                    return;
                }
                let html = '<div class="shelf-head">RESULTS (' + tracks.length + ')</div>';
                tracks.forEach((t) => {
                    const cover = (t.album && (t.album.cover_medium || t.album.cover)) || '';
                    const artist = (t.artist && t.artist.name) || '';
                    html += '<button class="row" data-add="' + t.id + '">' +
                        '<img src="' + escapeAttr(cover) + '" alt="">' +
                        '<div class="info"><p>' + escapeHtml(t.title) + '</p><small>' + escapeHtml(artist) + '</small></div>' +
                        '<span class="badge"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></span>' +
                        '</button>';
                });
                $('results-shelf').innerHTML = html;
                $('results-shelf').querySelectorAll('[data-add]').forEach((btn) => {
                    const id = btn.getAttribute('data-add');
                    const track = tracks.find((t) => String(t.id) === String(id));
                    btn.addEventListener('click', () => addSong(track, btn));
                });
            } catch (e) {
                $('results-shelf').innerHTML = '<div class="shelf-head">RESULTS</div><p class="empty">Search failed — try again.</p>';
            }
        }, 280);
    });

    async function addSong(track, btn) {
        if (!track) return;
        const badge = btn.querySelector('.badge');
        const prevHtml = badge.innerHTML;
        badge.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        try {
            await api('/party/' + CODE + '/song', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    member_id: memberId,
                    track_id: String(track.id),
                    title: track.title,
                    artist: (track.artist && track.artist.name) || '',
                    cover: (track.album && (track.album.cover_medium || track.album.cover)) || '',
                }),
            });
            toast('"' + track.title + '" added to the queue!');
        } catch (e) {
            toast('Could not add song');
        }
        setTimeout(() => { badge.innerHTML = prevHtml; }, 1500);
    }

    async function removeSong(songId) {
        try {
            await api('/party/' + CODE + '/song/' + songId + '?member_id=' + memberId, { method: 'DELETE' });
        } catch (e) { /* swallow */ }
    }

    load();
})();
</script>
</body>
</html>
"""


def render_guest_join_page(code: str) -> HTMLResponse:
    """Return the self-contained mobile join page with the party code
    baked in.  The HTML uses placeholders `__CODE__` that we replace
    here so the page is fully prepared when the WebView loads it
    — no client-side parsing of the URL needed."""
    safe = code.upper().replace("<", "").replace(">", "")[:24]
    html = GUEST_JOIN_HTML.replace("__CODE__", safe)
    return HTMLResponse(content=html, status_code=200)
