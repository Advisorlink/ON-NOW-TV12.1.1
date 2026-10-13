/**
 * App version + "What's New" changelog shown once per version when
 * the app opens.  Bump APP_VERSION and prepend a new entry to
 * WHATS_NEW whenever a release ships user-facing changes.
 */
export const APP_VERSION = '1.5.2';

// Most-recent version first.  Only the entry matching APP_VERSION is
// shown in the "What's New" popup.  `icon` = lucide icon name.
export const WHATS_NEW = {
    '1.5.2': {
        date: 'October 2026',
        headline: 'Smarter picks, a tidier Library and more control in the player',
        items: [
            {
                icon: 'eye', accent: '#7CF1A1',
                title: 'Know the copy before you press play',
                detail: 'Stop on a cover and, once its trailer starts, we check how many links exist. A small tag appears on the preview: Cam copy, Watchable cam copy or HD copy — with the live count beside it.',
                how: 'It only searches while the trailer is playing and stops the moment you move on.',
            },
            {
                icon: 'list-ordered', accent: '#5DC8FF',
                title: 'Best source first, every time',
                detail: 'Links now line up as ON NOW TV Direct → Torrentio → MediaFusion → EasyNews++ → everything else, and within each source the best-sized 1080p copy (1–4 GB) leads.',
                how: 'Autoplay follows the same order, so the first link is the one most likely to play instantly.',
            },
            {
                icon: 'clapperboard', accent: '#FFD36B',
                title: 'Three choices on a playing trailer',
                detail: 'Press OK while a trailer is playing to get Open Trailer, Play Movie (or Play Show) and Add to Library, stacked and easy to reach.',
                how: 'UP / DOWN moves between them, LEFT / RIGHT or BACK returns to the row.',
            },
            {
                icon: 'library', accent: '#C4A3FF',
                title: 'Library, reorganised',
                detail: 'Watch Later now sits at the top as a rail of widescreen cards with the title logo, followed by TV Shows, Movies, My Box Sets and My Actors.',
                how: 'Hold OK on a Watch Later card to remove it.',
            },
            {
                icon: 'film', accent: '#FF8FAB',
                title: 'Movies get their own shelf',
                detail: 'Add to Library on a movie now asks where it goes: your Movies shelf, your Watch Later queue, or both.',
            },
            {
                icon: 'layers', accent: '#6BE3E3',
                title: 'Show more at the end of a row',
                detail: 'Studio and box-set rows end with a Show more card. Each press reveals the next batch and keeps paging through the whole catalogue.',
                how: 'Focus lands on the first new title so you can keep scrolling right.',
            },
            {
                icon: 'info', accent: '#FF9B6B',
                title: 'See which link is playing',
                detail: 'The (i) panel in the player now shows the exact source and link name under NOW PLAYING, next to the buffer health — with a Swap stream button right there.',
            },
            {
                icon: 'gauge', accent: '#9FE870',
                title: 'Smoother on the box',
                detail: 'Rows load only when they are on screen, covers are lighter and the big artwork waits until you stop moving — tuned on real TV hardware.',
            },
        ],
    },
    '1.5.1': {
        date: 'October 2026',
        headline: 'Your latest updates, all in one place',
        items: [
            { icon: 'movement', title: 'Smoother browsing', detail: 'Fluid rows and top menu, even with trailers on.' },
            { icon: 'layers', title: 'Box Sets', detail: 'Films in order. Hold a set to save it to Library.' },
            { icon: 'clapperboard', title: 'Studios on Home', detail: 'Marvel, Disney, Pixar and more, with trailers.' },
            { icon: 'layout-grid', title: 'Arrange Home', detail: 'Move, hide or reset rows in Settings.' },
            { icon: 'plus-circle', title: 'Your own categories', detail: 'Choose a genre or type a theme for a new row.' },
            { icon: 'zap', title: 'ON NOW TV Direct', detail: 'Direct play for movies in the ON NOW TV library.' },
            { icon: 'search', title: 'Faster search', detail: 'TV keyboard, voice search and live suggestions.' },
            { icon: 'settings-2', title: 'Cleaner Settings', detail: 'An easier menu with Home and Playback sections.' },
            { icon: 'gift', title: 'Hallmark & Christmas', detail: 'Add either category from Home settings.' },
            { icon: 'sword', title: 'Anime', detail: 'A dedicated hub. Add its Home row in Settings.' },
            { icon: 'lightbulb', title: 'Tips & tricks', detail: 'Remote shortcuts together in Settings → Tips.' },
            { icon: 'eye-off', title: 'Trailers your way', detail: 'Coming Soon previews; turn trailers off to hide them.' },
            { icon: 'users', title: 'Easier party hosting', detail: 'Find your party title using the TV keyboard.' },
            { icon: 'gift', title: 'One update popup', detail: 'Every update on this screen, without scrolling.' },
        ],
    },
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
