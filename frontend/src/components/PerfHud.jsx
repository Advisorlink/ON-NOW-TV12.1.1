import React, { useEffect, useState } from 'react';
import { getAllPerf, getPerf, perfLabel, PERF_CHANGE } from '@/lib/perfLab';

/** Tiny always-on-top readout so the user can SEE which performance
 *  strategy is live on the box: FPS, dropped frames, DOM weight,
 *  mounted tiles / layers, and every lab setting.  Updates once per
 *  second — it must never become the thing it measures. */
export const PerfHud = () => {
    const [enabled, setEnabled] = useState(() => getPerf('hud') === 'on');
    const [stats, setStats] = useState(null);
    useEffect(() => {
        const sync = () => setEnabled(getPerf('hud') === 'on');
        window.addEventListener(PERF_CHANGE, sync);
        return () => window.removeEventListener(PERF_CHANGE, sync);
    }, []);
    useEffect(() => {
        if (!enabled) return undefined;
        let frames = 0; let slow = 0; let last = performance.now(); let raf = 0; let alive = true;
        const tick = (t) => {
            frames += 1; if (t - last > 34) slow += 1; last = t;
            if (alive) raf = requestAnimationFrame(tick);
        };
        raf = requestAnimationFrame(tick);
        const timer = setInterval(() => {
            const all = document.querySelectorAll('*').length;
            const tiles = document.querySelectorAll('[data-testid^="poster-"]').length;
            const recycled = document.querySelectorAll('[data-testid^="shelf-recycled-"]').length;
            const imgs = document.images.length;
            setStats({ fps: frames, slow, all, tiles, recycled, imgs, perf: getAllPerf() });
            frames = 0; slow = 0;
        }, 1000);
        return () => { alive = false; cancelAnimationFrame(raf); clearInterval(timer); };
    }, [enabled]);
    if (!enabled || !stats) return null;
    const fpsColor = stats.fps >= 50 ? '#7CF1A1' : stats.fps >= 30 ? '#FFD36B' : '#FF6B6B';
    return (
        <div
            data-testid="perf-hud"
            aria-hidden="true"
            className="vesper-mono fixed pointer-events-none"
            style={{
                top: 10, right: 12, zIndex: 99999, padding: '8px 10px', borderRadius: 8,
                background: 'rgba(4,6,12,0.88)', border: '1px solid rgba(124,241,241,0.35)',
                color: '#D8E6F2', fontSize: 11, lineHeight: 1.45, minWidth: 230,
            }}
        >
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                <span style={{ color: fpsColor, fontWeight: 800 }} data-testid="perf-hud-fps">{stats.fps} fps</span>
                <span style={{ color: stats.slow ? '#FF9B6B' : '#7CF1A1' }}>{stats.slow} slow frames</span>
            </div>
            <div style={{ color: '#9FB3C8' }}>
                DOM {stats.all} · tiles {stats.tiles}{stats.recycled ? ` (+${stats.recycled} recycled)` : ''} · imgs {stats.imgs}
            </div>
            <div style={{ marginTop: 5, borderTop: '1px solid rgba(255,255,255,0.08)', paddingTop: 5, color: '#7CF1F1' }}>
                {Object.entries(stats.perf).filter(([k]) => k !== 'hud').map(([k, v]) => (
                    <div key={k} data-testid={`perf-hud-${k}`}>
                        <span style={{ color: '#9FB3C8' }}>{k}</span> {perfLabel(k, v)}
                    </div>
                ))}
            </div>
        </div>
    );
};
