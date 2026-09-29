import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Boxes } from 'lucide-react';
import { toast } from 'sonner';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useBackHandler from '@/hooks/useBackHandler';
import useLongPress from '@/hooks/useLongPress';
import { BOX_SETS } from '@/lib/collections';
import { CollectionTile, useCollectionArt } from '@/components/CollectionsShelf';
import { addToLibrary, isInLibrary, removeFromLibrary } from '@/lib/library';

const boxSetLibId = (slug) => `boxset:${slug}`;

/**
 * Box Sets — every film franchise as a 16:9 title-treatment tile.
 * OK opens the set, push-and-hold saves / unsaves it in My Library.
 * Only sets with a real title logo are shown (no made-up wordmarks).
 */
export default function BoxSets() {
    useSpatialFocus();
    useBackHandler();
    const navigate = useNavigate();
    const art = useCollectionArt();
    const [, bump] = useState(0);

    useEffect(() => {
        const sync = () => bump((n) => n + 1);
        window.addEventListener('vesper:library-change', sync);
        return () => window.removeEventListener('vesper:library-change', sync);
    }, []);

    const loaded = Object.keys(art).length > 0;
    const sets = useMemo(
        () => (loaded ? BOX_SETS.filter((c) => art[c.slug]?.logo) : BOX_SETS),
        [art, loaded]
    );

    return (
        <div
            data-testid="boxsets-page"
            className="absolute inset-0 overflow-y-auto"
            style={{
                paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                paddingRight: 'clamp(40px, 4.2vw, 80px)',
                paddingTop: 'clamp(28px, 3vw, 56px)',
                paddingBottom: 80,
            }}
        >
            <header className="flex items-end justify-between mb-6">
                <div className="flex items-center gap-5 min-w-0">
                    <button
                        type="button"
                        data-testid="boxsets-back"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => navigate(-1)}
                        className="flex items-center justify-center rounded-full shrink-0"
                        style={{ width: 44, height: 44, background: 'rgba(255,255,255,0.08)', color: 'var(--vesper-text)' }}
                        aria-label="Back"
                    >
                        <ArrowLeft size={20} />
                    </button>
                    <div className="min-w-0">
                        <div className="vesper-eyebrow flex items-center gap-2">
                            <Boxes size={13} /> Browse · Box Sets
                        </div>
                        <h1
                            className="vesper-display truncate"
                            style={{ fontSize: 'clamp(26px, 2.6vw, 40px)', letterSpacing: '-0.03em', lineHeight: 1.05 }}
                        >
                            Box Sets
                        </h1>
                    </div>
                </div>
                <span
                    className="vesper-mono shrink-0"
                    style={{ color: 'var(--vesper-text-3)', fontSize: 'clamp(9px, 0.62vw, 11px)', letterSpacing: '0.22em', textTransform: 'uppercase' }}
                >
                    {sets.length} sets · hold OK to save to My Library
                </span>
            </header>

            <div
                data-testid="boxsets-grid"
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(clamp(200px, 15.6vw, 300px), 1fr))',
                    gap: 'clamp(14px, 1.25vw, 24px)',
                }}
            >
                {sets.map((c, i) => (
                    <BoxSetTile key={c.slug} col={c} art={art[c.slug]} first={i === 0} />
                ))}
            </div>
        </div>
    );
}

function BoxSetTile({ col, art, first }) {
    const navigate = useNavigate();
    const id = boxSetLibId(col.slug);
    const saved = isInLibrary(id);
    const press = useLongPress(
        () => {
            if (isInLibrary(id)) {
                removeFromLibrary(id);
                toast(`${col.name} removed from My Library`);
            } else {
                addToLibrary(id, {
                    type: 'boxset',
                    meta: { slug: col.slug, name: col.name, accent: col.accent, logo: art?.logo || null, backdrop: art?.backdrop || null },
                });
                toast(`${col.name} saved to My Library`);
            }
        },
        () => navigate(`/collections/${col.slug}`)
    );
    return (
        <CollectionTile
            col={col}
            art={art}
            saved={saved}
            width="100%"
            testId={`boxset-${col.slug}`}
            extraProps={{ ...press, ...(first ? { 'data-initial-focus': 'true' } : {}) }}
        />
    );
}
