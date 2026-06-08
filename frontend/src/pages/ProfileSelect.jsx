import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Pencil, Lock, Download } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import {
    listProfiles,
    setActiveProfile,
    removeProfile,
    profileHasPin,
    checkProfilePin,
    getKidsConfig,
} from '@/lib/profiles';
import { AvatarCircle } from '@/lib/avatars';
import PinGate from '@/components/PinGate';
import { API } from '@/lib/api';

/**
 * Netflix-style "Who's watching?" profile picker.  Shown on every
 * app launch (App.js routes / → here if no active profile).
 * Includes the always-present Kids profile at the end + an
 * "Add profile" tile (max 4 user profiles for clean UI).
 */
export default function ProfileSelect() {
    useSpatialFocus();
    const navigate = useNavigate();
    const [profiles, setProfiles] = useState(listProfiles());
    const [editMode, setEditMode] = useState(false);
    const [pinTarget, setPinTarget] = useState(null);   // profile awaiting PIN
    const [pinError, setPinError] = useState('');
    const [pinReset, setPinReset] = useState(0);
    // Profile awaiting "are you sure?" delete confirmation.  Stored
    // as the full profile object so the modal can show the name +
    // avatar in the prompt.
    const [removeTarget, setRemoveTarget] = useState(null);
    // Profile awaiting PIN entry before delete confirmation can
    // be shown.  Only used when the target has a PIN set.
    const [deletePinTarget, setDeletePinTarget] = useState(null);
    const [deletePinError, setDeletePinError] = useState('');
    const [deletePinReset, setDeletePinReset] = useState(0);

    // v2.10.5 — Warm the Kids backend cache while the user is
    // choosing their profile.  Both the kids shelves and heroes
    // endpoints are slow on cold cache (24-36 parallel TMDB calls
    // each).  Firing the fetch here means by the time the user
    // clicks a kids tile, the backend's 6-hour cache is already
    // hot and KidsHome renders almost instantly.  Fire-and-forget
    // — no need to wait for the response.
    useEffect(() => {
        try {
            const cfg = getKidsConfig();
            const q = new URLSearchParams({
                movie_cert: cfg.maxRatingMovie,
                tv_level: cfg.maxRatingSeries,
            }).toString();
            // Use `keepalive` so the request survives even if
            // the user clicks fast and the page begins unmounting.
            fetch(`${API}/tmdb/kids/shelves?${q}`, { keepalive: true }).catch(() => {});
            fetch(`${API}/tmdb/kids/heroes?${q}`, { keepalive: true }).catch(() => {});
        } catch { /* ignore */ }
    }, []);

    const activate = (id) => {
        setActiveProfile(id);
        navigate('/');
    };

    const pick = (p) => {
        if (editMode) {
            navigate(`/profiles/edit/${p.id}`);
            return;
        }
        if (profileHasPin(p)) {
            setPinError('');
            setPinTarget(p);
            return;
        }
        activate(p.id);
    };

    const requestRemove = (p) => {
        // Gate delete behind the profile's PIN.  Without this, a
        // PIN-protected profile could be wiped (along with its
        // library and watch-progress) just by pressing Remove on
        // the manage-profiles screen.
        if (profileHasPin(p)) {
            setDeletePinError('');
            setDeletePinTarget(p);
            return;
        }
        setRemoveTarget(p);
    };

    const onDeletePinSubmit = (entered) => {
        if (!deletePinTarget) return;
        if (checkProfilePin(deletePinTarget, entered)) {
            const target = deletePinTarget;
            setDeletePinTarget(null);
            setRemoveTarget(target);
        } else {
            setDeletePinError('Incorrect PIN. Try again.');
            setDeletePinReset((x) => x + 1);
        }
    };

    const onPinSubmit = (entered) => {
        if (!pinTarget) return;
        if (checkProfilePin(pinTarget, entered)) {
            const id = pinTarget.id;
            setPinTarget(null);
            activate(id);
        } else {
            setPinError('Incorrect PIN. Try again.');
            setPinReset((x) => x + 1);
        }
    };

    const refresh = () => setProfiles(listProfiles());

    return (
        <div
            data-testid="profile-select"
            className="relative w-screen h-[100dvh] flex flex-col items-center"
            style={{
                background:
                    'radial-gradient(circle at 50% 30%, rgba(var(--vesper-blue-rgb),0.18) 0%, transparent 60%), var(--vesper-bg-0)',
            }}
        >
            {/* Logo stays anchored near the top. */}
            <div
                className="vesper-display flex items-baseline justify-center select-none"
                style={{
                    paddingTop: 'clamp(48px, 6vh, 88px)',
                    letterSpacing: '-0.03em',
                    lineHeight: 1,
                }}
            >
                <span
                    style={{
                        fontSize: 'clamp(26px, 2.6vw, 38px)',
                        fontWeight: 700,
                        color: 'var(--vesper-text)',
                    }}
                >
                    ON NOW T
                </span>
                <span
                    style={{
                        fontSize: 'clamp(28px, 2.8vw, 42px)',
                        fontWeight: 800,
                        color: 'var(--vesper-blue-bright)',
                        textShadow:
                            '0 0 10px rgba(var(--vesper-blue-rgb), 0.7), 0 0 24px rgba(var(--vesper-blue-rgb), 0.45)',
                        letterSpacing: '-0.04em',
                        marginLeft: 1,
                    }}
                >
                    V2
                </span>
            </div>

            {/* Headline + profiles centred in the remaining space so
                the user's focal point lands roughly on the middle
                of the TV screen — much more relaxed than crowding
                everything near the top. */}
            <div
                className="flex-1 flex flex-col items-center justify-center w-full"
                style={{ paddingBottom: 'clamp(40px, 6vh, 80px)' }}
            >
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(40px, 5vw, 72px)',
                        letterSpacing: '-0.03em',
                        lineHeight: 1,
                        marginBottom: 8,
                    }}
                >
                    Who&apos;s ready to watch?
                </h1>
                <p
                    style={{
                        color: 'var(--vesper-text-2)',
                        fontSize: 16,
                        marginBottom: 48,
                    }}
                >
                    Choose a profile to continue
                </p>

                <div
                    className="flex items-start justify-center flex-wrap"
                    style={{ gap: 'clamp(24px, 3vw, 56px)', maxWidth: 1200 }}
                >
                    {profiles.map((p) => (
                        <ProfileTile
                            key={p.id}
                            profile={p}
                            editMode={editMode}
                            onPick={() => pick(p)}
                            onRemove={() => requestRemove(p)}
                        />
                    ))}
                    {/* No profile cap — user explicitly asked for
                        unlimited.  AddProfileTile always renders. */}
                    <AddProfileTile onClick={() => navigate('/profiles/new')} />
                </div>

                <div
                    style={{
                        display: 'flex',
                        flexWrap: 'wrap',
                        gap: 12,
                        marginTop: 48,
                        justifyContent: 'center',
                    }}
                >
                    <button
                        data-testid="manage-profiles"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => setEditMode((v) => !v)}
                        className="flex items-center gap-2 rounded-full"
                        style={{
                            height: 44,
                            padding: '0 22px',
                            fontSize: 14,
                            fontWeight: 600,
                            background: editMode
                                ? 'var(--vesper-blue)'
                                : 'rgba(255,255,255,0.08)',
                            color: editMode ? 'var(--vesper-bg-0)' : 'var(--vesper-text-2)',
                            border: '1px solid rgba(255,255,255,0.16)',
                        }}
                    >
                        <Pencil size={14} strokeWidth={2} />
                        {editMode ? 'Done' : 'Manage profiles'}
                    </button>
                    <button
                        data-testid="load-existing-profile"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => navigate('/profiles/load')}
                        className="flex items-center gap-2 rounded-full"
                        style={{
                            height: 44,
                            padding: '0 22px',
                            fontSize: 14,
                            fontWeight: 600,
                            background: 'rgba(93,200,255,0.10)',
                            color: 'var(--vesper-blue-bright)',
                            border: '1px solid rgba(93,200,255,0.36)',
                        }}
                    >
                        <Download size={14} strokeWidth={2.2} />
                        Load existing profile
                    </button>
                </div>
            </div>

            {removeTarget && (
                <DeleteProfileConfirm
                    profile={removeTarget}
                    onCancel={() => setRemoveTarget(null)}
                    onConfirm={() => {
                        removeProfile(removeTarget.id);
                        setRemoveTarget(null);
                        refresh();
                    }}
                />
            )}

            {deletePinTarget && (
                <PinGate
                    title={`Enter ${deletePinTarget.name}'s PIN to delete`}
                    subtitle="PIN required to remove this profile"
                    onSuccess={onDeletePinSubmit}
                    onCancel={() => setDeletePinTarget(null)}
                    error={deletePinError}
                    resetSignal={deletePinReset}
                />
            )}

            {pinTarget && (
                <PinGate
                    title={`Enter ${pinTarget.name}'s PIN`}
                    subtitle="4-digit PIN required to switch into this profile"
                    onSuccess={onPinSubmit}
                    onCancel={() => setPinTarget(null)}
                    error={pinError}
                    resetSignal={pinReset}
                />
            )}
        </div>
    );
}

