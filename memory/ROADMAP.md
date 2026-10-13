# ON NOW TV V2 — Priorities (2026-10-13)

## P0 — current
- **Sports navigation performance implemented/tested2026-10-13:** user clarified delay/black screen when opening match/team. Seed-first native draw, deferredWebView, off-main channel/alert/replay preparation, in-place team actions, bounded response cache, match-specific no-preview/no-animation transition and duplicate-tap guard.53 JVM tests + debug build/signature/alignment pass; synthetic matcher559→6ms, same results (NOT physical TV load timing). **Next:** matching-signer update on box and verify cold/repeat opens/Back/no-black behavior. No release publication or device installation performed. Prior graphics/portrait acceptance also pending.
- **Native phone/TV parity changes built/tested2026-10-13:** portrait Hub entry, phone orientation unlock with TV landscape retained, faded actual-team crests, responsive grid/match layout and sharper bounded3D.41 native tests (including real390dp native layout measurement) +18 backend tests + debug build/signature/alignment pass. **Next: matching-signer updated APK + actual phone/box screenshots/rotation/replay acceptance.** Browser samples are not native proof; no new APK published or physical install performed. See PRD and `test_reports/native_phone_tv_parity/`.
- **Automatic replay implemented/tested2026-10-13:** no Check replay; verified-only Watch replay on native cards/game pages, direct player handoff, account-isolated bounded/cancellable discovery.33 JVM tests +13 backend tests and debug assembly/signature pass. Real example Dodgers–Brewers NLCS Game1 on TNT SPORTS1 SD, ESPN11Oct2026 slate (broadcast12Oct01:00UK), valid bounded MPEGTS probe. **Next:** signed APK/install + physical device playback; current installed/published APK not updated. See PRD and provider_replay_recording_check.json. Browser examples remain MOCKED.
- **New: APK parse error unresolved** (Samsung screenshot,2026-10-13). Saved debug and downloaded GitHub release pass CRC/manifest/signature/data-offset alignment checks. Need user's exact failing APK/download link and Android version. No code fix or new APK issued. Do NOT follow initial RCA header-offset/16KB claim; independently disproved (see PRD). Optional CI signing/publish verification and download preflight guards not implemented. User switched to replay-button demonstration; existing browser locations shown and click-tested.
- Native section headings now **Live Now / Coming Up / Previous Games & Replays** with grouped cards. Default cross-sport feed still uses existing limited recent-results coverage; broader historic aggregation not added. Browser reference keeps its sample labels. Full visual acceptance requires actual phone/TV install, not browser screenshots.
- Premium V2 Sports Hub implemented: genuine shared3D fields with illustrative figures, team graphics/portraits, fixture-time channel matching, date browsing and provider-aware replay checks/player. **Passed:**18 backend tests,21 native tests, debug APK assembly/signature,12 WebGL surfaces and desktop/mobile checks. **Next acceptance:** update backend, run signed Live TV APK workflow, install on box; verify real provider metadata/recordings, graphics/fallback and D-pad behavior. Browser preview remains MOCKED; actual provider streaming is not tested from this pod. See PRD latest section and `test_reports/iteration_103_followup.json`.
- Astra selected and implemented natively as V2 Sports Hub with rounded V2 Live styling, dark-team contrast floor, single upcoming times, real feed error/cancellation handling and refreshed map/game views. Full Kotlin/resource compilation,6 JVM tests,13 backend tests and debug APK assembly/signature verification passed. **Next:** run **Build ON NOW V2 Live TV APK**, install release-signed update and verify D-pad/live channel/reminder flows on the box. SDK workflow hardened; actual remote CI rerun still needed because the original screenshot omitted the error detail. Browser `/sportshub-astra/` remains a SAMPLE design preview, not live playback.
- ON NOW TV Direct playback stutter/buffering and wrong audio/subtitle selection remain unresolved; recurring user issue. Not investigated during comparison-only work. Inspect VesperVlcEngine/ExoPlayer track selection and buffering in a separate task, then rebuild/install APK for actual device testing.
- Obtain Live TV crash photo/stack or trigger; unidentified device crash cannot yet be attributed to a specific line. Install rebuilt Live TV APK with login recovery/cancellation/cache fixes, and Music APK with scoped motion/update optimizations; test real D-pad + reset + favourites preservation.
- Install a newly built Vesper APK containing shelf + top-menu smoothing and v1.5.1 single-screen update notice. Validate normal/held/reversed D-pad movement with trailers ON and startup/login on the same box. Browser tests pass; physical hardware pending.
- Existing native APK fixes from prior sessions also await installation.
- Self-hosted launcher recovery remains blocked on user/VPS evidence: `systemctl status onnowtv-launcher.service` and `journalctl -u onnowtv-launcher.service -n 50 --no-pager`. Not touched during navigation work; no new production outage was reported this session.
- User verification of latest Anime opt-in change remains pending.

## P1 — user backlog, not part of this navigation task
- Companion in-playback stream/subtitle/next-episode controls.
- IPTV master expiry alert seven days before expiry; scheduled tasks need the platform scheduling workflow when implemented.
- Trivia launcher tile/install/update availability. Verify existing Trivia app first: historical PRD documents completed implementation, despite fork summary saying not started.
- Manual Wrong bucket? sports classification override.
- Pending-device registration notifications.
- LIVE chips on channel rows outside the hub.
- Box/network fingerprinting was proposed but NOT approved; do not implement without explicit user consent.

## P2 / future
- Optional bounded prefetch of only the currently highlighted match, after real-device performance acceptance; not implemented.
- Optional unobtrusive build-version label so screenshots identify the installed native build.
- Optional Replays-only filter, after automatic replay/device acceptance.
- Playback/navigation diagnostics screen (useful next enhancement: actual TV frame/latency counters).
- Optional Settings → Latest updates entry to reopen the concise release summary manually without startup repeats.
- Apply 16:9 Up Next artwork to channel previews.
- Replace superscript Live text with visual LIVE chips.
- P3: muted-over-an-hour guard.
- Existing critical security findings explicitly deferred by user; see historical CHANGELOG. Not fixed by this task.
- Iteration100 additionally observed backend lockout counter/proxy-origin behaviour in unrequested security tests. Original report retained; security task remains deferred. Same-origin login/navigation tests pass.
- Some external music streaming sources fail403/decode in browser; navigation/playback-state changes do not guarantee upstream playback availability.
- Legacy hook-dependency lint warnings in unrelated suite components remain technical debt; standard APK workflow already uses CI=false.