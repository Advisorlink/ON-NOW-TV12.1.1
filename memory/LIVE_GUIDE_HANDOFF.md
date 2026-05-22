# Live TV Guide Overlay — Handoff Context (v2.7.74 in progress)

> **READ THIS FIRST IF YOU'RE PICKING THIS UP MID-IMPLEMENTATION.**

## What we're building & WHY
The user wants the **old v2.6.2-style Live TV Guide overlay** ported into the new ExoPlayer activity. Currently it only exists in the legacy VLC activity (`LiveGuideController.kt` + XML views). The new design follows a user-supplied mockup which is much richer than the legacy XML build.

**Reference mockup**: `https://customer-assets.emergentagent.com/job_rebrand-app-5/artifacts/1yo0q5vp_a9d1c1e2-c9c4-454f-a8fe-ae3f1db3c4c9.png`

The user has spent thousands of credits and explicitly said **NO ITERATION** — get it right in one shot.

## Locked design (confirmed by user)
- **Target screen**: HK1 Android TV box, **1920 × 1080 landscape**, baseline density (1 dp ≈ 1 px). Source of truth: `/app/memory/PRD.md`.
- **Trigger**: Push LEFT while video plays → overlay slides in.
- **Two-pane drill-down**: Push LEFT a second time while focused on a channel → categories column slides in inset to the left of the channels.
- **Right side stays video**: The live video keeps playing on the right (NO backdrop image). Hover a channel for **1 s** → auto-tune (no OK required). OK on a channel → tune immediately.
- **Up Next thumbnails**: TMDB lookup per programme via new `/api/epg/art` endpoint, cached aggressively. Cinematic backdrop look.
- **Header**: "LIVE TV GUIDE" (cyan mono caps, top-left) + "<N> CHANNELS" + clock + date (top-right).
- **Close**: BACK or MENU.

## Files created / modified
| File | Status | Purpose |
|---|---|---|
| `/app/backend/server.py` | ✅ DONE | Added `/api/epg/art?title=&year=` endpoint at line ~2632. Returns `{backdrop, poster, media_type, tmdb_id, tmdb_title}`. Cached 7 days. Verified via curl with "Inception". |
| `/app/android/vesper-tv/app/src/main/java/tv/vesper/app/LiveGuideManager.kt` | ✅ DONE | Data model + state flows + TMDB art fetch + SharedPreferences parsing. Reads from key `live_guide` (already populated by `WebAppInterface.setLiveGuide`). |
| `/app/android/vesper-tv/app/src/main/java/tv/vesper/app/LiveGuideOverlay.kt` | ✅ DONE | Full Compose UI: header, channel rail, category rail, programme info, up-next strip. ~600 lines. Uses Coil for image loading. |
| `/app/android/vesper-tv/app/src/main/java/tv/vesper/app/ExoPlayerActivity.kt` | ⏳ IN PROGRESS | Added fields `isLive`, `liveStreamId`, `liveGuide`. **STILL TODO** ↓ |

## ExoPlayerActivity remaining work
The fields are added but NOT yet:

1. **Initialise `liveGuide` in `onCreate`** when `EXTRA_TYPE == "live"`. Pattern:
   ```kotlin
   if (intent.getStringExtra(VlcPlayerActivity.EXTRA_TYPE) == "live") {
       isLive = true
       val streamUrlForId = intent.getStringExtra(VlcPlayerActivity.EXTRA_URL) ?: ""
       liveStreamId = extractStreamIdFromUrl(streamUrlForId)  // last "/live/.../<id>.ts" path segment
       liveGuide = LiveGuideManager(
           ctx = this,
           backendBase = backendBaseFromEnv(),  // read REACT_APP_BACKEND_URL via SharedPreferences set by WebAppInterface, or hardcoded preview origin
           initialChannelStreamId = liveStreamId,
       ).also { it.loadFromPreferences() }
   }
   ```

