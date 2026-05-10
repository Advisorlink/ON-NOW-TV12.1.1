import React, { useEffect, useState } from 'react';
import { Delete, CornerDownLeft } from 'lucide-react';

/**
 * D-pad-friendly on-screen keyboard.
 * - Letters + numbers + URL punctuation
 * - Arrow keys move focus between keys (handled by global spatial nav)
 * - Enter inserts the focused character
 *
 * For a TV box without a paired keyboard, this is the primary input.
 * On desktop we still expose the underlying <input> so the user can
 * type / paste directly.
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
}) {
    const [v, setV] = useState(value || '');
    useEffect(() => setV(value || ''), [value]);

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
            <div
                data-testid="osk-input"
                className="px-5 py-4 rounded-xl mb-5 font-mono text-[20px] truncate"
                style={{
                    background: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(93,200,255,0.25)',
                    color: 'var(--vesper-text)',
                    minHeight: 60,
                }}
            >
                {v ? (
                    v
                ) : (
                    <span style={{ color: 'var(--vesper-text-3)' }}>
                        {placeholder}
                    </span>
                )}
                <span className="vesper-pulse ml-1" style={{ color: 'var(--vesper-blue)' }}>
                    |
                </span>
            </div>

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
