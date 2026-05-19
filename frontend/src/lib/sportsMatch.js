/**
 * sportsMatch — match a TheSportsDB fixture (team A vs team B) to a
 * user's IPTV channel + EPG entry.
 *
 *   Algorithm (v2):
 *     • For every EPG entry on every sports-tagged channel within
 *       ±3 h of the fixture kickoff, score by:
 *         (a) WHOLE team-name substring hit in `${title} ${desc}`
 *             (e.g. EPG "Liverpool v Man Utd" hits both teams)
 *         (b) Team-name TOKEN matches (Liverpool, Manchester, etc.)
 *         (c) Team aliases — Man Utd ↔ Manchester United,
 *             Spurs ↔ Tottenham Hotspur, etc.
 *         (d) Sport / league keyword bonus.
 *     • Substring + token unions are weighted heavily; sport/league
 *       hits are tie-breakers.
 *     • Stopwords are NOT dropped from substring scoring — that
 *       was the v1 bug ("United" gone meant Man Utd never matched).
 *
 *   Returns:
 *     [
 *       {
 *         streamId, channelName, streamIcon,
 *         epgTitle, startTs, stopTs, score,
 *       },
 *       …
 *     ]
 *   sorted by score desc, time-distance asc.
 */

import { loadCategories, loadChannels, loadEpg } from './liveCache.js';

// Common stopwords that are too generic to score on (token path
// only — substring path uses full names).
const STOP = new Set([
    'fc', 'afc', 'cf', 'sc', 'ac', 'cd', 'club', 'team',
    'united', 'city', 'town', 'rovers', 'wanderers', 'athletic',
    'real', 'inter', 'sporting',
    'vs', 'v', 'at', 'and', 'the', 'a', 'an', 'of',
    'fixture', 'match', 'game', 'live', 'highlights',
    'tonight', 'today',
]);

// Common team aliases — EPG providers often use the short form
// (Man Utd, Spurs) while TheSportsDB returns the full name
// (Manchester United, Tottenham Hotspur).  We expand both ways so
// either side hits.  Keep entries lowercase, single-word or
// hyphen-free, listing each canonical form + every realistic
// abbreviation/nickname the user might see in an EPG title.
const TEAM_ALIASES = {
    'manchester united': ['man utd', 'man united', 'manutd', 'mufc'],
    'manchester city': ['man city', 'mcfc', 'mancity'],
    'tottenham hotspur': ['tottenham', 'spurs'],
    'tottenham': ['spurs'],
    'wolverhampton wanderers': ['wolves', 'wolverhampton'],
    'newcastle united': ['newcastle', 'nufc'],
    'brighton and hove albion': ['brighton', 'bha'],
    'brighton & hove albion': ['brighton', 'bha'],
    'west ham united': ['west ham', 'whufc', 'hammers'],
    'leicester city': ['leicester', 'foxes'],
    'leeds united': ['leeds', 'lufc'],
    'nottingham forest': ['forest', 'nffc'],
    'crystal palace': ['palace', 'cpfc'],
    'sheffield united': ['sheffield utd', 'sheffield'],
    'aston villa': ['villa', 'avfc'],
    'bayern munich': ['bayern', 'fc bayern'],
    'borussia dortmund': ['dortmund', 'bvb'],
    'paris saint-germain': ['psg', 'paris sg', 'paris'],
    'real madrid': ['real', 'rmcf'],
    'atletico madrid': ['atleti', 'atletico'],
    'atlético madrid': ['atleti', 'atletico'],
    'barcelona': ['barca', 'fc barcelona', 'fcb'],
    'juventus': ['juve'],
    'ac milan': ['milan', 'rossoneri'],
    'inter milan': ['inter', 'internazionale'],
    // Rugby League NRL
    'penrith panthers': ['penrith', 'panthers'],
    'parramatta eels': ['parramatta', 'eels'],
    'south sydney rabbitohs': ['rabbitohs', 'souths', 'south sydney'],
    'sydney roosters': ['roosters'],
    'brisbane broncos': ['broncos'],
    'melbourne storm': ['storm'],
    'north queensland cowboys': ['cowboys', 'north queensland'],
    'canberra raiders': ['raiders'],
    'cronulla sharks': ['cronulla', 'sharks'],
    'wests tigers': ['tigers', 'wests'],
    'new zealand warriors': ['warriors'],
    // AFL
    'collingwood magpies': ['collingwood', 'magpies'],
    'richmond tigers': ['richmond'],
    'west coast eagles': ['west coast', 'eagles'],
    'sydney swans': ['sydney', 'swans'],
    'geelong cats': ['geelong', 'cats'],
};

