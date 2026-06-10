/**
 * Avatar library — v2.10.21
 *
 * Two render paths under one hood:
 *   1. Hand-illustrated character PNGs (~77 icons) categorised into
 *      Funny, Anime, Gamer, Sports, Animals, Movie Night.  These
 *      are shipped as local WebP files under /avatars/<id>.webp —
 *      no DiceBear / no network round-trip, so the picker loads
 *      instantly even on offline Android TV boxes.
 *   2. A small curated set of emoji-on-gradient avatars (16 fan
 *      favourites) preserved so users who picked an emoji before
 *      the icon overhaul keep their look and so a few quick
 *      "vibe" choices stay available.
 *
 * Backwards-compat: existing profiles store `avatarId` strings.
 * The legacy `a1…a100`, `m1…m6`, `cartoon-…`, `adventurer-…`,
 * `pixel-…` ids are routed through `getAvatar()` to a sane default
 * when the matching record no longer exists.
 */

import Host from '@/lib/host';

/* ----- 1. Curated EMOJI avatars (16 favourites kept) ------------- */

const EMOJI_AVATARS = [
    // Vibes & symbols — the most "personality" of the emoji set.
    { id: 'a47', e: '🔥', from: '#DC2626', to: '#FACC15', glow: '#FACC15' },
    { id: 'a48', e: '⚡', from: '#06B6D4', to: '#A78BFA', glow: '#06B6D4' },
    { id: 'a49', e: '🎬', from: '#1F2937', to: '#5DC8FF', glow: '#5DC8FF' },
    { id: 'a50', e: '💎', from: '#06B6D4', to: '#F472B6', glow: '#06B6D4' },
    { id: 'a46', e: '🌙', from: '#1E3A8A', to: '#7DD3FC', glow: '#7DD3FC' },
    { id: 'a39', e: '🚀', from: '#F97316', to: '#FACC15', glow: '#F97316' },

    // Funny faces — kept because they read instantly on small tiles.
    { id: 'a41', e: '😎', from: '#FBBF24', to: '#F97316', glow: '#FBBF24' },
    { id: 'a42', e: '🤠', from: '#8B5CF6', to: '#FACC15', glow: '#FACC15' },
    { id: 'a45', e: '🤪', from: '#FF6BCB', to: '#FACC15', glow: '#FF6BCB' },
    { id: 'a17', e: '👻', from: '#8B5CF6', to: '#E0E7FF', glow: '#8B5CF6' },

    // Magic / Cards — last of the legacy `m*` ids.
    { id: 'm1', e: '🎩', from: '#1F0036', to: '#9333EA', glow: '#9333EA' },
    { id: 'm4', e: '🔮', from: '#581C87', to: '#22D3EE', glow: '#A78BFA' },
    { id: 'm6', e: '✨', from: '#7C3AED', to: '#FBBF24', glow: '#FBBF24' },

    // Rainbow, unicorn, music — universal "kid-friendly" picks.
    { id: 'a10', e: '🦄', from: '#FF6BCB', to: '#A78BFA', glow: '#FF6BCB' },
    { id: 'a80', e: '🌈', from: '#F472B6', to: '#22D3EE', glow: '#F472B6' },
    { id: 'a35', e: '🎧', from: '#06B6D4', to: '#3B82F6', glow: '#06B6D4' },
];

/* ----- 2. ICON avatars (hand-illustrated PNG portraits) ----------- */

/* v2.10.23 — Resolve the avatar URL via `Host.publicAsset()` so
 * the same path works under both the live preview (http://) and
 * the bundled APK WebView (file:///android_asset/web/index.html).
 *
 * Bug we hit: an absolute path like `/avatars/<id>.jpg` resolves
 * to `file:///avatars/<id>.jpg` (filesystem root, broken) on the
 * sideloaded APK because there's no server to interpret `/` as
 * the bundled web root.  `Host.publicAsset()` resolves against
 * `document.baseURI` so the WebView finds the file at
 * `file:///android_asset/web/avatars/<id>.jpg`.
 *
 * This was the silent killer behind the "gradient circles with no
 * icons" bug on the projector — the WebView delivered transparent
 * 404s because it was looking in the wrong place.
 */
const icon = (id, glow) => ({
    id,
    src: Host.publicAsset(`avatars/${id}.jpg`),
    glow,
});

/* Per-category glow colour — sets the soft ring/shadow tone behind
 * the portrait so the picker reads as a "row of light pucks" rather
 * than a flat grid of stickers.  Picked by hand against each PNG.
 */
