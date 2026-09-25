/**
 * ON NOW TV V2 — theme registry (v1.2.0 "full skins").
 *
 * Each entry is no longer just an accent swap — it's a COMPLETE UI
 * personality: its own fonts, background texture, surface treatment,
 * corner language, text palette (incl. light themes) and focus feel.
 *
 * The provider paints these CSS variables onto <html> and also sets
 * `data-theme` + `data-theme-mode`.  The heavy per-skin restyling of
 * shared surfaces (.vesper-glass, poster tiles, focus rings, body
 * backdrop, SideNav) lives in index.css under `html[data-theme="…"]`.
 */

/* "#RRGGBB" → "R, G, B" so alphas can follow the active accent via
   rgba(var(--vesper-blue-rgb), a). */
function hexToRgb(hex) {
    const n = parseInt(hex.slice(1), 16);
    return `${(n >> 16) & 0xff}, ${(n >> 8) & 0xff}, ${n & 0xff}`;
}

const FONTS = {
    aurora: {
        display: '"Outfit", system-ui, sans-serif',
        body: '"Plus Jakarta Sans", system-ui, sans-serif',
        mono: '"JetBrains Mono", monospace',
    },
    brutalist: {
        display: '"Space Grotesk", system-ui, sans-serif',
        body: '"Space Mono", monospace',
        mono: '"Space Mono", monospace',
    },
    synthwave: {
        display: '"Chakra Petch", system-ui, sans-serif',
        body: '"Rajdhani", system-ui, sans-serif',
        mono: '"VT323", monospace',
    },
    paper: {
        display: '"Playfair Display", Georgia, serif',
        body: '"Source Sans 3", system-ui, sans-serif',
        mono: '"Courier Prime", monospace',
    },
    noir: {
        display: '"Cormorant Garamond", Georgia, serif',
        body: '"Inter", system-ui, sans-serif',
        mono: '"Cinzel", serif',
    },
    vapor: {
        display: '"Fredoka", system-ui, sans-serif',
        body: '"Nunito", system-ui, sans-serif',
        mono: '"Comfortaa", system-ui, cursive',
    },
};

function skin(cfg) {
    const {
        id, name, tagline, mode,
        bg0, bg1, bg2, surface, surface2,
        text, text2, text3,
        accent, bright, dim, glow, line, radius, fonts, preview,
    } = cfg;
    return {
        id, name, tagline, mode,
        layout: 'billboard',
        preview,
        tokens: {
            '--vesper-bg-0': bg0,
            '--vesper-bg-1': bg1,
            '--vesper-bg-2': bg2,
            '--vesper-surface': surface,
            '--vesper-surface-2': surface2,
            '--vesper-text': text,
            '--vesper-text-2': text2,
            '--vesper-text-3': text3,
            '--vesper-blue': accent,
            '--vesper-blue-bright': bright,
            '--vesper-blue-dim': dim,
            '--vesper-blue-glow': glow,
            '--vesper-blue-rgb': hexToRgb(accent),
            '--vesper-line': line,
            '--theme-accent': accent,
            '--theme-accent-soft': glow,
            '--theme-radius': radius,
            '--theme-font-display': fonts.display,
            '--theme-font-body': fonts.body,
            '--theme-font-mono': fonts.mono,
        },
    };
}