function ProfileTile({ profile, editMode, onPick, onRemove }) {
    const locked = profileHasPin(profile);
    return (
        <div className="flex flex-col items-center" style={{ width: 152 }}>
            <button
                data-testid={`profile-${profile.id}`}
                data-focusable="true"
                data-focus-style="tile"
                data-initial-focus={profile.id !== 'kids' ? 'true' : undefined}
                tabIndex={0}
                onClick={onPick}
                className="rounded-full relative"
                style={{
                    width: 130,
                    height: 130,
                    border: 'none',
                    padding: 0,
                    background: 'transparent',
                }}
            >
                <AvatarCircle avatarId={profile.avatarId} size={130} />
                {locked && (
                    <span
                        data-testid={`profile-lock-${profile.id}`}
                        style={{
                            position: 'absolute',
                            bottom: -2,
                            right: -2,
                            width: 36,
                            height: 36,
                            borderRadius: '50%',
                            background: 'rgba(6,8,15,0.92)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            border: '2px solid var(--vesper-blue)',
                            boxShadow: '0 0 14px var(--vesper-blue-glow)',
                            color: 'var(--vesper-blue-bright)',
                        }}
                    >
                        <Lock size={16} strokeWidth={2.4} />
                    </span>
                )}
            </button>
            <div
                className="vesper-display"
                style={{
                    marginTop: 16,
                    fontSize: 19,
                    letterSpacing: '-0.01em',
                    color: profile.kids
                        ? 'var(--vesper-blue-bright)'
                        : 'var(--vesper-text)',
                }}
            >
                {profile.name}
            </div>
            {profile.kids && (
                <div
                    className="vesper-mono"
                    style={{
                        marginTop: 4,
                        fontSize: 10,
                        letterSpacing: '0.24em',
                        color: 'var(--vesper-text-3)',
                    }}
                >
                    KID SAFE
                </div>
            )}
            {editMode && !profile.kids && (
                <button
                    data-testid={`remove-${profile.id}`}
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={onRemove}
                    style={{
                        marginTop: 10,
                        fontSize: 12,
                        padding: '4px 12px',
                        borderRadius: 999,
                        background: 'rgba(239,68,68,0.16)',
                        color: '#FCA5A5',
                        border: '1px solid rgba(239,68,68,0.32)',
                    }}
                >
                    Remove
                </button>
            )}
        </div>
    );
}

