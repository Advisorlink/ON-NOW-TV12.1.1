/**
 * Programme backdrop lookup — given an EPG title, hits the existing
 * `/api/tmdb/search` endpoint to find a backdrop URL.  Heavily
 * cached so D-pad scrubbing doesn't melt TMDB.
 *
 *  - 600 ms debounce — only the channel the user settles on fires
 *    a lookup.
 *  - In-memory cache by normalised title — re-focusing a channel
 *    you've seen before is instant.
 *  - Backend itself caches results for 1 h, so cold-cache lookups
 *    only hammer TMDB once per programme per hour.
 *  - Skip generic single-word titles ("News", "Sport", etc.) that
 *    will never match a real TMDB entry.
 */
import { useEffect, useRef, useState } from 'react';
import axios from 'axios';

const API = `${process.env.REACT_APP_BACKEND_URL}/api/tmdb/search`;
const cache = new Map(); // normalisedTitle -> { backdrop, poster } | null

const GENERIC = new Set([
    'news', 'sport', 'movie', 'movies', 'music', 'weather', 'cartoon',
    'cartoons', 'kids', 'show', 'live', 'programme', 'program', 'tv',
]);

function normalise(s) {
    return (s || '')
        .toLowerCase()
        .replace(/\(.*?\)/g, '')
        .replace(/\b(live|hd|fhd|uhd|new|repeat|season|series|episode)\b/g, '')
        .replace(/[^a-z0-9 ]/g, '')
        .replace(/\s+/g, ' ')
        .trim();
}

export default function useProgrammeBackdrop(title) {
    const [data, setData] = useState(null);
    const reqId = useRef(0);

    useEffect(() => {
        const key = normalise(title);
        if (!key || key.length < 4) { setData(null); return undefined; }
        const words = key.split(' ');
        if (words.length === 1 && GENERIC.has(key)) {
            setData(null);
            return undefined;
        }

        if (cache.has(key)) {
            setData(cache.get(key));
            return undefined;
        }

        setData(null);
        const myReq = ++reqId.current;
        const t = setTimeout(async () => {
            try {
                const r = await axios.get(API, { params: { q: key }, timeout: 8000 });
                if (reqId.current !== myReq) return;
                const arr = r?.data?.data || [];
                const hit = arr.find((it) => it.backdrop) || arr[0];
                const found = hit
                    ? { backdrop: hit.backdrop || '', poster: hit.poster || '' }
                    : null;
                cache.set(key, found);
                setData(found);
            } catch {
                if (reqId.current !== myReq) return;
                cache.set(key, null);
                setData(null);
            }
        }, 600);
        return () => clearTimeout(t);
    }, [title]);

    return data;
}
