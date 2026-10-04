import React, { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '@/contexts/AuthContext';
import { isKidsApp, isMusicApp, isTriviaApp } from '@/lib/profiles';
import { APP_VERSION, WHATS_NEW } from '@/lib/appVersion';
import { claimReleaseNotice, dismissReleaseNotice, releaseWasPresented } from '@/lib/releaseNotice';
import { useNativeBackTrap, triggerTrapBack } from '@/hooks/useNativeBackTrap';
import { ReleaseNotesPanel } from '@/components/ReleaseNotesPanel';

/** Single automatic release notice, after verified sign-in and boot. */
export default function WhatsNewModal() {
    const { pathname } = useLocation();
    const { status, sessionVerified, cloudRestore } = useAuth();
    const [open, setOpen] = useState(false);
    const returnFocus = useRef(null);
    const allowed = !isKidsApp() && !isMusicApp() && !isTriviaApp() &&
        (pathname === '/profiles' || pathname === '/');
    const ready = status === 'authenticated' && sessionVerified && allowed && !cloudRestore?.snapshot;

    useEffect(() => {
        if (!ready || releaseWasPresented() || !WHATS_NEW[APP_VERSION]) return undefined;
        let timer;
        const attempt = () => {
            // Don't place release notes on top of splash/PIN/restore dialogs.
            // Retry also covers a cloud-restore prompt arriving after sign-in.
            if (document.querySelector('[data-testid="boot-splash"], [data-focus-trap="true"]')) {
                timer = setTimeout(attempt, 150);
                return;
            }
            if (!claimReleaseNotice()) return;
            returnFocus.current = document.activeElement;
            setOpen(true);
        };
        timer = setTimeout(attempt, 150);
        return () => clearTimeout(timer);
    }, [ready]);

    const dismiss = () => {
        dismissReleaseNotice();
        setOpen(false);
        requestAnimationFrame(() => {
            const previous = returnFocus.current;
            const target = previous?.isConnected && previous.matches('[data-focusable="true"]')
                ? previous : document.querySelector('[data-focusable="true"]');
            target?.focus({ preventScroll: true });
        });
    };
    const visible = open && ready;
    useNativeBackTrap(visible, () => { dismiss(); return false; });

    if (!visible) return null;
    return <ReleaseNotesPanel release={WHATS_NEW[APP_VERSION]} version={APP_VERSION} onDismiss={dismiss} onBack={triggerTrapBack} />;
}