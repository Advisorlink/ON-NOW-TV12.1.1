# ROLLBACK NOTES — 11 June 2026

User requested a rollback of frontend navigation code to the state of **1 week ago (4 June 2026)** while preserving:
- All icons (avatars, profile picker, new icons added this week)
- All animations / GIFs (boot splash, orbital loader, spinning logo)
- Android scrubbing fix (ExoPlayer)
- Android "Skip next episode" fix
- All Android launcher work (boost button, wifi/offline overlays)
- Music / Karaoke app updates

This document is a complete record of exactly what was done and what was preserved, so nothing is lost or untraceable.

---

## Reference points

| Label | Git SHA | Date | Meaning |
|---|---|---|---|
| BEFORE_ROLLBACK | `HEAD` at time of rollback | 11 Jun 2026 (after the "100-credit deep dive") | Broken navigation state user complained about |
| TARGET (good) | `ef5b5f92` | 4 Jun 2026 16:27 UTC | Last commit of 4 June — the state user said "was working last week" |

Full backup of the BEFORE state (every file as it stood right before the rollback) is saved at:
- `/app/_pre_rollback_backup_2026-06-11/frontend_src/` — full copy of `/app/frontend/src`
- `/app/_pre_rollback_backup_2026-06-11/android/` — full copy of `/app/android`

Nothing is lost. If anything ends up wrong after the rollback, every original file can be restored from this folder.

---

## What was REVERTED to 4 June 2026 state

These are the navigation-related files where the bad behaviour lived. They were restored to their `ef5b5f92` state via `git checkout ef5b5f92 -- <path>`.

### Components (shelves, tiles, focus, nav)
- `frontend/src/components/AddToListModal.jsx`
- `frontend/src/components/CastRow.jsx`
- `frontend/src/components/ContinueWatchingShelf.jsx`
- `frontend/src/components/HeroBillboard.jsx`
- `frontend/src/components/KidsTabGridView.jsx`
- `frontend/src/components/MobileBottomNav.jsx`
- `frontend/src/components/NetworkPosterTile.jsx`
- `frontend/src/components/PosterTile.jsx`
- `frontend/src/components/RecommendationsRow.jsx`
- `frontend/src/components/SeriesEpisodes.jsx`
- `frontend/src/components/SideNav.jsx`
- `frontend/src/components/TabGridView.jsx`
- `frontend/src/components/UpcomingMoviesShelf.jsx`
- `frontend/src/components/UpdateGate.jsx`

### Hooks (spatial focus / back guards)
- `frontend/src/hooks/useKidsBackGuard.js`
- `frontend/src/hooks/useKidsKioskGuard.js`
- `frontend/src/hooks/useSpatialFocus.js`

### Lib (continue-watching logic, networks)
- `frontend/src/lib/continueWatching.js`
- `frontend/src/lib/networks.js`

### Pages (home / detail / library / player / search etc.)
- `frontend/src/pages/Detail.jsx`
- `frontend/src/pages/Home.jsx`
- `frontend/src/pages/KidsHome.jsx`
- `frontend/src/pages/Library.jsx`
- `frontend/src/pages/Network.jsx`
- `frontend/src/pages/Person.jsx`
- `frontend/src/pages/Player.jsx`
- `frontend/src/pages/Search.jsx`

---

## What was DELETED (new file added this week that drove the bad nav behaviour)

- `frontend/src/lib/navLoader.js`

This file did not exist on 4 June. It was the full-screen "LOADING TITLE" overlay system the agent introduced and then partially undid. Removing it ends that whole experiment cleanly.

---

## What was KEPT at the current (11 June) state — icons, animations, music, Android fixes

### Icons & avatars (kept as-is)
- `frontend/src/lib/avatars.jsx` — full avatar/icon library
- `frontend/src/lib/avatarTransform.js` — NEW this week — icon transform helpers (kept)
- `frontend/src/lib/img.js` — image/icon utility
- `frontend/src/lib/profileBackup.js` — profile (icon) backup
- `frontend/src/lib/profiles.js` — profile model
- `frontend/src/pages/ProfileEdit.jsx` — icon picker UI
- `frontend/src/pages/ProfileSelect.jsx` — profile/icon selector

