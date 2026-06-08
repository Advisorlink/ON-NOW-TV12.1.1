import React from 'react';

/**
 * SpinningLogo — a continuously-rotating ON NOW TV brand mark.
 * Drop-in replacement for the generic `<Loader2 className="vesper-spin" />`
 * loader on user-visible loading surfaces (player preview, detail
 * metadata, etc.).
 *
 * Sizes are in pixels.  The image rotates 360° infinitely; the
 * outer wrapper handles the spin so callers can compose layout
 * around it without breaking the animation.
 */
export default function SpinningLogo({
    size = 48,
    speedMs = 1100,
    className = '',
    style = {},
    title = 'Loading',
}) {
    return (
        <span
            role="img"
            aria-label={title}
            className={`vesper-spin ${className}`}
            style={{
                display: 'inline-block',
                width: size,
                height: size,
                animationDuration: `${speedMs}ms`,
                lineHeight: 0,
                ...style,
            }}
        >
            <img
                src="/brand/onnowtv-logo.png"
                alt=""
                draggable={false}
                style={{
                    width: '100%',
                    height: '100%',
                    objectFit: 'contain',
                    display: 'block',
                    // Subtle soft glow so it reads as "alive" on a
                    // dark TV background — the brand-blue radial
                    // halo matches the focus-ring colour elsewhere.
                    filter: 'drop-shadow(0 0 12px rgba(93,200,255,0.55))',
                }}
            />
        </span>
    );
}
