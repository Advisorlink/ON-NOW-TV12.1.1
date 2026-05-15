/**
 * sportsMatch — match a TheSportsDB fixture (team A vs team B) to a
 * user's IPTV channel + EPG entry.
 *
 *   Algorithm:
 *     • For every EPG entry on every sports-tagged channel within ±2 h of
 *       the fixture kickoff, score by how many DISTINCT team-name tokens
 *       from the fixture appear in the EPG title/description.
 *     • Tokens: drop stopwords ("vs", "v", "fc", "afc", "united",
 *       "city" — too generic), keep distinctive surnames/city names.
 *     • Channels with score ≥ 2 distinct tokens (or 1 token + same
 *       sport keyword) are returned.
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

// Common stopwords that are too generic to score on.
const STOP = new Set([
    'fc', 'afc', 'cf', 'sc', 'ac', 'cd', 'club', 'team',
    'united', 'city', 'town', 'rovers', 'wanderers', 'athletic',
    'real', 'inter', 'sporting',
    'vs', 'v', 'at', 'and', 'the', 'a', 'an', 'of',
    'fixture', 'match', 'game', 'live', 'highlights',
    'tonight', 'today',
]);

function tokens(text) {
    if (!text) return [];
    return String(text)
        .toLowerCase()
        .replace(/[^a-z0-9\s]/g, ' ')
        .split(/\s+/)
        .filter((w) => w.length >= 3 && !STOP.has(w));
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

    // Flat index: list of { streamId, channelName, icon, startTs, stopTs, titleTokens, fullText }
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
            idx.push({
                streamId: sid,
                channelName: meta.name,
                channelIcon: meta.icon,
                title,
                startTs: start,
                stopTs: stop,
                _tokens: tokens(`${title} ${desc}`),
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
export function matchFixture(providerId, fixture, { limit = 6, windowSec = 7200 } = {}) {
    const idx = buildIndex(providerId);
    if (!idx) return [];
    const ts = Number(fixture?.ts) || 0;
    if (!ts) return [];

    const homeT = tokens(fixture.home);
    const awayT = tokens(fixture.away);
    const leagueT = tokens(fixture.league);
    const titleT = tokens(fixture.title);
    const sportT = tokens(fixture.sport);
    const required = distinctTokens(homeT, awayT);
    if (required.size === 0) return [];

    const hits = [];
    for (const e of idx) {
        // Time-window filter — programme must overlap kickoff ±windowSec.
        if (e.startTs > ts + windowSec) continue;
        if (e.stopTs < ts - windowSec) continue;
        // Token scoring
        const eTokens = new Set(e._tokens);
        let homeHit = 0;
        for (const t of homeT) if (eTokens.has(t)) homeHit += 1;
        let awayHit = 0;
        for (const t of awayT) if (eTokens.has(t)) awayHit += 1;
        let leagueHit = 0;
        for (const t of leagueT) if (eTokens.has(t)) leagueHit += 1;
        let sportHit = 0;
        for (const t of sportT) if (eTokens.has(t)) sportHit += 1;

        // For team-vs-team fixtures: require at least one team token to hit
        // PLUS either the other team or the league.  This prevents random
        // sports programmes with one common word from matching.
        let score = 0;
        if (homeHit && awayHit) score = 100 + homeHit + awayHit + leagueHit;
        else if (homeHit + awayHit >= 1 && leagueHit >= 1) score = 40 + homeHit + awayHit + leagueHit;
        else if (titleT.length > 1 && titleT.every((t) => eTokens.has(t))) score = 60; // full title match for non-team events (e.g. "PGA Championship")
        else continue;

        // Boost for closer time match
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
