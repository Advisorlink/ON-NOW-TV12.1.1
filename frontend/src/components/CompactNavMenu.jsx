import React, { useEffect, useRef } from 'react';
import { Zap } from 'lucide-react';
import { useNativeBackTrap, triggerTrapBack } from '@/hooks/useNativeBackTrap';

export const CompactNavMenu = ({ items, isActive, go, autoplay, toggleAutoplay, onClose }) => {
    const root = useRef(null);
    useNativeBackTrap(true, () => { onClose(); return false; });
    useEffect(() => { root.current?.querySelector('button')?.focus({ preventScroll: true }); }, []);
    return (
        <>
            <button
                data-testid="compact-nav-dismiss"
                aria-label="Close navigation"
                tabIndex={-1}
                onClick={onClose}
                style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', pointerEvents: 'auto' }}
            />
            <div
                ref={root}
                role="dialog"
                aria-label="More navigation"
                aria-modal="true"
                data-testid="compact-nav-menu"
                data-focus-trap="true"
                className="vesper-glass"
                onKeyDown={(e) => {
                    if (['Escape', 'Backspace', 'GoBack'].includes(e.key)) {
                        e.preventDefault();
                        e.stopPropagation();
                        triggerTrapBack();
                    }
                }}
                style={{ position: 'fixed', top: 74, right: 12, width: 'min(280px, calc(100vw - 24px))', maxHeight: 'calc(100dvh - 96px)', overflowY: 'auto', padding: 8, borderRadius: 8, pointerEvents: 'auto', background: 'var(--vesper-bg-1)', border: '1px solid var(--vesper-line)' }}
            >
                {items.map(({ id, path, icon: Icon, label }) => (
                    <button
                        key={id}
                        data-testid={`top-nav-${id}`}
                        data-focusable="true"
                        data-focus-style="quiet"
                        onClick={() => { onClose(); go(path); }}
                        style={{ ...rowStyle, color: isActive({ id, path }) ? 'var(--vesper-blue)' : 'var(--vesper-text)' }}
                    >
                        <Icon size={20} /> {label}
                    </button>
                ))}
                <button
                    data-testid="top-nav-autoplay"
                    data-focusable="true"
                    data-focus-style="quiet"
                    onClick={toggleAutoplay}
                    aria-pressed={autoplay}
                    style={{ ...rowStyle, color: autoplay ? '#FFC350' : 'var(--vesper-text)' }}
                >
                    <Zap size={20} /> Auto play · {autoplay ? 'ON' : 'OFF'}
                </button>
            </div>
        </>
    );
};

const rowStyle = { display: 'flex', alignItems: 'center', gap: 12, padding: '12px', width: '100%', minHeight: 44, textAlign: 'left', borderRadius: 6, background: 'transparent' };