### Animations / GIFs (kept as-is)
- `frontend/src/components/BootSplash.jsx` — boot splash animation
- `frontend/src/components/OrbitalLoader.jsx` — NEW this week — orbital spinner
- `frontend/src/components/SpinningLogo.jsx` — NEW this week — spinning logo animation
- `frontend/src/index.css` — animation / icon CSS

### Music & Karaoke (kept as-is)
- `frontend/src/pages/music/KaraokeHome.jsx`
- `frontend/src/pages/music/MusicAlbum.jsx`
- `frontend/src/pages/music/MusicHome.jsx`
- `frontend/src/pages/music/tunes.css`

### App router (kept as-is)
- `frontend/src/App.js` — kept at HEAD so new ProfileEdit / ProfileSelect / Music routes still resolve

### Android — ALL kept (none touched)
- `android/vesper-tv/app/src/main/java/tv/vesper/app/ExoPlayerActivity.kt` — Skip-next-episode fix (KEPT)
- `android/vesper-tv/app/src/main/java/tv/vesper/app/PlayerOverlay.kt` — Scrubbing fix (KEPT)
- `android/vesper-tv/app/src/main/java/tv/vesper/app/MainActivity.kt` — KEPT
- `android/onnowtv-launcher/...` — Boost button, wifi/offline overlays, all new icons (KEPT)
- All other Android modules — KEPT

---

## Files that were ALREADY DELETED earlier this week (left as deleted, not restored)

- `frontend/src/lib/sportsMatch.js`
- `frontend/src/pages/SportsGuide.jsx`

These were removed earlier in the week as part of a separate decision (Sports feature retirement). I did not bring them back because that was an intentional deletion unrelated to navigation. If you want them back, say the word and I will restore from the backup folder.

---

## How to undo this rollback (if you ever want to)

```bash
# Restore EVERYTHING to the pre-rollback (11 June) state:
rm -rf /app/frontend/src
cp -r /app/_pre_rollback_backup_2026-06-11/frontend_src /app/frontend/src
sudo supervisorctl restart frontend
```

That brings the entire frontend back to exactly the broken state it was in right before this rollback, without losing any history.

---

## Date / time
Performed: 11 June 2026

---

## Post-rollback follow-up fixes (same day, 11 June 2026)

After the rollback the user reported it's working again, and asked for three further targeted fixes on top of the restored navigation. All three were applied without re-introducing any of the bad navigation behaviour:

### Fix A — Pre-buffer next episode at 5 min (was 4 min)
**File:** `android/vesper-tv/app/src/main/java/tv/vesper/app/ExoPlayerActivity.kt`
- Changed the prime-job window from `remaining in 0..240_000` (4 min) to `remaining in 0..300_000` (5 min).
- Updated the surrounding comment block (`v2.10.46`) so the threshold is documented.
- Pill surface threshold left at 3 min.

### Fix B — Continue Watching now reflects the episode the user actually skipped to
**File:** `frontend/src/lib/continueWatching.js`
- `getEntries()` now dedupes by show: for series (cwId contains `:`), only the entry with the most recent `updatedAt` per IMDB-prefix is returned. Movies dedupe by their own id (no-op).
- `syncFromNative()` rewritten into three passes:
  1. Clone metadata from any sibling entry of the same show when the native player has written a brand-new cwId (the in-place Skip-Next-Episode swap path). The new entry gets the new SxxExx label, current positionMs/durationMs, fresh `updatedAt`, and the sibling's poster/backdrop/synopsis/genres/route. **`streamUrl` and `subtitleUrl` are deliberately blanked** so the resume click routes through Detail (picking a fresh source for the new episode rather than replaying the old one).
  2. Refresh progress on already-existing entries (previous behaviour).
  3. Drop entries that crossed the "completed" threshold (within 30 s of duration) so the OLD episode's row disappears after the swap wrote it to 100 %.
- Added small helpers `showPrefixOf`, `seasonEpisodeOf`, `rewriteEpisodeLabel`.

