/**
 * 30 deterministic avatars — emoji on radial-gradient backgrounds.
 * No external images, no API calls; works fully offline.
 *
 * Mix of: animals, fantasy, pro/sports, food, faces, weather, tech —
 * something for every taste.  Each avatar has a unique gradient and
 * a glow ring color tuned to match the artwork.
 */

export const AVATARS = [
    // ---- Animals ----
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

    // ---- Fantasy / Cool ----
    { id: 'a11', e: '🐉', from: '#16A34A', to: '#FACC15', glow: '#16A34A' },
    { id: 'a12', e: '👻', from: '#8B5CF6', to: '#E0E7FF', glow: '#8B5CF6' },
    { id: 'a13', e: '🤖', from: '#3B82F6', to: '#06B6D4', glow: '#06B6D4' },
    { id: 'a14', e: '👽', from: '#22C55E', to: '#84CC16', glow: '#22C55E' },
    { id: 'a15', e: '🧙', from: '#7C3AED', to: '#F472B6', glow: '#7C3AED' },
    { id: 'a16', e: '🧛', from: '#1E1B4B', to: '#DC2626', glow: '#DC2626' },
    { id: 'a17', e: '🦸', from: '#2563EB', to: '#EF4444', glow: '#EF4444' },
    { id: 'a18', e: '🥷', from: '#0F172A', to: '#475569', glow: '#475569' },

    // ---- Sports / Profession ----
    { id: 'a19', e: '🎮', from: '#EC4899', to: '#8B5CF6', glow: '#EC4899' },
    { id: 'a20', e: '🎧', from: '#06B6D4', to: '#3B82F6', glow: '#06B6D4' },
    { id: 'a21', e: '⚽', from: '#10B981', to: '#FACC15', glow: '#10B981' },
    { id: 'a22', e: '🚀', from: '#F97316', to: '#FACC15', glow: '#F97316' },
    { id: 'a23', e: '🎸', from: '#DC2626', to: '#F59E0B', glow: '#DC2626' },

    // ---- Faces / Expressions ----
    { id: 'a24', e: '😎', from: '#FBBF24', to: '#F97316', glow: '#FBBF24' },
    { id: 'a25', e: '🤠', from: '#8B5CF6', to: '#FACC15', glow: '#FACC15' },
    { id: 'a26', e: '🥸', from: '#0EA5E9', to: '#A78BFA', glow: '#A78BFA' },

    // ---- Symbols ----
    { id: 'a27', e: '🌙', from: '#1E3A8A', to: '#7DD3FC', glow: '#7DD3FC' },
    { id: 'a28', e: '🔥', from: '#DC2626', to: '#FACC15', glow: '#FACC15' },
    { id: 'a29', e: '⚡', from: '#06B6D4', to: '#A78BFA', glow: '#06B6D4' },
    { id: 'a30', e: '🎬', from: '#1F2937', to: '#5DC8FF', glow: '#5DC8FF' },

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
