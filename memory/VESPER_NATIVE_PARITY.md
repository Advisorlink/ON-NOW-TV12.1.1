# Vesper native Android TV parity ledger

## Authoritative request and approval (2026-10-15)
The user wants a **separate, fully native Android app**, preserving current Vesper's features, appearance and behaviour rather than redesigning it. They asked for no omissions and Live TV-like smoothness. Target is **"Only tv boxes"**, reference **"The current vespa"**. They explicitly rejected an audit-only approval gate: **"No I just want you do do it as we go you do all yhe research knowing my requirements"**. Research and implementation are authorised continuously; do not ask them to reapprove the audit or each stage.

This is a multi-stage migration, NOT a complete replacement delivered in one change. No 100% parity/performance promise. The old app stays intact until feature, visual, device, data-migration and playback acceptance pass. Existing known bugs are tracked, not copied as desired behaviour.

## Reference boundaries
- React source: `frontend/src/App.js`, `pages/`, `components/`, `lib/`, `hooks/`, `themes/`.
- Existing wrapper: `android/vesper-tv`, package `tv.onnowtv.app`, namespace `tv.vesper.app`.
- New project: `android/vesper-native`, package/namespace `tv.onnowtv.vespernative`. Development launcher label **Vesper Native · Dev**.
- Kotlin Android Views/RecyclerView/Coil/OkHttp/coroutines. No WebView dependency, JavaScript bridge or bundled web assets in the new project.
- Source reference currently has top nav by default (not side nav), Electric theme, Geist typography, fixed top billboard and scrolling shelf region. Reuse login artwork, logo/banner and licensed Geist font. No stock substitutions.
- Kids/Tunes/Trivia/FTA/Live TV are separate apps sharing some React source. Record legacy routes/bridge links but do not silently move these entire products into native Vesper. Vesper's current package explicitly prohibits entering Kids mode.
- Device support remains Android 8+ like current Vesper, landscape, TV remote/generic box launcher and Leanback launcher; no phone/tablet UX work.

## First implementation checkpoint
**LATEST PROFILE/UI CHECKPOINT (supersedes in-progress note immediately below):** cold-start picker, exact four-digit PIN UI, cancel/Back/wrong-PIN reset, account Switch profile, current-profile read-only Library are connected and verified. All75 static avatar assets match reference icon IDs. Continue Watching/Watch Later/TV Shows/Movies open title details; saved long-row focus restores after Back. Network-error Retry is distinct from confirmed-empty Browse-only mode. **56 native tests (55 PASS/1 hardware skip),5 real API checks PASS**, signed/aligned/CRC-checked debug APK, SHA256 `c58c33f30e6d6208178c1aef96dca009d134f75c24f088c975dc5d329023656e`. `test_reports/vesper_native/profile_checkpoint.json` is newest result. No backend writes, PIN/account changes, snapshots persisted, physical-device visual/performance claim or full-parity claim. Profile CRUD, safe persistent merge, custom/emoji avatars, playable progress, actor/boxset pages and complete library layout still pending.

**FOLLOW-ON IN PROGRESS (GitHub question / user approved Continue):** wiring `ProfilePickerScreen`, `ProfilePinScreen` and `LibraryScreen` into cold-start sign-in + account profile switching. Native reads existing snapshots, excludes Kids, verifies PIN before exposing saved lists, displays saved movie/TV/Watch Later/Continue Watching titles and opens title details. Bundled static avatar JPGs reused. Confirmed empty cloud profiles have explicit Browse-only option with no personal data; errors never silently become empty or bypass PIN. Still NO creates/renames/deletes/cloud writes/persistent snapshots, no playable resume action, no actor/box-set page or custom/emoji avatar parity yet. This follow-on needs fresh UI/regression verification; earlier final results below refer to the first checkpoint.

**FINAL verification (supersedes interim counts below):** 45 native tests / **44 PASS / 1 hardware Keystore skip / 0 failures or errors**, successful debug APK; **5 REAL backend contract tests PASS**. Covers actual localStorage JSON strings, scoped/legacy profile/PIN/unknown field preservation and source isolation. APK signature/alignment/CRC verified, artifact appId `tv.onnowtv.vespernative`, no WebView. Evidence `test_reports/vesper_native/final_verification.json`, `final-regression-build.log`, `final-backend.log`, `final-manifest.log`. Iteration118's credential setup errors are resolved; populated `memory/test_credentials.md` existed and was updated, environment credentials injected, no account change. Native profile data module is tested but **not connected to screens**; full picker/library CRUD and safe persistent merge remain next. Physical visual/Keystore/playback/performance acceptance is pending.

