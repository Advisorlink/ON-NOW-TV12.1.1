/**
 * Parse a Torrentio/Stremio stream's title/name field to extract a
 * prominent quality badge (4K, 1080p, 720p, etc.) plus HDR/Dolby
 * Vision / Atmos flags.
 */

const QUALITY_PATTERNS = [
    { test: /\b(2160p?|4k|uhd|2160)\b/i, label: '4K', tone: 'gold' },
    { test: /\b1440p?\b/i, label: '1440p', tone: 'blue' },
    // User spec: anything that even mentions "1080" should count
    // as a 1080p product, whether or not the literal "p" is in
    // the title.  Covers cases like "BluRay 1080" or "1080.x264".
    { test: /\b1080p?\b|\b1080(?:[._-]|$)/i, label: '1080p', tone: 'blue' },
    { test: /\b720p?\b/i, label: '720p', tone: 'neutral' },
    { test: /\b480p?\b/i, label: '480p', tone: 'muted' },
    { test: /\b360p?\b/i, label: '360p', tone: 'muted' },
    { test: /\bhdcam\b/i, label: 'CAM', tone: 'red' },
    { test: /\b(ts|hdts|telesync)\b/i, label: 'TS', tone: 'red' },
];

/**
 * Loose "is this a 1080p stream?" check — used by the autoplay
 * picker.  Matches any token containing "1080" anywhere in the
 * stream's title/name/description, per user request.
 */
export function is1080p(stream) {
    const haystack = `${stream?.title || ''} ${stream?.name || ''} ${
        stream?.description || ''
    }`;
    return /1080/i.test(haystack);
}

/**
 * "Is this a 4K / 2160p stream?" — used by the autoplay picker
 * (party AND solo) to SKIP 4K streams unconditionally on the HK1
 * Android box, which can't actually decode 2160p HEVC in real time
 * → buffers + drops frames.  Per the user's repeated spec:
 *   "Only play 1080p, NEVER 4K — autoplay should never pick a 4K
 *    stream even when one is available."
 *
 * Detection signals (any single match → 4K):
 *   • Literal resolution tags: 4K, 2160p, 2160i, UHD, 4KBluRay,
 *     4KUHD, "Ultra HD" (Plex)
 *   • Common 4K-only release codecs/formats: HDR, HDR10, HDR10+,
 *     Dolby Vision / DV, IMAX Enhanced — virtually never appear on
 *     1080p torrents from the addons we use, so safe to treat as
 *     a 4K signal.  This is the v2.7.04 escalation: previously
 *     `is4K` only matched explicit "2160" or "4K" tokens, and the
 *     user reported solo-mode autoplay picking up streams titled
 *     e.g. "Web-DL HDR Atmos" (no 2160 in the name) that turned
 *     out to be 4K under the hood.  HDR/DV are the giveaway.
 *   • Bitrate hint: HEVC + ≥ 10 Mbps  → 4K territory.
 *   • Size hint: file size ≥ 20 GB for a single movie → almost
 *     certainly 4K (1080p movies typically peak at ~15 GB for the
 *     fattest BluRay encodes).
 */
export function is4K(stream) {
    const haystack = `${stream?.title || ''} ${stream?.name || ''} ${
        stream?.description || ''
    }`;
    if (/\b(2160p?i?|4k|uhd|4kbluray|4kuhd|ultra[\s_.\-]?hd)\b/i.test(haystack)) return true;
    /* v2.7.04 — HDR / Dolby Vision / IMAX Enhanced are 4K signals
       on every Stremio addon catalogue we serve (HDR-1080p is
       essentially non-existent in real-world torrents). */
    if (/\b(hdr10\+?|hdr|dolby[\s_.\-]?vision|\bdv\b|imax[\s_.\-]?enhanced)\b/i.test(haystack)) return true;
    /* High-bitrate HEVC pretty much guarantees 4K (Blu-ray 1080p
       HEVC tops out around 5-8 Mbps; 4K is 15-50 Mbps). */
    const bitrate = stream?.bitrate || stream?.bitrate_kbps || 0;
    const isHEVC = /\b(hevc|x265|h\.?265)\b/i.test(haystack);
    if (isHEVC && Number(bitrate) >= 10_000) return true;
    /* v2.7.04 — file-size sanity: pull a "12.4 GB" / "23 GB" /
       "9.8GB" hint out of the title and reject anything ≥ 20 GB.
       Torrentio puts the size in the description, e.g.
       "👤 423 💾 12.4 GB". */
    const sizeMatch = haystack.match(/(\d+(?:\.\d+)?)\s*GB\b/i);
    if (sizeMatch) {
        const gb = parseFloat(sizeMatch[1]);
        if (gb >= 20) return true;
    }
    return false;
}

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
