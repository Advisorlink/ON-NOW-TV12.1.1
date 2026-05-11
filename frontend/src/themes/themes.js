/**
 * ON NOW TV V2 — theme registry.
 *
 * Currently a single theme (Vesper Neon).  The provider + Settings
 * page are theme-agnostic, so adding more themes later is just a
 * matter of appending entries to `THEMES`.
 */

export const THEMES = [
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
];

export const DEFAULT_THEME_ID = 'vesper';

export function getTheme(id) {
    return THEMES.find((t) => t.id === id) || THEMES[0];
}
