/**
 * ON NOW TV V2 — theme registry.
 *
 * Each theme keeps the same Vesper layout (cinematic billboard,
 * inky midnight base, neon focus rings) and only swaps the accent
 * colour family.  The provider + Settings page are theme-agnostic
 * — they just paint whatever CSS variables we ship below onto the
 * `<html>` element.
 *
 * Adding a new colour: copy a block, change the accent triplet,
 * pick a complementary bg-0 tint (~6 % saturation, ~6 % lightness)
 * so the page doesn't look like flat black.
 */

const make = (id, name, tagline, accent, bright, glow, bg0) => ({
    id,
    name,
    tagline,
    layout: 'billboard',
    preview: {
        background: `radial-gradient(ellipse at 70% 40%, ${glow.replace('0.55', '0.32')} 0%, rgba(6,8,15,0) 60%), linear-gradient(135deg, ${bg0} 0%, ${bg0}f0 100%)`,
        wordmark: { color: accent, font: 'Geist', weight: 700 },
        accent,
    },
    tokens: {
        '--vesper-bg-0': bg0,
        '--vesper-bg-1': lighten(bg0, 4),
        '--vesper-bg-2': lighten(bg0, 8),
        '--vesper-text': '#F4F8FF',
        '--vesper-text-2': '#A8B5C7',
        '--vesper-text-3': '#67738A',
        '--vesper-blue': accent,
        '--vesper-blue-bright': bright,
        '--vesper-blue-glow': glow,
        // Bare RGB triplet for the accent — lets components do
        // `rgba(var(--vesper-blue-rgb), 0.4)` and have the alpha
        // recolour with the active theme.  Used by Player UI,
        // SideNav accents, hero glows, etc.
        '--vesper-blue-rgb': hexToRgb(accent),
        '--theme-accent': accent,
        '--theme-accent-soft': glow.replace('0.55', '0.14'),
        '--theme-radius': '14px',
        '--theme-font-display':
            '"Geist", "SF Pro Display", system-ui, sans-serif',
        '--theme-font-body':
            '"Geist", "SF Pro Text", system-ui, sans-serif',
        '--theme-font-mono':
            '"JetBrains Mono", "Geist Mono", monospace',
    },
});

/* Tiny utility: bump a hex colour's lightness without pulling in
   another lib.  Good enough for the BG variants we use here. */
function lighten(hex, amount) {
    const n = parseInt(hex.slice(1), 16);
    const r = Math.min(255, ((n >> 16) & 0xff) + amount);
    const g = Math.min(255, ((n >> 8) & 0xff) + amount);
    const b = Math.min(255, (n & 0xff) + amount);
    return (
        '#' +
        [r, g, b]
            .map((c) => c.toString(16).padStart(2, '0'))
            .join('')
    );
}

/* Convert "#RRGGBB" → "R, G, B" so the same accent can drive any
   alpha via `rgba(var(--vesper-blue-rgb), ${alpha})`.  This lets
   every translucent accent (hero glows, player progress fill,
   active-pill backgrounds, etc.) follow the active theme without
   hardcoding the blue triplet at the call site. */
function hexToRgb(hex) {
    const n = parseInt(hex.slice(1), 16);
    const r = (n >> 16) & 0xff;
    const g = (n >> 8) & 0xff;
    const b = n & 0xff;
    return `${r}, ${g}, ${b}`;
}

export const THEMES = [
    make(
        'vesper',
        'Vesper Neon',
        'Inky midnight · neon-blue accents · cinematic billboard',
        '#5DC8FF',
        '#7FD8FF',
        'rgba(93,200,255,0.55)',
        '#06080F'
    ),
    make(
        'magenta',
        'Hot Magenta',
        'Cyber pink · bold and bright · neon nightclub vibe',
        '#FF4DB8',
        '#FF7AC9',
        'rgba(255,77,184,0.55)',
        '#0A0610'
    ),
    make(
        'sunset',
        'Sunset Orange',
        'Warm tangerine · golden glow · arcade marquee energy',
        '#FF8A3D',
        '#FFB270',
        'rgba(255,138,61,0.55)',
        '#0F0805'
    ),
    make(
        'amethyst',
        'Amethyst',
        'Royal purple · velvety midnight · noir thriller mood',
        '#A86CFF',
        '#C39BFF',
        'rgba(168,108,255,0.55)',
        '#0A0612'
    ),
    make(
        'emerald',
        'Emerald',
        'Matrix green · phosphor-screen glow · retro CRT feel',
        '#3DE082',
        '#7CEDA8',
        'rgba(61,224,130,0.55)',
        '#04100A'
    ),
    make(
        'ember',
        'Ember Red',
        'Crimson fire · cinematic drama · big-event red carpet',
        '#FF5151',
        '#FF8585',
        'rgba(255,81,81,0.55)',
        '#100406'
    ),
    make(
        'gold',
        'Gilded Gold',
        'Champagne lux · award-show shine · old Hollywood',
        '#F5C24D',
        '#FFDC85',
        'rgba(245,194,77,0.55)',
        '#0E0A04'
    ),
    make(
        'mint',
        'Mint Frost',
        'Cool aqua-mint · airy and clean · fresh-out-of-the-box',
        '#5CE0D0',
        '#92ECDF',
        'rgba(92,224,208,0.55)',
        '#03100E'
    ),
];

export const DEFAULT_THEME_ID = 'vesper';

export function getTheme(id) {
    return THEMES.find((t) => t.id === id) || THEMES[0];
}
