import React from 'react';
import { isRebornBrand, REBORN_WORDMARK, REBORN_MARK } from '@/lib/brand';

/* Renders the Reborn Play logo inside the one-app, else the children (legacy ON NOW text mark). */
export function BrandWordmark({ height = 40, mark = false, style, children, testid = 'brand-wordmark' }) {
    if (!isRebornBrand()) return <>{children}</>;
    return (
        <img
            data-testid={testid}
            src={mark ? REBORN_MARK : REBORN_WORDMARK}
            alt="Reborn Play"
            draggable={false}
            style={{ height, width: 'auto', objectFit: 'contain', filter: 'drop-shadow(0 0 14px rgba(var(--vesper-blue-rgb), 0.45))', ...style }}
        />
    );
}