/* Expand: turn the canonical→[aliases] map into a flat lookup so
 * we can find every alias for any team name in O(1). */
const ALIAS_LOOKUP = (() => {
    const m = new Map();
    for (const [canon, aliases] of Object.entries(TEAM_ALIASES)) {
        const all = [canon, ...aliases];
        for (const name of all) m.set(name, all);
    }
    return m;
})();

function aliasesFor(teamName) {
    if (!teamName) return [];
    const key = String(teamName).toLowerCase().trim();
    return ALIAS_LOOKUP.get(key) || [key];
}

function tokens(text) {
    if (!text) return [];
    return String(text)
        .toLowerCase()
        .replace(/[^a-z0-9\s]/g, ' ')
        .split(/\s+/)
        .filter((w) => w.length >= 3 && !STOP.has(w));
}

/* Substring match — does `haystack` (already lowercase) contain
 * `needle` as a whole-ish phrase (word-boundary on at least one
 * side)?  We use a simple includes check first (cheap) and rule
 * out the "tarteam in Tartarus" false-positive with a manual word
 * boundary check on either end of the needle. */
function hasSubstring(haystack, needle) {
    if (!needle || needle.length < 3) return false;
    const idx = haystack.indexOf(needle);
    if (idx < 0) return false;
    const before = idx === 0 ? ' ' : haystack[idx - 1];
    const after = idx + needle.length >= haystack.length
        ? ' '
        : haystack[idx + needle.length];
    return /[^a-z0-9]/.test(before) && /[^a-z0-9]/.test(after);
}

function distinctTokens(a, b) {
    const sa = new Set(a);
    for (const t of b) sa.add(t);
    return sa;
}

let cachedIndex = null;
let cachedProviderId = null;
let cachedAt = 0;

function buildIndex(providerId) {
    if (!providerId) return null;
    const now = Date.now();
    if (cachedIndex && cachedProviderId === providerId && now - cachedAt < 60000) {
        return cachedIndex;
    }
    const cats = loadCategories(providerId) || [];
    const chans = loadChannels(providerId) || {};
    const epg = loadEpg(providerId) || {};

    const sportsCatIds = new Set(
        cats.filter((c) => /sport/i.test(c.category_name || '')).map((c) => String(c.category_id)),
    );

    const channelLookup = new Map();
    for (const catId in chans) {
        if (!sportsCatIds.has(String(catId))) continue;
        for (const ch of (chans[catId] || [])) {
            channelLookup.set(String(ch.stream_id), {
                name: ch.name || '',
                icon: ch.stream_icon || '',
            });
        }
    }

    /* Flat index: list of { streamId, channelName, icon, startTs,
     * stopTs, title, _tokens, _haystack } where `_haystack` is the
     * full lowercase `title + ' ' + description` string we'll run
     * substring queries against. */
    const idx = [];
    for (const sid in epg) {
        const meta = channelLookup.get(String(sid));
        if (!meta) continue;
        for (const it of (epg[sid] || [])) {
            const start = Number(it.startTimestamp) || 0;
            const stop = Number(it.stopTimestamp) || 0;
            if (!start) continue;
            const title = it.title || '';
            const desc = it.description || '';
            const blob = `${title} ${desc}`.toLowerCase();
            idx.push({
                streamId: sid,
                channelName: meta.name,
                channelIcon: meta.icon,
                title,
                startTs: start,
                stopTs: stop,
                _tokens: tokens(blob),
                _haystack: ` ${blob} `, // pad so word boundaries work at ends
            });
        }
    }
    cachedIndex = idx;
    cachedProviderId = providerId;
    cachedAt = now;
    return idx;
}

