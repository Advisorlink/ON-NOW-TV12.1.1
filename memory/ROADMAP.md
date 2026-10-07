# ON NOW TV V2 — Priorities (2026-10-07)

## P0 — current
- **Recurring P0:** build/install Vesper **play-layout-4** on affected phone/tablet. Actual layout blocker reproduced: absolute cast lane intercepts Play at844×390/1024×600; fixed by mobile normal-flow cast + one scroller/full-width hero. Trusted touch with real populated data now passes modern and legacy bridge tests. Desktop diagnostics overlap also fixed. Browser native bridge/lifecycle MOCKED; physical APK playback remains unverified. No Kotlin changed in this fix. See PRD + iteration104/105/followup.
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
- Playback/navigation diagnostics screen (useful next enhancement: actual TV frame/latency counters).
- Optional Settings → Latest updates entry to reopen the concise release summary manually without startup repeats.
- Optional playback-handoff diagnostics to record failed taps/player launches for easier real-device troubleshooting.
- Playback details stage/version panel implemented; optional later Copy details/share report action, only after current device issue is verified.
- Apply 16:9 Up Next artwork to channel previews.
- Replace superscript Live text with visual LIVE chips.
- P3: muted-over-an-hour guard.
- Existing critical security findings explicitly deferred by user; see historical CHANGELOG. Not fixed by this task.
- Iteration100 additionally observed backend lockout counter/proxy-origin behaviour in unrequested security tests. Original report retained; security task remains deferred. Same-origin login/navigation tests pass.
- Some external music streaming sources fail403/decode in browser; navigation/playback-state changes do not guarantee upstream playback availability.
- Legacy hook-dependency lint warnings in unrelated suite components remain technical debt; standard APK workflow already uses CI=false.