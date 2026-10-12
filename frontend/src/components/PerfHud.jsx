import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { getAllPerf, getPerf, perfLabel, PERF_CHANGE } from '@/lib/perfLab';
import { getSnapshot, gradeFor, startMetrics, stopMetrics, subscribe } from '@/lib/perfMetrics';
import { BENCH_PROGRESS, BENCH_PRESSES, consumeBenchmarkRequest, runBenchmark } from '@/lib/perfBench';

const PANEL = {
    position: 'fixed', top: 10, right: 12, zIndex: 99999, padding: '8px 10px', borderRadius: 8,
    background: 'rgba(4,6,12,0.88)', border: '1px solid rgba(124,241,241,0.35)',
    color: '#D8E6F2', fontSize: 11, lineHeight: 1.45, minWidth: 250,
};

/** Always-on-top readout of what actually makes the box feel chunky:
 *  key-press → paint latency, long tasks and slow frames, plus the
 *  active lab settings.  Updates on each press / once a second. */
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
        startMetrics();
        const refresh = () => setStats({
            ...getSnapshot(),
            dom: document.querySelectorAll('*').length,
            tiles: document.querySelectorAll('[data-testid^="poster-"]').length,
            recycled: document.querySelectorAll('[data-testid^="shelf-recycled-"]').length,
            perf: getAllPerf(),
        });
        refresh();
        const unsub = subscribe(refresh);
        return () => { unsub(); stopMetrics(); };
    }, [enabled]);
    if (!enabled || !stats) return null;
    const grade = gradeFor(stats.medianMs);
    return (
        <div data-testid="perf-hud" aria-hidden="true" className="vesper-mono pointer-events-none" style={PANEL}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                <span style={{ color: grade.color, fontWeight: 800 }} data-testid="perf-hud-latency">
                    {stats.presses ? `${stats.medianMs} ms / press` : 'press a D-pad key…'}
                </span>
                <span style={{ color: grade.color }} data-testid="perf-hud-grade">{grade.label}</span>
            </div>
            <div style={{ color: '#9FB3C8' }} data-testid="perf-hud-presses">
                worst {stats.worstMs} ms · p90 {stats.p90Ms} ms · {stats.presses} presses
            </div>
            <div style={{ color: stats.longTasks ? '#FF9B6B' : '#9FB3C8' }} data-testid="perf-hud-longtasks">
                long tasks {stats.longTasks} ({stats.longTaskMs} ms) · slow frames {stats.slowFrames}
            </div>
            <div style={{ color: '#9FB3C8' }} data-testid="perf-hud-fps">
                {stats.fps} fps · DOM {stats.dom} · tiles {stats.tiles}{stats.recycled ? ` (+${stats.recycled} recycled)` : ''}
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

/** Runs the automated D-pad benchmark once Home has mounted after
 *  "Run benchmark" was pressed in Settings, then returns to the lab. */
export const PerfBenchRunner = () => {
    const location = useLocation();
    const navigate = useNavigate();
    const [progress, setProgress] = useState(null);
    useEffect(() => {
        if (location.pathname !== '/' || !consumeBenchmarkRequest()) return undefined;
        let alive = true;
        const onProgress = (e) => { if (alive) setProgress(e.detail); };
        window.addEventListener(BENCH_PROGRESS, onProgress);
        setProgress({ done: 0, total: BENCH_PRESSES, warming: true });
        const start = async () => {
            // Wait for the catalogue rows to be on screen, then let images settle.
            for (let i = 0; i < 40 && alive; i += 1) {
                if (document.querySelectorAll('[data-testid^="shelf-row-"]').length >= 3) break;
                await new Promise((r) => setTimeout(r, 300));
            }
            await new Promise((r) => setTimeout(r, 2500));
            if (!alive) return;
            try { await runBenchmark(); } catch { /* rails never mounted */ }
            if (!alive) return;
            setProgress(null);
            try { sessionStorage.setItem('vesper-settings-jump-to', 'performance'); } catch { /* ignore */ }
            navigate('/settings');
        };
        start();
        return () => { alive = false; window.removeEventListener(BENCH_PROGRESS, onProgress); };
    }, [location.pathname, navigate]);
    if (!progress) return null;
    return (
        <div data-testid="perf-bench-overlay" className="vesper-mono pointer-events-none" style={{ ...PANEL, top: 'auto', bottom: 16, right: 16, minWidth: 260 }}>
            <div style={{ color: '#7CF1F1', fontWeight: 800 }}>Benchmark running — hands off the remote</div>
            <div style={{ color: '#9FB3C8' }} data-testid="perf-bench-progress">
                {progress.warming && !progress.done ? 'warming up…' : `${progress.done} / ${progress.total} presses`}
            </div>
        </div>
    );
};
