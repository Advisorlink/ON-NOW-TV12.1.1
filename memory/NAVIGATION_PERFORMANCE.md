# Vesper navigation investigation — 2026-10-02

## What was actually wrong
This was not simply “trailers are too heavy.” The shared row had conflicting ownership, and Vesper attached additional work to every change of focus that Kids did not.

1. **Explicit snapping:** Shelf assigned `scrollLeft` immediately; Android focus transitions were forcibly disabled. Prior v1.3.8 deliberately selected a no-slide design. Those cuts cannot look like a glide even on a fast CPU.
2. **Competing scroll/layout updates:** Shelf calculated its position before React changed which poster was wide; the global spatial engine read that intermediate layout and could enqueue an edge-comfort scroll; preview code could nudge it again 300 ms later. Going left/narrow screens exposed this particularly clearly.
3. **Excess reconciliation:** changing `wideIdx` rerendered every PosterTile. Hero-follow state lived in the Home page, so 90 ms focus updates reconciled the rest of Home as well as the hero.
4. **Work for titles being passed over:** zero-dwell previews immediately looked up candidates and could launch device extraction, neighbour extraction and HD warming. The requests are asynchronous (not proven synchronous blocking calls), but create avoidable callbacks, DOM/media updates and native work during navigation.
5. **Kids comparison:** current Kids code shares Shelf/spatial focus but has no TrailerHoverPreview or focus-follow hero hook. That explains the additional Vesper workload; installed APK versions in the recording cannot be established from video alone.

## Implementation
- Separate logical focus from presentation/media work. Focus responds immediately; hero waits 200 ms; trailer lookup waits 280 ms; optional HD warming waits one second of playing.
- One shelf owns its geometry. It snapshots current visible positions, reconciles the two changed widths, commits scroll once, and translates only visible neighbours for 180 ms. Incoming wide preview stays in its reserved slot. Repeated presses replace an animation, never append a queue.
- Use separate wrapper/button transforms to avoid fighting the existing focus scale. No animated width, layout-per-animation-frame loop, arbitrary key lag, or disable-trailers workaround.
- Memoize unchanged posters. Keep focus-follow state in a dedicated hero component, not the page.
- Stop old decoding immediately on navigation; invalidate async callbacks on unmount/disable. Existing fullscreen/resume and nonplaying title-open fallback retained.
- No new native code, server changes, auth changes or external services.

## Evidence
Tests ran on the environment URL from frontend/.env, with populated real catalogs and existing testuser. Before/after browser samples are diagnostics, not a controlled physical-device FPS benchmark.

| Eight right presses, ~100 ms spacing | Before | After |
| --- | ---: | ---: |
| Trailer/find-by-IMDb completed requests over navigation + settle | 12 | 2 |
| New requests during rapid navigation | immediate lookups observed | 0 |
| Sampled rAF gaps over 33 ms | 15 / 76 samples | 2 / 94 samples |
| Maximum sampled gap | 116.7 ms | 83.4 ms |

Functional evidence: one selected wide card; stable second-slot anchor (desktop raw layout x≈346.8); reversal settles; no queued focus moves; first/last handling and reduced motion pass. Main follow-up sampled Android/low-end classes two rAFs after a move: eight 180 ms translations running at ~16.6 ms, plus 120 ms focus transitions; zero remain after settling. Under 4× CPU throttling, held repeat events ended at the same title after an 800 ms release pause.

Main playback follow-up used a **MOCKED native bridge + candidate response**, local existing `e2e-test-clip.webm`, real UI: card 0.524 s → fullscreen 0.716 s → in-card 0.736 s, advancing/not paused. This verifies UI lifecycle and timestamp transfer, NOT real NewPipe/YouTube service availability. Without playable media, Enter correctly navigated to the title instead of presenting a dead fullscreen action.

Phone issue discovered during regression was fixed with compact navigation/More; all buttons and menu remain within 390 px. Intentional clipped carousel children are not document overflow. Tests also cover Desktop 1920×800, mobile 390×844, Collection/Anime and Kids context (`?profile=kids`).

## Remaining validation
Build/install the Vesper APK because the React bundle is packaged in its assets. Test on the original TV box with trailers ON: tap right/left, hold right, reverse immediately, release, pause for a trailer, expand/Back, and change rows. Actual hardware profiling is still needed before claiming a guaranteed frame rate.

External browser YouTube embed restrictions and addon CORS/502 responses were observed. The app retains its existing fallbacks; these service limitations were not repaired or hidden. Report: `test_reports/iteration_98.json`, with main-agent corrections/follow-up in `iteration_98_followup.json`.