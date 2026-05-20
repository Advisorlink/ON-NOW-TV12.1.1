This app runs on an HK1 Android TV box via a WebView. Target display: 1920x1080. All layouts must account for WebView rendering differences vs. desktop Chrome — do not assume browser spacing or overflow behavior. Reference this file before making any layout changes to the home screen.

---

# 🔒 PERMANENT INVARIANTS — DO NOT REGRESS

These behaviours were explicitly approved by the user on **v2.7.19 (Feb 2026)** and locked in as a permanent baseline.  Any future change that breaks ANY of these is a regression and must be reverted, not patched.

## Home screen — D-pad scroll engine

1. **One scroll-snap container.**  The Home shelves region uses
   `scroll-snap-type: y mandatory` + `scroll-behavior: auto` on
   `[data-testid="shelves-region"]`.  Do not add `scroll-behavior:
   smooth` or any CSS transition on `scroll-position`.

2. **Every row is a `[data-testid="shelf-page"]`** with
   `scroll-snap-align: center` and a height equal to
   `window.innerHeight - heroBillboardHeight`.  Row heights MUST be
   identical so snap commits to integer multiples of one
   `shelfPageHeight`.

3. **One code path for all rows.**  When a D-pad move lands focus
   inside a shelf-page, `useSpatialFocus.focusEl` MUST call
   `snapPage.scrollIntoView({ behavior: 'auto', block: 'center',
   inline: 'nearest' })` — the snap-row fast-path.  The per-pixel
   row-pin math (`targetTop = scrollerTop + 22 %`) is reserved for
   the Detail page / Library page where there is no snap container.

4. **Focus ring is an outline, not a box-shadow.**  CSS:
   ```
   [data-focus-style='tile']:focus-visible,
   [data-focus-style='tile'][data-focused='true'],
   [data-focus-style='pill']:focus-visible,
   [data-focus-style='pill'][data-focused='true'] {
       outline: 3px solid var(--vesper-blue-bright) !important;
       outline-offset: 2px !important;
   }
   ```
   Outlines are immune to inline `style.boxShadow` overrides on
   individual tiles (the bug that hid the ring on NetworkTile in
   v2.7.17 and earlier).  Do not replace the outline with a
   box-shadow.  Do not remove `!important`.

5. **Empty shelf-pages must not render.**  When Continue Watching
   has no items, `<ShelfPage><ContinueWatchingShelf/></ShelfPage>`
   must be skipped at the Home.jsx level (`{hasCW && ...}`).  Same
   for ForYou (`{hasViewingStyle && ...}`).  Empty snap-pages waste
   a scroll click and break the "every row treated the same" rule.

## Player — VOD

6. **VOD startPlayback is minimal (Stremio-style):**
   `media.setHWDecoderEnabled(true, false)` + `:network-caching=1500`.
   Nothing else for direct HTTPS movie / TV streams.  NEVER add
   `:no-mediacodec-dr` (caused green static lines in v2.7.16).
   NEVER add `:drop-late-frames`, `:clock-jitter=0`, or any avcodec
   tweak to the VOD branch (broke playback in v2.7.12).

7. **Force-SDR is opt-in via Settings.**  The toggle writes
   `force_sdr_playback` to `SharedPreferences("onnowtv_player")`.
   When ON, VOD adds `:codec=avcodec`.  Default OFF.  Do not flip
   the default.

## SSL — Nginx VPS

8. **Cert chain must remain RSA-2048 / ISRG Root X1.**  Older HK1
   boxes reject ECDSA Let's Encrypt certs.  Do not switch to ECDSA
   or any newer cert format.

---

# How to verify the home-snap regression test

Run the Playwright check at `/app/frontend/tests/home-snap.spec.js`
(see file).  Expected output for an ArrowDown sequence from CW:

```
scrollTop: 0 → 460 → 920 → 1380 → 1840 → 2300 → 2760 ...
```

Each value MUST be an exact integer multiple of `shelfPageHeight`.
Any intermediate value (e.g. 137, 312, etc.) means smooth-scroll
sneaked back in — that is a regression and must be fixed.
