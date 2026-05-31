"""
ON NOW TV Tunes — Self-contained mobile guest join HTML page (v2.8.77).

Served directly by the FastAPI backend at:
    GET /api/karaoke/join/{code}

v2.8.77 rewrite goals (per user feedback):
  • Match the dark-navy + neon-blue + starfield design from the TV
    home tiles — same visual language, no purple/pink leftovers.
  • Add an AVATAR step right after the party code is shown: take
    photo (camera) or upload from library, with a "skip" fallback
    that uses an initial-letter avatar.  Avatar is captured client-
    side via FileReader → canvas → base64 PNG (256×256) so the
    payload sent to the backend stays small.
  • Everything responsive, clean, modern.
"""
from __future__ import annotations

from fastapi.responses import HTMLResponse


GUEST_JOIN_HTML = r"""<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no, viewport-fit=cover">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="theme-color" content="#060a1c">
    <title>Join Karaoke — __CODE__</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Geist:wght@400;600;700;800&family=Geist+Mono:wght@600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-0:#060a1c; --bg-1:#0d1330;
            --panel-bg: linear-gradient(180deg, #0d1330 0%, #060a1c 100%);
            --panel-border: rgba(120, 170, 255, 0.18);
            --panel-border-h: rgba(120, 170, 255, 0.55);
            --blue: #5eb5ff; --blue-2:#7cc4ff; --blue-3:#aed7ff;
            --text:#fff;
            --text-2:rgba(200, 215, 240, 0.78);
            --text-3:rgba(200, 215, 240, 0.45);
            --glow: rgba(94, 181, 255, 0.5);
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
                radial-gradient(50% 40% at 100% 100%, rgba(120,90,255,0.12), transparent 70%),
                linear-gradient(180deg, #0b1226 0%, #060a1c 100%);
            padding: 24px 18px calc(80px + env(safe-area-inset-bottom));
            position:relative;
        }
        /* page-level starfield */
        body::before {
            content:''; position:fixed; inset:0; pointer-events:none; z-index:0;
            background-image:
                radial-gradient(1.5px 1.5px at 12% 8%, rgba(180,210,255,0.5), transparent 60%),
                radial-gradient(1px 1px at 30% 28%, rgba(180,210,255,0.4), transparent 60%),
                radial-gradient(1px 1px at 60% 16%, rgba(200,220,255,0.45), transparent 60%),
                radial-gradient(1.2px 1.2px at 85% 38%, rgba(180,210,255,0.4), transparent 60%),
                radial-gradient(1px 1px at 92% 12%, rgba(200,220,255,0.45), transparent 60%),
                radial-gradient(1.4px 1.4px at 18% 56%, rgba(180,210,255,0.35), transparent 60%),
                radial-gradient(1px 1px at 70% 76%, rgba(180,210,255,0.4), transparent 60%),
                radial-gradient(1px 1px at 45% 92%, rgba(180,210,255,0.35), transparent 60%);
        }
        section, .toast { position:relative; z-index:1; }

        .center { display:flex; flex-direction:column; align-items:center; text-align:center; gap:10px; }

        /* Hero icon glow at the top of the enter screen */
        .mic-glow {
            width:108px; height:108px; border-radius:28px;
            background:
                radial-gradient(ellipse at 50% 0%, rgba(78,167,255,0.18), transparent 70%),
                var(--panel-bg);
            border:1.5px solid var(--panel-border);
            display:grid; place-items:center; color:var(--blue);
            filter:drop-shadow(0 0 22px var(--glow));
            margin:0 auto 12px;
        }
        .mic-glow svg { width:60px; height:60px; }
        .eyebrow {
            color:var(--blue-2); font-size:11px; letter-spacing:0.3em;
            font-weight:700; margin:0; text-transform:uppercase;
        }
        .pcode {
            font-family:'Geist Mono', monospace; font-weight:800;
            color:var(--blue); text-shadow:0 0 22px var(--glow);
            font-size:34px; letter-spacing:0.04em; margin:6px 0;
        }
        .help { color:var(--text-2); font-size:15px; line-height:1.5; margin:0 0 18px; max-width:320px; }
        h1.title {
            font-family:'Geist', system-ui; font-size:30px; font-weight:800;
            margin:6px 0 6px; letter-spacing:-0.02em;
        }
        .card {
            background: var(--panel-bg);
            border:1.5px solid var(--panel-border);
            border-radius:22px;
            padding:18px;
            margin:14px 0;
            box-shadow: 0 18px 50px rgba(0,0,0,0.5);
        }
        label {
            display:block; color:var(--text-3); font-size:11px;
            letter-spacing:0.18em; text-transform:uppercase;
            font-weight:700; margin:0 0 8px;
        }
        input[type=text] {
            width:100%; background:rgba(120,170,255,0.05);
            border:1.5px solid var(--panel-border); border-radius:14px;
            padding:16px; font-size:17px; color:#fff;
            font-family:inherit; outline:none;
            transition:border-color 220ms, box-shadow 220ms;
        }
        input[type=text]:focus {
            border-color:var(--blue);
            box-shadow:0 0 0 3px rgba(94,181,255,0.25);
        }
        input[type=text]::placeholder { color:var(--text-3); }

        /* Primary CTA button */
        button.cta {
            width:100%; padding:16px; font-size:16px; font-weight:700;
            border-radius:14px; border:1.5px solid var(--blue);
            background:rgba(78,167,255,0.10); color:var(--blue-3);
            box-shadow:0 0 0 1px var(--blue) inset, 0 0 24px rgba(94,181,255,0.3);
            display:flex; align-items:center; justify-content:center; gap:10px;
            cursor:pointer; font-family:inherit; margin-top:14px;
            transition:transform 180ms, box-shadow 220ms;
        }
        button.cta:hover, button.cta:active {
            background:rgba(78,167,255,0.18);
            box-shadow:0 0 0 1.5px var(--blue) inset, 0 0 40px rgba(94,181,255,0.55);
            transform:translateY(-1px);
        }
        button.cta:disabled { opacity:0.4; cursor:not-allowed; transform:none; }

        /* Secondary / ghost button */
        button.ghost {
            width:100%; padding:14px; font-size:15px; font-weight:600;
            border-radius:14px; border:1.5px solid var(--panel-border);
            background:rgba(255,255,255,0.03); color:var(--text-2);
            display:flex; align-items:center; justify-content:center; gap:8px;
            cursor:pointer; font-family:inherit; margin-top:10px;
        }
        button.ghost:hover, button.ghost:active {
            background:rgba(120,170,255,0.08);
            border-color:var(--panel-border-h);
            color:#fff;
        }

        .error {
            color:#ff7ab8; background:rgba(255,122,184,0.08);
            border:1px solid rgba(255,122,184,0.5); padding:10px 14px;
            border-radius:10px; font-size:14px; margin:10px 0;
        }

        /* ===== AVATAR STEP ===== */
        .avatar-preview {
            width:140px; height:140px; border-radius:50%;
            margin:8px auto 18px;
            background:
                radial-gradient(ellipse at 50% 0%, rgba(78,167,255,0.18), transparent 70%),
                var(--panel-bg);
            border:2px solid var(--panel-border-h);
            display:grid; place-items:center;
            overflow:hidden; position:relative;
            box-shadow:0 0 36px var(--glow);
        }
        .avatar-preview img {
            width:100%; height:100%; object-fit:cover;
            position:absolute; inset:0;
        }
        .avatar-preview .ph-icon { color:var(--blue); }
        .avatar-preview .ph-icon svg { width:48px; height:48px; }
        .avatar-actions {
            display:grid; grid-template-columns:1fr 1fr; gap:10px;
        }
        .avatar-actions button {
            padding:14px; font-size:14px; font-weight:700;
            border-radius:14px; border:1.5px solid var(--panel-border);
            background:rgba(120,170,255,0.05); color:#fff;
            display:flex; flex-direction:column; align-items:center; justify-content:center;
            gap:6px; cursor:pointer; font-family:inherit;
            transition:transform 180ms, border-color 220ms, box-shadow 220ms;
        }
        .avatar-actions button:hover, .avatar-actions button:active {
            border-color:var(--panel-border-h);
            background:rgba(120,170,255,0.10);
            transform:translateY(-1px);
        }
        .avatar-actions button svg { width:22px; height:22px; color:var(--blue); }
        input[type=file] { display:none !important; }

        /* ===== SONGS PHASE ===== */
        .topbar {
            display:flex; align-items:center; justify-content:space-between;
            gap:12px; padding-bottom:14px;
            border-bottom:1px solid rgba(120,170,255,0.12); margin-bottom:14px;
        }
        .topbar .me {
            display:flex; align-items:center; gap:10px;
        }
        .topbar .me-av {
            width:42px; height:42px; border-radius:50%;
            background:linear-gradient(135deg, #2a4170, #1a2f56);
            color:var(--blue-3); border:1.5px solid var(--panel-border-h);
            display:grid; place-items:center; font-weight:800; font-size:16px;
            overflow:hidden; flex-shrink:0;
        }
        .topbar .me-av img { width:100%; height:100%; object-fit:cover; }
        .topbar .me-info p { margin:0; font-weight:700; font-size:15px; color:#fff; }
        .topbar .pcode { font-size:14px; margin:0; }
        .iconbtn {
            width:40px; height:40px; border-radius:12px;
            background:rgba(255,255,255,0.04); border:1px solid var(--panel-border);
            color:#fff; display:grid; place-items:center; cursor:pointer;
        }
        .search {
            position:relative;
            background:var(--panel-bg);
            border:1.5px solid var(--panel-border);
            border-radius:14px;
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
            color:var(--blue-2); font-size:11px; letter-spacing:0.24em;
            font-weight:700; margin:6px 0 10px; text-transform:uppercase;
        }
        .row {
            display:flex; align-items:center; gap:12px;
            background:var(--panel-bg);
            border:1px solid var(--panel-border);
            border-radius:14px; padding:10px; margin-bottom:10px;
            width:100%; text-align:left; cursor:pointer;
            color:inherit; font-family:inherit;
        }
        .row:active { background:rgba(78,167,255,0.12); border-color:var(--panel-border-h); }
        .row img {
            width:52px; height:52px; border-radius:10px; object-fit:cover;
            flex-shrink:0; background:#0d1330;
        }
        .row .info { flex:1; min-width:0; }
        .row .info p { margin:0; font-weight:600; font-size:15px;
            white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:#fff; }
        .row .info small { color:var(--text-3); font-size:12px; }
        .row .badge {
            width:36px; height:36px; border-radius:12px;
            background:rgba(78,167,255,0.18); border:1.5px solid var(--blue);
            color:var(--blue); display:grid; place-items:center; flex-shrink:0;
        }
        .row .remove { color:#ff7ab8; font-size:13px; font-weight:600; }
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

        .joined-pills {
            display:flex; gap:8px; overflow-x:auto; padding-bottom:6px;
            margin:0 -4px 12px; scrollbar-width:none;
        }
        .joined-pills::-webkit-scrollbar { display:none; }
        .pill {
            flex-shrink:0; padding:7px 12px; border-radius:30px;
            background:rgba(78,167,255,0.10); border:1px solid var(--panel-border);
            font-size:12px; white-space:nowrap; color:#fff;
            display:flex; align-items:center; gap:8px;
        }
        .pill .av {
            width:22px; height:22px; border-radius:50%;
            background:linear-gradient(135deg, #2a4170, #1a2f56);
            color:var(--blue-3); border:1px solid var(--panel-border-h);
            font-size:10px; font-weight:800;
            display:grid; place-items:center; overflow:hidden; flex-shrink:0;
        }
        .pill .av img { width:100%; height:100%; object-fit:cover; }
        .pill.host { background:rgba(78,167,255,0.18); border-color:var(--blue); }
        .pill.you { background:rgba(78,167,255,0.16); border-color:var(--blue); font-weight:700; }

        /* ===== MIC PHASE — phone-as-microphone ===== */
        .mic-phase {
            position:fixed; inset:0; z-index:60;
            background:
                radial-gradient(ellipse at 50% 0%, rgba(255,122,184,0.25), transparent 70%),
                radial-gradient(ellipse at 50% 100%, rgba(78,167,255,0.20), transparent 70%),
                linear-gradient(180deg, #1a0a2a 0%, #060418 100%);
            display:none; flex-direction:column; align-items:center;
            justify-content:center; padding:30px 24px;
            text-align:center; color:#fff;
        }
        .mic-phase.show { display:flex; }
        .mic-phase__eyebrow {
            color:#ffcfe4; font-size:11px; letter-spacing:0.34em;
            font-weight:800; text-transform:uppercase; margin:0 0 8px;
        }
        .mic-phase__title {
            font-family:'Geist', system-ui; font-weight:800;
            font-size:34px; letter-spacing:-0.025em; line-height:1;
            margin:0 0 4px;
            background: linear-gradient(90deg, #ff7ab8 0%, #ffb070 50%, #5eb5ff 100%);
            -webkit-background-clip:text; background-clip:text;
            -webkit-text-fill-color:transparent;
            filter:drop-shadow(0 0 22px rgba(255,122,184,0.4));
        }
        .mic-phase__sub {
            color:rgba(255,220,245,0.78); font-size:15px;
            margin:8px 0 28px; line-height:1.5;
        }

        /* Big beautiful microphone artwork — fills most of the screen */
        .mic-art {
            width:240px; height:240px; margin:18px auto 28px;
            position:relative;
        }
        .mic-art__halo {
            position:absolute; inset:-40px;
            border-radius:50%;
            background: radial-gradient(circle, rgba(255,122,184,0.25), transparent 70%);
            animation: micPulse 2s ease-in-out infinite;
        }
        .mic-art__halo.live { animation-duration: 0.6s; }
        .mic-art__ring {
            position:absolute; inset:0; border-radius:50%;
            border:3px solid rgba(255,122,184,0.55);
            box-shadow:
                0 0 0 1px rgba(255,255,255,0.08) inset,
                0 0 60px rgba(255,122,184,0.55),
                0 0 120px rgba(78,167,255,0.35);
            background:
                radial-gradient(ellipse at 50% 30%, rgba(255,255,255,0.06), transparent 60%),
                linear-gradient(180deg, #2a1240 0%, #14081f 100%);
            display:grid; place-items:center;
            overflow:hidden;
        }
        .mic-art__ring svg {
            width:120px; height:120px;
            color:#fff;
            filter:drop-shadow(0 0 18px #ff7ab8) drop-shadow(0 0 36px rgba(94,181,255,0.45));
        }
        /* Live volume meter — fills the ring with a radial bar */
        .mic-art__meter {
            position:absolute; left:50%; bottom:-26px; transform:translateX(-50%);
            width:74%; height:8px; border-radius:30px;
            background:rgba(255,255,255,0.08);
            overflow:hidden;
        }
        .mic-art__meter-bar {
            height:100%; width:0%;
            background: linear-gradient(90deg, #5eb5ff, #ff7ab8);
            border-radius:30px;
            transition:width 90ms linear;
            box-shadow: 0 0 18px #ff7ab8;
        }
        @keyframes micPulse {
            0%, 100% { transform:scale(1); opacity:0.7; }
            50% { transform:scale(1.05); opacity:1; }
        }

        button.mic-cta {
            width:100%; max-width:340px;
            padding:18px; font-size:17px; font-weight:800;
            border-radius:18px;
            background: linear-gradient(135deg, #ff7ab8 0%, #ffb070 100%);
            color:#1a0a2a; border:0;
            box-shadow: 0 18px 40px rgba(255,122,184,0.5), 0 0 0 2px rgba(255,122,184,0.5);
            display:flex; align-items:center; justify-content:center; gap:10px;
            cursor:pointer; font-family:inherit;
            letter-spacing:0.02em;
            transition:transform 200ms;
        }
        button.mic-cta:active { transform:scale(0.97); }
        button.mic-cta.live {
            background: linear-gradient(135deg, #22d671 0%, #0e8a52 100%);
            color:#04140b;
            box-shadow: 0 18px 40px rgba(34,214,113,0.45), 0 0 0 2px rgba(34,214,113,0.5);
        }
        button.mic-cta.live::before {
            content:''; width:10px; height:10px; border-radius:50%;
            background:#fff; box-shadow:0 0 12px #fff;
            animation: liveBlink 1.2s ease-in-out infinite;
        }
        @keyframes liveBlink {
            0%, 100% { opacity:1; }
            50% { opacity:0.3; }
        }
        .mic-status {
            color:rgba(255,255,255,0.7); font-size:13px;
            margin:18px 0 0; min-height:16px;
        }
        .mic-status.err { color:#ff7ab8; }

        /* v2.8.84 — Mic style picker (horizontal scroll) */
        .mic-picker {
            margin: 26px 0 0;
            width: 100%;
            max-width: 380px;
        }
        .mic-picker__eyebrow {
            color: rgba(255,255,255,0.55);
            font-family: 'Geist Mono', monospace;
            font-size: 10px;
            letter-spacing: 0.32em;
            font-weight: 700;
            text-transform: uppercase;
            margin: 0 0 12px;
        }
        .mic-picker__strip {
            display: flex;
            gap: 12px;
            overflow-x: auto;
            padding: 4px 4px 16px;
            scrollbar-width: none;
            scroll-snap-type: x mandatory;
            -webkit-overflow-scrolling: touch;
        }
        .mic-picker__strip::-webkit-scrollbar { display: none; }
        .mic-picker__option {
            flex-shrink: 0;
            scroll-snap-align: center;
            width: 84px; height: 92px;
            background: rgba(255,255,255,0.04);
            border: 1.5px solid rgba(255,255,255,0.12);
            border-radius: 14px;
            padding: 8px 6px;
            cursor: pointer;
            display: flex; flex-direction: column;
            align-items: center; justify-content: center;
            gap: 8px;
            transition: border-color 220ms, background 220ms, transform 200ms;
            font-family: inherit;
        }
        .mic-picker__option:active { transform: scale(0.94); }
        .mic-picker__option.is-active {
            border-color: #ff7ab8;
            background: rgba(255,122,184,0.14);
            box-shadow: 0 0 0 1.5px rgba(255,122,184,0.5) inset, 0 0 24px rgba(255,122,184,0.35);
        }
        .mic-picker__swatch {
            width: 56px; height: 56px; border-radius: 14px;
            position: relative; overflow: hidden;
            box-shadow: 0 8px 18px rgba(0,0,0,0.45);
        }
        .mic-picker__swatch::after {
            /* mic-shape silhouette overlay so each thumb still
               reads as a microphone, not just a colour swatch */
            content: '';
            position: absolute; inset: 8px 18px;
            background: rgba(0,0,0,0.45);
            border-radius: 50% 50% 8px 8px / 60% 60% 8px 8px;
        }
        .mic-picker__label {
            font-size: 9px;
            font-weight: 700;
            color: rgba(255,220,245,0.85);
            text-align: center;
            letter-spacing: 0.04em;
            line-height: 1;
            white-space: nowrap;
        }
        /* Per-style swatch backgrounds */
        .mic-picker__swatch[data-style="sm58"]    { background: linear-gradient(135deg, #1a1d24, #4a4f58); }
        .mic-picker__swatch[data-style="gold"]    { background: linear-gradient(135deg, #b87328, #ffe49a, #b87328); }
        .mic-picker__swatch[data-style="neon"]    { background: radial-gradient(circle at 30% 30%, #ff35a0, #00f0ff); }
        .mic-picker__swatch[data-style="crystal"] { background: linear-gradient(135deg, #ff7ab8, #ffd43b, #5eb5ff, #a96bff); }
        .mic-picker__swatch[data-style="ribbon"]  { background: linear-gradient(135deg, #6b6f78, #dadde2, #6b6f78); }
        .mic-picker__swatch[data-style="flame"]   { background: linear-gradient(180deg, #ffe600, #ff8a00, #ff2400, #0a0a0a); }
        .mic-picker__swatch[data-style="rose"]    { background: linear-gradient(135deg, #fff0e8, #f8b89c, #b87358); }
        .mic-picker__swatch[data-style="holo"]    { background: linear-gradient(135deg, #8cfff0, #aa78ff); }
        .mic-picker__swatch[data-style="lava"]    { background: radial-gradient(circle at 50% 30%, #fff9c4, #ff4500, #2a0a05); }
        .mic-picker__swatch[data-style="galaxy"]  { background: radial-gradient(circle at 30% 30%, #ffe1f5, #a96bff, #1a0a45, #080418); }

        /* =============================================================
         * v2.8.83 — LIVE state: full-screen real-microphone artwork
         *
         * Once the singer taps "Turn on your mic" AND the WebRTC peer
         * connects, the small mic icon dissolves and the entire phone
         * screen becomes a tall, photo-real karaoke microphone SVG
         * the singer holds up to their face.  The mic glows brighter
         * the louder they sing (CSS var `--vol` driven by the
         * AudioContext analyser).
         * ============================================================ */
        .mic-phase.is-live {
            padding: 0;
            background:
                radial-gradient(ellipse 70% 50% at 50% 30%,
                    rgba(255, 122, 184, calc(0.18 + var(--vol, 0) * 0.6)),
                    transparent 65%),
                radial-gradient(ellipse 90% 60% at 50% 100%,
                    rgba(78, 167, 255, 0.18),
                    transparent 70%),
                linear-gradient(180deg, #1a0a2a 0%, #060418 100%);
        }
        .mic-phase.is-live .mic-phase__pre,
        .mic-phase.is-live .mic-art,
        .mic-phase.is-live .mic-status,
        .mic-phase.is-live button.mic-cta { display: none; }

        .mic-live {
            display: none;
            position: relative;
            width: 100%;
            height: 100dvh;
            overflow: hidden;
        }
        .mic-phase.is-live .mic-live { display: block; }

        /* Floating song-title badge */
        .mic-live__head {
            position: absolute;
            top: calc(20px + env(safe-area-inset-top));
            left: 50%;
            transform: translateX(-50%);
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 6px;
            z-index: 3;
            width: 88%;
            text-align: center;
        }
        .mic-live__live-pill {
            display: inline-flex;
            align-items: center;
            gap: 8px;
            background: rgba(34, 214, 113, 0.18);
            border: 1px solid #22d671;
            color: #c8f8de;
            padding: 6px 14px;
            border-radius: 999px;
            font-size: 11px;
            font-weight: 800;
            letter-spacing: 0.28em;
            font-family: 'Geist Mono', monospace;
            text-transform: uppercase;
        }
        .mic-live__live-pill::before {
            content: '';
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: #22d671;
            box-shadow: 0 0 12px #22d671;
            animation: liveBlink 1.2s ease-in-out infinite;
        }
        .mic-live__song {
            color: #fff;
            font-weight: 800;
            font-size: 18px;
            letter-spacing: -0.01em;
            line-height: 1.2;
            margin: 0;
            text-shadow: 0 2px 12px rgba(0, 0, 0, 0.6);
        }
        .mic-live__artist {
            color: rgba(255, 220, 245, 0.7);
            font-size: 12px;
            margin: 0;
        }

        /* The SVG microphone fills the lower 70% of the screen,
           centered horizontally, so the singer naturally holds the
           phone vertically and the grille appears near their face. */
        .mic-live__svg-wrap {
            position: absolute;
            inset: 0;
            display: flex;
            align-items: center;
            justify-content: center;
            z-index: 2;
        }
        .mic-live__svg {
            width: clamp(220px, 78vw, 360px);
            height: auto;
            max-height: 88dvh;
            filter:
                drop-shadow(0 0 calc(20px + var(--vol, 0) * 60px) rgba(255, 122, 184, calc(0.55 + var(--vol, 0) * 0.45)))
                drop-shadow(0 0 calc(40px + var(--vol, 0) * 80px) rgba(78, 167, 255, calc(0.30 + var(--vol, 0) * 0.30)));
            transition: filter 80ms linear;
        }
        /* Subtle pulse on the grille that runs faster when louder. */
        .mic-live__grille-glow {
            transform-origin: center;
            animation: micGrilleGlow 1.4s ease-in-out infinite;
            transform-box: fill-box;
        }
        @keyframes micGrilleGlow {
            0%, 100% { opacity: 0.55; }
            50%      { opacity: 1; }
        }

        /* Bottom controls: stop button + sub-hint */
        .mic-live__foot {
            position: absolute;
            left: 0; right: 0;
            bottom: calc(28px + env(safe-area-inset-bottom));
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 14px;
            padding: 0 24px;
            z-index: 4;
        }
        .mic-live__stop {
            width: 100%;
            max-width: 320px;
            padding: 16px;
            font-size: 15px;
            font-weight: 800;
            border-radius: 14px;
            border: 1.5px solid rgba(255, 255, 255, 0.22);
            background: rgba(0, 0, 0, 0.55);
            color: #fff;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 10px;
            cursor: pointer;
            font-family: inherit;
            backdrop-filter: blur(12px);
            -webkit-backdrop-filter: blur(12px);
            letter-spacing: 0.02em;
        }
        .mic-live__stop:active { transform: scale(0.97); }
        .mic-live__hint {
            color: rgba(255, 220, 245, 0.62);
            font-size: 12px;
            margin: 0;
            text-align: center;
        }
    </style>
</head>
<body>

<!-- PHASE 1: ENTER NAME -->
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
        <p class="help">You're about to join a karaoke party. Type your name to get started.</p>
    </div>

    <div class="card">
        <label>Your name</label>
        <input id="name-input" type="text" maxlength="40" autofocus placeholder="e.g. Jamie">
        <p id="enter-err" class="error" style="display:none;"></p>
        <button id="name-next-btn" class="cta" disabled>
            Next: choose an avatar
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/>
            </svg>
        </button>
    </div>
</section>

<!-- PHASE 2: CHOOSE AVATAR -->
<section id="phase-avatar" style="display:none;">
    <div class="center">
        <p class="eyebrow">STEP 2 OF 2</p>
        <h1 class="title">Pick your photo</h1>
        <p class="help">Snap a quick selfie or upload a photo so everyone knows who's singing.</p>
    </div>

    <div class="card">
        <div class="avatar-preview" id="avatar-preview">
            <span id="avatar-initial-fallback" class="ph-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="9" r="4"/>
                    <path d="M4 21c0-4 4-7 8-7s8 3 8 7"/>
                </svg>
            </span>
        </div>

        <div class="avatar-actions">
            <button type="button" id="take-photo-btn">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/>
                    <circle cx="12" cy="13" r="4"/>
                </svg>
                Take Photo
            </button>
            <button type="button" id="upload-photo-btn">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                    <polyline points="17 8 12 3 7 8"/>
                    <line x1="12" y1="3" x2="12" y2="15"/>
                </svg>
                Upload Photo
            </button>
        </div>

        <input id="take-photo-file" type="file" accept="image/*" capture="user">
        <input id="upload-photo-file" type="file" accept="image/*">

        <p id="avatar-err" class="error" style="display:none;"></p>
        <button id="avatar-join-btn" class="cta">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>
            Join the Party
        </button>
        <button id="avatar-skip-btn" class="ghost">Skip — use my initials</button>
    </div>
</section>

<!-- PHASE 3: SONG PICKER -->
<section id="phase-songs" style="display:none;">
    <div class="topbar">
        <div class="me">
            <span class="me-av" id="me-av-circle"></span>
            <div class="me-info">
                <p id="songs-who"></p>
                <p class="pcode" id="songs-code"></p>
            </div>
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

<!-- MIC PHASE — phone-as-microphone overlay (v2.8.83) -->
<div id="phase-mic" class="mic-phase">
    <!-- "Pre-live" intro view (before user taps Turn On) -->
    <div class="mic-phase__pre">
        <p class="mic-phase__eyebrow" id="mic-up-next">YOU'RE UP NEXT</p>
        <h1 class="mic-phase__title" id="mic-song-title">Get ready</h1>
        <p class="mic-phase__sub" id="mic-song-sub">Hold your phone close to your face like a microphone.</p>

        <div class="mic-art">
            <div class="mic-art__halo" id="mic-halo"></div>
            <div class="mic-art__ring">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
                    <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
                    <line x1="12" y1="19" x2="12" y2="23"/>
                    <line x1="8" y1="23" x2="16" y2="23"/>
                </svg>
                <div class="mic-art__meter">
                    <div class="mic-art__meter-bar" id="mic-meter"></div>
                </div>
            </div>
        </div>

        <button id="mic-cta" class="mic-cta" type="button">
            Turn on your mic
        </button>
        <p id="mic-status" class="mic-status">Tap to start. Browser will ask for microphone permission.</p>

        <!-- v2.8.84 — Mic style picker.  Horizontal scrollable strip
             of 10 mini SVGs.  Tap to select; choice persists in
             localStorage and becomes the full-screen mic in LIVE
             state. -->
        <div class="mic-picker">
            <p class="mic-picker__eyebrow">CHOOSE YOUR MIC</p>
            <div class="mic-picker__strip" id="mic-picker-strip"></div>
        </div>
    </div>

    <!-- LIVE view: full-screen real-microphone artwork (v2.8.83) -->
    <div class="mic-live" id="mic-live">
        <div class="mic-live__head">
            <span class="mic-live__live-pill">LIVE</span>
            <p class="mic-live__song" id="mic-live-song">Now Singing</p>
            <p class="mic-live__artist" id="mic-live-artist"></p>
        </div>

        <div class="mic-live__svg-wrap">
            <!-- v2.8.84 — Mic SVG injected by JS based on the user's
                 chosen style (default: 'sm58').  10 designs available
                 via the picker on the pre-live screen. -->
            <div class="mic-live__svg" id="mic-live-svg"></div>
        </div>

        <div class="mic-live__foot">
            <button id="mic-stop-btn" class="mic-live__stop" type="button">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
                Stop singing
            </button>
            <p class="mic-live__hint">Hold the phone close to your mouth like a real mic.</p>
        </div>
    </div>
</div>


<!-- PHASE: ERROR -->
<section id="phase-error" class="center" style="display:none; padding-top:60px;">
    <div class="mic-glow" style="background: rgba(255,122,184,0.08); border-color:rgba(255,122,184,0.4); color:#ff7ab8;">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
    </div>
    <p class="eyebrow" style="color:#ff7ab8;">PARTY NOT FOUND</p>
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
    const LS_AVATAR  = 'tunes-karaoke-guest-avatar';
    const $ = (id) => document.getElementById(id);
    const show = (id) => {
        ['phase-enter','phase-avatar','phase-songs','phase-error'].forEach((p) => {
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
    let pendingName = '';
    let pendingAvatar = '';        // base64 data URL, '' for none
    let pollSince = 0;
    let polling = false;
    $('enter-code').textContent = CODE;
    $('songs-code').textContent = CODE;
    $('name-input').value = localStorage.getItem(LS_NAME) || '';
    if ($('name-input').value.trim()) $('name-next-btn').disabled = false;

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

    function escapeHtml(s) { return String(s).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
    function escapeAttr(s) { return escapeHtml(s); }
    function getInitial(name) { return (name || '?').trim()[0]?.toUpperCase() || '?'; }

    /* -------- Avatar capture: resize to 256x256 PNG via canvas -------- */
    function readAndResize(file, max) {
        return new Promise((resolve, reject) => {
            if (!file) return reject(new Error('No file'));
            const reader = new FileReader();
            reader.onerror = () => reject(new Error('Could not read file'));
            reader.onload = () => {
                const img = new Image();
                img.onload = () => {
                    const M = max || 256;
                    const side = Math.min(img.width, img.height);
                    const sx = (img.width  - side) / 2;
                    const sy = (img.height - side) / 2;
                    const canvas = document.createElement('canvas');
                    canvas.width = M; canvas.height = M;
                    const ctx = canvas.getContext('2d');
                    ctx.drawImage(img, sx, sy, side, side, 0, 0, M, M);
                    resolve(canvas.toDataURL('image/jpeg', 0.82));
                };
                img.onerror = () => reject(new Error('Bad image'));
                img.src = reader.result;
            };
            reader.readAsDataURL(file);
        });
    }

    function setPreview(dataUrl) {
        const wrap = $('avatar-preview');
        // Remove any existing img
        wrap.querySelectorAll('img').forEach((n) => n.remove());
        const fallback = $('avatar-initial-fallback');
        if (dataUrl) {
            fallback.style.display = 'none';
            const im = document.createElement('img');
            im.src = dataUrl;
            wrap.appendChild(im);
        } else {
            fallback.style.display = '';
        }
    }

    async function handleFile(file) {
        try {
            $('avatar-err').style.display = 'none';
            const url = await readAndResize(file, 256);
            pendingAvatar = url;
            setPreview(url);
        } catch (e) {
            $('avatar-err').textContent = (e && e.message) || 'Could not process image';
            $('avatar-err').style.display = 'block';
        }
    }

    $('take-photo-btn').addEventListener('click', () => $('take-photo-file').click());
    $('upload-photo-btn').addEventListener('click', () => $('upload-photo-file').click());
    $('take-photo-file').addEventListener('change', (e) => handleFile(e.target.files[0]));
    $('upload-photo-file').addEventListener('change', (e) => handleFile(e.target.files[0]));

    $('avatar-skip-btn').addEventListener('click', () => {
        pendingAvatar = '';
        setPreview('');
        joinNow();
    });
    $('avatar-join-btn').addEventListener('click', () => joinNow());

    async function joinNow() {
        $('avatar-join-btn').disabled = true;
        $('avatar-skip-btn').disabled = true;
        try {
            const r = await api('/party/' + CODE + '/join', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: pendingName, avatar: pendingAvatar || '' }),
            });
            memberId = r.member_id;
            party = r.party;
            pollSince = party.updated_at;
            localStorage.setItem(LS_MEMBER, memberId);
            localStorage.setItem(LS_PARTY, CODE);
            localStorage.setItem(LS_NAME, pendingName);
            if (pendingAvatar) {
                try { localStorage.setItem(LS_AVATAR, pendingAvatar); } catch (e) { /* quota — ignore */ }
            }
            goSongs();
        } catch (e) {
            $('avatar-err').textContent = (e && e.message) || 'Could not join';
            $('avatar-err').style.display = 'block';
            $('avatar-join-btn').disabled = false;
            $('avatar-skip-btn').disabled = false;
        }
    }

    /* -------- Phase rendering -------- */
    function renderJoinedPills() {
        const el = $('joined-pills'); el.innerHTML = '';
        party.members.forEach((m) => {
            const p = document.createElement('span');
            p.className = 'pill' + (m.is_host ? ' host' : '') + (m.id === memberId ? ' you' : '');
            const av = document.createElement('span');
            av.className = 'av';
            if (m.avatar) {
                av.innerHTML = '<img src="' + escapeAttr(m.avatar) + '" alt="">';
            } else {
                av.textContent = getInitial(m.name);
            }
            p.appendChild(av);
            const label = document.createElement('span');
            label.textContent = m.name + (m.is_host ? ' · HOST' : '');
            p.appendChild(label);
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
        let html = '<div class="shelf-head">YOUR QUEUE · ' + mine.length + '</div>';
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
        let html = '<div class="shelf-head">EVERYONE ELSE · ' + others.length + '</div>';
        others.forEach((q) => {
            html += '<div class="row other"><img src="' + escapeAttr(q.cover || '') + '" alt="">' +
                '<div class="info"><p>' + escapeHtml(q.title) + '</p>' +
                '<small>' + escapeHtml(q.artist) + ' · ' + escapeHtml(q.member_name) + '</small></div></div>';
        });
        wrap.innerHTML = html;
    }

    function renderTopbar() {
        const me = party.members.find((m) => m.id === memberId);
        const myName = me ? me.name : 'Guest';
        $('songs-who').textContent = myName;
        const av = $('me-av-circle');
        av.innerHTML = '';
        if (me && me.avatar) {
            const im = document.createElement('img');
            im.src = me.avatar;
            av.appendChild(im);
        } else {
            av.textContent = getInitial(myName);
        }
    }

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
        // v2.8.82 — Also wire up the phone-as-mic listener.
        startMicWatcher();
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
                    // v2.8.82 — Notify the mic watcher so it can
                    // (a) show/hide the "Turn on your mic" overlay
                    // when current_singer_id flips, and (b) feed any
                    // inbound WebRTC signals (TV's answer + ICE) to
                    // the live peer connection.
                    window.dispatchEvent(new CustomEvent('tunes-party-update'));
                }
            } catch (e) {
                await new Promise((res) => setTimeout(res, 3000));
            }
        }
    }

    $('name-input').addEventListener('input', () => {
        $('name-next-btn').disabled = $('name-input').value.trim().length === 0;
        $('enter-err').style.display = 'none';
    });
    $('name-input').addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !$('name-next-btn').disabled) $('name-next-btn').click();
    });

    $('name-next-btn').addEventListener('click', () => {
        const name = $('name-input').value.trim();
        if (!name) return;
        pendingName = name;
        // Restore avatar if returning user
        try {
            const cached = localStorage.getItem(LS_AVATAR);
            if (cached && cached.startsWith('data:image')) {
                pendingAvatar = cached;
                setPreview(cached);
            }
        } catch (e) { /* ignore */ }
        show('phase-avatar');
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
                let html = '<div class="shelf-head">RESULTS · ' + tracks.length + '</div>';
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

    /* =============================================================
     * v2.8.82 — Phone-as-microphone (WebRTC client)
     *
     * When the party's `current_singer_id` matches our member id
     * AND `mic_armed` is true, we show the big "Turn on your mic"
     * UI.  Tapping the CTA captures the phone mic with echo
     * cancellation + noise suppression, opens an RTCPeerConnection
     * to the TV via the party's signaling channel, and starts a
     * live volume meter so the singer can see the mic is working.
     *
     * The TV side does the inverse (receives the offer, plays the
     * remote stream through Web Audio).
     * ============================================================ */
    const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }];
    let micPC = null;
    let micStream = null;
    let micAudioCtx = null;
    let micMeterRAF = 0;
    let micProcessedSignalIds = new Set();
    let micShown = false;

    async function sendMicSignal(kind, payload) {
        try {
            await fetch('/api/karaoke/party/' + CODE + '/mic/signal', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    from_id: memberId,
                    to_id: 'tv',
                    kind: kind,
                    payload: payload || {},
                }),
            });
        } catch (e) { /* swallow */ }
    }

    function showMicPhase() {
        if (micShown) return;
        micShown = true;
        const upNext = party.current ? party.current : (party.queue[0] || null);
        $('mic-song-title').textContent = upNext ? upNext.title : 'You\'re up!';
        $('mic-song-sub').textContent = upNext
            ? 'Tap the button below, hold your phone like a mic, and sing along.'
            : 'Tap to turn on your phone microphone.';
        // v2.8.83 — Also pre-populate the LIVE-state song/artist
        // labels so the transition is seamless when the user taps.
        $('mic-live-song').textContent = upNext ? upNext.title : 'Now Singing';
        $('mic-live-artist').textContent = upNext ? (upNext.artist || '') : '';
        $('phase-mic').classList.remove('is-live');
        $('phase-mic').classList.add('show');
        $('phase-mic').style.setProperty('--vol', 0);
    }

    function hideMicPhase() {
        if (!micShown) return;
        micShown = false;
        $('phase-mic').classList.remove('show');
        $('phase-mic').classList.remove('is-live');
        cleanupMic();
    }

    function cleanupMic() {
        cancelAnimationFrame(micMeterRAF);
        micMeterRAF = 0;
        if (micPC) {
            try { micPC.close(); } catch {}
            micPC = null;
        }
        if (micStream) {
            try { micStream.getTracks().forEach((t) => t.stop()); } catch {}
            micStream = null;
        }
        if (micAudioCtx) {
            try { micAudioCtx.close(); } catch {}
            micAudioCtx = null;
        }
        $('mic-meter').style.width = '0%';
    }

    async function turnOnMic() {
        const btn = $('mic-cta');
        const status = $('mic-status');
        btn.disabled = true;
        status.textContent = 'Requesting microphone…';
        status.classList.remove('err');
        try {
            micStream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true,
                    channelCount: 1,
                    sampleRate: 48000,
                },
                video: false,
            });
        } catch (e) {
            status.textContent = 'Mic access denied. Allow the microphone and tap Try Again.';
            status.classList.add('err');
            btn.textContent = 'Try Again';
            btn.disabled = false;
            return;
        }

        // Live volume meter (drives both the pre-live bar and the
        // CSS --vol variable that pulses the LIVE-state glow).
        try {
            micAudioCtx = new (window.AudioContext || window.webkitAudioContext)();
            const src = micAudioCtx.createMediaStreamSource(micStream);
            const analyser = micAudioCtx.createAnalyser();
            analyser.fftSize = 1024;
            src.connect(analyser);
            const data = new Uint8Array(analyser.fftSize);
            let smoothed = 0;
            const tick = () => {
                analyser.getByteTimeDomainData(data);
                let sum = 0;
                for (let i = 0; i < data.length; i++) {
                    const v = (data[i] - 128) / 128;
                    sum += v * v;
                }
                const rms = Math.sqrt(sum / data.length);
                // Smooth so the glow doesn't flicker on every sample.
                smoothed = smoothed * 0.6 + rms * 0.4;
                const pct = Math.min(100, Math.round(smoothed * 220));
                $('mic-meter').style.width = pct + '%';
                // Map rms (~0..0.5 typical voice) to 0..1 for the
                // SVG glow filter.  Clamp to avoid runaway.
                const volNorm = Math.min(1, smoothed * 3.5);
                $('phase-mic').style.setProperty('--vol', volNorm.toFixed(3));
                micMeterRAF = requestAnimationFrame(tick);
            };
            tick();
        } catch (e) { /* meter optional */ }

        // WebRTC peer connection — open offer to TV
        try {
            micPC = new RTCPeerConnection({ iceServers: ICE_SERVERS });
            micStream.getTracks().forEach((t) => micPC.addTrack(t, micStream));
            micPC.onicecandidate = (e) => {
                if (e.candidate) sendMicSignal('ice', { candidate: e.candidate });
            };
            micPC.onconnectionstatechange = () => {
                if (!micPC) return;
                if (micPC.connectionState === 'connected') {
                    status.textContent = 'You\'re live!';
                    btn.classList.add('live');
                    btn.textContent = 'Mic ON · Singing';
                    $('mic-halo').classList.add('live');
                    btn.disabled = false;
                    // v2.8.83 — Transform the phone into a full-screen
                    // microphone the moment WebRTC is connected.
                    $('phase-mic').classList.add('is-live');
                } else if (micPC.connectionState === 'failed') {
                    status.textContent = 'Connection failed. Try Again.';
                    status.classList.add('err');
                    btn.disabled = false;
                }
            };
            const offer = await micPC.createOffer({ offerToReceiveAudio: false });
            await micPC.setLocalDescription(offer);
            await sendMicSignal('offer', { sdp: micPC.localDescription });
            // Tell server the mic is on so the TV can start playback
            await fetch('/api/karaoke/party/' + CODE + '/mic/on', { method: 'POST' });
            status.textContent = 'Connecting to TV…';
        } catch (e) {
            status.textContent = 'Could not establish connection: ' + (e.message || e);
            status.classList.add('err');
            btn.disabled = false;
        }
    }

    $('mic-cta').addEventListener('click', () => {
        const btn = $('mic-cta');
        if (btn.classList.contains('live')) {
            // Tap again = stop mic
            stopMicNow();
            return;
        }
        turnOnMic();
    });

    // v2.8.83 — Stop button inside the full-screen LIVE view.
    $('mic-stop-btn').addEventListener('click', () => stopMicNow());

    function stopMicNow() {
        cleanupMic();
        const btn = $('mic-cta');
        btn.classList.remove('live');
        btn.textContent = 'Turn on your mic';
        btn.disabled = false;
        $('mic-halo').classList.remove('live');
        $('mic-status').textContent = 'Mic off. Tap to turn back on.';
        $('mic-status').classList.remove('err');
        $('phase-mic').classList.remove('is-live');
        $('phase-mic').style.setProperty('--vol', 0);
        sendMicSignal('bye', {});
    }

    async function handleMicSignals(signals) {
        if (!micPC) return;
        for (const sig of (signals || [])) {
            if (sig.to_id !== memberId) continue;
            if (micProcessedSignalIds.has(sig.id)) continue;
            micProcessedSignalIds.add(sig.id);
            try {
                if (sig.kind === 'answer' && sig.payload?.sdp) {
                    await micPC.setRemoteDescription(sig.payload.sdp);
                } else if (sig.kind === 'ice' && sig.payload?.candidate) {
                    await micPC.addIceCandidate(sig.payload.candidate);
                }
            } catch (e) {
                console.warn('[mic] signal handler failed', sig.kind, e);
            }
        }
    }

    function startMicWatcher() {
        // Re-evaluates after every party update (polling already wired)
        const evaluate = async () => {
            if (!party) return;
            // Show / hide the mic phase based on whether we're the
            // current singer AND the mic is armed.
            const isMine = party.current_singer_id && party.current_singer_id === memberId;
            if (isMine && party.mic_armed) showMicPhase();
            else if (!isMine) hideMicPhase();
            // Always process inbound signals targeted at us.
            await handleMicSignals(party.signals);
        };
        evaluate();
        window.addEventListener('tunes-party-update', evaluate);
    }


    /* =============================================================
     * v2.8.84 — 10 microphone designs + picker
     *
     * Each entry returns the FULL <svg> string for a 200×600 viewBox.
     * Top of the viewBox = mic head; bottom = handle.  All designs
     * tuned to look great at both thumbnail (76×96) and full-screen
     * (≥360×~880) sizes.
     * ============================================================ */
    const MIC_STYLES = [
        {
            id: 'sm58', label: 'Classic',
            // Iconic Shure-style ball with diagonal wire mesh + matte black grip
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="sm58-ball" cx="50%" cy="35%" r="58%">
                        <stop offset="0%" stop-color="#9aa0a8"/>
                        <stop offset="55%" stop-color="#3a3f47"/>
                        <stop offset="100%" stop-color="#0c0e12"/>
                    </radialGradient>
                    <linearGradient id="sm58-body" x1="0%" y1="0%" x2="100%" y2="0%">
                        <stop offset="0%" stop-color="#0a0b0f"/>
                        <stop offset="50%" stop-color="#272a33"/>
                        <stop offset="100%" stop-color="#0a0b0f"/>
                    </linearGradient>
                </defs>
                <ellipse cx="100" cy="125" rx="95" ry="92" fill="rgba(255,255,255,0.06)"/>
                <circle cx="100" cy="125" r="85" fill="url(#sm58-ball)" stroke="rgba(255,255,255,0.18)" stroke-width="1.5"/>
                <g stroke="rgba(0,0,0,0.45)" stroke-width="1" fill="none">
                    <path d="M 25 125 Q 100 50 175 125"/><path d="M 25 125 Q 100 200 175 125"/>
                    <path d="M 30 95 Q 100 145 170 95"/><path d="M 30 155 Q 100 105 170 155"/>
                    <line x1="100" y1="40" x2="100" y2="210"/>
                    <line x1="60" y1="55" x2="140" y2="195"/><line x1="140" y1="55" x2="60" y2="195"/>
                </g>
                <ellipse cx="80" cy="92" rx="20" ry="11" fill="rgba(255,255,255,0.45)"/>
                <rect x="72" y="215" width="56" height="14" fill="#1e2129"/>
                <rect x="65" y="229" width="70" height="345" rx="8" fill="url(#sm58-body)" stroke="rgba(255,255,255,0.12)"/>
                <rect x="80" y="240" width="4" height="324" rx="2" fill="rgba(255,255,255,0.20)"/>
                <rect x="65" y="568" width="70" height="14" rx="4" fill="#0a0b0f"/>
            </svg>`,
        },
        {
            id: 'gold', label: 'Gold',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="gold-ball" cx="48%" cy="32%" r="60%">
                        <stop offset="0%" stop-color="#fff4c7"/>
                        <stop offset="40%" stop-color="#f0c14b"/>
                        <stop offset="80%" stop-color="#8a5a14"/>
                        <stop offset="100%" stop-color="#3a2008"/>
                    </radialGradient>
                    <linearGradient id="gold-leather" x1="0%" y1="0%" x2="100%" y2="0%">
                        <stop offset="0%" stop-color="#3a1c0a"/>
                        <stop offset="50%" stop-color="#7a3a14"/>
                        <stop offset="100%" stop-color="#3a1c0a"/>
                    </linearGradient>
                </defs>
                <circle cx="100" cy="125" r="88" fill="url(#gold-ball)" stroke="rgba(255,200,80,0.3)" stroke-width="2"/>
                <g stroke="rgba(70,40,8,0.7)" stroke-width="3" fill="none">
                    <line x1="40" y1="125" x2="160" y2="125"/>
                    <line x1="65" y1="70" x2="65" y2="180"/>
                    <line x1="85" y1="55" x2="85" y2="195"/>
                    <line x1="100" y1="48" x2="100" y2="202"/>
                    <line x1="115" y1="55" x2="115" y2="195"/>
                    <line x1="135" y1="70" x2="135" y2="180"/>
                </g>
                <ellipse cx="80" cy="90" rx="22" ry="12" fill="rgba(255,255,255,0.55)"/>
                <rect x="75" y="215" width="50" height="18" fill="#c89534"/>
                <rect x="75" y="219" width="50" height="3" fill="rgba(255,255,255,0.35)"/>
                <rect x="60" y="233" width="80" height="338" rx="12" fill="url(#gold-leather)"/>
                <g stroke="rgba(255,200,120,0.18)" stroke-width="1" fill="none">
                    <line x1="60" y1="280" x2="140" y2="280"/><line x1="60" y1="340" x2="140" y2="340"/>
                    <line x1="60" y1="400" x2="140" y2="400"/><line x1="60" y1="460" x2="140" y2="460"/>
                </g>
                <rect x="60" y="565" width="80" height="14" rx="4" fill="#c89534"/>
            </svg>`,
        },
        {
            id: 'neon', label: 'Neon',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="neon-glow" cx="50%" cy="40%" r="55%">
                        <stop offset="0%" stop-color="rgba(0,240,255,0.4)"/>
                        <stop offset="100%" stop-color="rgba(0,240,255,0)"/>
                    </radialGradient>
                </defs>
                <circle cx="100" cy="125" r="100" fill="url(#neon-glow)"/>
                <circle cx="100" cy="125" r="80" fill="rgba(10,8,30,0.92)" stroke="#00f0ff" stroke-width="3" filter="drop-shadow(0 0 6px #00f0ff)"/>
                <g fill="none" stroke="#ff35a0" stroke-width="2" filter="drop-shadow(0 0 4px #ff35a0)">
                    <circle cx="100" cy="125" r="60"/>
                    <line x1="40" y1="125" x2="160" y2="125"/><line x1="100" y1="65" x2="100" y2="185"/>
                    <line x1="60" y1="85" x2="140" y2="165"/><line x1="140" y1="85" x2="60" y2="165"/>
                </g>
                <rect x="74" y="216" width="52" height="14" fill="#0a0820" stroke="#00f0ff" stroke-width="1.5"/>
                <rect x="64" y="230" width="72" height="340" rx="10" fill="rgba(10,8,30,0.95)" stroke="#ff35a0" stroke-width="2.5" filter="drop-shadow(0 0 6px #ff35a0)"/>
                <line x1="100" y1="240" x2="100" y2="560" stroke="#00f0ff" stroke-width="1.5" opacity="0.7"/>
                <text x="100" y="380" text-anchor="middle" font-family="Geist Mono" font-size="14" fill="#00f0ff" letter-spacing="3" filter="drop-shadow(0 0 6px #00f0ff)">SING</text>
                <rect x="64" y="568" width="72" height="12" rx="4" fill="#0a0820" stroke="#ff35a0" stroke-width="1.5"/>
            </svg>`,
        },
        {
            id: 'crystal', label: 'Crystal',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="crystal-glow" cx="50%" cy="50%" r="55%">
                        <stop offset="0%" stop-color="rgba(255,255,255,0.95)"/>
                        <stop offset="50%" stop-color="rgba(180,220,255,0.4)"/>
                        <stop offset="100%" stop-color="rgba(220,180,255,0.05)"/>
                    </radialGradient>
                    <linearGradient id="crystal-rainbow" x1="0%" y1="0%" x2="100%" y2="100%">
                        <stop offset="0%" stop-color="#ff7ab8"/>
                        <stop offset="33%" stop-color="#ffd43b"/>
                        <stop offset="66%" stop-color="#5eb5ff"/>
                        <stop offset="100%" stop-color="#a96bff"/>
                    </linearGradient>
                </defs>
                <circle cx="100" cy="125" r="88" fill="url(#crystal-glow)" stroke="url(#crystal-rainbow)" stroke-width="2"/>
                <g stroke="rgba(255,255,255,0.6)" stroke-width="1.2" fill="rgba(255,255,255,0.04)">
                    <polygon points="100,50 140,90 100,130 60,90"/>
                    <polygon points="100,130 140,90 170,125 130,165"/>
                    <polygon points="100,130 60,90 30,125 70,165"/>
                    <polygon points="100,130 70,165 100,200 130,165"/>
                    <polygon points="70,165 100,200 70,200 50,180"/>
                    <polygon points="130,165 100,200 130,200 150,180"/>
                </g>
                <ellipse cx="80" cy="80" rx="18" ry="8" fill="rgba(255,255,255,0.85)"/>
                <ellipse cx="120" cy="105" rx="10" ry="5" fill="rgba(255,255,255,0.5)"/>
                <rect x="76" y="216" width="48" height="14" fill="rgba(220,200,255,0.6)" stroke="rgba(255,255,255,0.5)"/>
                <rect x="64" y="230" width="72" height="340" rx="12" fill="rgba(255,255,255,0.10)" stroke="url(#crystal-rainbow)" stroke-width="1.8"/>
                <g stroke="rgba(255,255,255,0.35)" stroke-width="1" fill="none">
                    <line x1="64" y1="300" x2="136" y2="300"/><line x1="64" y1="380" x2="136" y2="380"/>
                    <line x1="64" y1="460" x2="136" y2="460"/>
                    <line x1="80" y1="240" x2="80" y2="560"/><line x1="120" y1="240" x2="120" y2="560"/>
                </g>
                <rect x="64" y="568" width="72" height="12" rx="4" fill="rgba(220,200,255,0.4)" stroke="rgba(255,255,255,0.5)"/>
            </svg>`,
        },
        {
            id: 'ribbon', label: 'Vintage',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <linearGradient id="rib-chrome" x1="0%" y1="0%" x2="100%" y2="0%">
                        <stop offset="0%" stop-color="#6b6f78"/>
                        <stop offset="40%" stop-color="#dadde2"/>
                        <stop offset="60%" stop-color="#dadde2"/>
                        <stop offset="100%" stop-color="#6b6f78"/>
                    </linearGradient>
                    <linearGradient id="rib-mesh" x1="0%" y1="0%" x2="0%" y2="100%">
                        <stop offset="0%" stop-color="#3a3d44"/>
                        <stop offset="50%" stop-color="#1a1d24"/>
                        <stop offset="100%" stop-color="#3a3d44"/>
                    </linearGradient>
                </defs>
                <rect x="50" y="40" width="100" height="200" rx="12" fill="url(#rib-chrome)" stroke="rgba(0,0,0,0.5)"/>
                <rect x="60" y="52" width="80" height="176" rx="6" fill="url(#rib-mesh)"/>
                <g stroke="rgba(220,225,232,0.4)" stroke-width="2" fill="none">
                    <line x1="68" y1="60" x2="68" y2="220"/><line x1="80" y1="60" x2="80" y2="220"/>
                    <line x1="92" y1="60" x2="92" y2="220"/><line x1="104" y1="60" x2="104" y2="220"/>
                    <line x1="116" y1="60" x2="116" y2="220"/><line x1="128" y1="60" x2="128" y2="220"/>
                </g>
                <rect x="55" y="56" width="90" height="3" fill="rgba(255,255,255,0.7)"/>
                <ellipse cx="100" cy="140" rx="22" ry="38" fill="rgba(0,0,0,0.4)"/>
                <text x="100" y="146" text-anchor="middle" font-family="Geist Mono" font-size="9" font-weight="800" fill="rgba(255,220,180,0.6)" letter-spacing="2">RCA</text>
                <rect x="80" y="240" width="40" height="20" fill="url(#rib-chrome)" stroke="rgba(0,0,0,0.4)"/>
                <rect x="86" y="260" width="28" height="310" fill="url(#rib-chrome)" stroke="rgba(0,0,0,0.4)"/>
                <rect x="92" y="266" width="3" height="298" fill="rgba(255,255,255,0.6)"/>
                <rect x="78" y="568" width="44" height="14" rx="3" fill="#3a3d44"/>
            </svg>`,
        },
        {
            id: 'flame', label: 'Rockstar',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="flame-ball" cx="50%" cy="35%" r="60%">
                        <stop offset="0%" stop-color="#3a3a3a"/>
                        <stop offset="100%" stop-color="#0a0a0a"/>
                    </radialGradient>
                    <linearGradient id="flame-fire" x1="0%" y1="100%" x2="0%" y2="0%">
                        <stop offset="0%" stop-color="#ff2400"/>
                        <stop offset="50%" stop-color="#ff8a00"/>
                        <stop offset="100%" stop-color="#ffe600"/>
                    </linearGradient>
                </defs>
                <circle cx="100" cy="125" r="86" fill="url(#flame-ball)" stroke="#ff2400" stroke-width="2"/>
                <g fill="none" stroke="rgba(255,255,255,0.18)" stroke-width="1">
                    <circle cx="100" cy="125" r="68"/><circle cx="100" cy="125" r="50"/>
                    <line x1="40" y1="125" x2="160" y2="125"/><line x1="100" y1="65" x2="100" y2="185"/>
                </g>
                <ellipse cx="80" cy="92" rx="18" ry="9" fill="rgba(255,255,255,0.35)"/>
                <path d="M 100 30 Q 80 50 90 70 Q 70 60 85 90" fill="url(#flame-fire)"/>
                <rect x="74" y="216" width="52" height="14" fill="#1a0a0a" stroke="#ff2400"/>
                <rect x="64" y="230" width="72" height="340" rx="10" fill="#0a0a0a" stroke="#ff2400" stroke-width="1.5"/>
                <path d="M 64 270 Q 80 290 70 320 Q 90 310 85 340 Q 75 360 90 380" fill="none" stroke="url(#flame-fire)" stroke-width="3"/>
                <path d="M 136 320 Q 120 340 130 370 Q 110 360 115 390" fill="none" stroke="url(#flame-fire)" stroke-width="3"/>
                <path d="M 64 420 Q 80 440 70 470" fill="none" stroke="url(#flame-fire)" stroke-width="3"/>
                <text x="100" y="510" text-anchor="middle" font-family="Geist" font-weight="900" font-size="22" fill="#ff2400" filter="drop-shadow(0 0 4px #ff8a00)">ROCK</text>
                <rect x="64" y="568" width="72" height="12" rx="4" fill="#1a0a0a" stroke="#ff2400"/>
            </svg>`,
        },
        {
            id: 'rose', label: 'Rose Gold',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="rose-ball" cx="48%" cy="32%" r="62%">
                        <stop offset="0%" stop-color="#fff0e8"/>
                        <stop offset="45%" stop-color="#f8b89c"/>
                        <stop offset="85%" stop-color="#b87358"/>
                        <stop offset="100%" stop-color="#5a3325"/>
                    </radialGradient>
                    <linearGradient id="rose-body" x1="0%" y1="0%" x2="100%" y2="0%">
                        <stop offset="0%" stop-color="#7a4438"/>
                        <stop offset="50%" stop-color="#e0a48a"/>
                        <stop offset="100%" stop-color="#7a4438"/>
                    </linearGradient>
                </defs>
                <circle cx="100" cy="125" r="88" fill="url(#rose-ball)" stroke="rgba(255,180,150,0.6)" stroke-width="1.5"/>
                <g fill="rgba(120,60,50,0.25)">
                    ${(() => { let dots=''; for(let r=2;r<7;r++){ for(let c=2;c<7;c++){ dots+=`<circle cx="${40+c*16}" cy="${65+r*16}" r="2.2"/>`; } } return dots; })()}
                </g>
                <ellipse cx="78" cy="88" rx="22" ry="12" fill="rgba(255,255,255,0.65)"/>
                <ellipse cx="120" cy="108" rx="8" ry="4" fill="rgba(255,255,255,0.45)"/>
                <rect x="74" y="216" width="52" height="14" fill="#c2735c"/>
                <rect x="74" y="220" width="52" height="2.5" fill="rgba(255,255,255,0.5)"/>
                <rect x="62" y="230" width="76" height="340" rx="14" fill="url(#rose-body)" stroke="rgba(255,200,180,0.35)"/>
                <rect x="78" y="240" width="3" height="324" rx="1.5" fill="rgba(255,255,255,0.55)"/>
                <rect x="68" y="380" width="64" height="22" rx="3" fill="rgba(40,15,10,0.4)"/>
                <text x="100" y="396" text-anchor="middle" font-family="Geist" font-size="11" fill="#ffe8d8" font-weight="700" letter-spacing="1.5">ROSÉ</text>
                <rect x="62" y="565" width="76" height="14" rx="4" fill="#7a4438"/>
            </svg>`,
        },
        {
            id: 'holo', label: 'Holo',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="holo-glow" cx="50%" cy="40%" r="60%">
                        <stop offset="0%" stop-color="rgba(140,255,240,0.55)"/>
                        <stop offset="50%" stop-color="rgba(140,140,255,0.25)"/>
                        <stop offset="100%" stop-color="rgba(140,140,255,0)"/>
                    </radialGradient>
                    <linearGradient id="holo-grad" x1="0%" y1="0%" x2="0%" y2="100%">
                        <stop offset="0%" stop-color="rgba(140,255,240,0.85)"/>
                        <stop offset="100%" stop-color="rgba(170,120,255,0.55)"/>
                    </linearGradient>
                </defs>
                <circle cx="100" cy="125" r="105" fill="url(#holo-glow)"/>
                <circle cx="100" cy="125" r="78" fill="rgba(50,80,180,0.18)" stroke="url(#holo-grad)" stroke-width="2.5"/>
                <g fill="none" stroke="rgba(140,255,240,0.55)" stroke-width="1.2">
                    <ellipse cx="100" cy="125" rx="78" ry="20"/>
                    <ellipse cx="100" cy="125" rx="78" ry="40"/>
                    <ellipse cx="100" cy="125" rx="78" ry="60"/>
                    <ellipse cx="100" cy="125" rx="20" ry="78"/>
                    <ellipse cx="100" cy="125" rx="40" ry="78"/>
                    <ellipse cx="100" cy="125" rx="60" ry="78"/>
                </g>
                <ellipse cx="100" cy="50" rx="78" ry="8" fill="rgba(140,255,240,0.25)"/>
                <ellipse cx="100" cy="200" rx="78" ry="8" fill="rgba(170,120,255,0.2)"/>
                <rect x="76" y="218" width="48" height="12" fill="rgba(140,255,240,0.18)" stroke="url(#holo-grad)" stroke-width="1.5"/>
                <rect x="66" y="230" width="68" height="340" rx="12" fill="rgba(50,80,180,0.12)" stroke="url(#holo-grad)" stroke-width="2"/>
                <g stroke="rgba(140,255,240,0.4)" stroke-width="0.8" fill="none">
                    <line x1="66" y1="280" x2="134" y2="280"/><line x1="66" y1="330" x2="134" y2="330"/>
                    <line x1="66" y1="380" x2="134" y2="380"/><line x1="66" y1="430" x2="134" y2="430"/>
                    <line x1="66" y1="480" x2="134" y2="480"/>
                </g>
                <text x="100" y="410" text-anchor="middle" font-family="Geist Mono" font-size="10" fill="rgba(140,255,240,0.85)" letter-spacing="3">// SING</text>
                <rect x="66" y="568" width="68" height="12" rx="4" fill="rgba(140,255,240,0.18)" stroke="url(#holo-grad)"/>
            </svg>`,
        },
        {
            id: 'lava', label: 'Lava',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="lava-ball" cx="50%" cy="40%" r="60%">
                        <stop offset="0%" stop-color="#fff9c4"/>
                        <stop offset="25%" stop-color="#ffc107"/>
                        <stop offset="55%" stop-color="#ff4500"/>
                        <stop offset="90%" stop-color="#8b0000"/>
                        <stop offset="100%" stop-color="#2a0a05"/>
                    </radialGradient>
                </defs>
                <circle cx="100" cy="125" r="100" fill="rgba(255,80,0,0.25)"/>
                <circle cx="100" cy="125" r="86" fill="url(#lava-ball)" stroke="rgba(255,150,0,0.7)" stroke-width="2"/>
                <g fill="rgba(20,5,2,0.7)">
                    <ellipse cx="70" cy="100" rx="8" ry="14" transform="rotate(20 70 100)"/>
                    <ellipse cx="130" cy="110" rx="6" ry="10"/>
                    <ellipse cx="110" cy="170" rx="10" ry="6"/>
                    <ellipse cx="65" cy="150" rx="7" ry="11" transform="rotate(-30 65 150)"/>
                    <path d="M 90 95 Q 100 85 115 100 Q 105 120 90 110 Z"/>
                </g>
                <ellipse cx="80" cy="88" rx="18" ry="9" fill="rgba(255,255,180,0.7)"/>
                <rect x="74" y="216" width="52" height="14" fill="#2a0a05" stroke="rgba(255,100,0,0.5)"/>
                <rect x="64" y="230" width="72" height="340" rx="10" fill="#0a0303" stroke="rgba(150,40,0,0.7)" stroke-width="1.5"/>
                <path d="M 70 240 Q 80 270 75 300 Q 85 330 78 360" fill="none" stroke="#ff4500" stroke-width="2" filter="drop-shadow(0 0 4px #ff8a00)"/>
                <path d="M 130 250 Q 122 280 128 310 Q 118 340 125 370" fill="none" stroke="#ff4500" stroke-width="2" filter="drop-shadow(0 0 4px #ff8a00)"/>
                <path d="M 65 400 Q 100 410 135 400 Q 100 420 65 410" fill="#ff8a00" filter="drop-shadow(0 0 4px #ff4500)"/>
                <path d="M 75 470 Q 100 480 125 470 Q 100 490 75 480" fill="#ff4500" filter="drop-shadow(0 0 3px #ff8a00)"/>
                <rect x="64" y="568" width="72" height="12" rx="4" fill="#2a0a05" stroke="rgba(255,100,0,0.5)"/>
            </svg>`,
        },
        {
            id: 'galaxy', label: 'Galaxy',
            svg: `<svg viewBox="0 0 200 600" xmlns="http://www.w3.org/2000/svg">
                <defs>
                    <radialGradient id="galaxy-ball" cx="40%" cy="35%" r="65%">
                        <stop offset="0%" stop-color="#ffe1f5"/>
                        <stop offset="20%" stop-color="#a96bff"/>
                        <stop offset="60%" stop-color="#3a1a8a"/>
                        <stop offset="100%" stop-color="#080418"/>
                    </radialGradient>
                    <linearGradient id="galaxy-body" x1="0%" y1="0%" x2="0%" y2="100%">
                        <stop offset="0%" stop-color="#1a0a45"/>
                        <stop offset="100%" stop-color="#080418"/>
                    </linearGradient>
                </defs>
                <circle cx="100" cy="125" r="100" fill="rgba(169,107,255,0.15)"/>
                <circle cx="100" cy="125" r="86" fill="url(#galaxy-ball)" stroke="rgba(255,200,255,0.4)"/>
                <g fill="#fff">
                    <circle cx="60" cy="90" r="1.2" opacity="0.95"/><circle cx="80" cy="105" r="0.8" opacity="0.7"/>
                    <circle cx="135" cy="80" r="1.5" opacity="1"/><circle cx="120" cy="155" r="1" opacity="0.85"/>
                    <circle cx="50" cy="135" r="0.9" opacity="0.6"/><circle cx="155" cy="135" r="1.1" opacity="0.8"/>
                    <circle cx="90" cy="170" r="0.7" opacity="0.6"/><circle cx="145" cy="100" r="0.9" opacity="0.7"/>
                    <circle cx="70" cy="180" r="1.1" opacity="0.85"/>
                </g>
                <ellipse cx="100" cy="125" rx="80" ry="22" fill="none" stroke="rgba(255,200,255,0.25)" stroke-width="1" transform="rotate(25 100 125)"/>
                <ellipse cx="80" cy="80" rx="18" ry="10" fill="rgba(255,255,255,0.4)"/>
                <rect x="74" y="216" width="52" height="14" fill="#1a0a45"/>
                <rect x="64" y="230" width="72" height="340" rx="10" fill="url(#galaxy-body)" stroke="rgba(169,107,255,0.4)"/>
                <g fill="#fff">
                    <circle cx="80" cy="280" r="0.8"/><circle cx="120" cy="320" r="1"/><circle cx="75" cy="380" r="0.9"/>
                    <circle cx="125" cy="430" r="0.7"/><circle cx="85" cy="480" r="1.1"/><circle cx="115" cy="520" r="0.8"/>
                </g>
                <text x="100" y="400" text-anchor="middle" font-family="Geist" font-size="11" font-weight="700" fill="rgba(255,200,255,0.85)" letter-spacing="2">COSMIC</text>
                <rect x="64" y="568" width="72" height="12" rx="4" fill="#1a0a45"/>
            </svg>`,
        },
    ];

    const LS_MIC_STYLE = 'tunes-karaoke-mic-style';
    function getMicStyle() {
        try {
            const v = localStorage.getItem(LS_MIC_STYLE);
            if (v && MIC_STYLES.some((m) => m.id === v)) return v;
        } catch (e) {}
        return MIC_STYLES[0].id;
    }
    function setMicStyle(id) {
        try { localStorage.setItem(LS_MIC_STYLE, id); } catch (e) {}
        renderMicLive();
    }
    function renderMicLive() {
        const id = getMicStyle();
        const m = MIC_STYLES.find((x) => x.id === id) || MIC_STYLES[0];
        const wrap = document.getElementById('mic-live-svg');
        if (wrap) wrap.innerHTML = m.svg;
    }
    function renderMicPicker() {
        const strip = document.getElementById('mic-picker-strip');
        if (!strip) return;
        const active = getMicStyle();
        strip.innerHTML = MIC_STYLES.map((m) => `
            <button type="button" class="mic-picker__option ${m.id === active ? 'is-active' : ''}" data-mic="${m.id}">
                <span class="mic-picker__swatch" data-style="${m.id}"></span>
                <span class="mic-picker__label">${m.label}</span>
            </button>
        `).join('');
        strip.querySelectorAll('[data-mic]').forEach((btn) => {
            btn.addEventListener('click', () => {
                setMicStyle(btn.getAttribute('data-mic'));
                renderMicPicker();
            });
        });
    }

    // Initial render of picker + the LIVE mic SVG
    renderMicPicker();
    renderMicLive();


    // Hook into the existing polling loop to dispatch updates
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