**Foundation verification update (2026-10-15):** `testDebugUnitTest` + `assembleDebug` passed after fixes; 24 tests total, **23 passed / 1 hardware Keystore test skipped**, no failures/errors. 3 REAL external backend contract checks also passed. Added real-window, measured-layout D-pad tests; first-row Up → hero Info → active top-nav button, retained-focus Back, hero bounds and signout all pass. Native Keystore needs device coverage. The first build remains unfinished parity.

**Next data-layer increment (under test):** `profile/CloudSnapshot.kt`, `CloudProfile.kt`, `ProfileCloudRepository.kt`: current-JWT read-only pull, exact web account/profile key formats, no alternative-account key scanning, Kids excluded, exact stored PIN policy, private unknown-key preservation, defensive library/Continue Watching projections, per-session one-owner legacy promotion. A stale repository cannot call using a new login token, and a response arriving after account switch is discarded. No persistence/push/data mutation. This is migration groundwork only: **not connected to the picker/home UI yet**; no claim that profile/library parity is complete. Existing safe client backend/HTTPS auth is the binding—there is no separate signed-snapshot protocol in current API.

**Implemented, pending tests at creation of this ledger:** separate build; real existing sign-in/me/logout contract; encrypted token-only session storage; restore with offline Retry; bounded/cancellable backend client; catalogue discovery and four essential shelves; movie/series shelf filtering; focus-follow hero; title search across searchable addon catalogues; cached search/results/scroll retained on title Back; seed-first detail/metadata/episode text; native focus outlines; error/empty/retry; account/exit dialogs.

**Deliberate development-build limits:** no native playback yet, no profile picker/cloud import, no library writes, no trailer preview, no full production navigation, and no claim of pixel-identical home/details. Movies/TV currently filter shelves, not the reference genre grid. The development build is NOT a production-ready alternative to current Vesper. No dummy Play buttons, fake content, mock runtime APIs, or destructive partial cloud sync. The first development checkpoint lands directly in read-only browsing after sign-in; profile gate must be added before any personal data feature.

## Feature/acceptance matrix
Status: `PARTIAL` = some native implementation, `PENDING` = not implemented, `EXTERNAL` = separate app/reference boundary. Each needs source-state tests + native tests + real TV acceptance; a source inventory does not prove completeness.

