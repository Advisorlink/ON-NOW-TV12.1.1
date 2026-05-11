import React, { createContext, useContext, useEffect, useState } from 'react';
import { DEFAULT_THEME_ID, THEMES, getTheme } from './themes';

const STORAGE_KEY = 'onnowtv-theme';
const ThemeCtx = createContext({
    themeId: DEFAULT_THEME_ID,
    theme: getTheme(DEFAULT_THEME_ID),
    setThemeId: () => {},
});

export function ThemeProvider({ children }) {
    const [themeId, setThemeId] = useState(() => {
        try {
            const saved = localStorage.getItem(STORAGE_KEY);
            return saved && THEMES.some((t) => t.id === saved)
                ? saved
                : DEFAULT_THEME_ID;
        } catch {
            return DEFAULT_THEME_ID;
        }
    });

    useEffect(() => {
        const theme = getTheme(themeId);
        const root = document.documentElement;

        // Tag <html> with the theme id so CSS can target via
        //   html[data-theme='arcade'] .anything { ... }
        root.setAttribute('data-theme', themeId);

        // Apply CSS tokens
        for (const [k, v] of Object.entries(theme.tokens || {})) {
            root.style.setProperty(k, v);
        }

        // Body font (kept in sync with the body { ... } rule in index.css)
        if (typeof document !== 'undefined') {
            document.body.style.fontFamily =
                theme.tokens['--theme-font-body'] || '';
        }

        // Persist
        try {
            localStorage.setItem(STORAGE_KEY, themeId);
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