function AddProfileTile({ onClick }) {
    return (
        <div className="flex flex-col items-center" style={{ width: 152 }}>
            <button
                data-testid="profile-add"
                data-focusable="true"
                data-focus-style="tile"
                tabIndex={0}
                onClick={onClick}
                className="rounded-full flex items-center justify-center"
                style={{
                    width: 130,
                    height: 130,
                    background: 'rgba(255,255,255,0.05)',
                    border: '2px dashed rgba(255,255,255,0.20)',
                    color: 'var(--vesper-text-3)',
                }}
            >
                <Plus size={48} strokeWidth={1.5} />
            </button>
            <div
                className="vesper-display"
                style={{
                    marginTop: 16,
                    fontSize: 19,
                    letterSpacing: '-0.01em',
                    color: 'var(--vesper-text-2)',
                }}
            >
                Add Profile
            </div>
        </div>
    );
}


function DeleteProfileConfirm({ profile, onCancel, onConfirm }) {
    const cancelRef = React.useRef(null);

    // Imperatively focus the Cancel button on mount so the modal
    // opens with focus *on* the safe action (Cancel) — not on the
    // tile behind it.  Several retries defeat the in-flight Enter
    // release from the Remove click that opened the modal.
    React.useEffect(() => {
        const grab = () => {
            const btn = cancelRef.current;
            if (!btn) return;
            const modal = document.querySelector(
                '[data-testid="delete-profile-confirm"]'
            );
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (!modal || !modal.contains(el))
                        el.removeAttribute('data-focused');
                });
            try { btn.focus({ preventScroll: true }); } catch { /* ignore */ }
            btn.setAttribute('data-focused', 'true');
        };
        grab();
        const r = requestAnimationFrame(grab);
        const t1 = setTimeout(grab, 50);
        const t2 = setTimeout(grab, 150);
        return () => {
            cancelAnimationFrame(r);
            clearTimeout(t1);
            clearTimeout(t2);
        };
    }, []);

    // Escape/Backspace cancels.
    React.useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                onCancel();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [onCancel]);

    return (
        <div
            data-testid="delete-profile-confirm"
            className="fixed inset-0 z-[70] flex items-center justify-center"
            style={{
                background: 'rgba(6,8,15,0.78)',
                backdropFilter: 'blur(8px)',
                WebkitBackdropFilter: 'blur(8px)',
                padding: 24,
            }}
            onClick={(e) => {
                // Backdrop click closes the modal.
                if (e.target === e.currentTarget) onCancel();
            }}
        >
            <div
                className="flex flex-col items-center"
                style={{
                    background: 'rgba(11,19,34,0.96)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    borderRadius: 24,
                    padding: '40px 56px 36px',
                    minWidth: 420,
                    maxWidth: 540,
                    boxShadow:
                        '0 30px 80px rgba(0,0,0,0.55), 0 0 0 1px rgba(var(--vesper-blue-rgb),0.18)',
                }}
            >
                <div style={{ marginBottom: 18 }}>
                    <AvatarCircle avatarId={profile.avatarId} size={92} />
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: '#FCA5A5',
                        textTransform: 'uppercase',
                        marginBottom: 6,
                    }}
                >
                    Delete profile
                </div>
                <h2
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(24px, 2.4vw, 32px)',
                        letterSpacing: '-0.02em',
                        lineHeight: 1.1,
                        textAlign: 'center',
                        marginBottom: 10,
                    }}
                >
                    Are you sure you want to delete &ldquo;{profile.name}&rdquo;?
                </h2>
                <p
                    style={{
                        color: 'var(--vesper-text-2)',
                        fontSize: 14,
                        lineHeight: 1.5,
                        textAlign: 'center',
                        marginBottom: 26,
                        maxWidth: 380,
                    }}
                >
                    All of this profile&apos;s library, watch later list, and
                    progress will be gone for good. This can&apos;t be undone.
                </p>
                <div className="flex" style={{ gap: 12 }}>
                    <button
                        ref={cancelRef}
                        data-testid="delete-profile-cancel"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={onCancel}
                        className="rounded-full font-sans font-semibold"
                        style={{
                            height: 48,
                            padding: '0 26px',
                            fontSize: 15,
                            background: 'rgba(255,255,255,0.10)',
                            color: 'var(--vesper-text)',
                            border: '1px solid rgba(255,255,255,0.16)',
                        }}
                    >
                        Cancel
                    </button>
                    <button
                        data-testid="delete-profile-confirm-btn"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onConfirm}
                        className="rounded-full font-sans font-semibold"
                        style={{
                            height: 48,
                            padding: '0 26px',
                            fontSize: 15,
                            background: '#DC2626',
                            color: '#fff',
                            border: '1px solid rgba(239,68,68,0.5)',
                            boxShadow:
                                '0 8px 24px rgba(220,38,38,0.35)',
                        }}
                    >
                        Yes, delete
                    </button>
                </div>
            </div>
        </div>
    );
}
