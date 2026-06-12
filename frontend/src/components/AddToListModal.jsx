import React, { useEffect, useState, useRef } from 'react';
import { Check, X, Plus, Trash2, Bookmark, User } from 'lucide-react';
import {
    isInLibrary,
    addToLibrary,
    removeFromLibrary,
    isMovieInWatchLater,
    addToWatchLater,
    removeFromWatchLater,
    isActorInLibrary,
    addActorToLibrary,
    removeActorFromLibrary,
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
    // v2.10.46-g — Also remember the testid of the long-pressed
    // tile.  When the modal closes the tile DOM node we captured
    // here might have been recreated by React (key churn from a
    // shelf re-render after the favourite write), so the `node`
    // reference goes stale.  We fall back to `querySelector` on
    // the testid to recover the live element.
    const lastFocusedTestIdRef = useRef(null);
    const confirmBtnRef = useRef(null);
    // "armed" flips to true the moment the user releases the OK
    // key / mouse button after the modal mounts.  Until then, we
    // swallow Enter / Space keydowns so that the held-key repeats
    // from the original long-press DON'T fire a programmatic click
    // on the now-focused confirm button (which would instantly
    // confirm + close the modal).
    const armedRef = useRef(false);

    useEffect(() => {
        const onRequest = (e) => {
            if (!e.detail || !e.detail.id) return;
            lastFocusedRef.current = document.activeElement;
            lastFocusedTestIdRef.current =
                document.activeElement?.getAttribute?.('data-testid') || null;
            armedRef.current = false;
            setPayload(e.detail);
        };
        window.addEventListener('vesper:request-add-to-list', onRequest);
        return () =>
            window.removeEventListener('vesper:request-add-to-list', onRequest);
    }, []);

    // Imperatively focus the confirm button the moment the modal
    // mounts.  The global `data-initial-focus` retry loop in
    // useSpatialFocus only runs once at app boot, not on modal
    // open — so without this, focus stays on whatever poster the
    // user just long-pressed and the home behind keeps responding
    // to D-pad arrows.  We also aggressively clear lingering
    // `data-focused` on everything outside the modal so no tile in
    // the background still looks active.
    useEffect(() => {
        if (!payload) return;
        const focusConfirm = () => {
            // Strip data-focused from everything that is NOT inside
            // our modal.  Some Android WebViews keep painting the
            // pop-out scale until the attribute is removed.
            const modal = document.querySelector(
                '[data-testid="add-to-list-modal"]'
            );
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (!modal || !modal.contains(el)) {
                        el.removeAttribute('data-focused');
                    }
                });
            const btn = confirmBtnRef.current;
            if (!btn) return;
            try {
                btn.focus({ preventScroll: true });
                btn.setAttribute('data-focused', 'true');
            } catch {
                /* ignore */
            }
        };
        // Call several times — once synchronously, once next paint,
        // once after 50ms — to defeat any race with the imperative
        // focus calls the spatial-focus hook might still issue from
        // the in-flight long-press release.
        focusConfirm();
        const id1 = requestAnimationFrame(focusConfirm);
        const id2 = setTimeout(focusConfirm, 50);
        const id3 = setTimeout(focusConfirm, 150);
        return () => {
            cancelAnimationFrame(id1);
            clearTimeout(id2);
            clearTimeout(id3);
        };
    }, [payload]);

    // Capture-phase guards.  We MUST run before React's synthetic
    // handlers AND before the window-level spatial-focus handler so
    // we can veto the held-key auto-confirm.  We also catch leftover
    // mouseup on the backdrop here so it doesn't dismiss the modal.
    //
    // CRITICAL: only eat the FIRST keyup (the tail of the long-press
    // that opened the modal).  Once `armedRef` flips to true, all
    // subsequent Enter / Space presses must flow through to the
    // focused button — otherwise the user can't actually confirm
    // the action they wanted to take.  Earlier versions ate EVERY
    // keyup which broke "Add to My List" entirely.
    //
    // ALSO: trap D-pad arrow keys.  Without this, an unlucky press
    // of LEFT/RIGHT/UP/DOWN while the modal is open lets the global
    // spatial-focus engine move focus to an element OUTSIDE the
    // modal (a poster behind the backdrop, the SideNav, etc.) and
    // the user is suddenly driving the page behind the dim layer
    // with no way back except a mouse.  We instead bounce focus
    // between Confirm ↔ Cancel for LEFT/RIGHT, and swallow UP/DOWN
    // entirely.
    useEffect(() => {
        if (!payload) return;
        const onKeyDownCapture = (e) => {
            if (
                (e.key === 'Enter' || e.key === ' ') &&
                !armedRef.current
            ) {
                // First-keydown burst is still flowing from the
                // long-press — eat it.
                e.preventDefault();
                e.stopPropagation();
                return;
            }
            // D-pad arrow trap: never let focus escape the modal.
            if (
                e.key === 'ArrowLeft' ||
                e.key === 'ArrowRight' ||
                e.key === 'ArrowUp' ||
                e.key === 'ArrowDown'
            ) {
                e.preventDefault();
                e.stopPropagation();
                // Move focus between Confirm and Cancel for
                // horizontal arrows; vertical arrows do nothing (no
                // third element to navigate to).
                const root = document.querySelector(
                    '[data-testid="add-to-list-modal"]'
                );
                if (!root) return;
                const confirm = root.querySelector('[data-testid="modal-confirm"]');
                const cancel = root.querySelector('[data-testid="modal-cancel"]');
                if (!confirm || !cancel) return;
                const focused = document.activeElement;
                if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                    const target = focused === confirm ? cancel : confirm;
                    try {
                        // Clear any lingering data-focused outside the modal.
                        document
                            .querySelectorAll('[data-focused="true"]')
                            .forEach((el) => {
                                if (!root.contains(el)) {
                                    el.removeAttribute('data-focused');
                                }
                            });
                        // Strip data-focused from BOTH buttons before re-applying
                        // so the visual focus indicator never duplicates.
                        confirm.removeAttribute('data-focused');
                        cancel.removeAttribute('data-focused');
                        target.focus({ preventScroll: true });
                        target.setAttribute('data-focused', 'true');
                    } catch { /* ignore */ }
                }
            }
        };
        const onKeyUpCapture = (e) => {
            if ((e.key === 'Enter' || e.key === ' ') && !armedRef.current) {
                // First Enter/Space release after modal mounts is
                // the tail of the long-press the user opened the
                // modal with — eat it once and arm the modal.  All
                // subsequent presses are real user input and must
                // pass through to the buttons inside the modal.
                armedRef.current = true;
                e.preventDefault();
                e.stopPropagation();
            }
        };
        const onMouseUpCapture = (e) => {
            // The same press that opened the modal may resolve as
            // a mouseup over the backdrop.  Disarm one such event
            // so the backdrop click handler doesn't dismiss the
            // modal under the user.
            if (!armedRef.current) {
                e.preventDefault();
                e.stopPropagation();
                armedRef.current = true;
            }
        };
        // Also: if focus drifts out of the modal (e.g. a tile in the
        // background managed to grab it before our arrow trap could
        // veto), rubber-band it back to the confirm button.  This is
        // the belt + braces for the focus trap.
        const onFocusInCapture = (e) => {
            const root = document.querySelector(
                '[data-testid="add-to-list-modal"]'
            );
            if (!root) return;
            if (e.target && !root.contains(e.target)) {
                const confirm = root.querySelector('[data-testid="modal-confirm"]');
                try { confirm?.focus({ preventScroll: true }); } catch { /* ignore */ }
            }
        };
        document.addEventListener('keydown', onKeyDownCapture, true);
        document.addEventListener('keyup', onKeyUpCapture, true);
        document.addEventListener('mouseup', onMouseUpCapture, true);
        document.addEventListener('focusin', onFocusInCapture, true);
        return () => {
            document.removeEventListener('keydown', onKeyDownCapture, true);
            document.removeEventListener('keyup', onKeyUpCapture, true);
            document.removeEventListener('mouseup', onMouseUpCapture, true);
            document.removeEventListener('focusin', onFocusInCapture, true);
        };
    }, [payload]);

    const close = () => {
        setClosing(true);
        setTimeout(() => {
            setPayload(null);
            setClosing(false);
            // v2.10.46-g — More robust focus bounce-back.
            //   • Prefer the live DOM node captured on open, but
            //     fall back to a testid lookup if React recycled
            //     the tile during the modal's lifetime.
            //   • RE-APPLY focus across THREE frames (microtask,
            //     next paint, +120 ms).  Without the retries, an
            //     async focus event from the modal's unmount can
            //     steal focus back to body the moment after we
            //     restore it, and the user sees the highlight
            //     "disappear on first Left/Right".  Retrying makes
            //     the restore stick.
            //   • setAttribute(`data-focused`,'true') drives the
            //     ring even on Android WebViews where programmatic
            //     focus doesn't trigger :focus-visible.
            const restore = () => {
                let f = lastFocusedRef.current;
                if (!f || !document.body.contains(f)) {
                    const tid = lastFocusedTestIdRef.current;
                    if (tid) {
                        f = document.querySelector(`[data-testid="${tid}"]`);
                    }
                }
                if (!f || typeof f.focus !== 'function') return;
                try { f.focus({ preventScroll: false }); } catch { /* ignore */ }
                try {
                    document
                        .querySelectorAll('[data-focused="true"]')
                        .forEach((el) => {
                            if (el !== f) el.removeAttribute('data-focused');
                        });
                    f.setAttribute('data-focused', 'true');
                    if (typeof f.scrollIntoView === 'function') {
                        f.scrollIntoView({
                            behavior: 'smooth',
                            block: 'nearest',
                            inline: 'nearest',
                        });
                    }
                } catch { /* ignore */ }
            };
            restore();
            requestAnimationFrame(() => {
                restore();
                window.setTimeout(restore, 120);
            });
        }, 200);
    };

    if (!payload) return null;

    const isMovie = payload.type === 'movie';
    const isActor = payload.type === 'actor';
    // For series:  "Add/Remove from My List".
    // For movies:  "Add/Remove from Watch Later".
    // For actors:  "Add/Remove from My Actors".
    const isActive = isActor
        ? isActorInLibrary(payload.id)
        : isMovie
        ? isMovieInWatchLater(payload.id)
        : isInLibrary(payload.id);

    const onConfirm = () => {
        if (isActor) {
            if (isActive) {
                removeActorFromLibrary(payload.id);
            } else {
                addActorToLibrary({
                    id: payload.id,
                    name: payload.title,
                    profile: payload.poster,
                });
            }
        } else if (isMovie) {
            if (isActive) {
                removeFromWatchLater({ id: payload.id });
            } else {
                addToWatchLater({
                    id: payload.id,
                    movie: {
                        name: payload.title,
                        poster: payload.poster,
                        background: payload.background,
                        year: payload.year,
                        synopsis: payload.synopsis,
                    },
                });
            }
        } else {
            if (isActive) {
                removeFromLibrary(payload.id);
            } else {
                addToLibrary(payload.id, {
                    type: 'series',
                    meta: {
                        name: payload.title,
                        poster: payload.poster,
                        year: payload.year,
                    },
                });
            }
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
                    width: 'min(880px, 94vw)',
                    transform: closing ? 'scale(0.96)' : 'scale(1)',
                    transition:
                        'transform 200ms cubic-bezier(0.2,0.8,0.2,1), opacity 200ms ease',
                    opacity: closing ? 0 : 1,
                }}
            >
                <ModalCard
                    payload={payload}
                    isMovie={isMovie}
                    isActor={isActor}
                    isActive={isActive}
                    confirmBtnRef={confirmBtnRef}
                    onConfirm={onConfirm}
                    onCancel={close}
                />
            </div>
        </div>
    );
}

