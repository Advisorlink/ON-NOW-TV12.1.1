// YouTubeIFrameHost.jsx — Mounts ONE hidden YouTube IFrame Player
// API host into the DOM.  The PlayerEngine in useMusicPlayer
// reaches for this host via the well-known div id and drives it
// from there.
//
// Why an offscreen iframe instead of a visible player:
// - We want OUR custom album-art + lyrics + controls UI, not
//   YouTube's red-bar branding.
// - YouTube's API still emits audio when the iframe is offscreen,
//   so we get all the benefits of their player (PoToken, signature
//   ciphers, ad handling, signed CDN URLs) without the UI.
// - WebView on Android needs the iframe to be ATTACHED to the DOM
//   for audio to play — positioning it offscreen at width:1 px is
//   the documented workaround.
//
// Loaded once at the top of /music via MusicLayout.
import React, { useEffect, useRef } from 'react';

const YT_API_SRC = 'https://www.youtube.com/iframe_api';

export function YouTubeIFrameHost() {
    const hostRef = useRef(null);

    useEffect(() => {
        // Inject the YouTube IFrame API script once.
        if (typeof window === 'undefined') return undefined;
        if (!window.__ytApiLoadingPromise) {
            window.__ytApiLoadingPromise = new Promise((resolve) => {
                if (window.YT && window.YT.Player) { resolve(window.YT); return; }
                // YouTube's API calls this hook when the script
                // finishes downloading.  We chain off it so any
                // pre-existing handler still fires.
                const existing = window.onYouTubeIframeAPIReady;
                window.onYouTubeIframeAPIReady = () => {
                    if (typeof existing === 'function') existing();
                    resolve(window.YT);
                };
                const tag = document.createElement('script');
                tag.src = YT_API_SRC;
                tag.async = true;
                document.body.appendChild(tag);
            });
        }
        // Hot-reload guard: if the host div is already in the DOM
        // (e.g. React re-mounted MusicLayout) don't insert a second.
        return undefined;
    }, []);

    return (
        <div
            ref={hostRef}
            id="onnowtv-ytplayer-host"
            aria-hidden="true"
            style={{
                position: 'fixed',
                width: 1,
                height: 1,
                bottom: 0,
                left: 0,
                opacity: 0,
                pointerEvents: 'none',
                zIndex: -1,
            }}
        >
            <div id="onnowtv-ytplayer-target" />
        </div>
    );
}
