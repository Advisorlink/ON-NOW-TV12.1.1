import React from 'react';
import { Heart, Music2 } from 'lucide-react';

export default function MusicLibrary() {
    return (
        <div data-testid="music-library">
            <h1 className="tunes-page-title">Your Library</h1>
            <p className="tunes-page-subtitle">Playlists, liked songs, and recents — coming soon.</p>

            <div style={{
                padding: 60,
                textAlign: 'center',
                background: 'var(--tunes-glass-bg)',
                borderRadius: 18,
                border: '1px solid var(--tunes-glass-border)',
                marginTop: 40,
            }}>
                <Heart size={48} color="var(--tunes-accent)" style={{ marginBottom: 18 }} />
                <h2 style={{ fontSize: 22, fontWeight: 700, margin: '0 0 8px' }}>Playlists are coming soon</h2>
                <p style={{ color: 'var(--tunes-text-dim)', maxWidth: 480, margin: '0 auto' }}>
                    Soon you'll be able to save your favourite tracks, build playlists, and pick up exactly where you left off — even across devices.
                </p>
            </div>
        </div>
    );
}
