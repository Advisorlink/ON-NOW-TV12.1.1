/**
 * streamTags — client-side port of the backend's stream tagging
 * (`_filter_and_tag_english` + `_tag_addon_quality_premium` in
 * server.py).
 *
 * WHY THIS EXISTS: Torrentio (and other Cloudflare-walled addons)
 * can only be reached from the DEVICE's residential IP, so on real
 * boxes their streams arrive via the browser/bridge probe in
 * api.js — which never passed through the backend tagger.  Untagged
 * streams made the cascade rank BLIND: `_size_gb` missing (no 3 GB
 * cap), `_english_strict` missing (strict tiers never matched) and
 * `_pm_cached` missing — so autoplay routinely picked the LARGEST
 * 1080p file (Torrentio sorts big→small) or an UNCACHED debrid
 * "download" link that has to be cloud-downloaded before playback —
 * the root cause of 30s+ stream starts.
 */

const FOREIGN_FLAGS = [
    '\u{1F1F7}\u{1F1FA}', '\u{1F1EB}\u{1F1F7}', '\u{1F1EA}\u{1F1F8}',
    '\u{1F1EE}\u{1F1F9}', '\u{1F1E9}\u{1F1EA}', '\u{1F1F5}\u{1F1F9}',
    '\u{1F1E7}\u{1F1F7}', '\u{1F1F2}\u{1F1FD}', '\u{1F1F5}\u{1F1F1}',
    '\u{1F1EE}\u{1F1F3}', '\u{1F1EF}\u{1F1F5}', '\u{1F1F0}\u{1F1F7}',
    '\u{1F1E8}\u{1F1F3}', '\u{1F1F9}\u{1F1FC}', '\u{1F1F9}\u{1F1F7}',
    '\u{1F1F8}\u{1F1E6}', '\u{1F1EA}\u{1F1EC}', '\u{1F1F3}\u{1F1F1}',
    '\u{1F1E9}\u{1F1F0}', '\u{1F1F8}\u{1F1EA}', '\u{1F1F3}\u{1F1F4}',
    '\u{1F1EB}\u{1F1EE}', '\u{1F1E8}\u{1F1FF}', '\u{1F1ED}\u{1F1FA}',
    '\u{1F1EC}\u{1F1F7}', '\u{1F1F9}\u{1F1ED}', '\u{1F1FB}\u{1F1F3}',
    '\u{1F1EE}\u{1F1E9}', '\u{1F1EE}\u{1F1F1}', '\u{1F1FA}\u{1F1E6}',
    '\u{1F1F7}\u{1F1F4}', '\u{1F1ED}\u{1F1F0}',
];

const ENGLISH_FLAGS = [
    '\u{1F1EC}\u{1F1E7}', '\u{1F1FA}\u{1F1F8}', '\u{1F1E6}\u{1F1FA}',
    '\u{1F1E8}\u{1F1E6}', '\u{1F1F3}\u{1F1FF}', '\u{1F1EE}\u{1F1EA}',
];

const FOREIGN_LANG_RE = new RegExp(
    '\\b(' +
    'russian|francais|french|spanish|espanol|español|italian|italiano|' +
    'german|deutsch|portuguese|portugues|português|polish|polski|' +
    'hindi|tamil|telugu|malayalam|kannada|marathi|punjabi|bengali|' +
    'korean|japanese|nihongo|chinese|mandarin|cantonese|turkish|' +
    'arabic|farsi|persian|urdu|dutch|nederlands|danish|swedish|svenska|' +
    'norwegian|finnish|suomi|czech|cesky|hungarian|magyar|greek|' +
    'thai|vietnamese|indonesian|hebrew|ukrainian|romanian|romana|' +
    'bulgarian|serbian|croatian|slovak|slovenian|catalan|estonian|' +
    'latvian|lithuanian|filipino|tagalog|' +
    'rus|fra|fre|spa|esp|ita|ger|deu|jpn|jap|kor|chn|por|pol|hin|tam|' +
    'tel|ara|chi|nld|swe|nor|fin|cze|hun|gre|tha|vie|ind|heb|' +
    'ukr|rom|bul|srb|hrv|slk|slv|cat|est|lav|lit' +
    ')\\b',
    'i'
);

const ENGLISH_TOKEN_RE = /\b(english|eng)\b/i;

const NON_LATIN_RE = /[\u0400-\u04FF\u0500-\u052F\u0600-\u06FF\u0900-\u097F\u0590-\u05FF\u0370-\u03FF\u0E00-\u0E7F\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\uAC00-\uD7AF]/g;

const SIZE_RE = /(\d+(?:[.,]\d+)?)\s*(GB|MB|TB)\b/gi;

