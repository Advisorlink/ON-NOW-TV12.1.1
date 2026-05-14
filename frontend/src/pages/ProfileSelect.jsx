import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Pencil, Lock } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import {
    listProfiles,
    setActiveProfile,
    removeProfile,
    profileHasPin,
    checkProfilePin,
} from '@/lib/profiles';
import { AvatarCircle } from '@/lib/avatars';
import PinGate from '@/components/PinGate';

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
                            onRemove={() => {
                                removeProfile(p.id);
                                refresh();
                            }}
                        />
                    ))}
                    {profiles.filter((p) => !p.kids).length < 4 && (
                        <AddProfileTile onClick={() => navigate('/profiles/new')} />
                    )}
                </div>

                <button
                    data-testid="manage-profiles"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => setEditMode((v) => !v)}
                    className="flex items-center gap-2 mt-12 rounded-full"
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
            </div>

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
