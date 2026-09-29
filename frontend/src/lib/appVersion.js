/**
 * App version + "What's New" changelog shown once per version when
 * the app opens.  Bump APP_VERSION and prepend a new entry to
 * WHATS_NEW whenever a release ships user-facing changes.
 */
export const APP_VERSION = '1.4.0';

// Most-recent version first.  Only the entry matching APP_VERSION is
// shown in the "What's New" popup.
export const WHATS_NEW = {
    '1.4.0': {
        date: 'June 2026',
        items: [
            {
                title: 'New home screen layout',
                detail: 'Covers slide through a fixed trailer window on every row — trailers start the moment you land on a title.',
            },
            {
                title: 'Menu moved to the top',
                detail: 'Push UP from the top row to reach it. Prefer the old side rail? Switch it back in Settings → Playback → “Top menu bar”.',
            },
            {
                title: 'Trailers in full HD',
                detail: 'Play Full Screen expands the trailer in place and continues in 1080p from where it was up to. Back returns to the small version.',
            },
            {
                title: 'Search redesigned',
                detail: 'Recent searches run across the top and the keyboard fits the screen properly.',
            },
            {
                title: 'Correct episode every time',
                detail: 'Choosing an episode now plays that exact episode — no more stray episodes from other seasons.',
            },
            {
                title: 'Continue Watching fixed',
                detail: 'Episodes you finish in the player are ticked off, and removed titles stay removed.',
            },
            {
                title: 'Player polish',
                detail: 'Swap links opens on the first OK press, the scrub bar is easier to see and works with a mouse, and Back never exits the app from a trailer.',
            },
        ],
    },
    '1.1.0': {
        date: 'June 2026',
        items: [
            {
                title: 'Continue Watching fixed',
                detail: 'Shows stay put and vanish for good the moment you remove them.',
            },
            {
                title: 'Turn subtitles off — for good',
                detail: 'In the player, choose “Off” and pick to disable subtitles permanently or just this once.',
            },
            {
                title: 'Cinema tags',
                detail: 'Movies showing in cinemas right now get a clean CINEMA badge on the cover.',
            },
        ],
    },
};
