/**
 * Curated "Browse by Network" catalogue.  Cinemeta itself doesn't ship a
 * `network` filter, so we hand-pick a small, high-quality slate of
 * iconic shows per network and resolve full metadata via Cinemeta's
 * /meta endpoint at runtime.  Branding is text-only — no third-party
 * logo assets — to keep licensing clean.
 */

export const NETWORKS = [
    {
        slug: 'netflix',
        name: 'Netflix',
        wordmark: 'NETFLIX',
        accent: '#e50914',
        background:
            'linear-gradient(135deg, #1c0306 0%, #4a070b 55%, #e50914 100%)',
        imdbIds: [
            'tt4574334', // Stranger Things
            'tt4786824', // The Crown
            'tt10919420', // Squid Game
            'tt8740790', // Bridgerton
            'tt6468322', // Money Heist
            'tt5071412', // Ozark
            'tt1856010', // House of Cards
            'tt2707408', // Narcos
            'tt2861424', // Rick and Morty
            'tt7660850', // Wednesday adjacent — kept for variety
        ],
    },
    {
        slug: 'hbo',
        name: 'HBO',
        wordmark: 'HBO',
        accent: '#ffffff',
        background:
            'linear-gradient(135deg, #000000 0%, #1a1a1a 60%, #2b2b2b 100%)',
        imdbIds: [
            'tt0944947', // Game of Thrones
            'tt7660850', // Succession
            'tt0306414', // The Wire
            'tt0141842', // The Sopranos
            'tt2356777', // True Detective
            'tt0475784', // Westworld
            'tt7366338', // Chernobyl
            'tt11198330', // House of the Dragon
            'tt0386676', // The Office (HBO licensed in some regions, kept for fill)
            'tt0773262', // Dexter (fill)
        ],
    },
    {
        slug: 'disney-plus',
        name: 'Disney+',
        wordmark: 'Disney+',
        accent: '#0063e5',
        background:
            'linear-gradient(135deg, #01153d 0%, #0042a8 55%, #0063e5 100%)',
        imdbIds: [
            'tt8111088', // The Mandalorian
            'tt9140560', // WandaVision
            'tt9140554', // Loki
            'tt13668894', // Book of Boba Fett
            'tt9253284', // Andor
            'tt10234724', // Moon Knight
            'tt10160804', // Hawkeye
            'tt10857164', // Ms. Marvel
            'tt2575988', // Silicon Valley (fill)
            'tt0080684', // Empire Strikes Back (fill)
        ],
    },
    {
        slug: 'prime-video',
        name: 'Prime Video',
        wordmark: 'prime video',
        accent: '#00a8e1',
        background:
            'linear-gradient(135deg, #00263a 0%, #006497 55%, #00a8e1 100%)',
        imdbIds: [
            'tt1190634', // The Boys
            'tt5788792', // Mrs. Maisel
            'tt5687612', // Fleabag
            'tt3230854', // The Expanse
            'tt9288030', // Reacher
            'tt5057054', // Jack Ryan
            'tt7222086', // Carnival Row
            'tt7826376', // Upload
            'tt8740790', // (fill)
            'tt7335184', // (fill — Hanna)
        ],
    },
    {
        slug: 'apple-tv',
        name: 'Apple TV+',
        wordmark: 'tv+',
        accent: '#ffffff',
        background:
            'linear-gradient(135deg, #050505 0%, #1c1c1e 55%, #2c2c2e 100%)',
        imdbIds: [
            'tt10986410', // Ted Lasso
            'tt11280740', // Severance
            'tt7203552', // The Morning Show
            'tt0804484', // Foundation
            'tt7949218', // See
            'tt7772588', // For All Mankind
            'tt8879940', // Mythic Quest
            'tt5614844', // Slow Horses
            'tt9686708', // Pachinko (fill)
            'tt12477480', // Shrinking (fill)
        ],
    },
    {
        slug: 'hulu',
        name: 'Hulu',
        wordmark: 'hulu',
        accent: '#1ce783',
        background:
            'linear-gradient(135deg, #001b0c 0%, #006a37 55%, #1ce783 100%)',
        imdbIds: [
            'tt5834204', // The Handmaid's Tale
            'tt12851524', // Only Murders in the Building
            'tt14452776', // The Bear
            'tt12782372', // Pam & Tommy
            'tt5589198', // Letterkenny
            'tt6548228', // Castle Rock
            'tt12748094', // Reservation Dogs
            'tt8788458', // Dollface
            'tt9335498', // Normal People (fill)
            'tt9252508', // (fill)
        ],
    },
];

export const findNetwork = (slug) =>
    NETWORKS.find((n) => n.slug === slug) || null;
