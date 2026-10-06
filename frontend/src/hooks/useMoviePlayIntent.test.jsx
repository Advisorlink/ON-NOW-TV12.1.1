import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import useMoviePlayIntent from './useMoviePlayIntent';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function HookHarness(props) {
    const state = useMoviePlayIntent(props);
    React.useEffect(() => {
        props.onState(state);
    }, [state, props]);
    return null;
}

describe('useMoviePlayIntent', () => {
    let container;
    let root;
    let latestState;

    const renderHarness = async (props) => {
        await act(async () => {
            root.render(
                <HookHarness
                    {...props}
                    onState={(next) => {
                        latestState = next;
                    }}
                />
            );
        });
    };

    beforeEach(() => {
        container = document.createElement('div');
        document.body.appendChild(container);
        root = createRoot(container);
        latestState = null;
    });

    afterEach(async () => {
        await act(async () => {
            root.unmount();
        });
        container.remove();
    });

    test('launches immediately when candidate exists', async () => {
        const onPlay = jest.fn();
        const onUnavailable = jest.fn();
        const candidate = { url: 'https://example.com/stream.m3u8' };

        await renderHarness({
            titleKey: 'movie:1',
            candidate,
            loading: false,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        act(() => latestState.request());

        expect(onPlay).toHaveBeenCalledTimes(1);
        expect(onPlay).toHaveBeenCalledWith(candidate);
        expect(onUnavailable).not.toHaveBeenCalled();
    });

    test('queues while loading and launches once when candidate arrives', async () => {
        const onPlay = jest.fn();
        const onUnavailable = jest.fn();
        const candidate = { url: 'https://example.com/stream.webm' };

        await renderHarness({
            titleKey: 'movie:2',
            candidate: null,
            loading: true,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        act(() => latestState.request());
        expect(latestState.pending).toBe(true);

        await renderHarness({
            titleKey: 'movie:2',
            candidate,
            loading: true,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        expect(onPlay).toHaveBeenCalledTimes(1);
        expect(onPlay).toHaveBeenCalledWith(candidate);
        expect(onUnavailable).not.toHaveBeenCalled();
    });

    test('queued intent becomes unavailable when loading completes with no candidate', async () => {
        const onPlay = jest.fn();
        const onUnavailable = jest.fn();

        await renderHarness({
            titleKey: 'movie:3',
            candidate: null,
            loading: true,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        act(() => latestState.request());

        await renderHarness({
            titleKey: 'movie:3',
            candidate: null,
            loading: false,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        expect(onPlay).not.toHaveBeenCalled();
        expect(onUnavailable).toHaveBeenCalledTimes(1);
    });

    test('blocked request does not launch or mark unavailable', async () => {
        const onPlay = jest.fn();
        const onUnavailable = jest.fn();

        await renderHarness({
            titleKey: 'movie:4',
            candidate: { url: 'https://example.com/stream.webm' },
            loading: false,
            blocked: true,
            onPlay,
            onUnavailable,
        });

        act(() => latestState.request());

        expect(onPlay).not.toHaveBeenCalled();
        expect(onUnavailable).not.toHaveBeenCalled();
    });

    test('cancelled intent never launches when candidate later appears', async () => {
        const onPlay = jest.fn();
        const onUnavailable = jest.fn();

        await renderHarness({
            titleKey: 'movie:5',
            candidate: null,
            loading: true,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        act(() => latestState.request());
        act(() => latestState.cancel());

        await renderHarness({
            titleKey: 'movie:5',
            candidate: { url: 'https://example.com/stream.webm' },
            loading: true,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        expect(onPlay).not.toHaveBeenCalled();
        expect(onUnavailable).not.toHaveBeenCalled();
    });

    test('rapid duplicate requests launch only once for same title', async () => {
        const onPlay = jest.fn();
        const onUnavailable = jest.fn();
        const candidate = { url: 'https://example.com/stream.webm' };

        await renderHarness({
            titleKey: 'movie:6',
            candidate,
            loading: false,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        act(() => {
            latestState.request();
            latestState.request();
            latestState.request();
        });

        expect(onPlay).toHaveBeenCalledTimes(1);
        expect(onUnavailable).not.toHaveBeenCalled();
    });

    test('title change clears pending for previous title', async () => {
        const onPlay = jest.fn();
        const onUnavailable = jest.fn();

        await renderHarness({
            titleKey: 'movie:A',
            candidate: null,
            loading: true,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        act(() => latestState.request());
        expect(latestState.pending).toBe(true);

        await renderHarness({
            titleKey: 'movie:B',
            candidate: null,
            loading: true,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        await renderHarness({
            titleKey: 'movie:B',
            candidate: { url: 'https://example.com/new.webm' },
            loading: false,
            blocked: false,
            onPlay,
            onUnavailable,
        });

        expect(onPlay).not.toHaveBeenCalled();
        expect(onUnavailable).not.toHaveBeenCalled();
    });
});