export function clearMatchCache() {
    cachedIndex = null;
    cachedProviderId = null;
}

/**
 * Match one fixture against a provider's EPG.  Returns up to `limit`
 * channels that appear to be airing it.
 */
export function matchFixture(providerId, fixture, { limit = 6, windowSec = 10800 } = {}) {
    const idx = buildIndex(providerId);
    if (!idx) return [];
    const ts = Number(fixture?.ts) || 0;
    if (!ts) return [];

    /* For each team, gather aliases AND each alias's tokens.  We
     * combine both substring (alias-level) and token (word-level)
     * matching so abbreviated EPG titles still hit. */
    const homeAliases = aliasesFor(fixture.home);
    const awayAliases = aliasesFor(fixture.away);
    const homeT = tokens(homeAliases.join(' '));
    const awayT = tokens(awayAliases.join(' '));
    const leagueT = tokens(fixture.league);
    const titleT = tokens(fixture.title);
    const sportT = tokens(fixture.sport);
    const required = distinctTokens(homeT, awayT);
    if (required.size === 0 && homeAliases.length === 0 && awayAliases.length === 0) {
        return [];
    }

    const hits = [];
    for (const e of idx) {
        // Time-window filter — programme must overlap kickoff ±windowSec.
        if (e.startTs > ts + windowSec) continue;
        if (e.stopTs < ts - windowSec) continue;
        // Substring scoring on the full programme text (catches
        // "Liverpool v Man Utd" → "liverpool" + "man utd").
        const homeSub = homeAliases.some((a) => hasSubstring(e._haystack, a));
        const awaySub = awayAliases.some((a) => hasSubstring(e._haystack, a));
        // Token scoring (cheap fallback for word-level overlap).
        const eTokens = new Set(e._tokens);
        let homeHit = 0;
        for (const t of homeT) if (eTokens.has(t)) homeHit += 1;
        let awayHit = 0;
        for (const t of awayT) if (eTokens.has(t)) awayHit += 1;
        let leagueHit = 0;
        for (const t of leagueT) if (eTokens.has(t)) leagueHit += 1;
        let sportHit = 0;
        for (const t of sportT) if (eTokens.has(t)) sportHit += 1;

        /* Decision tree (substring first — it's the strongest signal):
         *   • Both teams as substrings → very confident.
         *   • One team substring + (other team OR league hit) → confident.
         *   • Both team token sets hit → moderately confident.
         *   • One team token + league token → weak match.
         *   • Full title-string match (non-team events) → moderate.
         *   • Otherwise: skip. */
        let score = 0;
        if (homeSub && awaySub) score = 200 + leagueHit * 2;
        else if (homeSub || awaySub) {
            if (leagueHit || homeHit + awayHit >= 1) score = 120 + leagueHit + homeHit + awayHit;
            // Even a single team substring with no other evidence
            // is worth a small score — better than nothing for
            // matches where the EPG title is generic ("Premier
            // League: Live").
            else score = 60;
        } else if (homeHit && awayHit) {
            score = 100 + homeHit + awayHit + leagueHit;
        } else if (homeHit + awayHit >= 1 && leagueHit >= 1) {
            score = 40 + homeHit + awayHit + leagueHit;
        } else if (titleT.length > 1 && titleT.every((t) => eTokens.has(t))) {
            score = 60;
        } else {
            continue;
        }

        // Boost for closer time match (kicks in at scoring tie).
        score -= Math.floor(Math.abs(e.startTs - ts) / 600); // -1 per 10 min away
        if (sportHit) score += 2;

        hits.push({
            streamId: e.streamId,
            channelName: e.channelName,
            channelIcon: e.channelIcon,
            epgTitle: e.title,
            startTs: e.startTs,
            stopTs: e.stopTs,
            score,
        });
    }

    hits.sort((a, b) => b.score - a.score);
    // De-dupe by channel; keep best score per channel.
    const seen = new Set();
    const out = [];
    for (const h of hits) {
        if (seen.has(h.streamId)) continue;
        seen.add(h.streamId);
        out.push(h);
        if (out.length >= limit) break;
    }
    return out;
}
