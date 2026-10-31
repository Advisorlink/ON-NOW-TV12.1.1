import { stremioIdFor } from '@/lib/host';

describe('stremioIdFor', () => {
    it('movies keep the bare imdb id', () => {
        expect(stremioIdFor('movie', 'tt1517268')).toBe('tt1517268');
        expect(stremioIdFor('movie', 'tt1517268:junk')).toBe('tt1517268');
    });
    it('episodes become imdb:season:episode', () => {
        expect(stremioIdFor('series', 'tt0903747:s1e5')).toBe('tt0903747:1:5');
        expect(stremioIdFor('series', 'tt0903747:S02E03')).toBe('tt0903747:2:3');
        expect(stremioIdFor('series', 'tt0903747:1:5')).toBe('tt0903747:1:5');
    });
    it('unknown ids give nothing (player keeps the single link)', () => {
        expect(stremioIdFor('series', 'tt0903747')).toBe('');
        expect(stremioIdFor('movie', 'abc')).toBe('');
        expect(stremioIdFor('movie', '')).toBe('');
    });
});
