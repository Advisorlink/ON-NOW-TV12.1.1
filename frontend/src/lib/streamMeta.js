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
 *
 * v2.10.77 — The haystack now ALSO includes the stream's
 * `description` field.  EasyNews++ (and other Usenet addons) put
 * the full release-name token-list there rather than in `title`,
 * which meant their 4K / 1080p / Atmos / HEVC / WEB-DL badges were
 * invisible in the picker before.
 */
export function qualityBadge(stream) {
    const haystack = `${stream?.title || ''} ${stream?.name || ''} ${
        stream?.description || ''
    }`;
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
    // v2.10.77 — Also scan `description` so EasyNews++ titles whose
    // codec/audio/HDR tokens live there (rather than `title`) light
    // up with the same chip set the user sees on Torrentio titles.
    const haystack = `${stream?.title || ''} ${stream?.name || ''} ${
        stream?.description || ''
    }`;
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
    // v2.10.77 — Also scan `description` so EasyNews++ file-size
    // tokens (which live in description rather than title) render
    // a chip in the picker.
    const haystack = `${stream?.title || ''} ${stream?.name || ''} ${
        stream?.description || ''
    }`;
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


/* ──────────────────────────────────────────────────────────────
 * v2.10.78 — NardBadges icon set
 *
 * The community-standard Stremio badge pack (github.com/vowl313/
 * NardBadges) gives every stream a uniform look across addons.
 * Each badge is a 48×24 PNG hosted on raw.githubusercontent.com.
 *
 * `nardResolutionIcon(stream)` returns the LARGE resolution chip
 * the picker renders on the left of each row.  `nardChips(stream)`
 * returns the full ordered list of secondary chips (release type,
 * HDR family, audio codec, video codec, language).
 * ──────────────────────────────────────────────────────────────*/

const NARD_BASE =
    'https://raw.githubusercontent.com/vowl313/NardBadges/main/';

function _streamText(s) {
    return `${s?.title || ''} ${s?.name || ''} ${s?.description || ''}`;
}

/** Resolution badge — 4K / FHD / HD or null. */
export function nardResolutionIcon(stream) {
    const t = _streamText(stream);
    if (/\b(2160p?|4k|uhd)\b/i.test(t) && !/\b(1080p?|720p?)\b/i.test(t)) {
        return { url: `${NARD_BASE}res-4k.png`, label: '4K' };
    }
    if (/\b1080p?\b|\bfhd\b|\bfull[ ._-]?hd\b/i.test(t)) {
        return { url: `${NARD_BASE}res-fhd.png`, label: 'FHD' };
    }
    if (/\b720p?\b|\bhd\b/i.test(t)) {
        return { url: `${NARD_BASE}res-hd.png`, label: 'HD' };
    }
    return null;
}

/* Each chip carries `{ url, label, group }` so the renderer can
 * group them visually (e.g. release type first, then HDR, then
 * audio, then video codec). */
function _push(out, seenGroups, group, url, label) {
    if (seenGroups.has(group)) return;
    seenGroups.add(group);
    out.push({ url, label, group });
}

/** Returns ordered list of NardBadges secondary chips. */
export function nardChips(stream) {
    const t = _streamText(stream);
    const out = [];
    const seen = new Set();

    // ── Release type (max 1) ──
    if (/\bremux\b/i.test(t)) _push(out, seen, 'rel', `${NARD_BASE}rel-remux.png`, 'REMUX');
    else if (/\bbluray\b|\bblu-?ray\b/i.test(t)) _push(out, seen, 'rel', `${NARD_BASE}rel-bluray.png`, 'BluRay');
    else if (/\bweb[-._ ]?dl\b/i.test(t)) _push(out, seen, 'rel', `${NARD_BASE}rel-webdl.png`, 'WEB-DL');
    else if (/\bweb[-._ ]?rip\b/i.test(t)) _push(out, seen, 'rel', `${NARD_BASE}rel-webrip.png`, 'WEBRip');

    // ── HDR family (max 1; combo icons take priority) ──
    const hasDV = /\b(dv|dovi|dolby[\s._-]?vision)\b/i.test(t);
    const hasHDR10p = /\bhdr[\s._-]?10[\s._-]?(\+|plus)\b/i.test(t);
    const hasHDR10 = /\bhdr[\s._-]?10\b/i.test(t) && !hasHDR10p;
    const hasHDR = /\bhdr\b|\bhlg\b/i.test(t) && !hasHDR10 && !hasHDR10p;
    const hasAtmos = /\batmos\b/i.test(t);
    const hasTrueHD = /\btruehd\b/i.test(t);
    const hasDDP = /\bddp\b|\bddp5\.?1\b|\be[-._]?ac3\b|\bdd\+/i.test(t);
    const hasDD = /\bdd5\.?1\b|\bac3\b/i.test(t);
    const hasIMAXE = /\bimax[\s._-]?enhanced\b/i.test(t);
    const hasIMAX = /\bimax\b/i.test(t) && !hasIMAXE;

    if (hasDV && hasAtmos) _push(out, seen, 'vis', `${NARD_BASE}vis-atmos-dv.png`, 'Atmos · DV');
    else if (hasDV && hasTrueHD) _push(out, seen, 'vis', `${NARD_BASE}vis-truehd-dv.png`, 'TrueHD · DV');
    else if (hasDV && hasDDP) _push(out, seen, 'vis', `${NARD_BASE}vis-ddp-dv.png`, 'DD+ · DV');
    else if (hasDV && hasDD) _push(out, seen, 'vis', `${NARD_BASE}vis-dd-dv.png`, 'DD · DV');
    else if (hasDV) _push(out, seen, 'vis', `${NARD_BASE}vis-dv.png`, 'DV');
    else if (hasHDR10p) _push(out, seen, 'vis', `${NARD_BASE}vis-hdr10plus.png`, 'HDR10+');
    else if (hasHDR10) _push(out, seen, 'vis', `${NARD_BASE}vis-hdr10.png`, 'HDR10');
    else if (hasHDR) _push(out, seen, 'vis', `${NARD_BASE}vis-hdr.png`, 'HDR');
    if (hasIMAXE) _push(out, seen, 'imax', `${NARD_BASE}vis-imax-enhanced.png`, 'IMAX Enhanced');
    else if (hasIMAX) _push(out, seen, 'imax', `${NARD_BASE}vis-imax.png`, 'IMAX');

    // ── Audio codec — pick the strongest (max 1).  HDR-combos
    // above already encode Atmos/TrueHD/DD/DDP with DV, so only
    // surface a standalone audio chip when no DV-combo was used.
    const visualUsedDV = out.some((c) => c.group === 'vis' && /DV/.test(c.label));
    if (!visualUsedDV) {
        if (hasAtmos) _push(out, seen, 'aud', `${NARD_BASE}aud-atmos.png`, 'Atmos');
        else if (hasTrueHD) _push(out, seen, 'aud', `${NARD_BASE}aud-truehd.png`, 'TrueHD');
        else if (/\bdts[\s._-]?hd[\s._-]?ma\b/i.test(t)) _push(out, seen, 'aud', `${NARD_BASE}aud-dtshdma.png`, 'DTS-HD MA');
        else if (/\bdts[\s._-]?hd\b/i.test(t)) _push(out, seen, 'aud', `${NARD_BASE}aud-dtshd.png`, 'DTS-HD');
        else if (/\bdts[\s._-]?x\b/i.test(t)) _push(out, seen, 'aud', `${NARD_BASE}aud-dtsx.png`, 'DTS:X');
        else if (/\bdts\b/i.test(t)) _push(out, seen, 'aud', `${NARD_BASE}aud-dts.png`, 'DTS');
        else if (hasDDP) _push(out, seen, 'aud', `${NARD_BASE}aud-ddplus.png`, 'DD+');
        else if (hasDD) _push(out, seen, 'aud', `${NARD_BASE}aud-dd.png`, 'DD');
        else if (/\bflac\b/i.test(t)) _push(out, seen, 'aud', `${NARD_BASE}flac.png`, 'FLAC');
        else if (/\baac\b/i.test(t)) _push(out, seen, 'aud', `${NARD_BASE}aac.png`, 'AAC');
        else if (/\bmp3\b/i.test(t)) _push(out, seen, 'aud', `${NARD_BASE}mp3.png`, 'MP3');
    }

    // ── Channels (max 1) ──
    if (/\b7\.?1\b/i.test(t)) _push(out, seen, 'ch', `${NARD_BASE}ch71.png`, '7.1');
    else if (/\b6\.?1\b/i.test(t)) _push(out, seen, 'ch', `${NARD_BASE}ch61.png`, '6.1');
    else if (/\b5\.?1\b/i.test(t)) _push(out, seen, 'ch', `${NARD_BASE}ch51.png`, '5.1');
    else if (/\b2\.?0\b/i.test(t)) _push(out, seen, 'ch', `${NARD_BASE}ch20.png`, '2.0');

    // ── Video codec (max 1) ──
    if (/\b(hevc|h\.?265|x265)\b/i.test(t)) _push(out, seen, 'vcodec', `${NARD_BASE}codec-hevc.png`, 'HEVC');
    else if (/\bav1\b/i.test(t)) _push(out, seen, 'vcodec', `${NARD_BASE}codec-av1.png`, 'AV1');
    else if (/\b(h\.?264|x264|avc)\b/i.test(t)) _push(out, seen, 'vcodec', `${NARD_BASE}codec-avc.png`, 'AVC');

    // ── 3D ──
    if (/\b3d\b/i.test(t)) _push(out, seen, '3d', `${NARD_BASE}3d.png`, '3D');

    return out;
}

/**
 * Format the "meta line" under the title (NardBadges-style):
 *   🔌 ADDON · ⚡ Cached · 💾 Size · 🌱 Seeders
 * Returns an array of `{ icon, text }` so the renderer can space
 * them with separators.
 */
export function nardMetaLine(stream) {
    const out = [];
    if (stream?._addon_source) {
        out.push({ icon: '🔌', text: String(stream._addon_source) });
    }
    if (stream?._pm_cached) {
        out.push({ icon: '⚡', text: 'Cached' });
    }
    const size = sizeLabel(stream);
    if (size) out.push({ icon: '💾', text: size });
    const seeders = stream?._seeders || stream?.seeders;
    if (typeof seeders === 'number' && seeders > 0) {
        out.push({ icon: '🌱', text: String(seeders) });
    }
    return out;
}
