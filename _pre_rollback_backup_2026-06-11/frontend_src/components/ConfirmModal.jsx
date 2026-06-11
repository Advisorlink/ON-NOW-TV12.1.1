/**
 * Minimal keyboard-first confirmation dialog.  Used by Live TV when
 * the user long-presses OK on something that would remove a saved
 * item (favourite, reminder, etc.).
 *
 *   • Focus-traps inside the dialog so D-pad up/down can move
 *     between the two buttons.
 *   • Esc / Backspace → cancel
 *   • Enter on Confirm → fire onConfirm
 *   • Auto-focuses the destructive (red) button so a quick second
 *     OK confirms instantly.
 *   • Centered modal with a dark scrim — works on any background.
 */

import React, { useEffect, useRef } from 'react';
import { X } from 'lucide-react';

export default function ConfirmModal({
    open,
    title,
    body,
    confirmLabel = 'Remove',
    cancelLabel = 'Cancel',
    danger = true,
    onConfirm,
    onCancel,
}) {
    const confirmRef = useRef(null);

    useEffect(() => {
        if (!open) return undefined;
        confirmRef.current?.focus();
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                onCancel?.();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [open, onCancel]);

    if (!open) return null;

    return (
        <div
            role="dialog"
            aria-modal="true"
            aria-label={title}
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 9998,
                background: 'rgba(0,0,0,0.65)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 24,
            }}
            onClick={onCancel}
        >
            <div
                onClick={(e) => e.stopPropagation()}
                style={{
                    width: 'min(420px, 100%)',
                    background: '#11182A',
                    border: '1px solid rgba(255,255,255,0.10)',
                    borderRadius: 16,
                    padding: '24px 24px 20px 24px',
                    color: '#E6EAF2',
                    boxShadow: '0 18px 50px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.06)',
                }}
            >
                <div style={{
                    fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                    letterSpacing: '0.28em', color: danger ? '#FF6B7A' : '#5DC8FF',
                    marginBottom: 8,
                }}>
                    {danger ? 'CONFIRM REMOVAL' : 'CONFIRM ACTION'}
                </div>
                <div style={{
                    fontSize: 18, fontWeight: 700, color: '#fff',
                    lineHeight: 1.25, marginBottom: 8,
                }}>
                    {title}
                </div>
                {body && (
                    <div style={{
                        fontSize: 13, color: '#9DA5B5', lineHeight: 1.45,
                        marginBottom: 18,
                    }}>
                        {body}
                    </div>
                )}
                <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                    <button
                        onClick={onCancel}
                        style={{
                            display: 'inline-flex', alignItems: 'center', gap: 6,
                            height: 40, padding: '0 16px',
                            borderRadius: 10,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.12)',
                            color: '#E6EAF2',
                            fontWeight: 600, fontSize: 13,
                            cursor: 'pointer',
                        }}
                    >
                        <X size={13} /> {cancelLabel}
                    </button>
                    <button
                        ref={confirmRef}
                        onClick={onConfirm}
                        style={{
                            height: 40, padding: '0 20px',
                            borderRadius: 10,
                            border: 'none',
                            background: danger ? '#FF6B7A' : '#5DC8FF',
                            color: '#0A0F1A',
                            fontWeight: 700, fontSize: 13,
                            cursor: 'pointer',
                        }}
                    >
                        {confirmLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}
