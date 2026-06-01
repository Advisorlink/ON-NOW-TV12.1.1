# ON NOW V2 Free-to-Air — Feb 2026 (v2.8.90)

Brand-new app for the launcher (4th tile, after Vesper / Tunes / Launcher).
Focused EPG-style guide for Brisbane AU free-to-air TV.

---

## ✅ Done in this batch (v2.8.90)

### Backend — new `/api/fta/*` router
`/app/backend/fta.py` (~270 lines, lint clean).  Three endpoints:
| Endpoint | What it does | Cache TTL |
|----------|--------------|-----------|
| `GET /api/fta/channels` | Channel list filtered to the **Brisbane FTA set** (21 channels: Seven family · Nine family · 10 family · ABC family · SBS family). Merges Stremio addon catalog with EPG channel metadata for proper LCNs + crisp logos. | 1 h |
| `GET /api/fta/streams/{channel_id}` | Resolves one channel's HLS URL via the Stremio addon's `/stream/tv/{id}.json` endpoint. | 5 min |
| `GET /api/fta/epg` | Parses `i.mjh.nz/au/Brisbane/epg.xml.gz` (9 MB XMLTV) using `iterparse` so memory stays low. Returns the next 24 h trimmed to ~3,500 programmes. | 30 min |
| `GET /api/fta/health` | Light probe with cache freshness counters. | – |

Both upstream sources are free / unauthenticated:
* **Channels + streams**: `https://kangaroostreams.hayd.uk/Brisbane/...` (the AU IPTV Brisbane Stremio addon)
* **EPG**: `https://i.mjh.nz/au/Brisbane/epg.xml.gz` (Matt Huntley's xmltv mirror)

Verified live: `/api/fta/epg` cold-start ~0.9 s on the preview pod, 21 channels, 3,446 programmes.

### Frontend — `/fta` route
`/app/frontend/src/pages/FreeToAir.jsx` (~520 lines) + `/app/frontend/src/pages/fta.css` (~430 lines).  Mounted via `App.js`.

**Layout matches the mockup**:
- Top bar: `V2 Free-to-Air` wordmark · `Free-to-Air | Favourites` tabs · live clock.
- Sidebar (360 px): 16:9 HLS preview (silent autoplay) · LIVE pill · current programme title + time + minutes-left · progress bar · channel logo + LCN + name · heart favourite toggle · synopsis · `PG / category / HD / CC` chips.
- Grid (right of sidebar): sticky channel rail with logos · scrollable EPG with proportional cells (`12 px = 1 minute`) · red NOW line + `12:35pm` badge.

**Interactions wired up**:
- Tab toggle (`Free-to-Air` ↔ `Favourites`) filters the channel set.
- Click a cell → preview pane updates to that channel's currently-airing show.
- Click again while the same channel is selected → opens the full-screen player.
- Press Back/Escape → exits full-screen back to the guide.
- `useSpatialFocus` + `data-focusable="true"` give D-pad navigation through cells.
- `useBackHandler('/')` returns to the Home screen when the user presses Back from the grid view.

**Performance**:
- Programmes are positioned absolutely with `left` + `width` in pixels — no layout work on focus change.
- Times header `translateX`'s in sync with the grid's `scrollLeft` (single `requestAnimationFrame`-friendly listener).
- 21 channels × ~165 programmes each = 3,500 absolutely-positioned buttons. Smoke test rendered all 716 visible cells without jank on the preview.

### Verified visually
Screenshot at `/tmp/fta_loaded.png` shows the exact mockup with real Brisbane EPG data:
* Seven · "The Agenda Setters: Rugby League" (LIVE)
* 7two · "A Touch Of Frost"
* 7mate · "Adventure Gold Diggers"
* 7Bravo · "Snapped"
* 9 · "Outback Opal Hunters" (LIVE)
* 9Gem · "Roland Garros - French Open Tennis"
* etc.

---

## ⏳ Phase 2 — APK wrapper (next session, ~30 min)

The user wants this as a SEPARATE APK for the launcher (4th tile after Vesper / Tunes / Launcher).  Right now it lives at `/fta` inside the existing Vesper React app, which means **you can test it right now on the preview URL** without waiting for an APK build.

To spin up the standalone APK, follow the **exact same pattern as `/app/android/onnowtv-tunes/`**:

1. Create `/app/android/onnowtv-fta/` directory tree.
2. Copy `onnowtv-tunes`'s `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew*`, `app/build.gradle.kts`.
3. Change package id to `tv.onnowtv.fta`, version `1.0.0`.
4. `MainActivity.kt` mirrors Tunes' but the WebView loads `https://onnowtv.duckdns.org/fta` (or the bundled SPA build pointed at `/fta`).
5. New CI workflow `/app/.github/workflows/build-fta.yml` modeled on `build-tunes.yml`.
6. Add an installed tile entry in the launcher admin (`launcher-backend`) for the new APK.

The React side of this is already production-ready — the APK is purely a WebView wrapper.

---

## ⏳ Polish backlog (low priority)
- Tweak EPG cell focused state to match the mockup's bright white border + outer glow (currently a thin white border; user mockup has slightly thicker treatment).
- Long-press OK on a cell to favourite that whole channel (currently the heart in the sidebar handles favourite toggle; the user described long-press-OK adding to favourites too).
- Show a "no EPG data" cell when a channel has zero programmes in the window (handled but could be styled nicer).

---

## Files added / changed

```
backend/fta.py                           NEW (~270 lines)
backend/server.py                        +4 lines (router include)
frontend/src/pages/FreeToAir.jsx         NEW (~520 lines)
frontend/src/pages/fta.css               NEW (~430 lines)
frontend/src/App.js                      +5 lines (route + import)
frontend/package.json                    +1 dep (react-window)
```

## Next agent — start here

1. Confirm `https://onnowtv.duckdns.org/api/fta/health` works on the VPS (the new `fta.py` file needs to be SCP'd over there before the `/fta` page can load on the production app — same flow we used in v2.8.89).
2. Build the standalone APK wrapper per "Phase 2" above.
3. Add long-press-OK favourites + improved focused-cell visuals (Polish backlog).
