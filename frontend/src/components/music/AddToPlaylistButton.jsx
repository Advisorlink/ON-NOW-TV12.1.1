/**
 * AddToPlaylistButton — dropdown that lists existing playlists +
 * a "+ New playlist" entry.  Adds the given track to the chosen
 * playlist.  Stops click-propagation so it can sit on cards.
 */
import React, { useEffect, useRef, useState } from 'react';
import { Plus, ListPlus, Check } from 'lucide-react';
import {
    getPlaylists,
    createPlaylist,
    addTrackToPlaylist,
    subscribeMusicLibrary,
} from '../../lib/music-library';

export function AddToPlaylistButton({ track, label = false }) {
    const [open, setOpen] = useState(false);
    const [playlists, setPlaylists] = useState(getPlaylists());
    const [createMode, setCreateMode] = useState(false);
    const [name, setName] = useState('');
    const [added, setAdded] = useState(false);
    const ref = useRef(null);

    useEffect(() => {
        const u = subscribeMusicLibrary(() => setPlaylists(getPlaylists()));
        return u;
    }, []);

    useEffect(() => {
        if (!open) return undefined;
        const onClick = (e) => {
            if (ref.current && !ref.current.contains(e.target)) setOpen(false);
        };
        document.addEventListener('mousedown', onClick);
        return () => document.removeEventListener('mousedown', onClick);
    }, [open]);

    const addTo = (pid) => {
        addTrackToPlaylist(pid, track);
        setAdded(true);
        setTimeout(() => { setAdded(false); setOpen(false); }, 900);
    };

    const onCreate = () => {
        const n = name.trim();
        if (!n) return;
        const id = createPlaylist(n);
        addTrackToPlaylist(id, track);
        setName('');
        setCreateMode(false);
        setAdded(true);
        setTimeout(() => { setAdded(false); setOpen(false); }, 900);
    };

    return (
        <div ref={ref} style={{ position: 'relative', display: 'inline-block' }}>
            <button
                type="button"
                onClick={(e) => { e.stopPropagation(); e.preventDefault(); setOpen((v) => !v); }}
                aria-label="Add to playlist"
                title="Add to playlist"
                data-testid={`add-to-playlist-${track?.id}`}
                tabIndex={-1}
                style={{
                    display: 'inline-flex', alignItems: 'center', gap: label ? 8 : 0,
                    padding: label ? '8px 14px' : 8,
                    background: added
                        ? 'linear-gradient(135deg, #10b981, #34d399)'
                        : 'rgba(255,255,255,0.06)',
                    border: added ? 'none' : '1px solid rgba(255,255,255,0.12)',
                    borderRadius: 999,
                    cursor: 'pointer',
                    color: '#fff',
                    fontSize: 13,
                    fontWeight: 600,
                    transition: 'transform 0.15s, background 0.15s',
                }}
            >
                {added ? <Check size={18} /> : <ListPlus size={18} />}
                {label && <span>{added ? 'Added' : 'Playlist'}</span>}
            </button>

            {open && (
                <div
                    style={{
                        position: 'absolute',
                        top: 'calc(100% + 8px)',
                        right: 0,
                        minWidth: 260,
                        maxHeight: 360,
                        overflowY: 'auto',
                        background: 'rgba(20,3,40,0.96)',
                        border: '1px solid rgba(255,255,255,0.12)',
                        borderRadius: 14,
                        padding: 8,
                        boxShadow: '0 20px 50px rgba(0,0,0,0.55)',
                        zIndex: 50,
                        backdropFilter: 'blur(20px)',
                    }}
                    onClick={(e) => e.stopPropagation()}
                >
                    {!createMode && (
                        <>
                            {playlists.length === 0 && (
                                <div style={{ padding: 14, color: 'rgba(255,255,255,0.55)', fontSize: 13 }}>
                                    No playlists yet. Create one below.
                                </div>
                            )}
                            {playlists.map((pl) => (
                                <button
                                    key={pl.id}
                                    type="button"
                                    onClick={() => addTo(pl.id)}
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    style={{
                                        display: 'flex',
                                        alignItems: 'center',
                                        justifyContent: 'space-between',
                                        width: '100%',
                                        padding: '10px 14px',
                                        background: 'transparent',
                                        border: '1px solid transparent',
                                        borderRadius: 10,
                                        cursor: 'pointer',
                                        color: '#fff',
                                        fontSize: 13,
                                        textAlign: 'left',
                                    }}
                                    onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,0.06)'; }}
                                    onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
                                >
                                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                        {pl.name}
                                    </span>
                                    <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.5)', marginLeft: 8 }}>
                                        {pl.tracks.length} tracks
                                    </span>
                                </button>
                            ))}
                            <div style={{ height: 1, background: 'rgba(255,255,255,0.08)', margin: '8px 0' }} />
                            <button
                                type="button"
                                onClick={() => setCreateMode(true)}
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                style={{
                                    display: 'flex', alignItems: 'center', gap: 8,
                                    width: '100%',
                                    padding: '10px 14px',
                                    background: 'transparent',
                                    border: '1px solid transparent',
                                    borderRadius: 10,
                                    cursor: 'pointer',
                                    color: 'var(--tunes-accent-3)',
                                    fontSize: 13,
                                    fontWeight: 600,
                                    textAlign: 'left',
                                }}
                            >
                                <Plus size={16} />
                                New playlist
                            </button>
                        </>
                    )}
                    {createMode && (
                        <div style={{ padding: 6 }}>
                            <input
                                type="text"
                                placeholder="Playlist name…"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                onKeyDown={(e) => { if (e.key === 'Enter') onCreate(); if (e.key === 'Escape') setCreateMode(false); }}
                                autoFocus
                                style={{
                                    width: '100%',
                                    padding: '12px 14px',
                                    background: 'rgba(255,255,255,0.05)',
                                    border: '1px solid rgba(255,255,255,0.12)',
                                    borderRadius: 10,
                                    color: '#fff',
                                    outline: 'none',
                                    fontSize: 14,
                                }}
                            />
                            <button
                                type="button"
                                onClick={onCreate}
                                disabled={!name.trim()}
                                style={{
                                    marginTop: 8,
                                    width: '100%',
                                    padding: '10px 14px',
                                    background: name.trim()
                                        ? 'linear-gradient(135deg, var(--tunes-accent), var(--tunes-accent-2))'
                                        : 'rgba(255,255,255,0.05)',
                                    border: 'none',
                                    borderRadius: 10,
                                    color: '#fff',
                                    fontWeight: 600,
                                    cursor: name.trim() ? 'pointer' : 'not-allowed',
                                    opacity: name.trim() ? 1 : 0.5,
                                }}
                            >
                                Create + add
                            </button>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
