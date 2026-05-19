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

    /* Tier 1: explicit aliases (Premier League, IPL, NRL, etc.). */
    if (ALIAS_LOOKUP.has(key)) return ALIAS_LOOKUP.get(key);

    /* Tier 2: auto-generated aliases for the long tail of teams
     * that AREN'T in our alias table (Chinese Super League,
     * Allsvenskan, KBO baseball, NCAA basketball, etc.).  EPG
     * providers almost never write the full team name verbatim —
     * they shorten to nickname / city / mascot.  Generate plausible
     * aliases by splitting the team name and keeping the
     * distinctive non-stopword tokens. */
    const out = new Set([key]);
    const raw = key.split(/\s+/).filter(Boolean);
    const distinctive = raw.filter((w) => w.length >= 3 && !STOP.has(w));

    if (distinctive.length >= 1) {
        // First distinctive word (usually city / first surname)
        // and last distinctive word (usually mascot/nickname).
        // For "Manchester United" → "Manchester" survives,
        // "United" is in STOP so it's filtered (good — too generic).
        // For "NC State Wolfpack" → "State" + "Wolfpack".
        // For "Duke Blue Devils" → "Duke" + "Devils".
        // For "Shenzhen Xinpengcheng" → "Shenzhen" + "Xinpengcheng".
        out.add(distinctive[0]);
        out.add(distinctive[distinctive.length - 1]);
        // First two words together — catches "NC State", "FC Köln".
        if (raw.length >= 2) {
            const pair = `${raw[0]} ${raw[1]}`;
            if (pair.length >= 6) out.add(pair);
        }
    }
    return [...out];
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

    /* Detect sport-flavoured CATEGORIES.  Cover the obvious "Sport"
     * label plus the league-specific buckets the user's provider
     * actually has: EPL, Formula One, F1 Replays, La Liga Replays,
     * Rugby-Pass, Motorsports Events, FOX/KAYO SPORTS, etc.
     * Regex is case-insensitive so we hit "UK | Sports", "USA |
     * SPORTS", "STAN Sports", "PEACOCK SPORT" etc. */
    const SPORT_CAT_RX =
        /sport|football|soccer|rugby|cricket|tennis|golf|formula|f1|motorsport|nfl|nba|nrl|afl|epl|la liga|laliga|bundesliga|serie a|ipl|kayo|fubo|espn|wwe|ufc|boxing|mma|hockey|baseball|peacock/i;

    const sportsCatIds = new Set(
        cats
            .filter((c) => SPORT_CAT_RX.test(c.category_name || ''))
            .map((c) => String(c.category_id)),
    );

    /* Channel detection — keyed by EPG_CHANNEL_ID so we can join
     * straight against the EPG map.  Also keep stream_id around so
     * the consumer (SportsGuide) can resolve playback URLs.
     *
     * BUG FIX (v2.6.91): the previous version keyed channelLookup
     * by stream_id, but the EPG map is keyed by epg_channel_id —
     * the join never matched and the matcher returned [] every
     * time.  This was the root cause of "no Watch On channels"
     * the user has been seeing for weeks. */
    const CHAN_NAME_RX =
        /\bsport|sports?\b|\befl\b|\bepl\b|\bnfl\b|\bnba\b|\bnrl\b|\bafl\b|\bmlb\b|\bnhl\b|\bipl\b|\bf1\b|formula\s?1|cricket|rugby|tennis|golf|premier\s+league|la\s+liga|bundesliga|serie\s+a|champions\s+league|europa\s+league|kayo|espn|tnt\s+sport|sky\s+sport|bein|fubo|peacock|fox\s+sport|fox\s+league|nbc\s+sport|optus\s+sport/i;

    const epgIdToChannels = new Map(); // epg_channel_id → [{ stream_id, name, icon }, …]
    for (const catId in chans) {
        const isSportCat = sportsCatIds.has(String(catId));
        for (const ch of (chans[catId] || [])) {
            const name = ch.name || '';
            const looksSporty = isSportCat || CHAN_NAME_RX.test(name);
            if (!looksSporty) continue;
            const epgId = ch.epg_channel_id;
            if (!epgId) continue;
            if (!epgIdToChannels.has(epgId)) epgIdToChannels.set(epgId, []);
            epgIdToChannels.get(epgId).push({
                stream_id: String(ch.stream_id),
                name,
                icon: ch.stream_icon || '',
            });
        }
    }

    /* Flat index keyed by EPG entries.  One EPG entry can map to
     * MULTIPLE channels (FHD / HD / SD / 50fps variants share the
     * same epg_channel_id), so we explode each entry per channel
     * variant — the de-dupe-by-channel step in `matchFixture` keeps
     * the final list clean while still surfacing every variant on
     * the user's box.  Earlier behaviour dropped duplicates here
     * which meant the user only saw 1 of 3 quality variants. */
    const idx = [];
    for (const epgId in epg) {
        const channelList = epgIdToChannels.get(epgId);
        if (!channelList || channelList.length === 0) continue;
        for (const it of (epg[epgId] || [])) {
            const start = Number(it.startTimestamp) || 0;
            const stop = Number(it.stopTimestamp) || 0;
            if (!start) continue;
            const title = it.title || '';
            const desc = it.description || it.desc || '';
            const blob = `${title} ${desc}`.toLowerCase();
            const _tokens = tokens(blob);
            const _haystack = ` ${blob} `;
            for (const ch of channelList) {
                idx.push({
                    streamId: ch.stream_id,
                    channelName: ch.name,
                    channelIcon: ch.icon,
                    epgChannelId: epgId,
                    title,
                    startTs: start,
                    stopTs: stop,
                    _tokens,
                    _haystack,
                });
            }
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
        } else if (leagueHit >= 2 || (leagueHit >= 1 && sportHit >= 1)) {
            /* TIER-4 (lowest-confidence) fallback: the EPG entry
             * doesn't mention either team, but the league name AND
             * sport keywords show up.  For obscure / international
             * fixtures (Chinese Super League, NCAA basketball, KBO
             * baseball etc.) this surfaces channels currently
             * airing the same league as a "you might find it here"
             * hint.  Score capped low so true team-matches always
             * outrank this. */
            score = 18 + leagueHit * 2 + sportHit;
        } else if (sportHit >= 2) {
            /* TIER-5: ultra-weak fallback for events where TheSportsDB
             * fixture's `sport` field has multiple distinctive tokens
             * the EPG also mentions (e.g. fixture sport='American
             * Football' + EPG title 'NFL Football: ...').  Surfaces
             * the broadcast as a possible viewing option. */
            score = 12 + sportHit;
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
            epgChannelId: e.epgChannelId,
            epgTitle: e.title,
            startTs: e.startTs,
            stopTs: e.stopTs,
            score,
        });
    }

    hits.sort((a, b) => b.score - a.score);
    /* De-dupe by EPG-channel-id (groups together "Sky Sports
     * Premier League FHD/HD/SD/50fps" variants — same channel
     * logically, just different quality streams).  We keep the
     * highest-scoring variant per logical channel so the user sees
     * up to `limit` DISTINCT channels broadcasting the fixture.
     * The streamId of the kept variant is the one the SportsGuide
     * "Watch on" click handler resolves to a playback URL. */
    const seen = new Set();
    const out = [];
    for (const h of hits) {
        const dedupKey = h.epgChannelId || h.streamId;
        if (seen.has(dedupKey)) continue;
        seen.add(dedupKey);
        out.push(h);
        if (out.length >= limit) break;
    }
    return out;
}
