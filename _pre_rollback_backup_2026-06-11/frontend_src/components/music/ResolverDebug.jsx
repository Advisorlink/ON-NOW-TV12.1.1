// ResolverDebug.jsx — Small on-screen panel that exposes the
// state of the music resolver chain so we can debug "why am I
// still getting 30-second previews?" without needing logcat.
//
// Activate by appending `?debug=1` to any /music URL, OR by
// clicking the title bar (or pressing 'd') 5 times in a row.
// Once activated, lives in the bottom-right corner of every
// music page and stays mounted across navigations.
//
// Shows:
//   • Native bridge present?   (yes/no, with build name)
//   • Last resolver attempt    (artist / title / source / latency)
//   • [Test bridge] button     (runs resolveYouTubeAudio("Adele","Hello")
//                               and pretty-prints the result)
//   • [Test backend] button    (calls /api/music/stream/... directly)
import React, { useEffect, useState } from 'react';
import { hasNativeBridge, resolveViaNative, resolveViaBackend } from '../../lib/musicResolver';

export function ResolverDebug() {
    const [open, setOpen] = useState(() => {
        if (typeof window === 'undefined') return false;
        try {
            const p = new URLSearchParams(window.location.search);
            if (p.get('debug') === '1') return true;
            // v2.8.50 — Tunes APK boots with `?box=1`; auto-show the
            // resolver overlay so the user can SEE on screen whether
            // the native bridge is healthy on their HK1.
            if (p.get('box') === '1') return true;
        } catch { /* ignore */ }
        try {
            return localStorage.getItem('onnowtv-resolver-debug') === '1';
        } catch { return false; }
    });
    const [native, setNative] = useState(() => hasNativeBridge());
    const [buildName, setBuildName] = useState('?');
    const [history, setHistory] = useState([]);
    const [busy, setBusy] = useState(false);

    // Probe for the bridge once after mount + every 2 s for the first
    // 10 s (WebView injection can race with the React render).
    useEffect(() => {
        let n = 0;
        const probe = () => {
            const has = hasNativeBridge();
            setNative(has);
            if (has) {
                try {
                    setBuildName(window.OnNowTV.buildName?.() || 'native');
                } catch { setBuildName('native'); }
            }
            n += 1;
            if (n < 6) setTimeout(probe, 2000);
        };
        probe();
    }, []);

    // Subscribe to resolver-debug events fired by lib/musicResolver.
    useEffect(() => {
        const onEvent = (e) => {
            const d = e.detail || {};
            setHistory((h) => [{
                ts: Date.now(),
                artist: d.artist || '?',
                title: d.title || '?',
                source: d.source || '?',
                ms: d.ms != null ? d.ms : '?',
                ok: !!d.ok,
                error: d.error || '',
            }, ...h].slice(0, 6));
        };
        window.addEventListener('onnowtv-resolver-event', onEvent);
        return () => window.removeEventListener('onnowtv-resolver-event', onEvent);
    }, []);

    // Hotkey: pressing 'D' 5 times within 3 s toggles the panel.
    useEffect(() => {
        let presses = 0;
        let last = 0;
        const onKey = (e) => {
            if (e.key.toLowerCase() !== 'd') return;
            const now = Date.now();
            if (now - last > 3000) presses = 0;
            last = now;
            presses += 1;
            if (presses >= 5) {
                presses = 0;
                setOpen((o) => {
                    const v = !o;
                    try { localStorage.setItem('onnowtv-resolver-debug', v ? '1' : '0'); } catch { /* ignore */ }
                    return v;
                });
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, []);

    if (!open) return null;

    const runNative = async () => {
        if (busy) return;
        setBusy(true);
        const t0 = performance.now();
        const r = await resolveViaNative('Adele', 'Hello');
        const ms = Math.round(performance.now() - t0);
        setHistory((h) => [{
            ts: Date.now(),
            artist: 'Adele', title: 'Hello',
            source: 'TEST · native',
            ms, ok: !!(r && r.ok),
            error: (r && !r.ok ? r.error : '') || (!r ? 'bridge not present' : ''),
        }, ...h].slice(0, 6));
        setBusy(false);
    };
    const runBackend = async () => {
        if (busy) return;
        setBusy(true);
        const t0 = performance.now();
        const r = await resolveViaBackend('test-1', 'Adele', 'Hello');
        const ms = Math.round(performance.now() - t0);
        setHistory((h) => [{
            ts: Date.now(),
            artist: 'Adele', title: 'Hello',
            source: 'TEST · backend → ' + (r?.source || '?'),
            ms, ok: !!(r && r.stream_url && r.is_full_track),
            error: (r && !r.stream_url) ? 'no stream_url' : '',
        }, ...h].slice(0, 6));
        setBusy(false);
    };

    const css = {
        wrap: {
            position: 'fixed', right: 18, bottom: 18, zIndex: 99999,
            width: 360, maxWidth: '94vw',
            background: 'rgba(11,15,28,0.96)',
            border: '1px solid rgba(93,200,255,0.35)',
            borderRadius: 14,
            padding: 14,
            color: '#E6EAF2',
            fontFamily: 'ui-monospace,SF Mono,Menlo,monospace',
            fontSize: 12,
            backdropFilter: 'blur(20px)',
            boxShadow: '0 12px 40px rgba(0,0,0,0.5)',
        },
        title: { fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', fontSize: 11, color: '#5DC8FF', marginBottom: 6 },
        row: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '6px 0' },
        pill: (ok) => ({
            padding: '2px 8px', borderRadius: 99,
            background: ok ? 'rgba(91,227,154,0.18)' : 'rgba(255,107,107,0.18)',
            color: ok ? '#5BE39A' : '#FF6B6B',
            fontSize: 10, fontWeight: 700, letterSpacing: '0.04em',
        }),
        btn: {
            padding: '6px 12px', borderRadius: 8, border: 'none',
            background: 'rgba(93,200,255,0.22)', color: '#5DC8FF',
            fontSize: 11, fontWeight: 600, cursor: 'pointer',
            marginRight: 6,
        },
        item: {
            padding: 8, margin: '4px 0', borderRadius: 8,
            background: 'rgba(255,255,255,0.04)', fontSize: 11,
            lineHeight: 1.4,
        },
        close: { position: 'absolute', top: 6, right: 8, background: 'none', border: 'none', color: '#7C8497', cursor: 'pointer', fontSize: 16 },
    };

    return (
        <div style={css.wrap} data-testid="resolver-debug-panel">
            <button data-testid="resolver-debug-close" style={css.close} onClick={() => {
                setOpen(false);
                try { localStorage.setItem('onnowtv-resolver-debug', '0'); } catch { /* ignore */ }
            }}>×</button>
            <div style={css.title}>♪ Resolver debug</div>

            <div style={css.row}>
                <span>Native bridge:</span>
                <span style={css.pill(native)}>{native ? `YES · ${buildName}` : 'NO'}</span>
            </div>

            <div style={{ ...css.row, marginTop: 10 }}>
                <button data-testid="resolver-debug-test-native" style={css.btn} disabled={busy || !native} onClick={runNative}>
                    Test native
                </button>
                <button data-testid="resolver-debug-test-backend" style={css.btn} disabled={busy} onClick={runBackend}>
                    Test backend
                </button>
            </div>

            <div style={css.title} >Recent resolves</div>
            {history.length === 0 ? (
                <div style={{ color: '#7C8497', fontSize: 11, padding: '6px 0' }}>none yet — play a track</div>
            ) : history.map((h, i) => (
                <div key={i} style={css.item}>
                    <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                        <span><b>{h.artist}</b> — {h.title}</span>
                        <span style={css.pill(h.ok)}>{h.source}</span>
                    </div>
                    <div style={{ color: '#7C8497', marginTop: 2 }}>
                        {h.ms} ms{h.error ? ` · ${h.error}` : ''}
                    </div>
                </div>
            ))}
        </div>
    );
}
