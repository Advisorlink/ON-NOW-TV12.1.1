import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import useTouchActivate from './useTouchActivate';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('useTouchActivate', () => {
    let node;
    let root;
    let handlers;
    let onAction;
    let now;
    let nowSpy;

    const target = {
        getBoundingClientRect: () => ({ left: 0, right: 200, top: 0, bottom: 80 }),
    };

    function Harness({ action = onAction }) {
        handlers = useTouchActivate(action);
        return null;
    }

    const renderHarness = async (props = {}) => {
        await act(async () => {
            root.render(<Harness {...props} />);
        });
    };

    beforeEach(async () => {
        node = document.createElement('div');
        document.body.appendChild(node);
        root = createRoot(node);
        onAction = jest.fn();
        now = 1000;
        nowSpy = jest.spyOn(performance, 'now').mockImplementation(() => now);
        await renderHarness();
    });

    afterEach(async () => {
        nowSpy.mockRestore();
        await act(async () => root.unmount());
        node.remove();
    });

    test('pointer touch down/up triggers action once and suppresses compatibility click detail=1', () => {
        handlers.onPointerDown({
            isPrimary: true,
            pointerType: 'touch',
            pointerId: 7,
            clientX: 20,
            clientY: 20,
            currentTarget: target,
        });
        now += 20;
        handlers.onPointerUp({ pointerId: 7, clientX: 21, clientY: 21 });

        expect(onAction).toHaveBeenCalledTimes(1);

        const clickEvent = {
            detail: 1,
            preventDefault: jest.fn(),
            stopPropagation: jest.fn(),
        };
        now += 100;
        handlers.onClick(clickEvent);

        expect(clickEvent.preventDefault).toHaveBeenCalledTimes(1);
        expect(clickEvent.stopPropagation).toHaveBeenCalledTimes(1);
        expect(onAction).toHaveBeenCalledTimes(1);
    });

    test('keyboard click detail=0 and later mouse click are retained', () => {
        handlers.onClick({ detail: 0 });
        expect(onAction).toHaveBeenCalledTimes(1);

        now += 900;
        handlers.onClick({ detail: 1 });
        expect(onAction).toHaveBeenCalledTimes(2);
    });

    test('drag > 10px cancels touch launch', () => {
        handlers.onPointerDown({
            isPrimary: true,
            pointerType: 'touch',
            pointerId: 2,
            clientX: 10,
            clientY: 10,
            currentTarget: target,
        });
        handlers.onPointerMove({ clientX: 30, clientY: 10 });
        handlers.onPointerUp({ pointerId: 2, clientX: 30, clientY: 10 });
        expect(onAction).not.toHaveBeenCalled();
    });

    test('pointer cancel prevents launch', () => {
        handlers.onPointerDown({
            isPrimary: true,
            pointerType: 'touch',
            pointerId: 3,
            clientX: 20,
            clientY: 20,
            currentTarget: target,
        });
        handlers.onPointerCancel();
        handlers.onPointerUp({ pointerId: 3, clientX: 20, clientY: 20 });
        expect(onAction).not.toHaveBeenCalled();
    });

    test('long press > 900ms does not launch', () => {
        handlers.onPointerDown({
            isPrimary: true,
            pointerType: 'touch',
            pointerId: 4,
            clientX: 20,
            clientY: 20,
            currentTarget: target,
        });
        now += 901;
        handlers.onPointerUp({ pointerId: 4, clientX: 20, clientY: 20 });
        expect(onAction).not.toHaveBeenCalled();
    });

    test('out-of-bounds release does not launch', () => {
        handlers.onPointerDown({
            isPrimary: true,
            pointerType: 'touch',
            pointerId: 5,
            clientX: 20,
            clientY: 20,
            currentTarget: target,
        });
        now += 30;
        handlers.onPointerUp({ pointerId: 5, clientX: 230, clientY: 20 });
        expect(onAction).not.toHaveBeenCalled();
    });

    test('non-primary pointer does not launch', () => {
        handlers.onPointerDown({
            isPrimary: false,
            pointerType: 'touch',
            pointerId: 6,
            clientX: 20,
            clientY: 20,
            currentTarget: target,
        });
        handlers.onPointerUp({ pointerId: 6, clientX: 20, clientY: 20 });
        expect(onAction).not.toHaveBeenCalled();
    });

    test('touch fallback without PointerEvent supports single touch and blocks multi-touch', async () => {
        const originalPointerEvent = window.PointerEvent;
        try {
            delete window.PointerEvent;
            await renderHarness();

            handlers.onTouchStart({
                touches: [{ identifier: 11, clientX: 12, clientY: 13 }],
                currentTarget: target,
            });
            handlers.onTouchEnd({
                changedTouches: [{ identifier: 11, clientX: 12, clientY: 13 }],
            });
            expect(onAction).toHaveBeenCalledTimes(1);

            handlers.onTouchStart({
                touches: [
                    { identifier: 12, clientX: 10, clientY: 10 },
                    { identifier: 13, clientX: 15, clientY: 15 },
                ],
                currentTarget: target,
            });
            handlers.onTouchEnd({
                changedTouches: [{ identifier: 12, clientX: 10, clientY: 10 }],
            });
            expect(onAction).toHaveBeenCalledTimes(1);
        } finally {
            window.PointerEvent = originalPointerEvent;
        }
    });

    test('null action (disabled button path) never launches', async () => {
        await renderHarness({ action: null });
        handlers.onPointerDown({
            isPrimary: true,
            pointerType: 'touch',
            pointerId: 8,
            clientX: 20,
            clientY: 20,
            currentTarget: target,
        });
        handlers.onPointerUp({ pointerId: 8, clientX: 20, clientY: 20 });
        handlers.onClick({ detail: 0 });
        expect(onAction).not.toHaveBeenCalled();
    });
});
