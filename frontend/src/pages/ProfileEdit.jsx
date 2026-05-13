import React, { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Check } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { saveProfile, listProfiles } from '@/lib/profiles';
import { AVATARS, AvatarCircle } from '@/lib/avatars';

/**
 * Profile create / edit page.
 *  - /profiles/new  → creates a new profile
 *  - /profiles/edit/:id → edits existing (or redirects if not found)
 *
 * 30 avatar choices in a responsive grid, plus a name input.
 * D-pad-driven: focus starts on name field, Tab/Down moves into
 * the avatar grid, Enter on Save persists + returns to picker.
 */
export default function ProfileEdit() {
    useSpatialFocus();
    const navigate = useNavigate();
    const { id: editingId } = useParams();
    const existing = editingId
        ? listProfiles().find((p) => p.id === editingId && !p.kids)
        : null;

    const [name, setName] = useState(existing?.name || '');
    const [avatarId, setAvatarId] = useState(existing?.avatarId || AVATARS[0].id);

    const onSave = () => {
        const trimmed = name.trim() || 'Profile';
        saveProfile({
            id: existing?.id,
            name: trimmed,
            avatarId,
            createdAt: existing?.createdAt,
        });
        navigate('/profiles');
    };

    const visibleAvatars = AVATARS.filter((a) => !a.hidden);

    return (
        <div
            data-testid="profile-edit"
            className="relative w-screen min-h-[100dvh] flex flex-col"
            style={{
                background: 'var(--vesper-bg-0)',
                padding: 'clamp(40px, 5vw, 80px)',
            }}
        >
            <header className="flex items-center gap-4 mb-10">
                <button
                    data-testid="profile-back"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={() => navigate('/profiles')}
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 48,
                        height: 48,
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(255,255,255,0.12)',
                        color: 'var(--vesper-text-2)',
                    }}
                >
                    <ArrowLeft size={20} />
                </button>
                <div>
                    <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.28em', color: 'var(--vesper-blue-bright)' }}>
                        {existing ? 'EDIT PROFILE' : 'NEW PROFILE'}
                    </div>
                    <h1 className="vesper-display" style={{ fontSize: 'clamp(32px, 4vw, 56px)', letterSpacing: '-0.025em', lineHeight: 1, marginTop: 6 }}>
                        Make it yours
                    </h1>
                </div>
            </header>

            <div className="flex items-center gap-8 mb-10">
                <AvatarCircle avatarId={avatarId} size={120} />
                <div className="flex-1">
                    <label
                        className="vesper-mono block"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-3)',
                            marginBottom: 8,
                        }}
                    >
                        Display Name
                    </label>
                    <input
                        data-testid="profile-name"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        type="text"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="e.g. Alex"
                        maxLength={20}
                        className="w-full rounded-lg"
                        style={{
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.16)',
                            color: 'var(--vesper-text)',
                            fontSize: 24,
                            padding: '14px 18px',
                            outline: 'none',
                        }}
                    />
                </div>
                <button
                    data-testid="profile-save"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={onSave}
                    className="flex items-center gap-2 rounded-full font-sans font-semibold"
                    style={{
                        height: 56,
                        padding: '0 28px',
                        fontSize: 16,
                        background: 'var(--vesper-blue)',
                        color: 'var(--vesper-bg-0)',
                    }}
                >
                    <Check size={18} strokeWidth={2.5} />
                    Save
                </button>
            </div>

            <h2
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    color: 'var(--vesper-text-3)',
                    marginBottom: 16,
                }}
            >
                CHOOSE AN AVATAR · {visibleAvatars.length}
            </h2>

            <div
                className="grid"
                style={{
                    gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))',
                    gap: 16,
                }}
            >
                {visibleAvatars.map((a) => {
                    const active = a.id === avatarId;
                    return (
                        <button
                            key={a.id}
                            data-testid={`avatar-pick-${a.id}`}
                            data-focusable="true"
                            data-focus-style="tile"
                            tabIndex={0}
                            onClick={() => setAvatarId(a.id)}
                            className="rounded-full flex items-center justify-center"
                            style={{
                                width: 96,
                                height: 96,
                                border: 'none',
                                padding: 0,
                                background: 'transparent',
                                position: 'relative',
                            }}
                        >
                            <AvatarCircle avatarId={a.id} size={96} ring={active} />
                            {active && (
                                <span
                                    style={{
                                        position: 'absolute',
                                        bottom: -4,
                                        right: -4,
                                        width: 28,
                                        height: 28,
                                        borderRadius: '50%',
                                        background: 'var(--vesper-blue)',
                                        color: 'var(--vesper-bg-0)',
                                        display: 'flex',
                                        alignItems: 'center',
                                        justifyContent: 'center',
                                        boxShadow:
                                            '0 0 0 3px var(--vesper-bg-0), 0 6px 18px rgba(93,200,255,0.6)',
                                    }}
                                >
                                    <Check size={16} strokeWidth={3} />
                                </span>
                            )}
                        </button>
                    );
                })}
            </div>
        </div>
    );
}
