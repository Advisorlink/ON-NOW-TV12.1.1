/**
 * Parse a Torrentio/Stremio stream's title/name field to extract a
 * prominent quality badge (4K, 1080p, 720p, etc.) plus HDR/Dolby
 * Vision / Atmos flags.
 */

const QUALITY_PATTERNS = [
    { test: /\b(2160p|4k|uhd)\b/i, label: '4K', tone: 'gold' },
    { test: /\b1440p\b/i, label: '1440p', tone: 'blue' },
    { test: /\b1080p\b/i, label: '1080p', tone: 'blue' },
    { test: /\b720p\b/i, label: '720p', tone: 'neutral' },
    { test: /\b480p\b/i, label: '480p', tone: 'muted' },
    { test: /\b360p\b/i, label: '360p', tone: 'muted' },
    { test: /\bhdcam\b/i, label: 'CAM', tone: 'red' },
    { test: /\b(ts|hdts|telesync)\b/i, label: 'TS', tone: 'red' },
];

/**
 * Returns the first matching quality badge for a stream, or null.
 * `tone` is a UI hint that the renderer can map to a color scheme.
 */
export function qualityBadge(stream) {
    const haystack = `${stream?.title || ''} ${stream?.name || ''}`;
    for (const p of QUALITY_PATTERNS) {
        if (p.test.test(haystack)) return { label: p.label, tone: p.tone };
    }
    return null;
}

/**
 * Extra tags (HDR / DV / ATMOS / REMUX / WEB-DL) — small inline
 * pills the renderer can append next to the main quality badge.
 */
const TAG_PATTERNS = [
    { test: /\bdolby[\s.-]?vision\b|\bdv\b/i, label: 'DV', tone: 'gold' },
    { test: /\bhdr10\+\b|\bhdr10plus\b/i, label: 'HDR10+', tone: 'gold' },
    { test: /\bhdr\b/i, label: 'HDR', tone: 'gold' },
    { test: /\batmos\b/i, label: 'Atmos', tone: 'violet' },
    { test: /\bremux\b/i, label: 'REMUX', tone: 'cyan' },
    { test: /\bbluray\b|\bblu-ray\b/i, label: 'BluRay', tone: 'cyan' },
    { test: /\bweb-?dl\b/i, label: 'WEB-DL', tone: 'neutral' },
    { test: /\bwebrip\b/i, label: 'WEB-Rip', tone: 'neutral' },
];

export function qualityTags(stream) {
    const haystack = `${stream?.title || ''} ${stream?.name || ''}`;
    const out = [];
    for (const t of TAG_PATTERNS) {
        if (t.test.test(haystack)) out.push({ label: t.label, tone: t.tone });
    }
    return out;
}

export const toneColors = {
    gold: { fg: '#FFD24A', bg: 'rgba(255,210,74,0.16)', border: 'rgba(255,210,74,0.35)' },
    blue: { fg: '#5DC8FF', bg: 'rgba(93,200,255,0.16)', border: 'rgba(93,200,255,0.35)' },
    cyan: { fg: '#7CF1F1', bg: 'rgba(124,241,241,0.14)', border: 'rgba(124,241,241,0.3)' },
    violet: { fg: '#C3A2FF', bg: 'rgba(195,162,255,0.16)', border: 'rgba(195,162,255,0.35)' },
    neutral: { fg: '#D6DCE7', bg: 'rgba(214,220,231,0.10)', border: 'rgba(214,220,231,0.22)' },
    muted: { fg: '#7E8A9C', bg: 'rgba(126,138,156,0.10)', border: 'rgba(126,138,156,0.18)' },
    red: { fg: '#FF6B6B', bg: 'rgba(255,107,107,0.14)', border: 'rgba(255,107,107,0.32)' },
};
