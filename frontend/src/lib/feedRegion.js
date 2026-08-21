/**
 * Per-profile "Home feed region" preference.
 *
 * Lets each profile choose whether their Home feed + For You rail
 * is the default English/global catalogue or a fully-dedicated
 * Indian ('indian' = all Indian languages) / Bollywood ('bollywood'
 * = Hindi only) version.  Stored scoped to the active profile so
 * one profile can be English while another is Indian.
 */
import { readScopedString, writeScopedString } from './profileScope';

const KEY = 'onnowtv-feed-region-v1';
const VALID = ['english', 'indian', 'bollywood'];

export function getFeedRegion() {
    try {
        const v = readScopedString(KEY);
        return VALID.includes(v) ? v : 'english';
    } catch {
        return 'english';
    }
}

export function setFeedRegion(region) {
    const clean = VALID.includes(region) ? region : 'english';
    writeScopedString(KEY, clean);
    try {
        window.dispatchEvent(new Event('vesper:feed-region-change'));
    } catch { /* noop */ }
    return clean;
}

export const FEED_REGIONS = [
    { id: 'english', label: 'English', note: 'The standard global catalogue' },
    { id: 'indian', label: 'Indian', note: 'Every Indian film & show — all languages' },
    { id: 'bollywood', label: 'Bollywood', note: 'Hindi-language films & shows only' },
];
