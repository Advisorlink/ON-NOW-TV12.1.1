import React from 'react';
import { getReleaseTag } from '@/lib/releaseTags';

/** Resolve the release-status lookup key for a tile item, or null
 *  when the item can't/shouldn't be tagged (TV, old titles). */
export function releaseTagKey(item) {
    if (!item || (item.type || 'movie') !== 'movie') return null;
    if (item.imdbId && String(item.imdbId).startsWith('tt')) {
        const y = item.year || 0;
        if (y && y < new Date().getFullYear() - 1) return null;
        return String(item.imdbId);
    }
    // Synthetic catalogs (e.g. "In Cinema") only carry a TMDB id.
    const m = String(item.routePath || '').match(/^\/resolve\/movie\/(\d+)/) ||
        String(item.id || '').match(/^cin-(\d+)$/);
    return m ? `tmdb:${m[1]}` : null;
}

export function useReleaseTag(key) {
    const [tag, setTag] = React.useState(null);
    React.useEffect(() => {
        if (!key) return undefined;
        let on = true;
        getReleaseTag(key, (t) => { if (on) setTag(t); });
        return () => { on = false; };
    }, [key]);
    return tag;
}

/** Frosted-glass CINEMA + HD/CAM badge (top-left of a poster). */
export default function ReleaseTagBadge({ tag }) {
    if (!tag || (!tag.cinema && !tag.quality)) return null;
    return (
        <span
            className="vesper-mono absolute z-10 pointer-events-none flex flex-col items-start"
            style={{
                top: 8,
                left: 8,
                gap: 3,
                padding: '5px 10px',
                borderRadius: 10,
                background: 'rgba(8, 12, 18, 0.58)',
                backdropFilter: 'blur(14px)',
                WebkitBackdropFilter: 'blur(14px)',
                border: '1px solid rgba(255, 255, 255, 0.14)',
                boxShadow: '0 4px 18px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.08)',
                fontSize: 9,
                fontWeight: 700,
                letterSpacing: '0.15em',
                textTransform: 'uppercase',
                lineHeight: 1,
            }}
        >
            {tag.cinema && (
                <span data-testid="poster-tag-cinema" style={{ color: 'var(--vesper-blue)' }}>
                    Cinema
                </span>
            )}
            {tag.quality && (
                <span
                    data-testid={`poster-tag-${tag.quality}`}
                    className="flex items-center"
                    style={{
                        gap: 5,
                        color: tag.quality === 'hd' ? '#4ADE80' : '#FBBF24',
                    }}
                >
                    <i
                        style={{
                            width: 4,
                            height: 4,
                            borderRadius: 99,
                            background: 'currentColor',
                            boxShadow: '0 0 6px currentColor',
                        }}
                    />
                    {tag.quality === 'hd' ? 'HD' : 'Cam'}
                </span>
            )}
        </span>
    );
}
