# Vesper Fix Batch — Feb 2026 (v2.8.88)

Full status of the 14-item Vesper fix list the user requested in
this session, with a clean handoff for the next agent.

---

## ✅ Done in this batch (v2.8.88)

| # | Item | File(s) touched | What changed |
|---|------|-----------------|--------------|
| 1 | 🎨 Splash screen redesign | `frontend/src/components/BootSplash.jsx` | Replaced with a cinematic "ON NOW V2" wordmark + "Welcome to ON NOW V2" tagline + animated underline sweep. No centered loader. ~2.2 s on every launch. Verified visually via Playwright. |
| 2 | 🧹 Delete All Continue Watching | `frontend/src/components/ContinueWatchingShelf.jsx` | Added `<DeleteAllCard>` at the end of the CW row (two-tap confirm pattern, dashed border → red gradient → clear). |
| 3 | ⬆️ Browse By Network spacing | `frontend/src/components/NetworksShelf.jsx` | Reduced top padding, added bottom padding so the row lifts without moving any other shelf. |
| 4 | 🏠 Movies tab → Home menu nav | `frontend/src/components/SideNav.jsx` | `samePath` check now compares pathname + search params; in-app Home button correctly returns user to For You from Movies / TV Shows. |
| 5 | 📐 TV Show seasons cropping | `frontend/src/components/SeriesEpisodes.jsx` | Added top + bottom padding to the season-pill scroller so focused (1.08×) state has breathing room. |
| 6 | 📊 Library 7-across grid | `frontend/src/pages/Library.jsx` | `FavouriteGrid` + `ActorGrid` switched to `repeat(7, minmax(0, 1fr))`. |
| 7 | 🎯 Library focus flow + Show more pill | `frontend/src/pages/Library.jsx` | `CollapsibleGrid` rewritten — physically slices children (no mask) so D-pad DOWN from bottom-visible tile goes straight to the next Section. Large "Show 12 more" / "Show less" pill at row end replaces the cryptic "…" icon. |
| 8 | 🎯 Reminders popover focus-to-top + trap | `frontend/src/pages/Library.jsx` | When the bell opens the popover, focus snaps to the first row + focus-trap prevents D-pad escape. |
| 9 | 🔒 "Now in HD" popup focus trap | `frontend/src/components/NotifyHitWatcher.jsx` | Added `containerRef` + `focusin` trap. |
| 10 | 🔒 Reminder toast focus trap | `frontend/src/components/ReminderWatcher.jsx` | Same focus-trap pattern. |
| 11 | ✅ HD validity check | `frontend/src/lib/notifyScanner.js` | New `isHdRelease()` validator: 3+ streams tagged 1080p/2160p/4K/UHD/HDR AND ≥60 % of the title's tokens in each stream blob. Old "any stream" trigger removed. |
| 12 | 🔧 Developer Unlock → extra add-on shelves | `frontend/src/pages/Home.jsx` | When `localStorage.onnowtv-dev-unlock === '1'` (Settings → Unlock testing), Home appends every other live shelf (IPTV / anime / channels / etc.) beneath the locked 4 essential rows. Toggle off reverts to locked layout. |
| 13 | ▶️ Continue Watching resume — VERIFIED | (no code change needed) | Confirmed by reading `ExoPlayerActivity.kt` lines 331+581: `EXTRA_START_AT_MS` is correctly consumed via `setMediaItem(item).setStartPositionMs(startPos)`. JS side in `ContinueWatchingShelf.resume()` already passes `startAtMs`. End-to-end flow is intact. Needs a HK1 retest to confirm runtime. |
| 14 | 🗑️ CW long-press OK → Remove/Cancel — VERIFIED | (no code change needed) | Confirmed at `ContinueWatchingShelf.CWTile`: `handleKeyDown` arms a 700 ms timer that fires `onConfirm()` → shows inline Remove/Cancel section. Short-press (< 700 ms) plays. Pattern in place. |

All edits pass lint. Frontend `yarn build` succeeds. Splash + Library
collapse pill verified visually.

---

## ⏳ Deferred — needs user clarification

| # | Item | What's blocking |
|---|------|-----------------|
| 15 | 🤖 Watch Together "Grok speed one" AI | `WatchTogether.jsx` has **no AI-call code path** today — `Sparkles` icon is imported but never rendered. The launcher's AI uses Groq's `llama-3.1-8b-instant` (set in `/app/launcher-backend/main.py`). The TMDB `/api/tmdb/party-picks` endpoint is curation-only (no LLM). **Action needed:** ask the user where they saw an AI feature in Watch Together — is it (a) a missing "Smart pick"/"Surprise me" button they want ADDED, or (b) a different page entirely (e.g., the launcher home voice command), or (c) something already shipping behind a flag we missed? |

---

## Quick reference: files touched

```
frontend/src/components/BootSplash.jsx               (full rewrite — splash)
frontend/src/components/ContinueWatchingShelf.jsx    (Delete-All card)
frontend/src/components/NetworksShelf.jsx            (spacing tweak)
frontend/src/components/SideNav.jsx                  (samePath fix)
frontend/src/components/SeriesEpisodes.jsx           (season-pill padding)
frontend/src/components/NotifyHitWatcher.jsx         (focus trap)
frontend/src/components/ReminderWatcher.jsx          (focus trap)
frontend/src/pages/Library.jsx                       (7-col grids + popover trap + Show-more pill)
frontend/src/pages/Home.jsx                          (devUnlock unlocks extra shelves)
frontend/src/lib/notifyScanner.js                    (isHdRelease validator)
```

## Next agent — start here

1. Read `/app/memory/PRD.md` (top 50 lines for current product state).
2. **User MUST `Save to GitHub` first**, then wait ~3-5 min for the
   `Build ON NOW TV Tunes APK` workflow + the Vesper APK CI to finish.
3. After the rebuilt APK is installed on the HK1, retest the 14
   items above as a smoke pass.
4. Pick up the one deferred item (#15 — Watch Together AI) by asking
   the user the question in the "Deferred" section above.
