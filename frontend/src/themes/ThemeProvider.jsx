import React, { createContext, useContext, useEffect, useState } from 'react';
import { DEFAULT_THEME_ID, THEMES, getTheme } from './themes';
import { readScopedString, writeScopedString } from '@/lib/profileScope';

const STORAGE_KEY = 'onnowtv-theme';
const ThemeCtx = createContext({
    themeId: DEFAULT_THEME_ID,
    theme: getTheme(DEFAULT_THEME_ID),
    setThemeId: () => {},
});

function loadTheme() {
    try {
        const saved = readScopedString(STORAGE_KEY);
        return saved && THEMES.some((t) => t.id === saved)
            ? saved
            : DEFAULT_THEME_ID;
    } catch {
        return DEFAULT_THEME_ID;
    }
}

export function ThemeProvider({ children }) {
    const [themeId, setThemeId] = useState(loadTheme);

    // Listen for profile switches so the theme follows the active
    // profile.  Profiles.js fires `vesper:profile-change`; we also
    // pick up `storage` events when a sibling tab switches profile.
    useEffect(() => {
        const sync = () => setThemeId(loadTheme());
        window.addEventListener('vesper:profile-change', sync);
        window.addEventListener('storage', sync);
        return () => {
            window.removeEventListener('vesper:profile-change', sync);
            window.removeEventListener('storage', sync);
        };
    }, []);

    useEffect(() => {
        const theme = getTheme(themeId);
        const root = document.documentElement;
        root.setAttribute('data-theme', themeId);
        for (const [k, v] of Object.entries(theme.tokens || {})) {
            root.style.setProperty(k, v);
        }
        if (typeof document !== 'undefined') {
            document.body.style.fontFamily =
                theme.tokens['--theme-font-body'] || '';
        }
        try {
            writeScopedString(STORAGE_KEY, themeId);
        } catch {
            /* incognito / disk full / etc. */
        }
    }, [themeId]);

    return (
        <ThemeCtx.Provider
            value={{
                themeId,
                theme: getTheme(themeId),
                setThemeId,
            }}
        >
            {children}
        </ThemeCtx.Provider>
    );
}

export function useTheme() {
    return useContext(ThemeCtx);
}
