import React, { useEffect, useRef, useState } from 'react';
import { Delete, CornerDownLeft } from 'lucide-react';

/**
 * D-pad-friendly on-screen keyboard with a real <input> address bar
 * at the top — you can click into it, type, or paste with Ctrl+V.
 * The OSK keys below remain as a fallback for the TV remote.
 *
 * Both modes feed the same React state.
 */
const KEYS = [
    'qwertyuiop'.split(''),
    'asdfghjkl'.split(''),
    'zxcvbnm.-'.split(''),
    '0123456789'.split(''),
    [':', '/', '_', '?', '=', '&', '+', '#', '@'],
];

export default function OnScreenKeyboard({
    value,
    onChange,
    onSubmit,
    placeholder = '',
    submitLabel = 'Done',
    autoFocusInput = true,
}) {
    const [v, setV] = useState(value || '');
    const inputRef = useRef(null);

    useEffect(() => setV(value || ''), [value]);

    // Auto-focus the input the first time the keyboard appears so a
    // desktop user can start typing/pasting immediately. TV users with
    // a remote can still D-pad past it to the on-screen keys.
    useEffect(() => {
        if (autoFocusInput && inputRef.current) {
            const t = setTimeout(() => {
                inputRef.current?.focus({ preventScroll: true });
                inputRef.current?.select?.();
            }, 80);
            return () => clearTimeout(t);
        }
    }, [autoFocusInput]);

    const update = (next) => {
        setV(next);
        onChange?.(next);
    };

    const press = (ch) => update(v + ch);
    const space = () => update(v + ' ');
    const back = () => update(v.slice(0, -1));
    const clear = () => update('');

    return (
        <div className="vesper-glass rounded-2xl p-7" style={{ width: '100%' }}>
            <input
                ref={inputRef}
                data-testid="osk-input"
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                type="url"
                value={v}
                placeholder={placeholder}
                spellCheck={false}
                autoCapitalize="off"
                autoCorrect="off"
                onChange={(e) => update(e.target.value)}
                onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                        e.preventDefault();
                        onSubmit?.(v);
                    }
                }}
                className="w-full px-5 py-4 rounded-xl mb-5 font-mono outline-none"
                style={{
                    background: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(var(--vesper-blue-rgb),0.25)',
                    color: 'var(--vesper-text)',
                    fontSize: 20,
                    letterSpacing: '0.02em',
                    minHeight: 60,
                    caretColor: 'var(--vesper-blue)',
                }}
            />

            <div className="flex flex-col gap-2.5">
                {KEYS.map((row, ri) => (
                    <div key={ri} className="flex justify-center gap-2">
                        {row.map((k) => (
                            <button
                                key={k}
                                data-focusable="true"
                                data-focus-style="key"
                                tabIndex={0}
                                onClick={() => press(k)}
                                className="font-mono rounded-lg"
                                style={{
                                    width: 60,
                                    height: 60,
                                    fontSize: 22,
                                    background: 'rgba(255,255,255,0.04)',
                                    border: '1px solid rgba(255,255,255,0.06)',
                                    color: 'var(--vesper-text)',
                                }}
                            >
                                {k}
                            </button>
                        ))}
                    </div>
                ))}
                <div className="flex justify-center gap-2 mt-1">
                    <button
                        data-focusable="true"
                        data-focus-style="key"
                        tabIndex={0}
                        onClick={space}
                        className="rounded-lg font-sans"
                        style={{
                            width: 280,
                            height: 60,
                            fontSize: 18,
                            background: 'rgba(255,255,255,0.04)',
                            border: '1px solid rgba(255,255,255,0.06)',
                            color: 'var(--vesper-text-2)',
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                        }}
                    >
                        Space
                    </button>
                    <button
                        data-focusable="true"
                        data-focus-style="key"
                        tabIndex={0}
                        onClick={back}
                        className="flex items-center justify-center gap-2 rounded-lg font-sans"
                        style={{
                            width: 110,
                            height: 60,
                            fontSize: 16,
                            background: 'rgba(255,255,255,0.04)',
                            border: '1px solid rgba(255,255,255,0.06)',
                            color: 'var(--vesper-text-2)',
                        }}
                    >
                        <Delete size={18} strokeWidth={1.6} /> Del
                    </button>
                    <button
                        data-focusable="true"
                        data-focus-style="key"
                        tabIndex={0}
                        onClick={clear}
                        className="rounded-lg font-sans"
                        style={{
                            width: 110,
                            height: 60,
                            fontSize: 16,
                            background: 'rgba(255,255,255,0.04)',
                            border: '1px solid rgba(255,255,255,0.06)',
                            color: 'var(--vesper-text-2)',
                            letterSpacing: '0.12em',
                            textTransform: 'uppercase',
                        }}
                    >
                        Clear
                    </button>
                    <button
                        data-testid="osk-submit"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => onSubmit?.(v)}
                        className="flex items-center justify-center gap-2 rounded-lg font-sans font-semibold"
                        style={{
                            width: 200,
                            height: 60,
                            fontSize: 18,
                            background: 'var(--vesper-blue)',
                            color: 'var(--vesper-bg-0)',
                            border: '1px solid var(--vesper-blue)',
                        }}
                    >
                        <CornerDownLeft size={18} strokeWidth={2.2} />
                        {submitLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}