export const THEMES = [
    skin({
        id: 'aurora-glass',
        name: 'Aurora Glass',
        tagline: 'Deep midnight · frosted neon glass · soft cyan glow',
        mode: 'dark',
        bg0: '#070B14', bg1: '#0D1424', bg2: '#131C30',
        surface: '#141D30', surface2: '#1C2740',
        text: '#F8FAFC', text2: '#94A3B8', text3: '#64748B',
        accent: '#0EA5E9', bright: '#38BDF8', dim: '#0B7BB0',
        glow: 'rgba(14,165,233,0.50)',
        line: 'rgba(255,255,255,0.10)', radius: '16px',
        fonts: FONTS.aurora,
        preview: {
            background:
                'radial-gradient(ellipse at 18% 8%, rgba(14,165,233,0.38) 0%, transparent 55%), radial-gradient(ellipse at 88% 84%, rgba(99,102,241,0.30) 0%, transparent 55%), #070B14',
            accent: '#38BDF8', fg: '#F8FAFC',
            taglineColor: 'rgba(248,250,252,0.72)',
            wordmark: { color: '#38BDF8', font: 'Outfit', weight: 800 },
            radius: 8, sharp: false,
        },
    }),
    skin({
        id: 'brutalist-mono',
        name: 'Brutalist Mono',
        tagline: 'Stark black & white · sharp edges · hard invert focus',
        mode: 'dark',
        bg0: '#050505', bg1: '#0A0A0A', bg2: '#111111',
        surface: '#0A0A0A', surface2: '#161616',
        text: '#FFFFFF', text2: '#A3A3A3', text3: '#5C5C5C',
        accent: '#FFFFFF', bright: '#FFFFFF', dim: '#A3A3A3',
        glow: 'rgba(255,255,255,0.80)',
        line: 'rgba(255,255,255,0.30)', radius: '0px',
        fonts: FONTS.brutalist,
        preview: {
            background:
                'linear-gradient(90deg, rgba(255,255,255,0.06) 1px, transparent 1px) 0 0 / 22px 22px, linear-gradient(0deg, rgba(255,255,255,0.06) 1px, transparent 1px) 0 0 / 22px 22px, #050505',
            accent: '#FFFFFF', fg: '#FFFFFF',
            taglineColor: 'rgba(255,255,255,0.70)',
            wordmark: { color: '#FFFFFF', font: 'Space Grotesk', weight: 700 },
            radius: 0, sharp: true,
        },
    }),
    skin({
        id: 'synthwave-crt',
        name: 'Synthwave CRT',
        tagline: 'Retro neon horizon · scanlines · magenta + cyan haze',
        mode: 'dark',
        bg0: '#120224', bg1: '#1A0730', bg2: '#240038',
        surface: '#1A002C', surface2: '#2A0842',
        text: '#FFE6FF', text2: '#FF80BF', text3: '#A34785',
        accent: '#FF007F', bright: '#00F0FF', dim: '#B3005A',
        glow: 'rgba(255,0,127,0.55)',
        line: 'rgba(255,0,127,0.35)', radius: '8px',
        fonts: FONTS.synthwave,
        preview: {
            background:
                'linear-gradient(180deg, #0A0017 0%, #3A0A52 55%, #7A1B6B 100%)',
            accent: '#00F0FF', fg: '#FFE6FF',
            taglineColor: 'rgba(255,230,255,0.78)',
            wordmark: { color: '#FF3DA6', font: 'Chakra Petch', weight: 700 },
            radius: 4, sharp: false,
        },
    }),
    skin({
        id: 'paper-editorial',
        name: 'Paper Editorial',
        tagline: 'Warm paper · ink serif headlines · magazine calm (light)',
        mode: 'light',
        bg0: '#F4EFE6', bg1: '#FBF8F2', bg2: '#FFFFFF',
        surface: '#FFFFFF', surface2: '#F5F1E8',
        text: '#1C1917', text2: '#57534E', text3: '#78716C',
        accent: '#991B1B', bright: '#B91C1C', dim: '#7F1616',
        glow: 'rgba(153,27,27,0.20)',
        line: 'rgba(28,25,23,0.14)', radius: '4px',
        fonts: FONTS.paper,
        preview: {
            background:
                'radial-gradient(circle at 50% 0%, #FFFDF9 0%, #EFE7D8 100%)',
            accent: '#991B1B', fg: '#1C1917',
            taglineColor: 'rgba(28,25,23,0.66)',
            wordmark: { color: '#1C1917', font: 'Playfair Display', weight: 800 },
            radius: 3, sharp: false,
        },
    }),
    skin({
        id: 'cinema-noir',
        name: 'Cinema Noir',
        tagline: 'Film grain · gold spotlight · classic serif drama',
        mode: 'dark',
        bg0: '#090807', bg1: '#0F0D0B', bg2: '#17140F',
        surface: '#141210', surface2: '#1E1A15',
        text: '#F5F2EB', text2: '#D4CEB8', text3: '#8C8573',
        accent: '#D97706', bright: '#F59E0B', dim: '#A15A05',
        glow: 'rgba(217,119,6,0.50)',
        line: 'rgba(217,119,6,0.22)', radius: '6px',
        fonts: FONTS.noir,
        preview: {
            background:
                'radial-gradient(circle at 50% 26%, rgba(217,119,6,0.30) 0%, transparent 66%), #090807',
            accent: '#F59E0B', fg: '#F5F2EB',
            taglineColor: 'rgba(245,242,235,0.72)',
            wordmark: { color: '#F5C36B', font: 'Cormorant Garamond', weight: 700 },
            radius: 5, sharp: false,
        },
    }),
    skin({
        id: 'vapor-bubble',
        name: 'Vapor Bubble',
        tagline: 'Candy gradients · rounded pills · bouncy playful pop',
        mode: 'dark',
        bg0: '#18122B', bg1: '#1F1638', bg2: '#2E134D',
        surface: '#2A1547', surface2: '#371A5C',
        text: '#FFFFFF', text2: '#F9A8D4', text3: '#A78BFA',
        accent: '#F472B6', bright: '#FB7185', dim: '#BE5591',
        glow: 'rgba(244,114,182,0.55)',
        line: 'rgba(244,114,182,0.30)', radius: '26px',
        fonts: FONTS.vapor,
        preview: {
            background:
                'linear-gradient(135deg, #2E134D 0%, #6D28D9 58%, #DB2777 100%)',
            accent: '#FB7185', fg: '#FFFFFF',
            taglineColor: 'rgba(255,255,255,0.85)',
            wordmark: { color: '#FFFFFF', font: 'Fredoka', weight: 700 },
            radius: 14, sharp: false,
        },
    }),
];

export const DEFAULT_THEME_ID = 'aurora-glass';

export function getTheme(id) {
    return THEMES.find((t) => t.id === id) || THEMES[0];
}
