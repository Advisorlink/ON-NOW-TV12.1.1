/**
 * App version + "What's New" changelog shown once per version when
 * the app opens.  Bump APP_VERSION and prepend a new entry to
 * WHATS_NEW whenever a release ships user-facing changes.
 */
export const APP_VERSION = '1.5.0';

// Most-recent version first.  Only the entry matching APP_VERSION is
// shown in the "What's New" popup.  `icon` = lucide icon name.
export const WHATS_NEW = {
    '1.5.0': {
        date: 'June 2026',
        headline: 'Anime, Box Sets, Studios & a Home screen that\u2019s yours',
        items: [
            {
                icon: 'layers',
                title: 'Box Sets',
                detail: 'A new Box Sets page in the menu \u2014 Batman, Bond, Star Wars, Harry Potter and 30+ more, every film in order. Push and hold a box set to save it to your Library.',
            },
            {
                icon: 'clapperboard',
                title: 'Studios on Home',
                detail: 'Marvel, DC, Disney, Pixar, A24 and more sit in their own Studios row. Open one and browse like the For You page \u2014 trailers, backdrops and all.',
            },
            {
                icon: 'layout-grid',
                title: 'Arrange your Home screen',
                detail: 'Settings \u2192 Home screen: move rows up or down, hide the ones you never use, and reset any time.',
            },
            {
                icon: 'plus-circle',
                title: 'Add your own category',
                detail: 'Pick a genre or type anything \u2014 \u201chorror comedy\u201d, \u201csports movies\u201d, \u201ctime travel\u201d \u2014 and we build the row for you.',
            },
            {
                icon: 'zap',
                title: 'ON NOW TV Direct',
                detail: 'Movies that are in the ON NOW TV library play straight away \u2014 no waiting for links. Depends on what\u2019s in the ON NOW TV library.',
            },
            {
                icon: 'search',
                title: 'Faster search',
                detail: 'Your TV\u2019s own keyboard, a dedicated voice button and live suggestions as you type.',
            },
            {
                icon: 'settings-2',
                title: 'Cleaner Settings',
                detail: 'Categories run down the left; push RIGHT to change anything. Tips now shows the actual tips.',
            },
            {
                icon: 'gift',
                title: 'Hallmark & Christmas',
                detail: 'A Hallmark row next to Christmas on Home, and Hallmark is a choice when you set up a profile.',
            },
            {
                icon: 'sword',
                title: 'Anime',
                detail: 'A full Anime section in the menu \u2014 trending series, movies, airing now, Ghibli, genres and classics \u2014 plus an Anime filter on Movies and TV Shows. Want it on Home? Add it as a category in Settings.',
            },
            {
                icon: 'lightbulb',
                title: 'Tips & tricks',
                detail: 'Settings \u2192 Tips lists every remote shortcut \u2014 push-and-hold to save, UP for the menu, swap links and more.',
            },
            {
                icon: 'eye-off',
                title: 'Trailers everywhere (or nowhere)',
                detail: 'Coming soon now plays trailers like every other row \u2014 and turning Auto-play off removes the trailer window completely.',
            },
            {
                icon: 'users',
                title: 'Easier party hosting',
                detail: 'Host a party now uses your TV\u2019s own keyboard to find the title, with a proper Search button.',
            },
        ],
    },
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
