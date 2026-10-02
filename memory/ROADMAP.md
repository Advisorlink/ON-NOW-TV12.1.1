# ON NOW TV V2 — Priorities (2026-10-02)

## P0 — current
- Install a newly built Vesper APK containing the navigation fixes; validate normal/held/reversed D-pad movement with trailers ON on the same box as the recording. Browser/CPU-throttled verification passes; physical hardware pending.
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
- Apply 16:9 Up Next artwork to channel previews.
- Replace superscript Live text with visual LIVE chips.
- P3: muted-over-an-hour guard.
- Existing critical security findings explicitly deferred by user; see historical CHANGELOG. Not fixed by this task.
- Legacy hook-dependency lint warnings in unrelated suite components remain technical debt; standard APK workflow already uses CI=false.