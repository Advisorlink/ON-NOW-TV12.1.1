/**
 * Programme backdrop lookup — given an EPG title or fallback channel
 * name, hits the existing `/api/tmdb/search` endpoint to find a
 * backdrop URL.  Heavily cached so D-pad scrubbing doesn't melt
 * TMDB.
 *
 *  - 500 ms debounce — only the channel the user settles on fires
 *    a lookup.
 *  - In-memory cache by normalised query — re-focusing a channel
 *    you've seen before is instant.
 *  - Backend itself caches results for 1 h.
 *  - Tries the EPG title first; falls back to the channel name if
 *    the EPG didn't match anything.  This means TMDB still shows
 *    a backdrop even on channels where EPG is missing or generic.
 */
import { useEffect, useRef, useState } from 'react';
import axios from 'axios';

const API = `${process.env.REACT_APP_BACKEND_URL}/api/tmdb/search`;
const cache = new Map(); // normalised query -> { backdrop, poster } | null

function normalise(s) {
    return (s || '')
        .toLowerCase()
        .replace(/\(.*?\)/g, '')
        .replace(/\b(live|hd|fhd|uhd|new|repeat|season|series|episode)\b/g, '')
        .replace(/[^a-z0-9 ]/g, '')
        .replace(/\s+/g, ' ')
        .trim();
}

async function searchOne(key) {
    if (cache.has(key)) return cache.get(key);
    try {
        const r = await axios.get(API, { params: { q: key }, timeout: 8000 });
        const arr = r?.data?.data || [];
        const hit = arr.find((it) => it.backdrop) || arr[0];
        const found = hit
            ? { backdrop: hit.backdrop || '', poster: hit.poster || '' }
            : null;
        cache.set(key, found);
        return found;
    } catch {
        cache.set(key, null);
        return null;
    }
}

export default function useProgrammeBackdrop(title, channelName) {
    const [data, setData] = useState(null);
    const reqId = useRef(0);

    useEffect(() => {
        const titleKey = normalise(title);
        const channelKey = normalise(channelName);

        // Try the longer of the two first — usually the programme
        // title gives us a specific movie/show that TMDB can match.
        const candidates = [];
        if (titleKey && titleKey.length >= 3) candidates.push(titleKey);
        if (channelKey && channelKey.length >= 3 && channelKey !== titleKey) {
            candidates.push(channelKey);
        }
        if (candidates.length === 0) { setData(null); return undefined; }

        // Synchronous cache hit on the first candidate?  Show
        // instantly, skip the network.
        if (cache.has(candidates[0])) {
            setData(cache.get(candidates[0]));
            return undefined;
        }

        setData(null);
        const myReq = ++reqId.current;
        const t = setTimeout(async () => {
            for (const key of candidates) {
                const hit = await searchOne(key);
                if (reqId.current !== myReq) return;
                if (hit?.backdrop) {
                    setData(hit);
                    return;
                }
            }
            if (reqId.current === myReq) setData(null);
        }, 500);
        return () => clearTimeout(t);
    }, [title, channelName]);

    return data;
}
