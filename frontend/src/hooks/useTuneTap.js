// ON NOW TV TUNES — useTuneTap (v2.10.47)
// =============================================================
// Combined tap-vs-hold handler for music tiles.
//
//   • Quick tap        → `onTap(item)` (typically: play the
//                          track / navigate to album / start
//                          radio stream / open podcast).
//   • Long-press OK    → fire `tunes:request-add-to-library`
//                          which the globally-mounted
//                          MusicAddToLibraryModal listens for.
//   • Re-tap while a
//     track is already
//     the current state → open the FullScreen player
//                          (`tunes:open-fullscreen` event).
//
// The last bullet implements the user-requested:
//   "Clicking the same song again should open full screen as
//    well.  Then push back and it'll go back to the previous
//    screen."
//
// Internally wraps `useLongPress`.  The tile component just
// spreads the returned props on its <button>/<a>.

import useLongPress from '../hooks/useLongPress';
import { useMusicPlayer } from '../hooks/useMusicPlayer';

export default function useTuneTap({ kind, item, list, onTap }) {
    const { state, controls } = useMusicPlayer();

    const isCurrent =
        kind === 'track' &&
        state?.current?.id === item?.id &&
        state?.kind === 'track';

    const handleTap = () => {
        if (kind === 'track' && isCurrent) {
            // v2.10.47 — Re-tapping the currently-playing track
            // opens the FullScreen player instead of stopping /
            // restarting playback.  Press BACK to return to the
            // previous screen (MusicLayout's back-handler closes
            // the overlay).
            try { window.dispatchEvent(new CustomEvent('tunes:open-fullscreen')); }
            catch { /* ignore */ }
            return;
        }
        if (typeof onTap === 'function') {
            onTap(item);
            return;
        }
        // Sensible defaults per kind when the caller didn't pass
        // an explicit onTap.
        if (kind === 'track' && controls?.playTrack) {
            controls.playTrack(item, list || [item]);
        } else if (kind === 'radio' && controls?.playRadio) {
            controls.playRadio(item);
        }
    };

    const handleLongPress = () => {
        try {
            window.dispatchEvent(new CustomEvent('tunes:request-add-to-library', {
                detail: { kind, item },
            }));
        } catch { /* ignore */ }
    };

    return useLongPress(handleLongPress, handleTap);
}