function ModalCard({ payload, isMovie, isActor, isActive, confirmBtnRef, onConfirm, onCancel }) {
    const { title, poster, year, genres, synopsis, type } = payload;

    // Wording matrix:
    //   movie  + !active → "Add to Watch Later"
    //   movie  +  active → "Remove from Watch Later"
    //   series + !active → "Add to My List"
    //   series +  active → "Remove from My List"
    //   actor  + !active → "Add to My Actors"
    //   actor  +  active → "Remove from My Actors"
    let eyebrow;
    let bigHeading;
    let confirmLabel;
    let ConfirmIcon;
    if (isActor) {
        eyebrow = isActive ? 'Remove from My Actors' : 'Add to My Actors';
        bigHeading = isActive ? 'Remove this actor?' : 'Save this actor?';
        confirmLabel = isActive ? 'Remove' : 'Add to My Actors';
        ConfirmIcon = isActive ? Trash2 : User;
    } else if (isMovie) {
        eyebrow = isActive ? 'Remove from Watch Later' : 'Add to Watch Later';
        bigHeading = isActive ? 'Remove this?' : 'Watch later?';
        confirmLabel = isActive ? 'Remove' : 'Add to Watch Later';
        ConfirmIcon = isActive ? Trash2 : Bookmark;
    } else {
        eyebrow = isActive ? 'Remove from My List' : 'Add to My List';
        bigHeading = isActive ? 'Remove this?' : 'Add this?';
        confirmLabel = isActive ? 'Remove' : 'Add to My List';
        ConfirmIcon = isActive ? Trash2 : Plus;
    }
    const destructive = isActive;
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
            {/* Cover art — tight portrait poster on the left so
                the modal reads as a wider rectangular pop-up
                rather than a square confirmation card. */}
            <div
                style={{
                    flex: '0 0 180px',
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
                    {eyebrow}
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
                    {bigHeading}
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
                        ref={confirmBtnRef}
                        data-testid="modal-confirm"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onConfirm}
                        className="flex items-center gap-2 rounded-full font-sans font-semibold"
                        style={{
                            flex: 1,
                            height: 52,
                            paddingLeft: 20,
                            paddingRight: 24,
                            fontSize: 15,
                            background: destructive
                                ? 'rgba(255, 81, 81, 0.92)'
                                : 'var(--vesper-blue)',
                            color: destructive ? '#fff' : 'var(--vesper-bg-0)',
                            border: 'none',
                            justifyContent: 'center',
                        }}
                    >
                        <ConfirmIcon size={16} strokeWidth={2.4} />
                        {confirmLabel}
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

                {!isActive && (
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
