import React from 'react';
import { createRoot } from 'react-dom/client';
import { act } from 'react';
import StreamPickerModal from './StreamPickerModal';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

test('remote BACK closes the open stream picker and nothing else', () => {
    const el = document.createElement('div');
    document.body.appendChild(el);
    const root = createRoot(el);
    const onClose = jest.fn();
    act(() => { root.render(<StreamPickerModal streams={[]} currentIdx={-1} onPick={() => {}} onClose={onClose} />); });
    expect(typeof window.__vesperBack).toBe('function');
    expect(window.__vesperBack()).toBe(true);
    expect(onClose).toHaveBeenCalledTimes(1);
    act(() => { root.unmount(); });
    expect(window.__vesperBack).toBeUndefined();
});