const ADDON_SOURCE_MAP = [
    ['plexio', 'PLEXIO'],
    ['ep-strem', 'PLEXIO'],
    ['torrentio', 'TORRENTIO'],
    ['watchhub', 'WATCHHUB'],
    ['opensub', 'OPENSUBS'],
    ['cinemeta', 'CINEMETA'],
    ['mediafusion', 'MEDIAFUSION'],
    ['aiostreams', 'AIO'],
    ['jackett', 'JACKETT'],
    ['orion', 'ORION'],
    ['easynews', 'EASYNEWS'],
    ['easy-news', 'EASYNEWS'],
    ['easy_news', 'EASYNEWS'],
];

const TORRENT_FAMILY = new Set(['TORRENTIO', 'MEDIAFUSION', 'AIO', 'JACKETT', 'ORION']);

function haystack(s) {
    const parts = [s?.title || '', s?.name || '', s?.description || ''];
    const bh = s?.behaviorHints;
    if (bh && typeof bh === 'object') parts.push(String(bh.filename || ''));
    return parts.join(' ');
}

function parseSizeGb(txt) {
    // Take the LAST match — Torrentio formats `👤 N 💾 SIZE`.
    let m = null;
    for (const hit of txt.matchAll(SIZE_RE)) m = hit;
    if (!m) return null;
    const n = parseFloat(m[1].replace(',', '.'));
    if (!isFinite(n)) return null;
    const u = m[2].toUpperCase();
    if (u === 'TB') return n * 1024;
    if (u === 'MB') return n / 1024;
    return n;
}

function detectAddonSource(s) {
    const blob = `${s?._addon_id || ''} ${s?._addon_name || ''} ${s?.name || ''}`.toLowerCase();
    for (const [needle, label] of ADDON_SOURCE_MAP) {
        if (blob.includes(needle)) return label;
    }
    const raw = s?._addon_name || 'STREAM';
    return String(raw).split(' ')[0].slice(0, 12).toUpperCase() || 'STREAM';
}

function detectQuality(txt) {
    const t = txt.toLowerCase();
    if (t.includes('2160p') || t.includes(' 4k') || t.includes('uhd')) return '4K';
    if (t.includes('1080p')) return '1080p';
    if (t.includes('720p')) return '720p';
    if (t.includes('480p') || t.includes(' sd ') || t.includes('cam') || t.includes('scr')) return 'SD';
    return '';
}

/* Debrid "download" links (e.g. Torrentio's "[PM download]") are NOT
 * cached on the provider — hitting them starts a cloud transfer that
 * takes 30s to minutes before a single byte of video flows.  The
 * addon banner lives in `name`, never in the release title, so this
 * check is precise. */
function detectDebridState(s, src) {
    if (!TORRENT_FAMILY.has(src)) return { cached: false, uncached: false };
    if (/download/i.test(String(s?.name || ''))) return { cached: false, uncached: true };
    if (s?.infoHash && !s?.url) return { cached: false, uncached: false };
    const url = String(s?.url || '').toLowerCase();
    return { cached: url.startsWith('http'), uncached: false };
}

/**
 * Tag (and language-filter) a raw addon stream list IN PLACE, exactly
 * mirroring the backend tagger.  Streams already carrying backend tags
 * pass through untouched except for the newer `_pm_uncached` field.
 * Foreign-language streams are DROPPED (backend parity).
 */
export function tagStreams(streams) {
    if (!Array.isArray(streams)) return [];
    const out = [];
    for (const s of streams) {
        if (!s || typeof s !== 'object') continue;
        const src = s._addon_source || detectAddonSource(s);
        if (s._is_english === undefined) {
            const txt = haystack(s);
            const nonLatin = (txt.match(NON_LATIN_RE) || []).length;
            const hasEngTok = ENGLISH_TOKEN_RE.test(txt);
            const hasEngFlag = ENGLISH_FLAGS.some((f) => txt.includes(f));
            const hasForFlag = FOREIGN_FLAGS.some((f) => txt.includes(f));
            const hasForTok = FOREIGN_LANG_RE.test(txt);
            const anyForeign = hasForFlag || hasForTok || nonLatin >= 2;
            if (nonLatin >= 2) {
                if (!hasEngTok) continue;
                s._is_english = true;
                s._english_strict = false;
            } else {
                const english = hasEngTok || hasEngFlag || !anyForeign;
                if (anyForeign && !english) continue;
                s._is_english = english;
                s._english_strict = !anyForeign;
            }
            s._size_gb = parseSizeGb(txt);
            s._addon_source = src;
            s._quality_label = detectQuality(txt);
        }
        if (s._pm_uncached === undefined) {
            const st = detectDebridState(s, src);
            s._pm_cached = s._pm_cached === undefined ? st.cached : (s._pm_cached && !st.uncached);
            s._pm_uncached = st.uncached;
        }
        out.push(s);
    }
    return out;
}
