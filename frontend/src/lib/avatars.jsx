/**
 * 30 deterministic avatars — emoji on radial-gradient backgrounds.
 * No external images, no API calls; works fully offline.
 *
 * Mix of: animals, fantasy, pro/sports, food, faces, weather, tech —
 * something for every taste.  Each avatar has a unique gradient and
 * a glow ring color tuned to match the artwork.
 */

export const AVATARS = [
    // ---- Animals (15) ----
    { id: 'a1',  e: '🦁', from: '#FF6B6B', to: '#FFD93D', glow: '#FF6B6B' },
    { id: 'a2',  e: '🐯', from: '#FFA940', to: '#FFEC8B', glow: '#FFA940' },
    { id: 'a3',  e: '🐼', from: '#1B1F2E', to: '#FFFFFF', glow: '#FFFFFF' },
    { id: 'a4',  e: '🦊', from: '#FF8C42', to: '#FFD27D', glow: '#FF8C42' },
    { id: 'a5',  e: '🐸', from: '#3DDC97', to: '#A8FF60', glow: '#3DDC97' },
    { id: 'a6',  e: '🐧', from: '#1F2630', to: '#9AC8FF', glow: '#9AC8FF' },
    { id: 'a7',  e: '🐱', from: '#C779D0', to: '#FEAC5E', glow: '#FEAC5E' },
    { id: 'a8',  e: '🐶', from: '#A88758', to: '#F2D2A8', glow: '#F2D2A8' },
    { id: 'a9',  e: '🐵', from: '#B8651A', to: '#FFD27D', glow: '#FFD27D' },
    { id: 'a10', e: '🦄', from: '#FF6BCB', to: '#A78BFA', glow: '#FF6BCB' },
    { id: 'a11', e: '🐺', from: '#475569', to: '#94A3B8', glow: '#94A3B8' },
    { id: 'a12', e: '🦝', from: '#52525B', to: '#A8A29E', glow: '#A8A29E' },
    { id: 'a13', e: '🐨', from: '#71717A', to: '#E4E4E7', glow: '#E4E4E7' },
    { id: 'a14', e: '🦅', from: '#7C2D12', to: '#FBBF24', glow: '#FBBF24' },
    { id: 'a15', e: '🦖', from: '#15803D', to: '#FACC15', glow: '#15803D' },

    // ---- Fantasy / Cool (10) ----
    { id: 'a16', e: '🐉', from: '#16A34A', to: '#FACC15', glow: '#16A34A' },
    { id: 'a17', e: '👻', from: '#8B5CF6', to: '#E0E7FF', glow: '#8B5CF6' },
    { id: 'a18', e: '🤖', from: '#3B82F6', to: '#06B6D4', glow: '#06B6D4' },
    { id: 'a19', e: '👽', from: '#22C55E', to: '#84CC16', glow: '#22C55E' },
    { id: 'a20', e: '🧙', from: '#7C3AED', to: '#F472B6', glow: '#7C3AED' },
    { id: 'a21', e: '🧛', from: '#1E1B4B', to: '#DC2626', glow: '#DC2626' },
    { id: 'a22', e: '🦸', from: '#2563EB', to: '#EF4444', glow: '#EF4444' },
    { id: 'a23', e: '🥷', from: '#0F172A', to: '#475569', glow: '#475569' },
    { id: 'a24', e: '🧟', from: '#365314', to: '#65A30D', glow: '#65A30D' },
    { id: 'a25', e: '🤡', from: '#FF0080', to: '#FFD700', glow: '#FF0080' },

    // ---- Sports (8) ----
    { id: 'a26', e: '⚽', from: '#10B981', to: '#FACC15', glow: '#10B981' },
    { id: 'a27', e: '🏀', from: '#EA580C', to: '#FCD34D', glow: '#EA580C' },
    { id: 'a28', e: '🏈', from: '#92400E', to: '#FBBF24', glow: '#92400E' },
    { id: 'a29', e: '⚾', from: '#FFFFFF', to: '#DC2626', glow: '#DC2626' },
    { id: 'a30', e: '🎾', from: '#84CC16', to: '#FACC15', glow: '#84CC16' },
    { id: 'a31', e: '🏎️', from: '#DC2626', to: '#1F2937', glow: '#DC2626' },
    { id: 'a32', e: '🏆', from: '#FACC15', to: '#FB923C', glow: '#FACC15' },
    { id: 'a33', e: '🥊', from: '#7F1D1D', to: '#F87171', glow: '#7F1D1D' },

    // ---- Music / Profession / Gaming (7) ----
    { id: 'a34', e: '🎮', from: '#EC4899', to: '#8B5CF6', glow: '#EC4899' },
    { id: 'a35', e: '🎧', from: '#06B6D4', to: '#3B82F6', glow: '#06B6D4' },
    { id: 'a36', e: '🎸', from: '#DC2626', to: '#F59E0B', glow: '#DC2626' },
    { id: 'a37', e: '🎺', from: '#B45309', to: '#FBBF24', glow: '#FBBF24' },
    { id: 'a38', e: '🎤', from: '#9333EA', to: '#F472B6', glow: '#F472B6' },
    { id: 'a39', e: '🚀', from: '#F97316', to: '#FACC15', glow: '#F97316' },
    { id: 'a40', e: '🧑‍🚀', from: '#0EA5E9', to: '#7DD3FC', glow: '#7DD3FC' },

    // ---- Funny faces (5) ----
    { id: 'a41', e: '😎', from: '#FBBF24', to: '#F97316', glow: '#FBBF24' },
    { id: 'a42', e: '🤠', from: '#8B5CF6', to: '#FACC15', glow: '#FACC15' },
    { id: 'a43', e: '🥸', from: '#0EA5E9', to: '#A78BFA', glow: '#A78BFA' },
    { id: 'a44', e: '🤓', from: '#10B981', to: '#06B6D4', glow: '#10B981' },
    { id: 'a45', e: '🤪', from: '#FF6BCB', to: '#FACC15', glow: '#FF6BCB' },

    // ---- Symbols / Vibes (5) ----
    { id: 'a46', e: '🌙', from: '#1E3A8A', to: '#7DD3FC', glow: '#7DD3FC' },
    { id: 'a47', e: '🔥', from: '#DC2626', to: '#FACC15', glow: '#FACC15' },
    { id: 'a48', e: '⚡', from: '#06B6D4', to: '#A78BFA', glow: '#06B6D4' },
    { id: 'a49', e: '🎬', from: '#1F2937', to: '#5DC8FF', glow: '#5DC8FF' },
    { id: 'a50', e: '💎', from: '#06B6D4', to: '#F472B6', glow: '#06B6D4' },

    // ---- Kids permanent profile avatar (hidden from picker) ----
    { id: 'kids-default', e: '🧸', from: '#FFC857', to: '#FF6B9D', glow: '#FFC857', hidden: true },
];

export function getAvatar(id) {
    return AVATARS.find((a) => a.id === id) || AVATARS[0];
}

/**
 * Reusable avatar circle renderer.  `size` in px.
 */
export function AvatarCircle({ avatarId, size = 96, ring = false }) {
    const a = getAvatar(avatarId);
    const fontSize = Math.round(size * 0.55);
    return (
        <span
            data-testid={`avatar-${avatarId}`}
            style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: size,
                height: size,
                borderRadius: '50%',
                background: `radial-gradient(circle at 30% 30%, ${a.from}, ${a.to})`,
                boxShadow: ring
                    ? `0 0 0 3px var(--vesper-blue-bright), 0 0 32px 4px ${a.glow}66`
                    : `0 14px 36px -10px ${a.glow}88`,
                fontSize,
                lineHeight: 1,
                userSelect: 'none',
            }}
        >
            {a.e}
        </span>
    );
}
