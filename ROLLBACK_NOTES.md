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
