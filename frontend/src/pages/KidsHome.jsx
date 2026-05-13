import React, { useMemo, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { LogOut } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAddons } from '@/hooks/useAddons';
import { useLiveShelves } from '@/hooks/useLiveShelves';
import { getKidsConfig, isKidsSafe } from '@/lib/profiles';
import * as img from '@/lib/img';

/**
 * Kids-mode home page.
 *  - Bright, playful gradient background (no neon-blue
 *    cyberpunk vibes — that's for the adult mode).
 *  - Filters every shelf through `isKidsSafe(meta, cfg)`.
 *  - Exit button bottom-right → routes to /kids/exit-pin, which
 *    requires the parent PIN if one is set.
 */
export default function KidsHome() {
    useSpatialFocus();
    const navigate = useNavigate();
    const { addons } = useAddons();
    const cfg = useMemo(() => getKidsConfig(), []);

    const { shelves, loading } = useLiveShelves(addons, null, 60);

    const filteredShelves = useMemo(() => {
        return (shelves || [])
            .map((s) => ({
                ...s,
                items: (s.items || []).filter((it) => {
                    // Coerce minimal meta shape for the filter.
                    return isKidsSafe(
                        {
                            type: it.type || s.type,
                            adult: it.adult,
                            certification:
                                it.certification ||
                                it.certificate ||
                                it.rating,
                            imdbRating: it.imdbRating,
                        },
                        cfg
                    );
                }),
            }))
            .filter((s) => s.items.length > 0);
    }, [shelves, cfg]);

    useEffect(() => {
        const main = document.querySelector('[data-testid="kids-main"]');
        if (main) main.scrollTop = 0;
    }, []);

    return (
        <div
            data-testid="kids-home"
            className="relative w-screen h-[100dvh] overflow-hidden"
            style={{
                background:
                    'radial-gradient(circle at 15% 10%, #FF6BCB22 0%, transparent 40%), ' +
                    'radial-gradient(circle at 85% 30%, #FACC1522 0%, transparent 45%), ' +
                    'radial-gradient(circle at 50% 90%, #3DDC9722 0%, transparent 50%), ' +
                    'var(--vesper-bg-0)',
            }}
        >
            {/* Top bar */}
            <header
                className="absolute top-0 left-0 right-0 z-20 flex items-center justify-between"
                style={{
                    padding: '24px 40px',
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0.7) 0%, transparent 100%)',
                }}
            >
                <div className="flex items-center gap-3">
                    <span style={{ fontSize: 40 }}>🧸</span>
                    <div>
                        <div
                            className="vesper-mono"
                            style={{
                                fontSize: 11,
                                letterSpacing: '0.32em',
                                color: '#FFC857',
                            }}
                        >
                            KIDS MODE
                        </div>
                        <div
                            className="vesper-display"
                            style={{
                                fontSize: 28,
                                letterSpacing: '-0.025em',
                                lineHeight: 1,
                                color: '#fff',
                            }}
                        >
                            Let's watch!
                        </div>
                    </div>
                </div>
                <button
                    data-testid="kids-exit"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => navigate('/kids/exit-pin')}
                    className="flex items-center gap-2 rounded-full"
                    style={{
                        height: 44,
                        padding: '0 18px',
                        background: 'rgba(255,255,255,0.10)',
                        color: '#fff',
                        border: '1px solid rgba(255,255,255,0.20)',
                        fontSize: 14,
                        fontWeight: 600,
                    }}
                >
                    <LogOut size={15} strokeWidth={2} />
                    Exit Kids
                </button>
            </header>

            <main
                data-testid="kids-main"
                className="absolute inset-0 overflow-y-auto"
                style={{ paddingTop: 120, paddingBottom: 40 }}
            >
                {loading && filteredShelves.length === 0 && (
                    <div
                        className="vesper-mono"
                        style={{
                            textAlign: 'center',
                            padding: 40,
                            color: 'var(--vesper-text-3)',
                        }}
                    >
                        LOADING…
                    </div>
                )}

                {!loading && filteredShelves.length === 0 && (
                    <div
                        style={{
                            textAlign: 'center',
                            padding: '60px 40px',
                            color: 'var(--vesper-text-2)',
                        }}
                    >
                        <div style={{ fontSize: 64, marginBottom: 16 }}>🐻</div>
                        <div className="vesper-display" style={{ fontSize: 28 }}>
                            No kid-safe shows yet
                        </div>
                        <div style={{ marginTop: 8, fontSize: 15 }}>
                            Ask a grown-up to add some catalogues.
                        </div>
                    </div>
                )}

                {filteredShelves.map((shelf) => (
                    <KidsShelf key={shelf.id} shelf={shelf} navigate={navigate} />
                ))}
            </main>
        </div>
    );
}

function KidsShelf({ shelf, navigate }) {
    return (
        <section
            data-testid={`kids-shelf-${shelf.id}`}
            style={{ marginBottom: 36 }}
        >
            <div style={{ padding: '0 40px', marginBottom: 14 }}>
                <h2
                    className="vesper-display"
                    style={{
                        fontSize: 24,
                        letterSpacing: '-0.02em',
                        color: '#fff',
                    }}
                >
                    {shelf.title}
                </h2>
            </div>
            <div
                className="vesper-shelf flex"
                style={{
                    gap: 18,
                    overflowX: 'auto',
                    padding: '12px 40px',
                    scrollbarWidth: 'none',
                }}
            >
                {(shelf.items || []).slice(0, 28).map((it) => (
                    <button
                        key={it.imdbId || it.id}
                        data-testid={`kids-${it.imdbId || it.id}`}
                        data-focusable="true"
                        data-focus-style="tile"
                        tabIndex={0}
                        onClick={() =>
                            navigate(
                                it.routePath ||
                                    `/title/${it.type || 'movie'}/${it.imdbId || it.id}`
                            )
                        }
                        className="rounded-2xl overflow-hidden shrink-0"
                        style={{
                            width: 180,
                            aspectRatio: '2 / 3',
                            border: '3px solid rgba(255,200,87,0.30)',
                            padding: 0,
                            background: 'rgba(255,255,255,0.05)',
                        }}
                    >
                        {it.poster ? (
                            <img
                                src={img.poster(it.poster)}
                                alt={it.title}
                                loading="lazy"
                                decoding="async"
                                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                            />
                        ) : (
                            <div
                                className="flex items-center justify-center w-full h-full"
                                style={{ fontSize: 64 }}
                            >
                                🎬
                            </div>
                        )}
                    </button>
                ))}
            </div>
        </section>
    );
}
