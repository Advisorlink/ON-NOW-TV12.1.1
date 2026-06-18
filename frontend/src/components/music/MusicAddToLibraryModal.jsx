// ON NOW TV TUNES — Add-to-library modal (v2.10.47)
// =============================================================
// User-requested parity with Vesper's long-press → "Add to My List"
// pop-up.  Fires on `tunes:request-add-to-library` events
// dispatched by any tile that wraps its OK / mouse-down handler in
// `useLongPress`.  The payload shape is:
//
//   { kind: 'track' | 'album' | 'artist' | 'radio' | 'podcast',
//     item: { id, title|name, picture|cover|favicon|artwork, ... } }
//
// Toggles between "Add to your library?" and "Remove from your
// library?" based on the current `isMusicLiked(kind, id)` state.
// Modeled on `AddToListModal.jsx` (Vesper's V2) for visual + UX
// consistency, but slimmed down to the smaller information set we
// have for music tiles (no IMDb scrape, no genre list, no synopsis).
//
// Globally mounted from `MusicLayout` so any tile in the music
// shell can fire the event.

import React, { useEffect, useRef, useState } from 'react';
import { Heart, Check, X } from 'lucide-react';
import {
    isMusicLiked,
    saveMusicLike,
    removeMusicLike,
} from '../../lib/music-library';

/** Pretty label for the modal heading. */
const KIND_LABELS = {
    track:   'song',
    album:   'album',
    artist:  'artist',
    radio:   'radio station',
    podcast: 'podcast',
};

function pickArt(payload) {
    if (!payload?.item) return null;
    const it = payload.item;
    return (
        it.picture ||
        it.cover_xl ||
        it.cover ||
        it.album?.cover ||
        it.favicon ||
        it.artwork ||
        null
    );
}

function pickTitle(payload) {
    if (!payload?.item) return '';
    return payload.item.title || payload.item.name || 'this item';
}

function pickSubtitle(payload) {
    if (!payload?.item) return '';
    const it = payload.item;
    return (
        it.artist?.name ||
        it.country ||
        it.subtitle ||
        ''
    );
}

export default function MusicAddToLibraryModal() {
    const [payload, setPayload] = useState(null);
    const [closing, setClosing] = useState(false);
    const lastFocusedRef = useRef(null);
    const confirmBtnRef = useRef(null);
    // "armed" flips to true the moment the user releases OK / mouse
    // after the modal mounts.  Until then we swallow Enter / Space
    // keydowns so the held-key from the original long-press doesn't
    // immediately fire a programmatic click on the confirm button.
    const armedRef = useRef(false);

    useEffect(() => {
        const onRequest = (e) => {
            if (!e.detail || !e.detail.kind || !e.detail.item) return;
            lastFocusedRef.current = document.activeElement;
            armedRef.current = false;
            setPayload(e.detail);
        };
        window.addEventListener('tunes:request-add-to-library', onRequest);
        return () => window.removeEventListener('tunes:request-add-to-library', onRequest);
    }, []);

    // Auto-focus the confirm button when the modal mounts, but
    // delay one tick so the spatial-focus MutationObserver picks
    // up the new focus-trap subtree first.
    useEffect(() => {
        if (!payload) return undefined;
        const id = requestAnimationFrame(() => {
            try { confirmBtnRef.current?.focus({ preventScroll: true }); }
            catch { /* ignore */ }
        });
        return () => cancelAnimationFrame(id);
    }, [payload]);

    // Arm the keyboard handlers ~120 ms after open so the user's
    // held Enter from the long-press doesn't auto-confirm.
    useEffect(() => {
        if (!payload) return undefined;
        const t = setTimeout(() => { armedRef.current = true; }, 120);
        return () => clearTimeout(t);
    }, [payload]);

    // Escape / back closes the modal.
    useEffect(() => {
        if (!payload) return undefined;
        const onKey = (e) => {
            if (
                e.key === 'Escape' ||
                e.key === 'GoBack' ||
                e.key === 'BrowserBack' ||
                e.keyCode === 4 /* AKEYCODE_BACK */
            ) {
                e.preventDefault();
                e.stopPropagation();
                close();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [payload]);

    const close = () => {
        setClosing(true);
        setTimeout(() => {
            setPayload(null);
            setClosing(false);
            // Restore focus to whatever tile triggered the modal.
            try { lastFocusedRef.current?.focus?.({ preventScroll: true }); }
            catch { /* ignore */ }
        }, 160);
    };

    if (!payload) return null;

    const kind = payload.kind;
    const it = payload.item;
    const liked = isMusicLiked(kind, it.id);
    const label = KIND_LABELS[kind] || 'item';
    const title = pickTitle(payload);
    const subtitle = pickSubtitle(payload);
    const art = pickArt(payload);

    const onConfirm = () => {
        if (liked) removeMusicLike(kind, it.id);
        else saveMusicLike(kind, it);
        close();
    };

    return (
        <div
            className={'tunes-add-modal' + (closing ? ' is-closing' : '')}
            data-testid="tunes-add-to-library-modal"
            data-focus-trap="true"
            role="dialog"
            aria-modal="true"
            aria-labelledby="tunes-add-modal-title"
            onClick={(e) => { if (e.target === e.currentTarget) close(); }}
        >
            <div className="tunes-add-modal__card">
                <button
                    type="button"
                    className="tunes-add-modal__close"
                    onClick={close}
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    aria-label="Close"
                    data-testid="tunes-add-modal-cancel-x"
                >
                    <X size={20} />
                </button>

                {art && (
                    <img
                        src={art}
                        alt=""
                        className="tunes-add-modal__art"
                        loading="lazy"
                    />
                )}
                <div className="tunes-add-modal__eyebrow">
                    {liked ? 'Already saved' : 'Add to your library'}
                </div>
                <h2 id="tunes-add-modal-title" className="tunes-add-modal__title">
                    {title}
                </h2>
                {subtitle && (
                    <p className="tunes-add-modal__subtitle">{subtitle}</p>
                )}
                <p className="tunes-add-modal__prompt">
                    {liked
                        ? `Remove this ${label} from your library?`
                        : `Save this ${label} to your library?`}
                </p>

                <div className="tunes-add-modal__actions">
                    <button
                        ref={confirmBtnRef}
                        type="button"
                        className={
                            'tunes-add-modal__btn '
                            + (liked
                                ? 'tunes-add-modal__btn--danger'
                                : 'tunes-add-modal__btn--primary')
                        }
                        onClick={onConfirm}
                        onKeyDown={(e) => {
                            // Block the held-Enter from the original
                            // long-press until we've armed.
                            if (!armedRef.current && (e.key === 'Enter' || e.key === ' ')) {
                                e.preventDefault();
                                e.stopPropagation();
                            }
                        }}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        data-testid="tunes-add-modal-confirm"
                    >
                        {liked
                            ? (<><X size={16} /> Remove</>)
                            : (<><Heart size={16} fill="currentColor" /> Add</>)
                        }
                    </button>
                    <button
                        type="button"
                        className="tunes-add-modal__btn tunes-add-modal__btn--ghost"
                        onClick={close}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        data-testid="tunes-add-modal-cancel"
                    >
                        <Check size={16} /> Cancel
                    </button>
                </div>
                <p className="tunes-add-modal__hint">
                    Press &amp; hold OK on any tile to open this menu.
                </p>
            </div>
        </div>
    );
}