const ICON_AVATARS = {
    funny: [
        icon('fn-popcorn-fg',    '#FBBF24'),
        icon('fn-popcorn-tu',    '#F59E0B'),
        icon('fn-monster',       '#A78BFA'),
        icon('fn-monster-mascot','#C084FC'),
        icon('fn-slime',         '#22D3EE'),
        icon('fn-soda',          '#EF4444'),
        icon('fn-noodle',        '#F97316'),
        icon('fn-alien-neon',    '#10B981'),
        icon('fn-alien-pixel',   '#22D3EE'),
        icon('fn-alien-badge',   '#A78BFA'),
        icon('fn-cactus-alien',  '#84CC16'),
    ],
    anime: [
        icon('an-samurai',       '#DC2626'),
        icon('an-magical-girl',  '#F472B6'),
        icon('an-cyber-youth',   '#22D3EE'),
        icon('an-gothic',        '#DC2626'),
        icon('an-icy',           '#7DD3FC'),
        icon('an-boy-confident', '#F59E0B'),
        icon('an-boy-neon',      '#22D3EE'),
        icon('an-pastel-girl',   '#F472B6'),
        icon('an-dreamy-girl',   '#A78BFA'),
        icon('an-vibrant',       '#06B6D4'),
        icon('an-idol-cheer',    '#FBBF24'),
        icon('an-idol-concert',  '#EC4899'),
        icon('an-pop-idol',      '#F472B6'),
        icon('an-idol-pastel',   '#A78BFA'),
    ],
    gamer: [
        icon('gm-assassin',         '#22D3EE'),
        icon('gm-cyber-neon',       '#A78BFA'),
        icon('gm-electric',         '#5DC8FF'),
        icon('gm-green-gamer',      '#22C55E'),
        icon('gm-ape',              '#FACC15'),
        icon('gm-android',          '#22D3EE'),
        icon('gm-cyber-portrait',   '#A78BFA'),
        icon('gm-cyber-portrait2',  '#22D3EE'),
        icon('gm-cyborg',           '#06B6D4'),
        icon('gm-cyborg-neon',      '#A78BFA'),
        icon('gm-cat-gamer',        '#F472B6'),
        icon('gm-neon-cyber',       '#22D3EE'),
        icon('gm-neon-dynamic',     '#EC4899'),
        icon('gm-gamer-neon',       '#A78BFA'),
        icon('gm-chibi-gamer',      '#5DC8FF'),
        icon('gm-gamer-pro',        '#06B6D4'),
        icon('gm-robot-sleek',      '#06B6D4'),
        icon('gm-robot-cute',       '#FACC15'),
        icon('gm-skull',            '#A78BFA'),
    ],
    sports: [
        icon('sp-cricket',    '#FACC15'),
        icon('sp-basketball', '#EA580C'),
        icon('sp-soccer',     '#10B981'),
        icon('sp-baseball',   '#DC2626'),
        icon('sp-boxing',     '#7F1D1D'),
        icon('sp-football',   '#F97316'),
        icon('sp-golf',       '#FACC15'),
        icon('sp-surf',       '#F472B6'),
        icon('sp-tennis',     '#22C55E'),
    ],
    animals: [
        icon('an-lion',         '#FBBF24'),
        icon('an-tiger',        '#F97316'),
        icon('an-panda',        '#FFFFFF'),
        icon('an-koala',        '#94A3B8'),
        icon('an-penguin',      '#5DC8FF'),
        icon('an-pup',          '#F472B6'),
        icon('an-cat-orange',   '#FB923C'),
        icon('an-cat-black',    '#A78BFA'),
        icon('an-cat-tabby',    '#FBBF24'),
        icon('an-cat-smug',     '#F59E0B'),
        icon('an-fox-clever',   '#FB923C'),
        icon('an-fox-cosmic',   '#A78BFA'),
        icon('an-fox-fire',     '#EF4444'),
        icon('an-fox-hero',     '#DC2626'),
        icon('an-wolf-ice',     '#5DC8FF'),
        icon('an-wolf-silver',  '#94A3B8'),
        icon('an-owl-geo',      '#22D3EE'),
        icon('an-owl-gold',     '#FACC15'),
        icon('an-racc-hero',    '#F472B6'),
        icon('an-racc-super',   '#A78BFA'),
        icon('an-sloth-sunset', '#FB923C'),
        icon('an-sloth-hood',   '#FACC15'),
        icon('an-sloth-dream',  '#A78BFA'),
        icon('an-sloth-smug',   '#F59E0B'),
    ],
};

/* ----- 3. Categories used by the picker -------------------------- */

