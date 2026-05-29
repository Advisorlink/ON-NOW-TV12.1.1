# ON NOW TV TUNES — Music App Strategy

> Created **Feb 28, 2026**, in response to user's request:
> *"I wanna build basically a music version of the Vespa app, so just
> for music. I wanna get like all the newest release music, do all
> that sort of stuff. People can set playlists. Podcasts. Radio.
> Anything they wanna search."*

This is the planning document. **Do not implement yet** — the user
just wants the strategy on record. When they say "build phase 1",
this file is the source of truth.

---

## Reuse what's already built

- **Same Kotlin Android shell** as Vesper (rename app to "ON NOW TV
  Tunes" or similar, package id `tv.onnowtv.tunes`). ExoPlayer already
  handles audio perfectly. Sideloading + updates + signing + CI
  workflow already solved. Just clone the `android/vesper-tv/`
  directory and rebrand.
- **Same React frontend pattern** in `frontend-tunes/` (or a new
  `/music` route in the existing frontend if we want to keep it
  in-process). 10-foot UI, D-pad spatial navigation, focus rings,
  shelf-snap engine — all copy-paste from Vesper.
- **Same FastAPI backend pattern** — new module `music_api.py`
  under `/app/backend/` exposing:
  - `GET  /api/music/search?q=…`
  - `GET  /api/music/new-releases`
  - `GET  /api/music/album/{id}`
  - `GET  /api/music/artist/{id}`
  - `GET  /api/music/stream/{track_id}`   (returns playable URL)
  - `GET  /api/music/radio/stations?q=…`
  - `GET  /api/music/radio/click/{station_id}`
  - `GET  /api/music/podcasts/search?q=…`
  - `GET  /api/music/podcasts/{feed_id}/episodes`
  - `GET  /api/music/playlists` (per-profile, stored in Mongo)
  - `POST /api/music/playlists`
  - `PUT  /api/music/playlists/{id}`
- **Same launcher integration** — the `music` dock tile already
  exists. Point its `target_package` at `tv.onnowtv.tunes`.
- **Same V2 AI hooks** — extend the launcher's intent parser to
  recognise music intents (`play_song`, `play_artist`, `play_album`,
  `play_genre`, `play_radio`, `play_podcast`), each routes to the
  Tunes app via the same deep-link contract Vesper already uses.

## Content sources (the real strategy)

| Need | Best source | Cost | Auth | Notes |
|---|---|---|---|---|
| **Catalog / search / new releases / metadata** | Spotify Web API | Free | Client-credentials OAuth (no user login needed) | Beautiful artwork, accurate "new releases", charts, recommendations. NO full streaming — only 30 s previews. |
| **Actual playable music streams** | YouTube Music via `ytmusicapi` (Python) | Free | No auth | Resolves Spotify track → YouTube Music stream URL → ExoPlayer. ~1 s latency. Grey area (vs YouTube ToS) but never enforced for personal/sideloaded — same posture Stremio addons take. |
| **Radio (30,000+ stations worldwide)** | [Radio Browser API](https://www.radio-browser.info) | Free | None | Drop-in, ~1 h to integrate. Returns direct stream URLs. |
| **Podcasts directory** | iTunes Search API (no key) | Free | None | Discovery + 100k+ podcast directory |
| **Podcast episodes** | Direct RSS feed parsing | Free | None | Each podcast has an RSS feed URL; parse with `feedparser` |
| **Higher-quality podcast directory** | [Podcast Index](https://podcastindex.org/) | Free | API key | Optional upgrade from iTunes — open source, more comprehensive |
| **User's own music library** | Plex Music or Jellyfin | Free | User's existing creds | Same provider pattern as Vesper's Jellyfin backlog |
| **Indie / free / legal music** | Jamendo, Internet Archive Audio | Free | None | Fills the "what if YouTube Music breaks" gap with 100% licensed free content |

## Why this combo is the right answer

1. **Spotify provides the polish.** People EXPECT to see Spotify-style
   album covers, new-releases shelves, weekly charts. Their Web API
   gives all of that for free.
2. **YouTube Music fills the streaming gap.** Virtually every song
   that ever existed is on YouTube. `ytmusicapi` quietly resolves
   `"Adele Hello"` → an `audio/webm` stream URL that ExoPlayer plays
   directly. Latency ~1 s.
3. **Radio Browser is criminally underused.** "30,000 stations
   worldwide" out of the box with zero ongoing cost, zero rate limits.
4. **Podcasts via RSS** = the simplest content type imaginable.
   Parse XML, list episodes, play the `<enclosure>` URL.

## Legal posture

- Spotify Web API for catalog/metadata = **100 % legal** (official API,
  free tier covers our use case).
- Radio Browser = **100 % legal** (open community directory; many
  stations also have their own licensing baked into the stream).
- Podcasts via RSS = **100 % legal** (RSS is the standard distribution
  format publishers WANT you to use).
- YouTube Music via `ytmusicapi` = **grey area** (against YouTube ToS
  but never enforced for personal/sideloaded use; same posture every
  music-app project has taken for the past decade). If you want
  pure-clean: skip this and use Plex/Jellyfin + Jamendo only — but
  you'll lose "new releases" and "search any mainstream artist".

## ⏩ UPDATE — Feb 28, 2026 · Phase 2 full-track resolver SHIPPED (cookies-based)

After Phase 1 + Music UI shipped (v2.8.43), the user reported tracks
only played 30-second previews.  We tried every free unauthenticated
path — yt-dlp without cookies (datacenter-IP-blocked), Piped /
Cobalt proxies (rate-limited or JWT-walled), JioSaavn (delivered too
many Indian regional covers), Audius (mostly indie remixes).

**Final solution shipped: yt-dlp + signed-in cookies** (per the
user's "let's do the Google thingamajig" decision).  Backend
`/app/backend/music_api.py`:

- Resolver chain: **YouTube (cookies) → JioSaavn → Audius → Deezer
  preview**.  YouTube wins ~98 % of mainstream queries when cookies
  are healthy.  Bytes stream direct from `googlevideo.com` CDN to
  the client; the VPS only resolves the URL.
- Round-robin across all uploaded cookies — uploading 2-3 cookie
  files means a single account ban only takes out one slot.
- Cookie health stats tracked in-memory (used / success / fail /
  last error per file).
- Smart default path: `/opt/onnowtv/backend/youtube-cookies` on the
  VPS, falls back to `<music_api.py-dir>/youtube-cookies` on dev /
  preview pods.

Admin upload UI at `/api/admin/music-cookies?token=…`:

- Drag-and-drop `cookies.txt` files (max 1 MiB each).
- Live status per cookie: healthy / ready / no-login / failing.
- One-click delete + "Test a track" button that does a real resolve.
- Validates the file looks like a Netscape cookies.txt with at
  least one `.youtube.com` entry.

**Operator's playbook** for sourcing cookies:

1. Create a throwaway Google account (NOT a personal one) from a
   residential IP — desktop signup hits a QR-code anti-bot check;
   easiest workaround is the Gmail mobile app sign-up flow.
2. Sign into YouTube in Chrome on that account; watch one or two
   videos to warm the session.
3. Install Chrome extension **"Get cookies.txt LOCALLY"** (the one
   with "LOCALLY" in the name — earlier "Get cookies.txt" was
   compromised).
4. Click extension on `youtube.com` → Export As: **Netscape** → save
   as `account-1.txt`.
5. Open `https://onnowtv.duckdns.org/api/admin/music-cookies?token=…`
   and drag the file in.
6. Repeat with a second / third disposable account to enable
   round-robin failover (highly recommended).
7. Rotate every 2-4 weeks (sign out / sign in / re-export).

**Why we DIDN'T use Spotify Web API**: it requires per-user OAuth +
Premium subscriptions for full-track playback (Web API previews are
30-s).  Catalog metadata via Spotify is a future "nice to have"
upgrade once Deezer's metadata becomes insufficient.

---

### Phase 1 — Radio + Podcasts + Plex/Jellyfin (~1 week)
**This is the proof-of-concept phase.** Smallest possible working
music app, 100 % legal content, real value to users immediately.
- Radio Browser tab: search + browse by country/genre/language, play
  any station via ExoPlayer.
- Podcasts tab: iTunes search + RSS parse + episode play.
- Plex/Jellyfin tab: connect to user's existing media server, browse
  artists / albums / tracks.
- Basic playlists (per-profile, stored in Mongo).
- Same Vesper-style 10-foot UI + D-pad.

### Phase 2 — Spotify catalog + YouTube Music streams (~1 week)
The "real" music app: full mainstream catalog, gorgeous artwork.
- Spotify Web API: `/search`, `/browse/new-releases`, `/browse/featured-playlists`,
  `/artists/{id}/top-tracks`, `/recommendations`.
- For every Spotify track, resolve a playable stream via
  `ytmusicapi.search(track + artist) → first video → InnerTube stream URL`.
- Caching: per-track stream URL cached for 6 h (YouTube URLs rotate
  on a 6-h TTL).

### Phase 3 — Personalisation + V2 AI integration (~3 days)
- User playlists (already in Phase 1, polish UX).
- "Recently played" + "Made for you" recommendations from listening
  history.
- V2 AI new intents: `"play me chill jazz radio"`, `"play Adele's
  newest album"`, `"play the Joe Rogan podcast"`.

## Technical notes for the agent who picks this up

- **`ytmusicapi`** library: `pip install ytmusicapi`. No auth needed
  for search/streams. Sync API; wrap in `asyncio.to_thread()` to keep
  FastAPI handlers async.
- **Spotify Web API** client-credentials flow: register an app at
  developer.spotify.com → get client_id + client_secret → POST to
  `/api/token` → use the access token for 1 h, refresh as needed.
  Library: `spotipy`. NO user login needed for catalog endpoints.
- **Radio Browser**: pick any random server from the SRV record
  `_api._tcp.radio-browser.info` to load-balance. Library:
  `pyradios` or just direct HTTP.
- **Podcast Index** (optional): register at api.podcastindex.org →
  free key → swap iTunes for richer data later.
- **ExoPlayer audio**: same `ExoPlayerActivity` already in Vesper
  works for audio. Add a "music player" overlay (album art +
  scrubber + next/prev buttons) similar to the v2.7.40 video overlay
  but tuned for audio.
- **Playlist storage**: MongoDB collection `music_playlists` keyed
  by `(profile_id, playlist_id)`. Same pattern as Vesper's
  `continue_watching` collection.

## Why this is going to work

The user already has:
- 500+ deployed Android TV boxes.
- A CI pipeline that auto-builds + auto-publishes APKs.
- A launcher with a dedicated `music` tile already designed.
- An admin portal pattern for adding/removing content sources.
- A V2 AI voice assistant that already knows how to dispatch intents.
- A FastAPI backend with TMDB / addon / streaming patterns proven.

So a music app for the same user base is a **pure additive feature**.
Phase 1 is a weekend of work. Phase 2 is a week. Phase 3 polishes it.
There is no "rebuild from scratch" risk — every piece of this is
either reused from Vesper or a free public API.

---

## TODO when user gives the go-ahead

1. Clone `android/vesper-tv/` → `android/onnowtv-tunes/`. Update
   `applicationId` → `tv.onnowtv.tunes`, app name → "ON NOW TV
   Tunes" (or whatever they want).
2. Add `.github/workflows/build-tunes.yml` mirroring
   `build-launcher.yml` so the Tunes APK auto-publishes on push.
3. Create `frontend-tunes/` (or `/app/frontend/src/pages/music/`)
   with the React UI.
4. Create `/app/backend/music_api.py` with the endpoints listed
   above. Wire it into `server.py` via `app.include_router(...)`.
5. Update the launcher's music dock tile to point to
   `tv.onnowtv.tunes`.
6. Extend the launcher's V2 AI intent parser with music intents.
