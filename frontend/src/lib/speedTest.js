/* Internet speed test — ping + download against Cloudflare's public speed-test
 * edge (speed.cloudflare.com), the same multi-stream method Speedtest uses. */
const CF = 'https://speed.cloudflare.com/__down';
const bust = () => `${Date.now()}-${Math.random().toString(36).slice(2)}`;

export async function measurePing({ rounds = 8, signal } = {}) {
    const samples = [];
    for (let i = 0; i < rounds; i += 1) {
        const t0 = performance.now();
        await fetch(`${CF}?bytes=0&r=${bust()}`, { cache: 'no-store', signal });
        samples.push(performance.now() - t0);
    }
    samples.sort((a, b) => a - b);
    return Math.round(samples[Math.floor(samples.length / 4)]);
}

export async function measureDownload({ durationMs = 8000, streams = 5, chunkBytes = 25_000_000, onProgress, signal } = {}) {
    const ctrl = new AbortController();
    signal?.addEventListener('abort', () => ctrl.abort(), { once: true });
    const start = performance.now();
    let total = 0;
    const samples = [];
    const timer = setTimeout(() => ctrl.abort(), durationMs);
    const ticker = setInterval(() => {
        const t = performance.now() - start;
        samples.push({ t, bytes: total });
        const win = samples.find((s) => s.t >= t - 1000) || samples[0];
        const mbps = win && t > win.t ? ((total - win.bytes) * 8) / ((t - win.t) / 1000) / 1e6 : 0;
        onProgress?.({ mbps, progress: Math.min(1, t / durationMs), bytes: total });
    }, 200);
    const worker = async () => {
        while (!ctrl.signal.aborted) {
            try {
                const res = await fetch(`${CF}?bytes=${chunkBytes}&r=${bust()}`, { cache: 'no-store', signal: ctrl.signal });
                if (!res.body) { total += (await res.arrayBuffer()).byteLength; continue; }
                const reader = res.body.getReader();
                for (;;) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    total += value.byteLength;
                }
            } catch (e) {
                if (ctrl.signal.aborted) return;
                ctrl.abort();
                throw e;
            }
        }
    };
    try {
        await Promise.all(Array.from({ length: streams }, worker));
    } finally {
        clearTimeout(timer);
        clearInterval(ticker);
    }
    const end = performance.now() - start;
    const warm = samples.find((s) => s.t >= 1000) || samples[0] || { t: 0, bytes: 0 };
    const mbps = end > warm.t ? ((total - warm.bytes) * 8) / ((end - warm.t) / 1000) / 1e6 : 0;
    return { mbps: Math.round(mbps * 10) / 10, bytes: total };
}

export const QUALITY_TIERS = [
    { label: '720p', min: 10 },
    { label: '1080p', min: 20 },
    { label: '4K', min: 100 },
];

export function speedVerdict(mbps) {
    if (mbps >= 100) return { label: 'Your internet is very healthy', tone: '#7CF1A1' };
    if (mbps >= 20) return { label: 'Your internet is healthy', tone: '#5DC8FF' };
    if (mbps >= 10) return { label: 'Your internet is a bit slow', tone: '#FFD36B' };
    return { label: 'Your internet is struggling', tone: '#FF8E8E' };
}

export function pingVerdict(ms) {
    if (ms <= 30) return 'Excellent';
    if (ms <= 70) return 'Good';
    if (ms <= 150) return 'Fair';
    return 'High';
}