export const AVATAR_CATEGORIES = [
    { id: 'funny',   label: 'Funny',       items: ICON_AVATARS.funny },
    { id: 'anime',   label: 'Anime',       items: ICON_AVATARS.anime },
    { id: 'gamer',   label: 'Gamer',       items: ICON_AVATARS.gamer },
    { id: 'sports',  label: 'Sports',      items: ICON_AVATARS.sports },
    { id: 'animals', label: 'Animals',     items: ICON_AVATARS.animals },
    { id: 'emoji',   label: 'Quick Vibes', items: EMOJI_AVATARS },
];

/* Flat list of every avatar.  Order matches AVATAR_CATEGORIES. */
export const AVATARS = AVATAR_CATEGORIES.flatMap((c) => c.items);

/**
 * Look up a single avatar emoji character by id (e.g. 'a47' → '🔥').
 * Returns '🎬' if no match (the new icon avatars don't carry an
 * emoji, so reactions fall back to the film-clapper for those).
 */
export function avatarEmojiById(id) {
    if (!id) return '🎬';
    const av = AVATARS.find((a) => a.id === id);
    if (av && av.e) return av.e;
    return '🎬';
}

/* Synthetic Kids profile avatar (hidden from picker). */
const KIDS_AVATAR = {
    id: 'kids-default',
    e: '🧸',
    from: '#FFC857',
    to: '#FF6B9D',
    glow: '#FFC857',
    hidden: true,
};
AVATARS.push(KIDS_AVATAR);

/* ----- 4. Custom user-built DiceBear avatars --------------------- */

const DICEBEAR = 'https://api.dicebear.com/9.x';
const CUSTOM_KEY = 'onnowtv-custom-avatars-v1';

/**
 * Curated option palette for the in-app "Build your own" avatar
 * builder.  Each group is rendered as a chip row in the wizard;
 * the chosen value lands in the URLSearchParams used to call the
 * DiceBear `avataaars` endpoint.  Keeping the palette curated (and
 * smaller than DiceBear's full menu) means the picker stays
 * readable on a TV without paging.
 */
export const AVATAR_BUILDER_OPTIONS = {
    top: [
        'shortFlat', 'shortWaved', 'shortRound', 'shortCurly',
        'frizzle', 'shaggy', 'shaggyMullet', 'shavedSides',
        'theCaesar', 'theCaesarAndSidePart', 'sides',
        'bigHair', 'curly', 'curvy', 'straight01', 'straight02',
        'straightAndStrand', 'bob', 'bun', 'longButNotTooLong',
        'miaWallace', 'fro', 'froBand', 'dreads', 'dreads01', 'dreads02',
        'hat', 'hijab', 'turban', 'winterHat02', 'winterHat03', 'winterHat04',
    ],
    hairColor: [
        '2c1b18', '4a312c', '724133', 'a55728', 'b58143',
        'd6b370', 'ecdcbf', 'f59797', 'c93305', '0e0e0e',
    ],
    eyes: [
        'default', 'happy', 'side', 'wink', 'winkWacky',
        'squint', 'surprised', 'hearts', 'cry', 'closed', 'eyeRoll',
    ],
    eyebrows: [
        'default', 'defaultNatural', 'raisedExcited', 'raisedExcitedNatural',
        'unibrowNatural', 'upDown', 'upDownNatural', 'angryNatural',
        'flatNatural', 'sadConcerned', 'sadConcernedNatural',
    ],
    mouth: [
        'default', 'smile', 'twinkle', 'tongue', 'serious',
        'sad', 'screamOpen', 'eating', 'concerned', 'disbelief', 'grimace',
    ],
    facialHair: [
        'blank', 'beardLight', 'beardMajestic', 'beardMedium',
        'moustacheFancy', 'moustacheMagnum',
    ],
    accessories: [
        'blank', 'kurt', 'prescription01', 'prescription02',
        'round', 'sunglasses', 'wayfarers', 'eyepatch',
    ],
    skinColor: [
        'edb98a', 'ffdbb4', 'fd9841', 'd08b5b', 'ae5d29', '614335',
    ],
    backgroundColor: [
        '4f46e5', '7c3aed', 'ec4899', 'f59e0b', '10b981', '06b6d4',
        '1e293b', '000000',
    ],
};

/**
 * Build the avataaars PNG URL for a set of builder choices.  Any
 * undefined option falls back to a sensible default so the avatar
 * never returns a 400.
 */