### Fix C — Long-press OK no longer auto-deletes the tile
**File:** `frontend/src/components/ContinueWatchingShelf.jsx`
- Inside `CWTile`, on the confirm card:
  - Cancel button is now rendered **first** in the DOM so the residual synthetic click from releasing OK lands on Cancel (harmless), not Remove.
  - Cancel is explicitly focused via `cancelBtnRef.focus()` after React commits the confirm card.
  - Remove button is wrapped in `guardedRemove` — a 600 ms grace period during which any click on Remove is ignored. After 600 ms the user has clearly released OK and any subsequent click is a deliberate confirmation.
- Long-press timer (700 ms) and auto-repeat suppression logic left untouched.

**Verification:** dev server compiled cleanly (only a pre-existing HeroBillboard ESLint warning from the June 4 code). Screenshot captured of the home page — UI loads with icons and animations intact. Native (Kotlin) change will compile via the user's GitHub Actions CI; cannot be built locally (ARM container vs x86 AAPT2).

---

## Second round of follow-up fixes (same day, 11 June 2026)

User reported four more items on top of the first round. All four addressed without re-touching the spatial-focus / Detail-state plumbing the user already rejected:

### Fix D — Profile selector restored to the SideNav rail
**File:** `frontend/src/components/SideNav.jsx`
- Re-added `useEffect`, `UserCircle2`, `getActiveProfile`, `AvatarCircle` imports.
- Re-added `profileRev` state + `vesper:profile-change` listener so the avatar refreshes when the profile is changed elsewhere.
- Re-added `activeProfile` `React.useMemo` (with intentional `profileRev` / `location.pathname` deps; ESLint warning suppressed).
- Re-added `openProfilePicker` that collapses the rail and navigates to `/profiles`.
- Pinned a new profile button at the BOTTOM of the rail (`mt-auto`) with `data-testid="nav-profile"`, focusable, shows the active avatar (or a generic `UserCircle2`) when collapsed; expanded view shows the profile name. Not in the NAV array, so it doesn't disturb the existing nav layout.

### Fix E — Tile-click on a movie goes straight to autoplay (no streams visible)
**Files:**
- `frontend/src/components/PosterTile.jsx` — when `item.type === 'movie'`, `onTap` now navigates to `/title/movie/<id>?autoplay=1`. Series tiles unchanged (they need to land on the episode picker).
- `frontend/src/components/NetworkPosterTile.jsx` — same `?autoplay=1` query added for movies after IMDB resolution.
- `frontend/src/pages/Detail.jsx` —
  - The non-party autoplay `useEffect` no longer requires the per-user "Autoplay 1080p" preference when the URL carries an explicit `?autoplay=1`. Tile-click intent overrides the toggle.
  - When no 1080p candidate is found but autoplay was explicitly requested, the player falls back to the first direct stream → first stream so the user still lands in the player rather than the picker.
  - Added a full-screen `data-testid="detail-autoplay-loader"` scrim that covers the entire Detail page while `autoplayRequested && type==='movie' && !autoplayFired`. Renders a spinner + "Finding stream / Starting playback" caption + (once metadata resolves) the title — so the user only ever sees a clean loader, never the streams picker, before the native player takes over. The "Coming Soon" unavailable modal still surfaces if no stream can be found (loader hides itself when `showUnavailableModal` is true).

### Fix F — Next-episode pre-buffer at 6 min, pill at 5 min
**File:** `android/vesper-tv/app/src/main/java/tv/vesper/app/ExoPlayerActivity.kt`
- `shouldPrime` window widened from `0..300_000` → `0..360_000` (6 min).
- `show` (pill) window widened from `0..180_000` → `0..300_000` (5 min).
- Comments updated to `v2.10.46-c` so the next agent sees the latest decision.

### Fix G — TV-show "metadata" → 1 s loading screen
**Status: SKIPPED** (user explicitly said "if you can't do that, then that's fine, just leave it"). Touching the series detail loading path risks the exact navigation regressions just rolled back, so I did not modify it.

---

## Third round of follow-up fixes (same day, 11 June 2026)

