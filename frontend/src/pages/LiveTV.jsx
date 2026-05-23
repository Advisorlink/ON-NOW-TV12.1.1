/**
 * Live TV — temporarily disabled.
 *
 * All Live TV plumbing (IPTV channels, EPG, Sports Guide integration,
 * native Compose overlay, instant-bundle pre-warm) has been removed
 * from this app per user request.  This page renders a simple
 * "Coming Soon" placeholder so the existing /live-tv route + nav
 * entries keep working without any of the old code paths.
 *
 * A brand-new native Android TV launcher (see /app/android/
 * onnowtv-launcher/) will own the Live TV experience going forward.
 */
import React from 'react';
import { Tv } from 'lucide-react';

export default function LiveTV() {
    return (
        <div
            data-testid="live-tv-coming-soon"
            style={{
                position: 'fixed',
                inset: 0,
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                background:
                    'radial-gradient(ellipse at center, #0A1828 0%, #050B14 60%, #000000 100%)',
                color: '#E6F0FF',
                gap: 28,
                padding: '0 48px',
                textAlign: 'center',
            }}
        >
            <div
                style={{
                    width: 132,
                    height: 132,
                    borderRadius: 36,
                    background:
                        'linear-gradient(140deg, rgba(56,194,255,0.18), rgba(13,71,161,0.12))',
                    border: '1px solid rgba(56,194,255,0.35)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    boxShadow:
                        '0 0 60px rgba(56,194,255,0.22), inset 0 0 32px rgba(56,194,255,0.08)',
                }}
            >
                <Tv size={62} strokeWidth={1.5} color="#38C2FF" />
            </div>

            <h1
                style={{
                    fontSize: 56,
                    fontWeight: 700,
                    letterSpacing: -1,
                    margin: 0,
                    lineHeight: 1.1,
                }}
            >
                Live&nbsp;TV&nbsp;—&nbsp;Coming&nbsp;Soon
            </h1>

            <p
                style={{
                    fontSize: 18,
                    color: '#8FA3BF',
                    margin: 0,
                    maxWidth: 620,
                    lineHeight: 1.55,
                }}
            >
                We&rsquo;re rebuilding Live&nbsp;TV from the ground up in our brand-new
                native launcher.&nbsp;Check back here once the new release lands
                on your&nbsp;box.
            </p>
        </div>
    );
}