| ID | Source of truth | Required parity and less-obvious states | Status |
|---|---|---|---|
| AUTH-01 | `lib/auth.js`, `contexts/AuthContext.jsx`, `components/LoginScreen.jsx`, `backend/auth_router.py` | JSON username/password/client_id; bearer access_token + account envelope; 401, disabled, expired, 429; keystore session; offline restore does not log out; logout | PARTIAL; core client implemented, full artwork/form polish pending |
| BOOT-01 | `BootSplash`, `LoginGate`, `CloudRestoreMount`, `App.js` | Cold boot, session restore, cloud restore choice before profile selection, splash timing, no blank transition | PARTIAL session/retry only |
| PROFILE-01 | `pages/ProfileSelect`, `ProfileEdit`, `ProfileLoad`, `lib/profiles.js` | Always show picker at cold start, create/edit/remove/switch, avatars/custom uploads, PIN, account scoping, viewing preferences | PARTIAL: picker/PIN/switch/static avatars/account scoping; CRUD/custom avatars/preferences pending |
| PROFILE-02 | `lib/profileScope.js`, `profileBackup.js`, `vesperCloudSync.js`, `backend/vesper_sync.py` | Pull before push; profile key compatibility; full-snapshot backup restore/code; preserve unknown keys; secrets excluded; avoid partial overwrite | PARTIAL: real read-only snapshot/key compatibility connected; persistent merge/write/backup pending |
| NAV-01 | `SideNav`, `TopNav`, `prefs.js`, `useHomeBackHandler`, `useFocusRestore` | Top icon pill default; optional side rail; active route, label on focus, Back stack, exact tile/scroll restore; Search first | PARTIAL native top subset/Back; all entries + side rail pending |
| HOME-01 | `Home.jsx`, `HeroBillboard`, `useFocusHero`, `useLiveHeroes` | Fixed billboard, focus dwell, real art/title/synopsis, rotation, Play/Info/My List, no tile movement/jitter | PARTIAL fixed hero/Info/dwell; rotation/full actions pending |
| HOME-02 | `useLiveShelves`, `Shelf`, `perfLab.js` | New movies/new series/popular movies/popular series, 18 visible, 60 cached, Show more, lazy rows, failure isolation/circuit breaker, direct fallback | PARTIAL native recycling/backend cache/pagination; circuit breaker/direct fallback pending |
| HOME-03 | `NetworksShelf`, `CollectionsShelf`, `UpcomingMoviesShelf` | Networks, Studios, upcoming within 60 days, exact branded art and ordering | PENDING |
| HOME-04 | `ContinueWatchingShelf`, `ForYouShelf`, `CustomRowShelf`, `homeRows.js` | Continue Watching, For You, custom categories/genres/query, show/hide/reorder/reset | PENDING |
| GRID-01 | `TabGridView`, `useTabCatalog`, `useTabGenreCatalog` | Movies/TV genre tabs, selection persistence, grid pagination, order, no duplicate fetches | PENDING; current native filter is development shelf filter only |
| PREVIEW-01 | `TrailerHoverPreview`, `HdTrailerVideo`, `PreviewStreamCheck`, `trailerEngine` | Focused card expansion, English trailer, stop on focus exit, fullscreen preserve position; 3-action menu; stream-quality verdict after playing | PENDING |
| SEARCH-01 | `Search.jsx` | Native keyboard input, recent 8 per profile, clear history, suggestions, title/actor search, query-results restore, exact-name actor priority, voice/remote typing | PARTIAL submitted addon title search and retained Back state only |
| META-01 | `Detail.jsx`, `Resolve.jsx`, `api.getMeta` | Seed details, enriched metadata, logo, rating/runtime/certification, watched state, artwork, unavailable/notify state, TMDB→IMDB resolution | PARTIAL seed/meta/read-only synopsis only |
| META-02 | `SeriesEpisodes.jsx` | Seasons, Specials, exact episode IDs, release gating, overview, watched/resume, long press watch later, next episode focus, independent TV autoplay | PARTIAL episode metadata text only, not playable |
| META-03 | `CastRow`, `RecommendationsRow`, `Person.jsx` | Cast/filmography/recommendations, actor favourite, real TMDB portrait/detail routes | PENDING |
| BROWSE-01 | `Network.jsx`, `Collection.jsx`, `lib/networks.js`, `collections.js` | Network/studio pages, filters, Show more, collection metadata/artwork | PENDING |
| BROWSE-02 | `BoxSets.jsx`, `Anime.jsx` | Box Sets browsing/favourites, anime catalogue rows/grids and filtering | PENDING |
| STREAM-01 | `api.getStreams`, `streamOrder`, `streamTags`, `episodeMatch` | Progressive backend + residential-device probes; backend-rich entries win; provider diagnostics; Direct→Torrentio→MediaFusion→EasyNews→others, uncached last; 1080p 1–4GB preference, Torrentio wait ≤3s | PENDING |
| STREAM-02 | `StreamPickerModal`, `StreamUnavailableModal`, `Detail.jsx` | Auto play master/TV independent toggle, manual choice, swap stream, unavailable retry, subtitles/headers, sources/quality metadata | PENDING |
| PLAYER-01 | `android/vesper-tv/.../ExoPlayerActivity`, `NativePlayerActivity`, `PlayerOverlay`, `VesperMpvEngine`, `VesperVlcEngine` | Reuse/extract actual native player capabilities, Media3/VLC/MPV, codecs, engine preference, HDR/force SDR, buffer controls, audio/subtitle tracks, offset/size, playback speed, aspect/zoom | PENDING; do not substitute a minimal player and call it parity |
| PLAYER-02 | `WebAppInterface`, native progress helpers, `continueWatching.js` | Native progress→profile schema; seek/resume; completed/watched threshold/tombstones; next episode; stop/release on Back/background | PENDING |
| LIB-01 | `Library.jsx`, `AddToListModal`, `library.js` | Watch Later 16:9/logo first, TV Shows, Movies, My Box Sets, My Actors; long OK remove confirmation; favourites/watch later separate; episode reminders/dismissed episodes | PARTIAL: read-only TV/Movie/WatchLater/CW lists + detail navigation; writes/landscape layout/actors/boxsets/reminders pending |
| CAL-01 | `CalendarPage`, `LibraryCalendar`, `newEpisodes`, `notifyScanner` | Full release calendar, upcoming episodes, upcoming movie notifications and focus/navigation | PENDING |
| PARTY-01 | `WatchTogether.jsx`, `Party*`, `Player.jsx` | Create/join/leave/reconnect rooms, lobby, QR/companion link, host controls, synchronized media position/pause/play, exact episodes | PENDING |
| PARTY-02 | `PartyVoiceDock`, `PartyReactions`, `VoiceReactionButton`, hooks | Reactions, voice/mute/permissions, invitation edge cases | PENDING |
| AI-01 | `V2AI`, `V2AIResolve`, `lib/host.js`, native mic bridge | Mic/STT capture/cancel, intent resolution, loading/errors, hold OK, native route handoff; existing backend service reuse | PENDING |
| SETTINGS-01 | `Settings.jsx`, `themes/themes.js`, `ThemeProvider` | All 8 colour themes + 6 skins/font/radius; default Electric; preview/focus/selection; profile scope | PENDING; Electric tokens/Geist only |
| SETTINGS-02 | `Settings.jsx`, `prefs.js`, `homeRows.js`, `viewingStyle.js` | Appearance/home rows/playback/personality/help/tips/what's new/backup/signout sections; auto trailers/master autoplay; side/top menu; force SDR; player engine | PARTIAL signout only |
| HELP-01 | `Onboarding`, `WhatsNewModal`, `WhatsNewTour`, `FeatureNudge`, `DPadHint` | First-run/replay tours, release acknowledgement, contextual hints, focus trapping | PENDING |
| SOURCES-01 | `Sources.jsx`, `api.js`, `useAddons`, `XtreamLogin` | Operator-only sources, manifest normalization/install/remove/suggested; provider setup restrictions; not visible in ordinary nav | PENDING; read-only existing addon discovery only |
| HOST-01 | `WebAppInterface`, `MainActivity`, native data | Presence, device ID, launcher exit, companion remote/keyboard, start/stop/cancel mic, fetch timeouts, TV box class; launcher-only updates | PENDING; independent package prevents accidental current app updates |
| DEEP-01 | `DeepLinkHandler`, `App.js`, old AndroidManifest | Title/resolve/party/episode intents, correct profile, no Kids leakage; imported routes safely validated | PENDING; new app has no exported deep links yet |
| SUITE-01 | `App.js`, separate Android projects | Legacy `/kids/*`, `/music/*`, `/trivia/*`, `/fta`, `/live-tv` and removed `/sports`; current native Vesper does not import their web bundles | EXTERNAL; audit launcher interoperability before acceptance |

