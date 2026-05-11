/**
 * ON NOW TV V2 — theme registry.
 *
 * Each theme controls THREE things:
 *
 *   1. Layout — which Home-page React component is mounted
 *      (vesper / paper / arcade).  Themes that share an architecture
 *      can still vary heavily via tokens.
 *   2. CSS tokens — applied as CSS variables on <html> so every
 *      existing component re-styles itself instantly.
 *   3. Font stack — applied via `font-family` on <body>.
 *
 * Add a new theme by appending an entry to THEMES.  Settings will
 * automatically render a preview tile for it.
 */

export const THEMES = [
    /* ------------------------------------------------------------- */
    /*  THEME 1 — Vesper Neon (default)                              */
    /* ------------------------------------------------------------- */
    {
        id: 'vesper',
        name: 'Vesper Neon',
        tagline: 'Inky midnight · neon-blue accents · cinematic billboard',
        layout: 'billboard',
        preview: {
            background:
                'radial-gradient(ellipse at 70% 40%, rgba(93,200,255,0.32) 0%, rgba(6,8,15,0) 60%), linear-gradient(135deg, #06080F 0%, #0B1322 100%)',
            wordmark: { color: '#5DC8FF', font: 'Geist', weight: 700 },
            accent: '#5DC8FF',
        },
        tokens: {
            '--vesper-bg-0': '#06080F',
            '--vesper-bg-1': '#0B1322',
            '--vesper-bg-2': '#11192B',
            '--vesper-text': '#F4F8FF',
            '--vesper-text-2': '#A8B5C7',
            '--vesper-text-3': '#67738A',
            '--vesper-blue': '#5DC8FF',
            '--vesper-blue-bright': '#7FD8FF',
            '--vesper-blue-glow': 'rgba(93,200,255,0.55)',
            '--theme-accent': '#5DC8FF',
            '--theme-accent-soft': 'rgba(93,200,255,0.14)',
            '--theme-radius': '14px',
            '--theme-font-display':
                '"Geist", "SF Pro Display", system-ui, sans-serif',
            '--theme-font-body':
                '"Geist", "SF Pro Text", system-ui, sans-serif',
            '--theme-font-mono':
                '"JetBrains Mono", "Geist Mono", monospace',
        },
    },

    /* ------------------------------------------------------------- */
    /*  THEME 2 — Paper Cinema (editorial, warm, magazine-style)     */
    /* ------------------------------------------------------------- */
    {
        id: 'paper',
        name: 'Paper Cinema',
        tagline:
            'Cream paper · serif headlines · single-feature editorial · Letterboxd vibes',
        layout: 'paper',
        preview: {
            background:
                'linear-gradient(180deg, #F4ECDC 0%, #E2D5BC 100%)',
            wordmark: { color: '#1F1A14', font: 'Cormorant Garamond', weight: 700 },
            accent: '#B0382C',
        },
        tokens: {
            '--vesper-bg-0': '#F4ECDC',
            '--vesper-bg-1': '#EBE0C7',
            '--vesper-bg-2': '#DCCFB2',
            '--vesper-text': '#1F1A14',
            '--vesper-text-2': '#5A4F40',
            '--vesper-text-3': '#8C7E68',
            '--vesper-blue': '#B0382C',
            '--vesper-blue-bright': '#C9483A',
            '--vesper-blue-glow': 'rgba(176,56,44,0.30)',
            '--theme-accent': '#B0382C',
            '--theme-accent-soft': 'rgba(176,56,44,0.10)',
            '--theme-radius': '4px',
            '--theme-font-display':
                '"Cormorant Garamond", "Playfair Display", Georgia, serif',
            '--theme-font-body':
                '"Source Sans 3", "Source Sans Pro", Georgia, sans-serif',
            '--theme-font-mono':
                '"IBM Plex Mono", "Courier New", monospace',
        },
    },

    /* ------------------------------------------------------------- */
    /*  THEME 3 — Arcade (cyberpunk neon, dense landscape grid)      */
    /* ------------------------------------------------------------- */
    {
        id: 'arcade',
        name: 'Arcade',
        tagline:
            'Pink/cyan cyberpunk · brutalist mono · dense landscape grid · top nav',
        layout: 'arcade',
        preview: {
            background:
                'linear-gradient(135deg, #0A0014 0%, #2A0040 50%, #001A2E 100%)',
            wordmark: { color: '#FF2EAB', font: 'JetBrains Mono', weight: 700 },
            accent: '#FF2EAB',
        },
        tokens: {
            '--vesper-bg-0': '#0A0014',
            '--vesper-bg-1': '#180028',
            '--vesper-bg-2': '#28003E',
            '--vesper-text': '#FBE9FF',
            '--vesper-text-2': '#B79CD0',
            '--vesper-text-3': '#7A6090',
            '--vesper-blue': '#FF2EAB',
            '--vesper-blue-bright': '#FF6ECF',
            '--vesper-blue-glow': 'rgba(255,46,171,0.55)',
            '--theme-accent': '#FF2EAB',
            '--theme-accent-soft': 'rgba(255,46,171,0.16)',
            '--theme-cyan': '#23E5FF',
            '--theme-radius': '2px',
            '--theme-font-display':
                '"JetBrains Mono", "Courier New", monospace',
            '--theme-font-body':
                '"JetBrains Mono", "Courier New", monospace',
            '--theme-font-mono':
                '"JetBrains Mono", "Courier New", monospace',
        },
    },
];

export const DEFAULT_THEME_ID = 'vesper';

export function getTheme(id) {
    return THEMES.find((t) => t.id === id) || THEMES[0];
}