User reported two more regressions:

### Fix H — Movie Detail page now reliably auto-focuses the primary CTA
**File:** `frontend/src/pages/Detail.jsx`
- The mount-time late-arrival watcher (around line 695) was only matching `[data-testid^="detail-play-"]:not([disabled])` and only for 4 s. Now it ALSO matches `[data-testid="detail-choose-stream"]:not([disabled])` (the CTA shown when the user's Autoplay-1080p toggle is OFF), and the watch window is extended from 4 s → 10 s so slow addons that take 6-9 s to resolve still get their CTA auto-focused.
- Added a **second, narrower hook** that fires the moment `streamLoading` flips false on a movie page. It explicitly focuses the same primary CTA (autoplay OR choose-stream) and honours user-moved focus (won't steal focus from someone browsing the cast row or episode list).
- Net effect: clicking into a movie now lands focus on the primary action button as soon as the streams resolve — user can press OK immediately without ever pressing DOWN.

### Fix I — Restored "Watching" yellow + "Watched" green badges on episode cards
**File:** `frontend/src/components/SeriesEpisodes.jsx`
- The yellow "Watching" badge (`data-testid="watching-<s>-<e>"`) was lost in the June-4 rollback. Reinstated with the original style: amber 250/204/21, clock SVG, "WATCHING" caption — shown when the user has any progress on the episode but hasn't crossed the watched threshold.
- The existing "Watched" badge was using `--vesper-blue-rgb` (blue) which blended into the rest of the page. Restored the original GREEN palette (rgba(34,197,94)) so it's clearly distinct.

---

## Fourth round of follow-up fixes (same day, 11 June 2026)

User reported two more regressions:

### Fix J — Focus bounce-back to the original tile after Add-to-List
**File:** `frontend/src/components/AddToListModal.jsx`
- The modal already captured `document.activeElement` into `lastFocusedRef` on open and called `.focus()` on close. But the visual highlight ring (driven by `data-focused="true"`, not the browser's `:focus-visible`) wasn't being repainted on the restored tile, so the user saw focus snap to nothing.
- Updated the `close()` flow to also strip `data-focused` from any lingering elements, set `data-focused="true"` on the restored tile, and `scrollIntoView({block:'nearest', inline:'nearest'})` in case the tile drifted off-screen while the modal was up. Now works for movies, series and actor tiles — anywhere `vesper:request-add-to-list` is dispatched from.

### Fix K — Episode preview image left-edge clipping
**File:** `frontend/src/components/SeriesEpisodes.jsx`
- The episode card's outer `<li>` had `overflow-hidden` (June 4 state). When the inner button scales up via `data-focus-style="quiet"` on focus, the LI was clipping the left edge of the thumbnail.
- Dropped `overflow-hidden` (kept `rounded-2xl`). The inner thumbnail still has its own `rounded-xl` so the rounded corners are preserved. Comment v2.10.46-e added so the next agent understands why.

---

## Fifth round of follow-up fixes (same day, 11 June 2026)

User requested:

### Fix L — Rail re-order: Search → top, Live TV / Sports Guide removed
**File:** `frontend/src/components/SideNav.jsx`
- NAV array re-ordered: Search is now item 0, above Home.
- Removed Live TV entry (its experience lives in the dedicated `onnowtv-livetv` app — duplicating it in Vesper added clutter).
- Removed Sports Guide entry (feature retired earlier this week — `SportsGuide.jsx` and `lib/sportsMatch.js` were deleted, so the menu item was leading nowhere).
- Cleaned up `Radio`, `Trophy`, `Plug` icon imports that are no longer used.

### Fix M — Incremental "type-ahead" search
**File:** `frontend/src/pages/Search.jsx`
- Added `searchSeqRef` (monotonic counter to discard out-of-order responses) and `searchDebounceRef` (250 ms debounce timer).
- New `scheduleTypeAheadSearch(value)` helper schedules a debounced `doSearch` on every keystroke ≥ 2 chars. Shorter queries reset results and bump the seq so any in-flight request can't repaint stale results.
- `doSearch` tags each call with the current seq and bails on state mutation if a newer query has fired since.
- Wired `scheduleTypeAheadSearch` into the `TVKeyboard onChange`. Existing Search button + Enter handler still work for explicit submit. The user now sees results populate as they type — no more "press Search and wait" friction.

### Fix N — Calendar redesigned for 16:9 fit (D-pad navigable, rectangular weekly thumbnails)
**File:** `frontend/src/components/LibraryCalendar.jsx`
- Outer container now `display:flex; flexDirection:column; overflow:hidden` — calendar NEVER scrolls; content auto-sizes to the viewport. Padding tightened from `40/64/80/120` to `20/48/24/100`.
- Month grid + Detail panel row now uses `flex: 1 1 auto; minHeight: 0` so it absorbs whatever vertical space is left after the header and the "This week" rail.
- `MonthGrid`:
  - Wrapper padding 24 → 16.
  - Day cells lost their `aspectRatio: '1 / 1.05'`; cells now flow into a 6-row `gridTemplateRows: 'repeat(6, minmax(0, 1fr))'` so they share the available height evenly.
  - Day-of-week label gap and cell gap 8 → 6 for a denser layout.
- `DetailPanel`: removed the rigid `minHeight: 360`; now `height: 100%`, `flexDirection: column`. The episode list inside scrolls (`overflow-y: auto`) when there are too many episodes to fit, instead of growing the panel.
- `UpcomingRail`:
  - Rail tiles 280 px → 240 px (still 16:9 rectangular thumbnails). 6-7 fit on screen without horizontal scrolling on most TVs.
  - Section `marginTop` 40 → 18 and pinned `flex: 0 0 auto` at the bottom of the flex column.
- All existing D-pad focus attributes (`data-focusable="true"`, `data-focus-style="tile"`) preserved on the day cells and rail tiles — Header arrow buttons, day cells and rail tiles are all remote-navigable as before. No feature loss.

---

## Sixth round of follow-up fixes (same day, 11 June 2026)

User reported three regressions:

### Fix O — Reverted type-ahead search
**File:** `frontend/src/pages/Search.jsx`
- User didn't like results firing after the first 2 letters. Removed `searchSeqRef`, `searchDebounceRef`, `scheduleTypeAheadSearch` and reverted `doSearch` and the `TVKeyboard onChange` to the pre-type-ahead behaviour. Submit via the Search button / Enter key only.

### Fix P — Focus bounce-back now survives D-pad arrows
**File:** `frontend/src/components/AddToListModal.jsx`
- The Round-4 fix restored focus via `lastFocusedRef.focus()` + `data-focused="true"`, which made the highlight ring snap back — but the user said it disappeared on the FIRST Left / Right press. Root causes: (a) React sometimes recycles the tile DOM during the modal's life so the captured `node` reference goes stale, and (b) async focus events from the modal's unmount can steal focus back to body the moment after we restore it.
- Now we ALSO capture `data-testid` on open and fall back to a live `querySelector` lookup if the original node is no longer in `document.body`.
- The restore now runs THREE times — synchronously, on next paint (rAF), and at +120 ms — so any late blur from the modal unmount can't yank focus to body.
- Works for movie / TV / actor tiles — any element that long-pressed into `vesper:request-add-to-list`.

### Fix Q — Long-press OK on Continue Watching no longer dismisses on release
**File:** `frontend/src/components/ContinueWatchingShelf.jsx`
- The Round-1 fix grace-guarded ONLY the Remove button. User reported that releasing OK while focus was on Cancel still dismissed the modal — so they were stuck having to keep OK held while pressing Left / Right to reach Remove.
- Replaced the timestamp-based guard with `confirmArmedRef` that gates BOTH buttons. `armed` is `false` on confirm-card mount; auto-arms after 700 ms OR on the user's first D-pad arrow press (whichever happens first). Either path then permits clicks on Cancel / Remove.
- Cancel is also focused twice (microtask + rAF) AND given `data-focused="true"` programmatically so the highlight ring is visible immediately — the user can release OK and IMMEDIATELY press Left / Right to navigate Cancel ↔ Remove.

---

## Seventh round of follow-up fixes (same day, 11 June 2026)

User confirmed Rounds 1–6 are "exactly where it needs to be, working perfectly" and asked to "lock it in". One last targeted issue remained:

### Fix R — Episode click with Autoplay ON no longer shows the streams drawer
**File:** `frontend/src/components/SeriesEpisodes.jsx`
- Previously `handleEpisodeClick` called `setOpenEpisodeId(ep.id)` UP-FRONT, then fetched streams, then fired autoplay. That meant the inline streams drawer briefly flashed before the player took over — exactly the "links" the user said shouldn't appear with Autoplay on.
- Now: with Autoplay ON we DON'T open the drawer up-front. Streams are fetched silently and the player launches.
- Candidate selection broadened from "1080p only" to "1080p → first direct → first stream" so the user still lands in the player when no 1080p exists.
- The drawer opens ONLY as a fallback if absolutely no playable stream can be picked (so the user isn't stranded). With Autoplay OFF the previous expand-on-tap behaviour is unchanged.

---

## Eighth round — Onboarding redesign (11 June 2026)

User: "make it fit the 16 by 9 screen properly, it just looks so stretched out. Redesign the whole thing. Take out all the em dashes."

### Fix S — Onboarding redesigned for proper 16:9 fit
**File:** `frontend/src/components/Onboarding.jsx`
- Outer container converted from `flex items-center justify-center` (which let columns drift to opposite edges) into a CSS grid with three fixed rows: `clamp(60px, 6.5vh, 96px) 1fr clamp(80px, 8vh, 120px)` for header / hero / footer.
- Hero row is a 2-col grid (`1.25fr : 1fr`, `columnGap clamp(40px, 4vw, 64px)`) constrained to `maxWidth: 1240` and centered. The "stretched apart" gap is gone.
- Header strip: brand left, Skip right, both on one row.
- Title font shrunk from `clamp(36px, 4vw, 64px)` to `clamp(28px, 3vw, 48px)` so it doesn't overpower the column on a 1080p TV.
- Body max-width tightened from `58ch` to `52ch`; eyebrow icon trimmed 38 → 34 px.
- Right scene now `width: 100%, maxWidth: 420px, justifySelf: center` so the D-pad / mockup never balloons past 420 px.
- Nav buttons + step indicator + progress bar pinned to the bottom grid row so they're always in the same place regardless of which scene is shown.
- All keyframes (`vesperOnbFade`, `vesperOnbGlow`, `vesperOnbPulse`, etc.) preserved.

### Em-dash cleanup
- Removed the user-visible em-dash in the `no-mouse` step body (only one in copy that's shown on screen).
- Collapsed double-spaces between sentences inside all body strings so the copy reads cleaner.
- Code-comment em-dashes left as-is (never rendered to the user).

---

## Ninth round — Onboarding fully rebuilt (11 June 2026)

User: "I want the whole onboarding slides redesigned completely. I don't like them being side by side." Round 8's grid-with-side-by-side was discarded.

### Fix T — Onboarding switched to a vertically-stacked centered slide
**File:** `frontend/src/components/Onboarding.jsx`
- Outer is now a flex column (header / hero / footer). The hero centers everything horizontally and vertically.
- The scene art is the centerpiece — capped at `min(420px, 36vh)` with a soft radial glow halo behind it so each illustration feels deliberate and theatrical.
- The eyebrow chip + title + body cascade DIRECTLY BELOW the scene, all centred, with a 54 ch body column. Reads as one composed slide.
- Top strip is a balanced 3-cell flex: brand left ("ON NOW TV · WELCOME TOUR"), "STEP 01 / 15" centred (zero-padded for premium feel), Skip button right.
- Bottom rail uses a PIP progress (one dot per step, active step elongates into a 28 px bar) instead of the linear progress line. Past steps glow at lower intensity; pending steps are dim.
- Each scene transition runs a 520 ms `vesperOnbSceneIn` keyframe (blur-out + scale + translate-up) so the slides feel cinematic instead of just snapping in.
