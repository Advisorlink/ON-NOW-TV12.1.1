import React from 'react';
import { Delete, ArrowUp, Space } from 'lucide-react';
import useIsMobile from '@/lib/useIsMobile';

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
 *
 * **Mobile mode**: when running on a phone (`useIsMobile()`), the
 * grid is replaced with a native HTML <input> so the OS keyboard
 * pops up.  The 10-column TV keyboard is essentially impossible to
 * use on a 360 px screen — each key would be 28 px wide and 32 px
 * tall with no thumb-friendly target area.
 */
export default function TVKeyboard({
    value,
    onChange,
    onSubmit,
    maxLength = 40,
    variant = 'name',
}) {
    const isMobile = useIsMobile();
    const [shift, setShift] = React.useState(false);
    // Mirror the controlled `value` prop into a ref so that the
    // click handlers ALWAYS read the freshest value — even if two
    // clicks arrive in the same React batch before a re-render
    // commits the new prop.  Without this, rapid taps would each
    // see the same stale `value` and onChange would emit the SAME
    // single-character string twice (visible to the parent as a
    // "first character lost" bug).
    const valueRef = React.useRef(value);
    React.useEffect(() => { valueRef.current = value; }, [value]);

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
        const cur = valueRef.current ?? '';
        if (cur.length >= maxLength) return;
        // Letters honour the Shift toggle; everything else is
        // inserted as-is (digits, apostrophe, hyphen).
        const isLetter = /[A-Z]/.test(ch);
        const next =
            cur +
            (isLetter && !shift ? ch.toLowerCase() : ch);
        valueRef.current = next;  // keep ref in lock-step so two
                                  // rapid clicks don't both see the
                                  // pre-update value.
        onChange(next);
        if (shift && isLetter) setShift(false);
    };

    const back = () => {
        const cur = valueRef.current ?? '';
        const next = cur.slice(0, -1);
        valueRef.current = next;
        onChange(next);
    };

    const space = () => {
        const cur = valueRef.current ?? '';
        if (cur.length >= maxLength) return;
        const next = cur + ' ';
        valueRef.current = next;
        onChange(next);
    };

    /* Mobile branch — native input.  The OS keyboard does a far
       better job on phones than our 10-col TV grid ever could.  We
       still expose `data-testid="tv-keyboard-<variant>"` so existing
       e2e tests find the field. */
    if (isMobile) {
        const isPin = variant === 'pin';
        return (
            <div
                data-testid={`tv-keyboard-${variant}`}
                style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 14 }}
            >
                <input
                    data-testid={`tv-keyboard-input-${variant}`}
                    autoFocus
                    value={value || ''}
                    onChange={(e) => {
                        const next = isPin
                            ? e.target.value.replace(/\D/g, '').slice(0, maxLength)
                            : e.target.value.slice(0, maxLength);
                        valueRef.current = next;
                        onChange(next);
                    }}
                    onKeyDown={(e) => {
                        if (e.key === 'Enter' && onSubmit) {
                            e.preventDefault();
                            onSubmit();
                        }
                    }}
                    inputMode={isPin ? 'numeric' : 'text'}
                    pattern={isPin ? '[0-9]*' : undefined}
                    type={isPin ? 'tel' : 'text'}
                    maxLength={maxLength}
                    autoCapitalize={isPin ? 'off' : 'words'}
                    autoCorrect="off"
                    spellCheck={false}
                    placeholder={isPin ? '••••' : 'Type here'}
                    enterKeyHint={onSubmit ? 'go' : 'done'}
                    style={{
                        width: '100%',
                        height: 56,
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(93,200,255,0.35)',
                        borderRadius: 14,
                        color: '#fff',
                        fontSize: isPin ? 24 : 18,
                        fontWeight: 600,
                        letterSpacing: isPin ? '0.4em' : '0.01em',
                        textAlign: isPin ? 'center' : 'left',
                        padding: '0 18px',
                        outline: 'none',
                        WebkitTapHighlightColor: 'transparent',
                    }}
                />
                {onSubmit && (
                    <button
                        data-testid={`tv-keyboard-submit-${variant}`}
                        onClick={onSubmit}
                        style={{
                            width: '100%',
                            height: 52,
                            background: 'var(--vesper-blue)',
                            border: 'none',
                            borderRadius: 14,
                            color: 'var(--vesper-bg-0)',
                            fontSize: 15,
                            fontWeight: 700,
                            letterSpacing: '0.04em',
                            cursor: 'pointer',
                            WebkitTapHighlightColor: 'transparent',
                        }}
                    >
                        Continue
                    </button>
                )}
            </div>
        );
    }

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
            // NOTE: do NOT handle onKeyDown here.  Browsers already
            // fire a synthetic `click` when Enter / Space is pressed
            // on a focused <button>, so re-triggering onPress from
            // keydown produces a duplicate letter on every press.
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
