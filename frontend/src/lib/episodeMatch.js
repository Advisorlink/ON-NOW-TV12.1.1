/**
 * episodeMatch — guard against addons returning the WRONG episode.
 *
 * Easynews++ (Usenet search) regularly answers a request for
 * `tt0903747:1:3` with files such as `Breaking Bad.S03E02.mkv` or
 * `Breaking.Bad.S01E01.GERMAN...mkv`.  Because autoplay picks the
 * smallest 1080p file, those strays used to play instead of S01E03.
 *
 * We read the release name (filename / title / description) for
 * SxxEyy, SxxEyy-Ezz and NxNN tags and compare them to the requested
 * season/episode:
 *   true  → a tag matches (multi-episode ranges count)
 *   false → tags were found but NONE match  → drop the stream
 *   null  → no episode tag at all (season packs, dated shows, anime
 *           absolute numbering) → keep, we can't prove it wrong
 */
const SE_RE = /(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?:[ ._-]?(?:e|to|-)[ ._-]?(\d{1,3}))?(?![0-9])/gi;
const X_RE = /(?<![\dx])(\d{1,2})x(\d{1,3})(?![\dp])/gi;

export function parseEpisodeTags(text) {
    const out = [];
    if (!text) return out;
    const src = String(text);
    for (const m of src.matchAll(SE_RE)) {
        const from = parseInt(m[2], 10);
        const to = m[3] ? parseInt(m[3], 10) : from;
        out.push({ season: parseInt(m[1], 10), from, to: Math.max(from, to) });
    }
    for (const m of src.matchAll(X_RE)) {
        const ep = parseInt(m[2], 10);
        if (ep === 264 || ep === 265 || ep > 150) continue;
        out.push({ season: parseInt(m[1], 10), from: ep, to: ep });
    }
    return out;
}

export function streamText(s) {
    return [
        s?.behaviorHints?.filename,
        s?.title,
        s?.description,
        s?.name,
    ]
        .filter(Boolean)
        .join('\n');
}

export function streamEpisodeMatch(s, season, episode) {
    const tags = parseEpisodeTags(streamText(s));
    if (!tags.length) return null;
    return tags.some((t) => t.season === season && episode >= t.from && episode <= t.to);
}

export function parseSeriesId(itemId) {
    const m = /^(.+?):(\d+):(\d+)$/.exec(String(itemId || ''));
    if (!m) return null;
    return { base: m[1], season: parseInt(m[2], 10), episode: parseInt(m[3], 10) };
}

/** Tag `_ep_match` and drop confirmed wrong-episode streams. */
export function filterEpisodeStreams(streams, type, itemId) {
    if (type !== 'series' || !Array.isArray(streams) || !streams.length) return streams;
    const want = parseSeriesId(itemId);
    if (!want) return streams;
    const out = [];
    for (const s of streams) {
        const match = streamEpisodeMatch(s, want.season, want.episode);
        if (match === false) continue;
        out.push(s._ep_match === match ? s : { ...s, _ep_match: match });
    }
    return out;
}
