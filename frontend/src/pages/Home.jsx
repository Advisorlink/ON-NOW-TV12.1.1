import React from 'react';
import SideNav from '@/components/SideNav';
import HeroBillboard from '@/components/HeroBillboard';
import Shelf from '@/components/Shelf';
import { SHELVES } from '@/data/mockCatalog';
import useSpatialFocus from '@/hooks/useSpatialFocus';

export default function Home() {
    useSpatialFocus();

    return (
        <div
            data-testid="home-page"
            className="relative w-screen h-screen overflow-hidden"
            style={{ background: 'var(--vesper-bg-0)' }}
        >
            <SideNav active="home" />

            <main
                className="absolute inset-0 overflow-y-auto"
                style={{
                    paddingLeft: 0, // shelves/hero handle their own gutter
                    scrollBehavior: 'smooth',
                }}
            >
                <HeroBillboard />

                {SHELVES.map((shelf) => (
                    <Shelf key={shelf.id} shelf={shelf} />
                ))}

                <footer
                    className="flex items-center justify-between"
                    style={{
                        paddingLeft: 160,
                        paddingRight: 80,
                        paddingTop: 96,
                        paddingBottom: 80,
                    }}
                >
                    <div className="vesper-eyebrow">
                        Vesper · Vespertine v0.1
                    </div>
                    <div
                        className="font-mono"
                        style={{
                            color: 'var(--vesper-text3)',
                            fontSize: 14,
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                        }}
                    >
                        Stremio addons · Plex · Jellyfin · ready to wire
                    </div>
                </footer>
            </main>
        </div>
    );
}
