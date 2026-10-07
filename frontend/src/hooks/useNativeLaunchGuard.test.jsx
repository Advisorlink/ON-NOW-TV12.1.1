import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import useNativeLaunchGuard from './useNativeLaunchGuard';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('native playback launch lifecycle', () => {
    let root, node, gate;
    function Harness({ scope = 'movie:A', enabled = true }) {
        gate = useNativeLaunchGuard(scope, enabled);
        return null;
    }
    beforeEach(() => {
        jest.useFakeTimers();
        node = document.createElement('div');
        document.body.appendChild(node);
        root = createRoot(node);
        act(() => root.render(<Harness />));
    });
    afterEach(() => {
        act(() => root.unmount());
        node.remove();
        jest.useRealTimers();
    });
    test('blocks duplicate launches while a native handoff is in flight', () => {
        let first, second;
        act(() => { first = gate.begin(); });
        act(() => jest.advanceTimersByTime(2000));
        act(() => { second = gate.begin(); });
        expect(first).toBe(true);
        expect(second).toBe(false);
        expect(gate.busy).toBe(true);
    });
    test('return from native player permits explicit replay', () => {
        act(() => { gate.begin(); window.dispatchEvent(new Event('blur')); });
        act(() => jest.advanceTimersByTime(20000));
        expect(gate.error).toBe('');
        act(() => window.dispatchEvent(new Event('focus')));
        expect(gate.busy).toBe(false);
        let replay;
        act(() => { replay = gate.begin(); });
        expect(replay).toBe(true);
    });
    test('button focus alone cannot unlock the native handoff', () => {
        act(() => gate.begin());
        act(() => window.dispatchEvent(new Event('focus')));
        expect(gate.busy).toBe(true);
    });
    test('failed handoff has visible feedback and permits retry', () => {
        act(() => gate.begin());
        act(() => jest.advanceTimersByTime(8001));
        expect(gate.busy).toBe(false);
        expect(gate.error).toMatch(/player did not open/);
        let retry;
        act(() => { retry = gate.begin(); });
        expect(retry).toBe(true);
        expect(gate.error).toBe('');
    });
    test('changing title cancels old watchdog and launch lock', () => {
        act(() => gate.begin());
        act(() => root.render(<Harness scope="movie:B" />));
        act(() => jest.advanceTimersByTime(9000));
        expect(gate.busy).toBe(false);
        expect(gate.error).toBe('');
    });
    test('opened ack cancels watchdog even if blur never fires', () => {
        act(() => gate.begin());
        const requestId = 'play-req-1';
        act(() => {
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: { requestId, status: 'dispatching' },
            }));
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: { requestId, status: 'opened' },
            }));
        });
        act(() => jest.advanceTimersByTime(9000));
        expect(gate.error).toBe('');
        expect(gate.busy).toBe(true);
    });
    test('ignores failed events for a different request id', () => {
        act(() => gate.begin());
        act(() => {
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: { requestId: 'play-main', status: 'dispatching' },
            }));
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: {
                    requestId: 'play-other',
                    status: 'failed',
                    message: 'other launch failed',
                },
            }));
        });
        expect(gate.error).toBe('');
        expect(gate.busy).toBe(true);
    });
    test('failed ack for current request shows message and allows retry', () => {
        let replay;
        act(() => gate.begin());
        act(() => {
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: { requestId: 'play-main-2', status: 'dispatching' },
            }));
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: {
                    requestId: 'play-main-2',
                    status: 'failed',
                    message: 'explicit native failure',
                },
            }));
        });
        expect(gate.error).toBe('explicit native failure');
        expect(gate.busy).toBe(false);
        act(() => {
            replay = gate.begin();
        });
        expect(replay).toBe(true);
    });
    test('returned event unlocks and permits replay', () => {
        let replay;
        act(() => gate.begin());
        act(() => {
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: { requestId: 'play-main-3', status: 'dispatching' },
            }));
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: { requestId: 'play-main-3', status: 'opened' },
            }));
            window.dispatchEvent(new CustomEvent('vesper:native-playback', {
                detail: { requestId: 'play-main-3', status: 'returned' },
            }));
        });
        expect(gate.busy).toBe(false);
        act(() => { replay = gate.begin(); });
        expect(replay).toBe(true);
    });
    test('late failed after a transient returned (engine-retry cascade) still shows the error', () => {
        const fire = (status, message) => window.dispatchEvent(new CustomEvent('vesper:native-playback', {
            detail: { requestId: 'play-main-4', status, message },
        }));
        act(() => gate.begin());
        act(() => { fire('dispatching'); fire('received'); fire('launched'); fire('retrying', 'init-error'); fire('returned'); });
        expect(gate.busy).toBe(false);
        expect(gate.error).toBe('');
        act(() => fire('failed', 'The player could not initialise on this device (IllegalStateException).'));
        expect(gate.error).toMatch(/could not initialise/);
        let retry;
        act(() => { retry = gate.begin(); });
        expect(retry).toBe(true);
        expect(gate.error).toBe('');
    });

    test('ordinary browser navigation is not native-locked', () => {
        act(() => root.render(<Harness enabled={false} />));
        let first, second;
        act(() => { first = gate.begin(); second = gate.begin(); });
        expect(first && second).toBe(true);
        expect(gate.busy).toBe(false);
    });
    test('unmount clears watchdog timers', () => {
        act(() => gate.begin());
        act(() => root.render(null));
        expect(jest.getTimerCount()).toBe(0);
    });
});