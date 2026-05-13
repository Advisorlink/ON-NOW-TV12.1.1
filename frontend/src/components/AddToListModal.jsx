import React, { useEffect, useState, useRef } from 'react';
import { Check, X, Plus, Trash2 } from 'lucide-react';
import {
    isInLibrary,
    addToLibrary,
    removeFromLibrary,
} from '@/lib/library';

/**
 * "Add to My List" modal — fired by long-pressing OK / mouse on
 * any poster, hero card, or library tile.
 *
 * Listens for `vesper:request-add-to-list` events fired from
 * `useLongPress` handlers.  Payload:
 *   {
 *     id:       "tt1234567",         // imdb id
 *     type:     "movie" | "series",
 *     title:    "Game of Thrones",
 *     poster:   "https://…",
 *     year:     "2011–2019",
 *     genres:   ["Drama", "Action"],
 *     synopsis: "Nine noble families…",
 *   }
 *
 * Toggles based on isInLibrary(id):
 *   - not in library: "Add to My List?" + Add / Cancel
 *   - in library:     "Remove from My List?" + Remove / Cancel
 *
 * Globally mounted in `App.js` so any tile in the tree can fire it.
 */
export default function AddToListModal() {
    const [payload, setPayload] = useState(null);
    const [closing, setClosing] = useState(false);
    const lastFocusedRef = useRef(null);

    useEffect(() => {
        const onRequest = (e) => {
            if (!e.detail || !e.detail.id) return;
            lastFocusedRef.current = document.activeElement;
            setPayload(e.detail);
        };
        window.addEventListener('vesper:request-add-to-list', onRequest);
        return () =>
            window.removeEventListener('vesper:request-add-to-list', onRequest);
    }, []);

    const close = () => {
        setClosing(true);
        setTimeout(() => {
            setPayload(null);
            setClosing(false);
            // Return focus to whatever was focused before the modal
            // opened (Android WebView doesn't always do this on its own).
            const f = lastFocusedRef.current;
            if (f && typeof f.focus === 'function') {
                try { f.focus({ preventScroll: true }); } catch { /* ignore */ }
            }
        }, 200);
    };

    if (!payload) return null;

    const inList = isInLibrary(payload.id);

    const onConfirm = () => {
        if (inList) {
            removeFromLibrary(payload.id);
        } else {
            addToLibrary(payload.id, {
                type: payload.type === 'series' ? 'series' : 'movie',
                meta: {
                    name: payload.title,
                    poster: payload.poster,
                    year: payload.year,
                },
            });
        }
        close();
    };

    return (
        <div
            data-testid="add-to-list-modal"
            className="fixed inset-0 z-[70] flex items-center justify-center"
            onClick={close}
            style={{
                background: 'rgba(0,0,0,0.72)',
                backdropFilter: 'blur(8px)',
                WebkitBackdropFilter: 'blur(8px)',
                opacity: closing ? 0 : 1,
                transition: 'opacity 200ms ease',
            }}
        >
            <div
                onClick={(e) => e.stopPropagation()}
                style={{
                    width: 'min(720px, 92vw)',
                    transform: closing ? 'scale(0.96)' : 'scale(1)',
                    transition:
                        'transform 200ms cubic-bezier(0.2,0.8,0.2,1), opacity 200ms ease',
                    opacity: closing ? 0 : 1,
                }}
            >
                <ModalCard
                    payload={payload}
                    inList={inList}
                    onConfirm={onConfirm}
                    onCancel={close}
                />
            </div>
        </div>
    );
}

