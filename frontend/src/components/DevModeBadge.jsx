import React, { useEffect, useState } from 'react';
import Host from '@/lib/host';

/**
 * Tiny floating badge that only appears when the app is running
 * from a non-`file://` origin inside the Android wrapper.  Lets
 * the developer exit dev mode (return to the bundled assets) with
 * a single click — no Settings dive, no force-stop, no APK rebuild.
 *
 * Outside Android (e.g. desktop preview) renders nothing.
 */
export default function DevModeBadge() {
    const [visible, setVisible] = useState(false);

    useEffect(() => {
        // Show the badge only when:
        //   • we're inside the Android wrapper (host bridge present)
        //   • AND the WebView is NOT loading a file:// URL.
        // The second condition means we're in dev mode (the Activity
        // was launched against a network URL).
        if (!Host.isAndroid) return;
        try {
            const href = window.location.href || '';
            if (!href.startsWith('file://')) setVisible(true);
        } catch {
            /* noop */
        }
    }, []);

    if (!visible) return null;

    const exit = () => {
        try {
            // Native: clear the prefs override and recreate Activity.
            if (window.OnNowTV && window.OnNowTV.setDevUrl) {
                window.OnNowTV.setDevUrl('');
            }
        } catch {
            /* noop */
        }
    };

    return (
        <button
            data-testid="dev-mode-badge"
            onClick={exit}
            style={{
                position: 'fixed',
                top: 10,
                right: 10,
                zIndex: 9999,
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                height: 24,
                padding: '0 10px',
                borderRadius: 999,
                background: 'rgba(255,107,203,0.22)',
                color: '#FF6BCB',
                border: '1px solid rgba(255,107,203,0.6)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.18em',
                fontFamily:
                    'JetBrains Mono, Geist Mono, ui-monospace, monospace',
                textTransform: 'uppercase',
                cursor: 'pointer',
            }}
        >
            <span
                style={{
                    width: 6,
                    height: 6,
                    borderRadius: '50%',
                    background: '#FF6BCB',
                    boxShadow: '0 0 6px #FF6BCB',
                }}
            />
            DEV · Exit
        </button>
    );
}