2. **Key handling in `dispatchKeyEvent`** (search for the existing `inParty` block; live mode adds an analogous branch BEFORE the party logic):
   ```kotlin
   if (isLive && liveGuide != null) {
       val mode = liveGuide!!.mode.value
       if (event.action == KeyEvent.ACTION_DOWN) {
           when (event.keyCode) {
               KeyEvent.KEYCODE_DPAD_LEFT -> {
                   if (mode == LiveGuideManager.MODE_CLOSED) {
                       liveGuide!!.open(); return true
                   }
                   // when already open, leave the event to focusable rows
                   // so the channel row's onKeyEvent picks up LEFT-from-channel
                   // → openCategories().
               }
               KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE,
               KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_INFO -> {
                   liveGuide!!.toggle(); return true
               }
               KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                   if (mode != LiveGuideManager.MODE_CLOSED) {
                       liveGuide!!.close(); return true
                   }
               }
           }
       }
   }
   ```

3. **In-place channel tune** (no activity restart, just swap ExoPlayer's `MediaItem`):
   ```kotlin
   private fun tuneToLiveChannel(ch: LiveGuideManager.LiveChannel) {
       try {
           streamUrl = ch.streamUrl
           liveStreamId = ch.streamId
           val item = MediaItem.fromUri(ch.streamUrl)
           player.setMediaItem(item, /* resetPosition */ true)
           player.prepare()
           player.playWhenReady = true
           liveGuide?.markPlaying(ch.streamId)
       } catch (t: Throwable) { Log.w(TAG, "tuneToLiveChannel failed", t) }
   }
   ```

4. **Render `LiveGuideOverlay`** in `setContent { PlayerOverlay(...) }` block. Easiest path: add a sibling Compose call after the PlayerOverlay invocation:
   ```kotlin
   liveGuide?.let { mgr ->
       LiveGuideOverlay(
           manager = mgr,
           onTuneChannel = { tuneToLiveChannel(it) },
       )
   }
   ```
   Place it INSIDE the same `setContent` lambda, AFTER `PlayerOverlay(...)`, so it stacks on top.

5. **Cleanup in `onDestroy`**:
   ```kotlin
   try { liveGuide?.release() } catch (_: Exception) {}
   ```

## Backend base URL access
`LiveGuideManager` constructor takes `backendBase`. For the HK1 box that's the production preview URL. The simplest way to derive it: `WebAppInterface` already has `getBackendOrigin()` or similar — search for `REACT_APP_BACKEND_URL` usage in `WebAppInterface.kt`. If not present, hardcode `"https://rebrand-app-5.preview.emergentagent.com"` for now (it's what the React app uses).

## Stream ID extraction
Xtream Codes live URLs look like:
`http://host:port/live/<user>/<pass>/<streamId>.ts` or `<streamId>.m3u8`.

The `streamId` is the last path segment without extension. Helper:
```kotlin
private fun extractStreamIdFromUrl(url: String): String {
    val last = url.substringAfterLast('/').substringBeforeLast('.')
    return last.takeIf { it.matches(Regex("^\\d+$")) } ?: ""
}
```

## Data already wired
- `WebAppInterface.setLiveGuide(providerId, categoriesJson, channelsJson, epgJson, favoritesJson)` is called by React BEFORE launching a live channel. Stores into `SharedPreferences("live_guide")`. **No backend changes needed for the data flow.**
- `LiveGuideManager.loadFromPreferences()` reads it. Already implemented.

## Version & changelog
- `versionCode 244`, `versionName "2.7.74"` — **bump before committing**.
- Add CHANGELOG entry summarising: "Native Live TV Guide overlay ported into ExoPlayer. Slide-in left rail with channel + category drill-down, auto-tune on 1 s hover, TMDB-resolved Up Next thumbnails, full HK1-tuned 1920×1080 layout."

## Backend & service status when leaving off
- Backend running ✅ (`curl https://rebrand-app-5.preview.emergentagent.com/api/` → 200)
- New `/api/epg/art` endpoint verified ✅
- No supervisor restarts needed unless backend code is touched again.

## DO NOT
- Do NOT restart Gradle / build the APK locally. CI builds it (`Save to GitHub` button).
- Do NOT modify `LiveGuideController.kt` (legacy VLC code — kept for VLC playback path).
- Do NOT add backdrop image to the right side of the overlay — the user EXPLICITLY chose live video instead.
- Do NOT touch `/app/frontend/src/pages/LiveTV.jsx` — that's the React browser, separate from this native overlay.