## Backend/data contracts captured
- Login `POST /api/auth/login` JSON `{username,password,client_id}` → `{access_token,token_type,expires_in,account,iptv}`. New client deliberately ignores `iptv` secrets for catalogue work.
- `GET /api/auth/me` bearer → `{account}`. `POST /api/auth/logout` stateless; clear local session even on outage. Do not invent refresh tokens.
- `GET /api/addons` → addon array containing id/name/url/catalogs/resources.
- `GET /api/addons/{addon}/catalog/{type}/{catalog}?search=&skip=&genre=` → `{cached,data:{metas:[]}}`.
- `GET /api/meta/{type}/{id}` → `{cached,data:{meta:{…}},source?}`.
- Later: `/api/streams/{type}/{id}`, `/api/subtitles/{type}/{id}`, `/api/tmdb/*`, `/api/networks/*`, `/api/vesper/sync/{pull,push,meta}`, `/api/backup/*`, party APIs, V2AI APIs.
- Backend addon records are currently operator/global (`DEFAULT_USER`) not per-account. Do not imply account-private catalogues. Native session/cache lifecycle remains isolated.
- Existing account passwords are plaintext server-side (previously deferred by user). This project does NOT resolve that backlog item and does not add a new plaintext password store.

## Architecture / implementation order
1. **Foundation/read-only browse** (current): separate installable dev package, real auth/API client, reusable native widgets, cancelling network, native focus, feature ledger and tests.
2. **Profile/data parity**: import same cloud snapshot safely, keyed per account/profile, preserve all keys including unknown, no write until successful merge; picker/PIN/avatar/viewing style; library and continue watching. UI screenshot comparison now possible per real profile.
3. **Full browse/visual parity**: all home rows, grids, networks/studios/box sets/anime, actor search, details/cast/seasons, themes/side menu/trailer behaviour. Keep a release-blocking comparison list.
4. **Player extraction + stream flow**: study reusable native player boundaries; carry source ranking/headers/tracks/progress/party hooks, not wrapper; unit parity vectors for stream ordering/episode matching; physical device streaming.
5. **Advanced flows**: party, voice/AI/remote/presence, backup/settings/tours/calendar/notifications/operator flows; launcher cooperation and safe migrations.
6. **Acceptance**: every matrix item tested; side-by-side screenshot states on the actual target TV; remote cold-start/restoration; codec/audio/subtitles long runs; low-memory/process death/offline/provider faults; measured key-to-frame timings and memory on HK1-class boxes. Only then mark full parity.

## Verification limits
- Browser screenshots are reference/smoke only and cannot verify the native implementation. The first reference capture included What's New; subsequent comparison must dismiss it to inspect Home.
- Existing web reference has offscreen carousel/hero elements reported by naive overflow detection; unchanged in this migration, not a new native layout result.
- No Android device/emulator connected (`adb devices` empty at start). No physical playback/performance/pixel-parity claim.
- Native widgets expose unique `R.id.test_id` keyed tags + generated Android view IDs (native equivalent of data-testid), with readable contentDescription on controls. Test visible bound/recycled views, not arbitrary unbound RecyclerView holders.
- Test network fixtures may be MOCKED; shipped runtime APIs must not be mocked.

## Next priority
Finish verifying the foundation, then continue profile/cloud import parity without asking for another approval gate. Do not publish this development checkpoint as a replacement for the full current app.