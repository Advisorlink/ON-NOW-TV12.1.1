import React from 'react';
import { Delete, ArrowUp, Space } from 'lucide-react';

/**
 * Themed TV-friendly on-screen keyboard.  Rendered as a grid of
 * D-pad-focusable keys so the user never has to summon the Android
 * IME — which on the HK1 is slow, ugly, and breaks the visual
 * rhythm of the rest of the app.
 *
 * Props:
 *   value         — current text value
 *   onChange(v)   — fires whenever the value changes
 *   onSubmit()    — fires when the user presses ENTER
 *   maxLength     — clamp typed value to this length (default 40)
 *   variant       — 'name' (alpha + space) or 'pin' (digits)
 *                   defaults to 'name'
 */
export default function TVKeyboard({
    value,
    onChange,
    onSubmit,
    maxLength = 40,
    variant = 'name',
}) {
    const [shift, setShift] = React.useState(false);

    const rows =
        variant === 'pin'
            ? [
                  ['1', '2', '3'],
                  ['4', '5', '6'],
                  ['7', '8', '9'],
                  ['0'],
              ]
            : [
                  ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'],
                  ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'],
                  ['Z', 'X', 'C', 'V', 'B', 'N', 'M', "'", '-'],
                  ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'],
              ];

    const append = (ch) => {
        if (value.length >= maxLength) return;
        // Letters honour the Shift toggle; everything else is
        // inserted as-is (digits, apostrophe, hyphen).
        const isLetter = /[A-Z]/.test(ch);
        const next =
            value +
            (isLetter && !shift ? ch.toLowerCase() : ch);
        onChange(next);
        if (shift && isLetter) setShift(false);
    };

    const back = () => {
        onChange(value.slice(0, -1));
    };

    const space = () => {
        if (value.length >= maxLength) return;
        onChange(value + ' ');
    };

    return (
        <div
            data-testid={`tv-keyboard-${variant}`}
            className="flex flex-col items-center"
            style={{ gap: 8, width: '100%' }}
        >
            {rows.map((row, i) => (
                <div
                    key={i}
                    className="flex"
                    style={{
                        gap: 8,
                        justifyContent: 'center',
                        width: '100%',
                    }}
                >
                    {row.map((ch) => (
                        <Key
                            key={ch}
                            label={
                                variant === 'name' && !shift && /[A-Z]/.test(ch)
                                    ? ch.toLowerCase()
                                    : ch
                            }
                            onPress={() => append(ch)}
                            testId={`tv-key-${ch.toLowerCase()}`}
                        />
                    ))}
                </div>
            ))}

            <div
                className="flex"
                style={{
                    gap: 8,
                    marginTop: 6,
                    justifyContent: 'center',
                    width: '100%',
                }}
            >
                {variant === 'name' && (
                    <Key
                        label={<ArrowUp size={18} strokeWidth={2.4} />}
                        onPress={() => setShift((s) => !s)}
                        wide
                        active={shift}
                        testId="tv-key-shift"
                    />
                )}
                {variant === 'name' && (
                    <Key
                        label={
                            <span className="flex items-center gap-2">
                                <Space size={18} strokeWidth={2} />
                                <span
                                    className="vesper-mono"
                                    style={{
                                        fontSize: 11,
                                        letterSpacing: '0.18em',
                                        textTransform: 'uppercase',
                                    }}
                                >
                                    Space
                                </span>
                            </span>
                        }
                        onPress={space}
                        flex
                        testId="tv-key-space"
                    />
                )}
                <Key
                    label={<Delete size={18} strokeWidth={2.2} />}
                    onPress={back}
                    wide
                    testId="tv-key-back"
                />
                <Key
                    label={
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 11,
                                letterSpacing: '0.22em',
                                textTransform: 'uppercase',
                                fontWeight: 700,
                            }}
                        >
                            Enter
                        </span>
                    }
                    onPress={() => {
                        if (onSubmit) onSubmit();
                    }}
                    wide
                    primary
                    testId="tv-key-enter"
                />
            </div>
        </div>
    );
}

function Key({ label, onPress, wide, flex, primary, active, testId }) {
    return (
        <button
            data-testid={testId}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={onPress}
            onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onPress();
                }
            }}
            className="flex items-center justify-center font-sans"
            style={{
                height: 48,
                minWidth: wide ? 64 : 44,
                width: flex ? undefined : wide ? 64 : 44,
                flex: flex ? 1 : '0 0 auto',
                maxWidth: flex ? 220 : undefined,
                borderRadius: 12,
                background: active
                    ? 'var(--vesper-blue)'
                    : primary
                    ? 'rgba(var(--vesper-blue-rgb),0.18)'
                    : 'rgba(255,255,255,0.06)',
                border: primary
                    ? '1px solid rgba(var(--vesper-blue-rgb),0.55)'
                    : '1px solid rgba(255,255,255,0.10)',
                color: active
                    ? 'var(--vesper-bg-0)'
                    : primary
                    ? 'var(--vesper-blue-bright)'
                    : 'var(--vesper-text)',
                fontSize: 18,
                fontWeight: 600,
                padding: '0 8px',
                lineHeight: 1,
            }}
        >
            {label}
        </button>
    );
}
