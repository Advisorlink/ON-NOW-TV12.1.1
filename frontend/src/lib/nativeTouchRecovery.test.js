import fs from 'fs';
import path from 'path';

// Test the exact asset shipped in the APK, not a reimplementation.
const source = fs.readFileSync(path.resolve(process.cwd(), '../android/vesper-tv/app/src/main/assets/touch-playback.js'), 'utf8');

describe('Android playback hit-test recovery asset', () => {
    let api, button, click;
    beforeEach(() => {
        document.body.innerHTML = '<button data-testid="detail-play-autoplay" data-native-activation="playback">Play</button>';
        button = document.querySelector('button');
        click = jest.fn();
        button.addEventListener('click', click);
        Object.defineProperty(document, 'hidden', { configurable: true, value: false });
        Object.defineProperty(window, 'visualViewport', { configurable: true, value: { width: 1024, height: 768, scale: 1, offsetLeft: 0, offsetTop: 0 } });
        document.elementFromPoint = jest.fn(() => button);
        window.history.replaceState({}, '', '/');
        delete window.__vesperLastPlaybackActivation;
        // eslint-disable-next-line no-new-func
        api = new Function(`return ${source}`)();
    });
    afterEach(() => { document.body.innerHTML = ''; });

    test('recovers a missing DOM activation via the same button click as the remote', () => {
        const before = api.probe(100, 100, 1024, 768);
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('recovered');
        expect(click).toHaveBeenCalledTimes(1);
        expect(document.activeElement).toBe(button);
    });
    test('an accepted normal action is never repeated', () => {
        const before = api.probe(100, 100, 1024, 768);
        button.setAttribute('data-native-activation-version', '1');
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('already-handled');
        expect(click).not.toHaveBeenCalled();
    });
    test('slow probe completed after normal input cannot replay it', () => {
        window.__vesperLastPlaybackActivation = 1200;
        const before = { ...api.probe(100, 100, 1024, 768), touchAt: 1000 };
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('already-handled');
        expect(click).not.toHaveBeenCalled();
    });
    test('an earlier unrelated gesture does not block a new recovery', () => {
        window.__vesperLastPlaybackActivation = 900;
        const before = { ...api.probe(100, 100, 1024, 768), touchAt: 1000 };
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('recovered');
    });
    test('navigation between raw Android DOWN and delayed probe cancels', () => {
        window.history.replaceState({}, '', '/second');
        const before = { ...api.probe(100, 100, 1024, 768), nativeHref: `${window.location.origin}/first` };
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('cancelled');
        expect(click).not.toHaveBeenCalled();
    });
    test('replaced stream row with same test ID is a different target', () => {
        const before = api.probe(100, 100, 1024, 768);
        const replacement = button.cloneNode(true);
        button.replaceWith(replacement);
        document.elementFromPoint = jest.fn(() => replacement);
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('different-target');
    });
    test('disabled and covered controls are not activated', () => {
        const before = api.probe(100, 100, 1024, 768);
        button.disabled = true;
        expect(api.probe(100, 100, 1024, 768)).toBeNull();
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('different-target');
        document.elementFromPoint = jest.fn(() => document.body);
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('different-target');
        expect(click).not.toHaveBeenCalled();
    });
    test('background pages and missing snapshots are cancelled', () => {
        const before = api.probe(100, 100, 1024, 768);
        Object.defineProperty(document, 'hidden', { configurable: true, value: true });
        expect(api.activate(before, 100, 100, 1024, 768)).toBe('cancelled');
        expect(api.activate(null, 100, 100, 1024, 768)).toBe('cancelled');
    });
    test('pinch-zoom and viewport offsets are left to ordinary browser input', () => {
        window.visualViewport.scale = 2;
        expect(api.probe(100, 100, 1024, 768)).toBeNull();
        window.visualViewport.scale = 1;
        window.visualViewport.offsetLeft = 20;
        expect(api.probe(100, 100, 1024, 768)).toBeNull();
    });
    test('native physical pixels map to CSS pixels', () => {
        api.probe(300, 600, 3072, 2304);
        expect(document.elementFromPoint).toHaveBeenLastCalledWith(100, 200);
    });
});