function ModalCard({ payload, inList, onConfirm, onCancel }) {
    const { title, poster, year, genres, synopsis, type } = payload;
    return (
        <div
            className="overflow-hidden flex"
            style={{
                background:
                    'linear-gradient(180deg, rgba(14,18,30,0.98) 0%, rgba(10,14,26,0.98) 100%)',
                border: '1px solid rgba(var(--vesper-blue-rgb), 0.5)',
                borderRadius: 22,
                boxShadow:
                    '0 40px 100px rgba(0,0,0,0.7), 0 0 0 1px rgba(var(--vesper-blue-rgb), 0.18), 0 0 64px rgba(var(--vesper-blue-rgb), 0.18)',
            }}
        >
            {/* Cover art */}
            <div
                style={{
                    flex: '0 0 240px',
                    aspectRatio: '2 / 3',
                    background: poster
                        ? '#1a1f2e'
                        : 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.3), rgba(10,14,26,0.9))',
                    position: 'relative',
                    overflow: 'hidden',
                }}
            >
                {poster ? (
                    <img
                        src={poster}
                        alt=""
                        className="w-full h-full object-cover"
                    />
                ) : (
                    <div
                        className="w-full h-full flex items-center justify-center vesper-display"
                        style={{
                            fontSize: 96,
                            color: 'rgba(var(--vesper-blue-rgb), 0.35)',
                        }}
                    >
                        {(title || '?')[0]}
                    </div>
                )}
                <div
                    className="absolute inset-0"
                    style={{
                        background:
                            'linear-gradient(135deg, rgba(0,0,0,0) 60%, rgba(0,0,0,0.5) 100%)',
                    }}
                />
            </div>

            {/* Right side: content + actions */}
            <div
                className="flex-1 min-w-0 flex flex-col"
                style={{ padding: '28px 32px' }}
            >
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-blue-bright)',
                        marginBottom: 6,
                    }}
                >
                    {inList ? 'Remove from My List' : 'Add to My List'}
                </div>

                <div
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(28px, 3vw, 40px)',
                        letterSpacing: '-0.025em',
                        lineHeight: 1.05,
                        marginBottom: 12,
                        color: 'var(--vesper-text)',
                    }}
                >
                    {inList ? 'Remove this?' : 'Add this?'}
                </div>

                <div
                    className="vesper-display"
                    style={{
                        fontSize: 20,
                        letterSpacing: '-0.015em',
                        color: 'var(--vesper-text)',
                        marginBottom: 6,
                    }}
                >
                    {title}
                </div>

                <div
                    className="vesper-meta flex items-center gap-2 flex-wrap"
                    style={{
                        fontSize: 13,
                        color: 'var(--vesper-text-2)',
                        marginBottom: 12,
                    }}
                >
                    {year && <span>{year}</span>}
                    {year && genres?.length > 0 && <span style={{ opacity: 0.4 }}>·</span>}
                    {genres?.length > 0 && (
                        <span>{genres.slice(0, 3).join(' · ')}</span>
                    )}
                    {type && (
                        <>
                            <span style={{ opacity: 0.4 }}>·</span>
                            <span style={{ textTransform: 'uppercase', letterSpacing: '0.18em', fontSize: 10 }}>
                                {type === 'series' ? 'Series' : 'Movie'}
                            </span>
                        </>
                    )}
                </div>

                {synopsis && (
                    <p
                        style={{
                            fontSize: 13.5,
                            lineHeight: 1.55,
                            color: 'var(--vesper-text-2)',
                            marginBottom: 22,
                            display: '-webkit-box',
                            WebkitLineClamp: 3,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                        }}
                    >
                        {synopsis}
                    </p>
                )}

                <div className="flex items-center gap-3 mt-auto">
                    <button
                        data-testid="modal-confirm"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={onConfirm}
                        className="flex items-center gap-2 rounded-full font-sans font-semibold"
                        style={{
                            flex: 1,
                            height: 52,
                            paddingLeft: 20,
                            paddingRight: 24,
                            fontSize: 15,
                            background: inList
                                ? 'rgba(255, 81, 81, 0.92)'
                                : 'var(--vesper-blue)',
                            color: inList ? '#fff' : 'var(--vesper-bg-0)',
                            border: 'none',
                            justifyContent: 'center',
                        }}
                    >
                        {inList ? (
                            <Trash2 size={16} strokeWidth={2.4} />
                        ) : (
                            <Plus size={16} strokeWidth={2.4} />
                        )}
                        {inList ? 'Remove' : 'Add to My List'}
                    </button>
                    <button
                        data-testid="modal-cancel"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onCancel}
                        className="flex items-center gap-2 rounded-full font-sans font-medium"
                        style={{
                            flex: '0 0 auto',
                            height: 52,
                            paddingLeft: 20,
                            paddingRight: 24,
                            fontSize: 15,
                            background: 'rgba(255,255,255,0.06)',
                            color: 'var(--vesper-text)',
                            border: '1px solid rgba(255,255,255,0.16)',
                            justifyContent: 'center',
                        }}
                    >
                        <X size={16} strokeWidth={2.2} />
                        Cancel
                    </button>
                </div>

                {!inList && (
                    <div
                        className="vesper-mono flex items-center gap-2"
                        style={{
                            marginTop: 14,
                            fontSize: 10,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-3)',
                        }}
                    >
                        <Check size={11} strokeWidth={2.4} />
                        Tip · press &amp; hold OK on any tile to add it
                    </div>
                )}
            </div>
        </div>
    );
}
