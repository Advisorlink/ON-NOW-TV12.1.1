/**
 * App version + "What's New" changelog shown once per version when
 * the app opens.  Bump APP_VERSION and prepend a new entry to
 * WHATS_NEW whenever a release ships user-facing changes.
 */
export const APP_VERSION = '1.1.0';

// Most-recent version first.  Only the entry matching APP_VERSION is
// shown in the "What's New" popup.
export const WHATS_NEW = {
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
