import { isHandheldInput } from './deviceInput';

describe('isHandheldInput', () => {
    test('Android phone UA with OnNowTV suffix remains handheld even when wide', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 OnNowTV/2.19.7',
            coarse: true,
            touchPoints: 5,
            touchEvents: true,
            width: 1920,
        });
        expect(value).toBe(true);
    });

    test('Android tablet coarse + wide is handheld', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (Linux; Android 14; SM-X610) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            coarse: true,
            touchPoints: 5,
            touchEvents: true,
            width: 1920,
        });
        expect(value).toBe(true);
    });

    test('iPad desktop-mac touch UA is handheld', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
            coarse: true,
            touchPoints: 5,
            touchEvents: true,
            width: 1366,
        });
        expect(value).toBe(true);
    });

    test('Android TV UA stays non-handheld', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (Linux; Android 12; Chromecast) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 GoogleTV OnNowTV/2.19.7',
            coarse: true,
            touchPoints: 1,
            touchEvents: true,
            width: 1920,
        });
        expect(value).toBe(false);
    });

    test('SMART-TV UA stays non-handheld', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0)',
            coarse: false,
            touchPoints: 0,
            touchEvents: false,
            width: 1920,
        });
        expect(value).toBe(false);
    });

    test('Amazon Fire TV AFT UA stays non-handheld', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (Linux; Android 11; AFTSS) AppleWebKit/537.36 (KHTML, like Gecko) Silk/112.3.5 like Chrome/112.0.5615.213 Safari/537.36',
            coarse: true,
            touchPoints: 1,
            touchEvents: true,
            width: 1920,
        });
        expect(value).toBe(false);
    });

    test('Desktop non-touch remains non-handheld', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            coarse: false,
            touchPoints: 0,
            touchEvents: false,
            width: 1280,
        });
        expect(value).toBe(false);
    });

    test('Narrow coarse input is handheld fallback', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            coarse: true,
            touchPoints: 1,
            touchEvents: true,
            width: 700,
        });
        expect(value).toBe(true);
    });

    const oldApkBox = {
        userAgent: 'Mozilla/5.0 (Linux; Android 9; HK1 Build/PPR1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36 OnNowTV/2.19.7',
        coarse: true, touchPoints: 1, touchEvents: true, width: 960, height: 540, inApk: true, nativeTv: null,
    };

    test('old APK: landscape box with touch flags (even "Mobile" UA) stays TV until proven otherwise', () => {
        expect(isHandheldInput(oldApkBox)).toBe(false);
        expect(isHandheldInput({ ...oldApkBox, inputSeen: 'key' })).toBe(false);
    });

    test('old APK: first finger on the glass flips to handheld', () => {
        expect(isHandheldInput({ ...oldApkBox, inputSeen: 'touch' })).toBe(true);
    });

    test('old APK: portrait touch screen is handheld immediately', () => {
        expect(isHandheldInput({ ...oldApkBox, width: 412, height: 915 })).toBe(true);
    });

    test('new APK: isTelevision() decides regardless of UA/touch', () => {
        expect(isHandheldInput({ ...oldApkBox, nativeTv: true, inputSeen: 'touch' })).toBe(false);
        expect(isHandheldInput({ ...oldApkBox, nativeTv: false, width: 1280, height: 800 })).toBe(true);
    });

    test('Android box stamped VesperTV stays TV even with touch + coarse', () => {
        const value = isHandheldInput({
            userAgent: 'Mozilla/5.0 (Linux; Android 9; HK1 Build/PPR1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 OnNowTV/2.21.0 VesperTV',
            coarse: true,
            touchPoints: 1,
            touchEvents: true,
            width: 1920,
        });
        expect(value).toBe(false);
    });
});
