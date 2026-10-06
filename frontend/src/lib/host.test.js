const setUserAgent = (ua) => {
    Object.defineProperty(window.navigator, 'userAgent', {
        value: ua,
        configurable: true,
    });
};

jest.mock('@/lib/streamMeta', () => ({
    sizeLabel: () => '',
}), { virtual: true });

describe('Host.playVideo bridge selection', () => {
    beforeEach(() => {
        jest.resetModules();
        window.OnNowTV = undefined;
        setUserAgent('Mozilla/5.0 (Linux; Android 14; Pixel) OnNowTV/2.20.0');
    });

    test('prefers playMedia JSON bridge and dispatches correlation event', () => {
        const playMedia = jest.fn((requestId) => {
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: { requestId, status: 'opened' },
            }));
        });
        window.OnNowTV = {
            isAndroidHost: () => true,
            playMedia,
            playInternalRichV2: jest.fn(),
        };

        const events = [];
        const listener = (e) => events.push(e.detail);
        window.addEventListener('vesper:native-playback', listener);

        const Host = require('./host').default;
        const launched = Host.playVideo({
            url: 'https://cdn.example.com/stream.m3u8',
            title: 'The Màtrix 🧪',
            type: 'movie',
            subtitleUrl: '',
            poster: null,
            backdrop: undefined,
            synopsis: undefined,
            year: 1999,
            rating: null,
            runtime: undefined,
            genres: ['Action', 'Sci-Fi'],
            startAtMs: 1234,
            cwId: 'tt0133093',
            streamsList: [
                { title: 'Direct1080p', url: 'https://cdn.example.com/stream.m3u8', _is_english: true },
                { title: 'Torrent', infoHash: 'abc123' },
            ],
        });

        expect(launched).toBe(true);
        expect(playMedia).toHaveBeenCalledTimes(1);
        const [requestId, payloadRaw] = playMedia.mock.calls[0];
        expect(requestId).toMatch(/^play-/);
        const payload = JSON.parse(payloadRaw);
        expect(payload.title).toBe('The Màtrix 🧪');
        expect(payload.cwId).toBe('tt0133093');
        expect(payload.startAtMs).toBe(1234);
        expect(payload.subtitleUrl).toBeNull();
        expect(payload.poster).toBeNull();
        expect(payload.backdrop).toBeNull();
        expect(payload.currentStreamIdx).toBe(0);
        expect(payload.streamsJson).toContain('Direct1080p');
        expect(payload.streamsJson).not.toContain('abc123');
        expect(events[0]?.status).toBe('dispatching');

        window.removeEventListener('vesper:native-playback', listener);
    });

    test('resolves bridge at call time after module import', () => {
        const Host = require('./host').default;
        const playMedia = jest.fn();
        window.OnNowTV = { isAndroidHost: () => true, playMedia };

        const ok = Host.playVideo({ url: 'https://cdn.example.com/a.m3u8', title: 'Late Bridge' });
        expect(ok).toBe(true);
        expect(playMedia).toHaveBeenCalledTimes(1);
    });

    test('a native rejection emits a correlated failure instead of silently falling back', () => {
        window.OnNowTV = { isAndroidHost: () => true, playMedia: jest.fn(() => false) };
        const events = [];
        const listener = (event) => events.push(event.detail);
        window.addEventListener('vesper:native-playback', listener);
        const Host = require('./host').default;
        expect(Host.playVideo({ url: 'https://cdn.example.com/a.m3u8', title: 'Rejected' })).toBe(true);
        expect(events.map((event) => event.status)).toEqual(['dispatching', 'failed']);
        expect(events[1].requestId).toBe(events[0].requestId);
        expect(events[1].message).toContain('host is unavailable');
        window.removeEventListener('vesper:native-playback', listener);
    });

    test('falls back to playInternalRichV2 when playMedia is unavailable', () => {
        const playInternalRichV2 = jest.fn();
        window.OnNowTV = {
            isAndroidHost: () => true,
            playInternalRichV2,
        };

        const Host = require('./host').default;
        const ok = Host.playVideo({ url: 'https://cdn.example.com/legacy.m3u8', title: 'Legacy' });
        expect(ok).toBe(true);
        expect(playInternalRichV2).toHaveBeenCalledTimes(1);
    });

    test('party launch path still uses playInternalParty unchanged', () => {
        const playInternalParty = jest.fn();
        const playMedia = jest.fn();
        window.OnNowTV = {
            isAndroidHost: () => true,
            playInternalParty,
            playMedia,
        };

        const Host = require('./host').default;
        const ok = Host.playVideo({
            url: 'https://cdn.example.com/party.m3u8',
            title: 'Party',
            partyCode: 'AB12CD',
            partyRole: 'host',
        });
        expect(ok).toBe(true);
        expect(playInternalParty).toHaveBeenCalledTimes(1);
        expect(playMedia).not.toHaveBeenCalled();
    });
});
