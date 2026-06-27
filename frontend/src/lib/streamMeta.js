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
 * → buffers + drops frames.
 *
 * v2.7.07 — toned down from v2.7.04's over-aggressive heuristic.
 * The user reported normal autoplay buffering because HDR-tagged
 * 1080p streams (which DO exist — every Plex 1080p HDR Blu-ray
 * remux qualifies) were being mis-classified as 4K, so autoplay
 * fell back to worse streams.  Revised contract:
 *
 *   • EXPLICIT 1080p token in the title → ALWAYS treat as 1080p,
 *     even if HDR/DV/HEVC also present.  This is the key fix.
 *   • Explicit 4K markers (4K, 2160p, UHD, "Ultra HD") → 4K.
 *   • HDR / Dolby Vision WITHOUT a 1080p marker → 4K (a real
 *     "Movie · WEB-DL HDR" without resolution is almost always
 *     a 4K release on Stremio addons).
 *   • File size ≥ 25 GB → 4K (1080p remuxes top out around
 *     20 GB; only 4K hits this).
 *   • High-bitrate HEVC (≥ 10 Mbps) with no 1080p marker → 4K.
 */
export function is4K(stream) {
    const haystack = `${stream?.title || ''} ${stream?.name || ''} ${
        stream?.description || ''
    }`;
    /* Hard explicit 4K markers — always 4K, regardless of other
       tokens.  Note we do NOT include "Ultra HD" → it sometimes
       appears on 1080p Plex titles as a quality descriptor. */
    if (/\b(2160p?i?|4kbluray|4kuhd|4kweb|4kdvd)\b/i.test(haystack)) return true;
    if (/\b4k\b/i.test(haystack)) return true;
    if (/\buhd\b/i.test(haystack) && !/\b1080p?\b/i.test(haystack)) return true;
    /* Explicit 1080p marker → always 1080p, even with HDR/DV. */
    const has1080 = /\b1080p?\b/i.test(haystack);
    if (has1080) return false;
    /* No 1080 marker — now check the secondary signals. */
    if (/\b(hdr10\+?|dolby[\s_.\-]?vision|\bdv\b|imax[\s_.\-]?enhanced)\b/i.test(haystack)) return true;
    /* Stand-alone "HDR" (not HDR10/HDR10+) is a weaker signal —
       still trip if present without 1080p. */
    if (/\bhdr\b/i.test(haystack)) return true;
    /* High-bitrate HEVC → 4K. */
    const bitrate = stream?.bitrate || stream?.bitrate_kbps || 0;
    const isHEVC = /\b(hevc|x265|h\.?265)\b/i.test(haystack);
    if (isHEVC && Number(bitrate) >= 10_000) return true;
    /* Size hint: 25 GB+ is 4K territory (bumped from 20 GB in
       v2.7.04 since 1080p remuxes can legitimately hit 22 GB). */
    const sizeMatch = haystack.match(/(\d+(?:\.\d+)?)\s*GB\b/i);
    if (sizeMatch) {
        const gb = parseFloat(sizeMatch[1]);
        if (gb >= 25) return true;
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
 * Extra tags (HDR / DV / ATMOS / REMUX / WEB-DL / audio codecs /
 * video codecs) — small inline pills the renderer appends next to
 * the main quality badge.
 *
 * v2.10.74 — Expanded for Easynews++ releases, which embed rich
 * codec / channel info in their titles (e.g.,
 *   "Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.HEVC-FGT.mkv").
 * The picker now surfaces:
 *   • HDR family — HDR10+, Dolby Vision, plain HDR
 *   • Audio    — Atmos, TrueHD, DTS-HD/MA/X, DTS, DD+ (E-AC3), AC3,
 *                AAC, FLAC, MP3, plus 5.1 / 7.1 channel pills
 *   • Video    — HEVC (h.265), AV1, x264 (h.264)
 *   • Source   — REMUX, BluRay, WEB-DL, WEBRip, HDTV
 * Ordering of TAG_PATTERNS matters — the renderer dedups by
 * label, so put the most-specific pattern first (e.g. DTS-HD MA
 * before plain DTS, HDR10+ before HDR10 before HDR).
 */
const TAG_PATTERNS = [
    /* ── HDR family ── */
    { test: /\bdolby[\s.\-_]?vision\b|\bdv\b/i, label: 'DV', tone: 'gold' },
    { test: /\bhdr10\+|\bhdr10plus\b/i,         label: 'HDR10+', tone: 'gold' },
    { test: /\bhdr10\b/i,                       label: 'HDR10', tone: 'gold' },
    { test: /\bhdr\b/i,                         label: 'HDR', tone: 'gold' },
    /* ── Audio codecs (most specific first) ── */
    { test: /\batmos\b/i,                       label: 'Atmos', tone: 'violet' },
    { test: /\btruehd\b/i,                      label: 'TrueHD', tone: 'violet' },
    { test: /\bdts[\s.\-_]?hd[\s.\-_]?ma\b/i,   label: 'DTS-HD MA', tone: 'violet' },
    { test: /\bdts[\s.\-_]?hd\b/i,              label: 'DTS-HD', tone: 'violet' },
    { test: /\bdts[\s.\-_]?x\b/i,               label: 'DTS:X', tone: 'violet' },
    { test: /\bdts\b/i,                         label: 'DTS', tone: 'violet' },
    { test: /\bddp\b|\bddp5\.?1\b|\be[-_.]?ac3\b|\bdd\+/i, label: 'DD+', tone: 'violet' },
    { test: /\bdd5\.?1\b|\bac3\b/i,             label: 'DD5.1', tone: 'violet' },
    { test: /\bflac\b/i,                        label: 'FLAC', tone: 'violet' },
    { test: /\baac\b/i,                         label: 'AAC', tone: 'violet' },
    { test: /\bmp3\b/i,                         label: 'MP3', tone: 'muted' },
    /* ── Channels (only when not already captured by a codec) ── */
    { test: /\b7\.?1\b/i,                       label: '7.1', tone: 'muted' },
    { test: /\b5\.?1\b/i,                       label: '5.1', tone: 'muted' },
    /* ── Video codecs ── */
    { test: /\b(hevc|h\.?265|x265)\b/i,         label: 'HEVC', tone: 'cyan' },
    { test: /\bav1\b/i,                         label: 'AV1', tone: 'cyan' },
    { test: /\b(h\.?264|x264|avc)\b/i,          label: 'H.264', tone: 'cyan' },
    /* ── Source / release type ── */
    { test: /\bremux\b/i,                       label: 'REMUX', tone: 'cyan' },
    { test: /\bbluray\b|\bblu-ray\b/i,          label: 'BluRay', tone: 'cyan' },
    { test: /\bweb-?dl\b/i,                     label: 'WEB-DL', tone: 'neutral' },
    { test: /\bwebrip\b/i,                      label: 'WEB-Rip', tone: 'neutral' },
    { test: /\bhdtv\b/i,                        label: 'HDTV', tone: 'neutral' },
    { test: /\bdvdrip\b/i,                      label: 'DVDRip', tone: 'muted' },
];

export function qualityTags(stream) {
    const haystack = `${stream?.title || ''} ${stream?.name || ''}`;
    const out = [];
    const seen = new Set();
    for (const t of TAG_PATTERNS) {
        if (seen.has(t.label)) continue;
        if (t.test.test(haystack)) {
            seen.add(t.label);
            out.push({ label: t.label, tone: t.tone });
        }
    }
    return out;
}

/**
 * v2.10.74 — Best-effort file-size extraction from a stream title.
 * Returns a normalised string like "12.4 GB" / "850 MB" / null.
 * Used by StreamPickerModal so the user can see how chunky each
 * link is before committing.
 */
export function sizeLabel(stream) {
    const haystack = `${stream?.title || ''} ${stream?.name || ''}`;
    // Match e.g. "💾 12.4 GB", "[850 MB]", "Movie.2024.4.7GB.mkv".
    const m = haystack.match(/(\d+(?:[.,]\d+)?)\s*(GB|MB)\b/i);
    if (!m) return null;
    const num = parseFloat(m[1].replace(',', '.'));
    if (!isFinite(num)) return null;
    return `${num} ${m[2].toUpperCase()}`;
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
