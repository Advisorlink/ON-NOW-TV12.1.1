import React, { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { RadioTower } from 'lucide-react';
import { hasLiveTv, openLiveTv } from '@/lib/liveTv';
import { brandName } from '@/lib/brand';

/** Hands off to the native Live TV guide; on the web it explains where Live TV lives. */
export default function LiveTv() {
    const navigate = useNavigate();
    const fired = useRef(false);
    useEffect(() => {
        if (fired.current) return;
        fired.current = true;
        if (hasLiveTv()) {
            openLiveTv();
            const t = setTimeout(() => navigate('/', { replace: true }), 400);
            return () => clearTimeout(t);
        }
        return undefined;
    }, [navigate]);

    return (
        <div data-testid="live-tv-page" style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--vesper-text, #E6EAF2)' }}>
            <div style={{ textAlign: 'center', maxWidth: 560, padding: 32 }}>
                <RadioTower size={44} style={{ color: '#5DC8FF', margin: '0 auto 18px' }} />
                <h1 style={{ fontSize: 28, fontWeight: 800, margin: 0 }}>{hasLiveTv() ? 'Opening Live TV…' : 'Live TV'}</h1>
                <p style={{ opacity: 0.7, marginTop: 12, fontSize: 15 }}>
                    {hasLiveTv() ? 'The channel guide is starting.' : `Live channels are available inside the ${brandName()} app on your TV box.`}
                </p>
            </div>
        </div>
    );
}