export function buildCustomDiceBearUrl(opts = {}) {
    const params = new URLSearchParams({
        seed: opts.seed || `custom-${Date.now()}`,
        size: '160',
        radius: '50',
        backgroundType: 'solid',
    });
    if (opts.top)              params.set('top', opts.top);
    if (opts.hairColor)        params.set('hairColor', opts.hairColor);
    if (opts.eyes)             params.set('eyes', opts.eyes);
    if (opts.eyebrows)         params.set('eyebrows', opts.eyebrows);
    if (opts.mouth)            params.set('mouth', opts.mouth);
    if (opts.facialHair && opts.facialHair !== 'blank') {
        params.set('facialHair', opts.facialHair);
    } else {
        params.set('facialHairProbability', '0');
    }
    if (opts.accessories && opts.accessories !== 'blank') {
        params.set('accessories', opts.accessories);
        params.set('accessoriesProbability', '100');
    } else {
        params.set('accessoriesProbability', '0');
    }
    if (opts.skinColor)        params.set('skinColor', opts.skinColor);
    if (opts.backgroundColor)  params.set('backgroundColor', opts.backgroundColor);
    return `${DICEBEAR}/avataaars/png?${params.toString()}`;
}

/** Read every locally-saved custom avatar from storage. */
export function loadCustomAvatars() {
    try {
        const raw = localStorage.getItem(CUSTOM_KEY);
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

/**
 * Persist a new custom avatar.  Returns the stored record so the
 * caller can immediately reference it on the new profile.
 */
export function saveCustomAvatar(opts) {
    const id = `custom-${Math.random().toString(36).slice(2, 10)}`;
    const src = buildCustomDiceBearUrl({ ...opts, seed: id });
    const record = {
        id,
        src,
        glow: '#' + (opts.backgroundColor || '06b6d4'),
        options: opts,
        createdAt: Date.now(),
    };
    try {
        const existing = loadCustomAvatars();
        existing.push(record);
        localStorage.setItem(CUSTOM_KEY, JSON.stringify(existing));
    } catch { /* ignore quota */ }
    return record;
}

export function getAvatar(id) {
    return (
        AVATARS.find((a) => a.id === id) ||
        loadCustomAvatars().find((a) => a.id === id) ||
        // Legacy fallback for any pre-v2.10.21 profile that picked
        // an `a1…a100` / `m*` / `cartoon-*` / `adventurer-*` /
        // `pixel-*` id that no longer exists.  Use the first icon
        // (popcorn finger-guns) so the avatar circle stays vibrant
        // rather than reverting to a sad lion emoji.
        AVATARS[0]
    );
}

/**
 * Reusable circular avatar.  Two render paths:
 *   - Emoji avatars: emoji glyph on a radial gradient (offline-safe).
 *   - Image avatars: full-bleed PNG/WebP character portrait.
 *
 * v2.8.89 — Optional `srcOverride` lets remote viewers render a
 * custom DiceBear avatar even when the avatar isn't in their local
 * `loadCustomAvatars()` store.  Watch Together broadcasts the
 * sender's full `avatar_src` along with the `avatarId`, so the
 * dock can pass it here directly.  Without this, custom avatars
 * fell back to the default `a1` (lion) because the receiver's
 * `getAvatar(id)` lookup returned `AVATARS[0]`.
 */
export function AvatarCircle({ avatarId, srcOverride, size = 96, ring = false }) {
    const a = getAvatar(avatarId);
    const effectiveSrc = srcOverride || a.src;
    const isImage = !!effectiveSrc;
    const fontSize = Math.round(size * 0.55);

    const baseStyle = {
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        position: 'relative',
        width: size,
        height: size,
        borderRadius: '50%',
        overflow: 'hidden',
        boxShadow: ring
            ? `0 0 0 3px var(--vesper-blue-bright), 0 0 32px 4px ${a.glow}66`
            : `0 14px 36px -10px ${a.glow}88`,
        userSelect: 'none',
    };

    if (isImage) {
        return (
            <span
                data-testid={`avatar-${avatarId}`}
                style={{
                    ...baseStyle,
                    background: `radial-gradient(circle at 30% 30%, ${a.glow}33, rgba(0,0,0,0.6))`,
                }}
            >
                <img
                    src={effectiveSrc}
                    alt=""
                    decoding="async"
                    draggable={false}
                    style={{
                        position: 'absolute',
                        inset: 0,
                        width: '100%',
                        height: '100%',
                        objectFit: 'cover',
                        display: 'block',
                        pointerEvents: 'none',
                    }}
                    onError={(e) => {
                        e.currentTarget.style.visibility = 'hidden';
                    }}
                />
            </span>
        );
    }

    return (
        <span
            data-testid={`avatar-${avatarId}`}
            style={{
                ...baseStyle,
                background: `radial-gradient(circle at 30% 30%, ${a.from}, ${a.to})`,
                fontSize,
                lineHeight: 1,
            }}
        >
            {a.e}
        </span>
    );
}
