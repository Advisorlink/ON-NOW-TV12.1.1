import React from 'react';

/**
 * SpinningLogo — neon brand-cyan loading mark.
 *
 * v2.10.9 — Replaced the 2 MB `onnowtv-logo.png` <img> with an
 * inline SVG ring.  Reasons:
 *   • The PNG path (`/brand/onnowtv-logo.png`) doesn't resolve in
 *     the Android WebView (base URL is `file:///android_asset/web/`)
 *     so it rendered as a broken-image placeholder on TVs.
 *   • A small SVG is sharp at every size, has zero network cost,
 *     and inherits `currentColor` so it picks up the brand neon
 *     cyan wherever it's dropped in.
 *
 * The ring uses a 270° arc + a 90° gap so the rotation reads as a
 * spinner.  Soft outer glow matches the brand-blue focus ring used
 * elsewhere in the app.
 */
export default function SpinningLogo({
    size = 48,
    speedMs = 1100,
    className = '',
    style = {},
    title = 'Loading',
    color = 'var(--vesper-blue, #5DC8FF)',
    strokeWidth = 5,
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
                color,
                ...style,
            }}
        >
            <svg
                viewBox="0 0 48 48"
                width="100%"
                height="100%"
                fill="none"
                style={{
                    display: 'block',
                    overflow: 'visible',
                    filter: 'drop-shadow(0 0 12px rgba(93,200,255,0.55))',
                }}
                aria-hidden="true"
            >
                {/* Faint background ring so the spinner reads as a
                    full circle even at the moment of low contrast. */}
                <circle
                    cx="24"
                    cy="24"
                    r="18"
                    stroke="currentColor"
                    strokeOpacity="0.18"
                    strokeWidth={strokeWidth}
                />
                {/* Active arc — 75 % of the circumference (270°),
                    with a 25 % gap that rotates with the parent. */}
                <circle
                    cx="24"
                    cy="24"
                    r="18"
                    stroke="currentColor"
                    strokeWidth={strokeWidth}
                    strokeLinecap="round"
                    strokeDasharray="85 28"
                />
            </svg>
        </span>
    );
}
