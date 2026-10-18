# Vesper — user video comparison / v0.4.0 checkpoint (2026-10-18)

## Authoritative references
- NEW native recording (incorrect appearance): `20261018_184157.mp4`, https://customer-assets-jt897jd0.emergentagent.net/job_rebrand-app-5/artifacts/1ecc97c14eb20aaf_20261018_184157.mp4
- TARGET original V2: `20261018_184411.mp4`, https://customer-assets-jt897jd0.emergentagent.net/job_rebrand-app-5/artifacts/d67d2f89380003a8_20261018_184411.mp4
- User explicitly says first=new native; second=existing V2. Do not reverse them. User wants the native TV-box app to match layouts, sizes, trailers and movie details, not a fresh design.

Both videos were analyzed, then downloaded/contact-sheet frames inspected. Camera perspective means they are NOT pixel-exact captures. Use TV content proportions and original React source, never camera-image pixels as CSS measurements.

## Visible differences and verified source causes
1. Native hero/nav/fonts/posters too small, grid far denser than V2 on the same screen. Native measurements were authored at half the original 1920px CSS values but assumed Android density already yielded 960dp. Generic boxes reporting1080p/mdpi violate that assumption. No density normalization existed.
2. Native shelves revealed row boundaries/next headings instead of the original fixed-half-screen hero + one complete row/page.
3. V2 has a wide landscape trailer slot beside portrait posters, expanding with focus, with native trailer playback/menu. Native v0.3 had only posters and no preview implementation.
4. Native movie details lacked V2's separate top-edge Back/Home controls, title/logo composition, secondary actions and bottom cast strip. The reference has cast imagery and actor focus behavior, not a standalone framed poster as some automated video descriptions suggested.

## Implemented
- `TvViewport`: browse Activity only uses 960×540 logical units for16:9, preserving physical resolution/fontScale and normalizing density **and screen dp configuration together**. MainActivity owns this override; original player context/global resources unchanged. 720p/1080p mdpi+xhdpi/4K measured in regressions.
- Browse six-column policy and tile dimensions filling each cell at2:3, rather than tiny fixed posters with excessive gaps. Home rail remains source-faithful: one16:9slot plus2:3posters; it is NOT forced to six narrow cards when trailers are off.
- `PagedShelfLayout`: snaps full shelf page below the fixed hero. Offset0 is relative to LinearLayoutManager's padded start, so preserves paddingTop; explicit test proves this. Heading/count styling, bottom anchoring and focus-safe rail insets adjusted.
- `TrailerRepository`, `DeviceTrailerExtractor`, `TrailerPreview`, `TrailerRail`, `TrailerDialog`: original V2 NewPipeExtractor0.26.3 device-first muxed extraction, existing backend fallback with paired audio support, native Media3 texture surface, one shared home decoder,280ms dwell, cancellation/HTTP call abort, bounded short-lived memory cache. Validated actual candidates only, no watch-URL/player iframe fallback. Fullscreen reparents same player to retain position; Back returns to slot. Hide/background/dispose stop and release. Missing trailers retain artwork and functional Play.
- Local/profile-scoped automatic-trailers setting (read-only cloud seed); no cloud write.
- Details: title/logo, compact metadata/two-line synopsis, Play, manual Choose stream bypass, Trailer, disabled Add to Library, autoplay toggle, separate top-corner Back/Home, bottom native cast cards with desaturated idle photos/focus colour, actor biography focus/read-only dialog. Existing series episode/release logic retained.

## Verification and limitations
- Report124: native suites/builds,13 backend/API checks passed. Network fixtures in native tests are **MOCKED**, runtime integrations are real. Actual existing metadata/HD trailer backend contract passed separately.
- Main reviewed added test files. Strengthened density tests (the initial new test incorrectly preserved original screen dp rather than normalized dp), added physical-pixel preservation, exact six-column cell dimensions, padded paging and long-details/manual-choice acceptance tests. Latest forced full run:115 cases,114pass,0failure/errors,1existing hardware Keystore skip.
- Debug/release assembly passes; release prefill disabled/empty. Private code8 build preserves signing identity, CRC and alignment verified. All515 original frontend baseline files match, original wrapper untouched, cloud remains READ-ONLY.
- **NOT established:** actual GPU pixels, on-device NewPipe success/decoder/audio behavior, overscan/user-box resolution reporting, real D-pad trailer/fullscreen races/performance, complete actor filmography/similar views, other missing feature matrix. Do not tell user everything matches or trailers have been seen playing on their box. They need this new build to test against video2.

## Next acceptance sequence
Install v0.4.0 over the separate native test app using existing private helper. Same account/profile/title as V2; compare Home proportions, TV/movie grid six columns, row up/down alignment, wide trailer appearance/sound/fullscreen/Back, movie Details/cast, Play vs Choose stream, rapid reversals and background/resume. Capture straight-on shots where possible. Keep capability/APK private because approved debug prefill is extractable. No credentials, accounts or PINs changed during this work.