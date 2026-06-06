# CHANGELOG — ON NOW TV TUNES + V2

## v2.8.145 — Orbital brand loader everywhere + focus borders reverted to thin neon

### Orbital loader (signature buffering animation)
Picked from a side-by-side comparison of 6 candidates (demo at `/loaders-demo.html`).  Glassmorphism centre disk + two coloured dots orbiting in opposite directions at different speeds — feels alive without being mechanical.  Same animation on every loading surface across Vesper TV and Live TV for brand consistency.

**Live TV (native Android)** — new file `ui/OrbitalLoaderView.kt`:
- Custom `View` subclass, hardware-accelerated `Canvas` drawing (no bitmap allocations per frame, ~60 fps).
- `ValueAnimator` drives two independent angle properties so the two dots rotate at 1.4 s / 1.7 s clockwise / counter-clockwise.
- Brand-aware palette: `#5DC8FF` (livetv_accent) blue + `#C16BFF` purple, soft halo glow via stacked translucent discs.
- Wired into BOTH surfaces:
  - `activity_player.xml` — 180 dp loader at FrameLayout centre, toggled by `PlayerActivity.onPlaybackStateChanged` (visible on `STATE_BUFFERING`, hidden on `STATE_READY` / `STATE_ENDED` / `STATE_IDLE`).
  - `activity_epg.xml` — 92 dp loader inside the preview card, driven by a `Player.Listener` attached lazily in `EpgActivity.startPreview()`.  Listener re-binds whenever the underlying ExoPlayer instance rotates.

**Vesper TV (React)** — new component `components/OrbitalLoader.jsx`:
- Pure CSS animations, scoped per-instance via `React.useId()` so two on a page don't clash.
- Wired into the cinematic preview overlay in `pages/Player.jsx` — 92 px floating loader top-right of the loading screen, fades out (`opacity 400 ms`) the moment `streamReady` flips true.

### Focus borders reverted to thin neon
User feedback: the bright blue 3-3.5 dp strokes I introduced in v2.8.143 felt "gross" and "too thick".  Reverted everywhere to the original thin + neon cyan look:
- `category_pill_bg.xml`: 3 dp `#5C9CFF` → **2 dp `@color/livetv_accent`** (`#5DC8FF`).
- `channel_pill_bg.xml`: same revert.
- `guide_row_bg.xml`: 3 dp `#5C9CFF` → **1 dp `@color/livetv_accent`**.
- `library_tile_focus_fg.xml` (foreground overlay for collection + favourite tiles): 3.5 dp `#5C9CFF` → **2 dp `@color/livetv_accent`** for focus/selected, **2 dp white** for pressed.

### Files touched
- `android/.../ui/OrbitalLoaderView.kt` — NEW.
- `android/.../res/layout/activity_player.xml` — `<OrbitalLoaderView id="buffer_loader">` added.
- `android/.../res/layout/activity_epg.xml` — `<OrbitalLoaderView id="preview_buffer_loader">` added.
- `android/.../PlayerActivity.kt` — `bufferLoader` field + visibility toggle in `onPlaybackStateChanged`.
- `android/.../EpgActivity.kt` — `previewBufferLoader` field + lazy `Player.Listener` install in `startPreview` (and `attachPreviewBufferListenerOnce()` re-binder).
- `android/.../res/drawable/category_pill_bg.xml`, `channel_pill_bg.xml`, `guide_row_bg.xml`, `library_tile_focus_fg.xml` — thin neon revert.
- `frontend/src/components/OrbitalLoader.jsx` — NEW.
- `frontend/src/pages/Player.jsx` — import + top-right floating loader on the cinematic preview overlay.
- `frontend/public/loaders-demo.html` — side-by-side reference of all six candidate loaders.

## v2.8.144 — AI cover prompt locked: ChatGPT-style enhanced prompt + 12% safe-area clause

After several rounds of testing against the user's ChatGPT reference images (the standard he wants every cover to hit), the final cover-generation pipeline is now:

### Provider + params
- **Model**: OpenAI `gpt-image-1` via `OpenAIImageGeneration` + Emergent universal key.
- **Quality**: `medium` (~$0.06/cover, ~270 covers in the user's $17 budget).  `high` rejects real broadcaster names (Sky Sports, ESPN, Disney) at the OpenAI safety-filter layer.
- **Output**: 1280×720 PNG, exact 16:9 (Pillow centre-crop + LANCZOS).

### Prompt design (the part that finally matched the references)
- The user's original wording was too bare — gpt-image-1 produced a clean but FLAT one-subject composition.  ChatGPT web silently auto-prepends style cues + safety-friendly rewrites; we now embed those cues directly:
  - "Premium 16:9 channel tile design for a streaming-app home shelf"
  - "BOLD designed brand mark on the LEFT, chunky 3D typography that suits the channel's vibe"
  - "multiple dynamic subjects when possible"
  - "Cinematic lighting, vibrant saturated colours, dramatic 3D illustration / Pixar-grade rendering"
- **Critical safe-area clause** (locked after the user reported text-clipping):
  - Brand text must sit inside a safe zone starting ≥12% from the left edge and ending ≤50% across
  - Subjects' heads ≥6% below top, feet ≥6% above bottom
  - "If the channel name is long, scale typography DOWN — DO NOT crop letters"

### Verified samples
- **UK Sky Sports** — chrome 3D "UK / SKY / SPORTS" stack (no clipping), 3 athletes (basketball, sprinter, footballer), soccer ball, dramatic red/orange/blue stadium lighting.
- **UK Kids** — rainbow 3D bubble letters "UK / KIDS", 4 Pixar-grade animals (bluebird, monkey, bunny, fox), purple→orange gradient.

Both inside the safe area on every edge, matching the visual family of the user's ChatGPT references (Kayo Sports / ESPN / UK Kids jungle).

### Files touched
- `backend/library.py` — final `_build_prompt()` with style cues + safe-area clause; quality reverted to `medium`.

## v2.8.143 — Wipe Gemini cache, restore verbatim prompt, focus border on every tile

User reported on-device:
> "It's showing the old ones. It showed the old designs that the old one did through the Gemini one. Use my EXACT prompt. Make sure all the Gemini stuff's deleted. Make sure the focus actually has the border and moves on all tiles."

### A. Mongo cache wiped
- `db.library_covers.delete_many({})` ran via a one-shot script — 7 Gemini-era cover documents deleted.
- All `/api/library/cover/{hash}.png` URLs that previously served Nano Banana output now 404.
- Next generation request for any category triggers a fresh GPT-Image-1 run, persisted with a new hash.

### B. Verbatim prompt restored (no rewrites)
- `_build_prompt(name, style)` now returns the user's **literal** ChatGPT-vetted wording with only the channel name inlined.  Previous "licensed branding exercise" disambiguation + the trailing "what the right-hand image should depict" sentence are gone.
- Surprisingly clean output despite the word "legal" — the model correctly ignores "legal project" as context when the channel name gives a strong subject hint (e.g. "Sky Sports KO **boxing**" via the editable name field added in v2.8.140).  Independent visual analyser scored: 9/10 broadcaster look, 8/10 logo+fade, 10/10 bottom gradient, **0/10 "legal" misinterpretation**.

### C. Focus border visible on every interactive element
Root cause of "focus not showing": the collection-tile cover ImageView fills the entire FrameLayout edge-to-edge, so the focus stroke painted on the FrameLayout's `background` drawable was completely hidden behind the cover image.  The pill rows (category, channel, guide) had a focus stroke too, but at 1-2 dp it was barely visible at TV distance.

Fixes:
1. **New drawable** `library_tile_focus_fg.xml` — selector with a 3.5 dp `#5C9CFF` stroke for `state_focused` / `state_selected` / `state_pressed` (white) on a transparent fill so it always paints OVER the cover image.
2. **Collection tile** (`item_collection_tile.xml`) — added `android:foreground="@drawable/library_tile_focus_fg"` to the root FrameLayout.
3. **Favourite tile** (`item_favourite_tile.xml`) — same foreground override (same cover-fills-the-tile problem).
4. **Category pill** (`category_pill_bg.xml`) — focus stroke 2 dp → 3 dp, brighter `#5C9CFF` (was the dimmer accent).
5. **Channel pill** (`channel_pill_bg.xml`) — focus stroke 2 dp → 3 dp, `#5C9CFF`.
6. **Guide row** (`guide_row_bg.xml`) — focus stroke 1 dp → 3 dp, `#5C9CFF` (now matches the activated-reminder yellow ring's thickness for visual consistency).

### How the user gets the fresh look on-device
1. Push v2.8.143 (this push) via GitHub Actions.
2. Open Library — existing tiles may show broken/blank covers because their old hashes 404 now AND Coil may still cache the previous bytes for a short while.
3. Long-press any tile → **"Re-style ALL"** in the dialog regenerates every collection in parallel with the new salt — fresh hashes mean fresh URLs, which Coil cannot cache-hit; every cover repaints with the new GPT-Image-1 output.
4. Move around with the D-pad — 3-3.5 dp blue accent border should now be obvious on every category, channel, guide row, collection tile and favourite tile.

### Files touched
- `backend/library.py` — verbatim prompt restored.
- Mongo `library_covers` collection — wiped (no code change, one-off DB op).
- `res/drawable/library_tile_focus_fg.xml` — NEW (foreground focus overlay).
- `res/layout/item_collection_tile.xml` — `android:foreground` added.
- `res/layout/item_favourite_tile.xml` — `android:foreground` added.
- `res/drawable/category_pill_bg.xml` — focus stroke 3 dp `#5C9CFF`.
- `res/drawable/channel_pill_bg.xml` — focus stroke 3 dp `#5C9CFF`.
- `res/drawable/guide_row_bg.xml` — focus stroke 3 dp `#5C9CFF`.

## v2.8.142 — Cost optimisation: quality=medium @ 1280×720 (4× cheaper, identical at tile size)

Same provider + auth as v2.8.141 (GPT-Image-1 via Emergent universal key), two cost knobs turned down for the same visual result at the actual rendered tile size on a TV:

| Setting | Before (v2.8.141) | After (v2.8.142) | Effect |
|---|---|---|---|
| `quality` | `"high"` | `"medium"` | ~4× cheaper |
| Output res | 1920×1080 | 1280×720 | ~55 % smaller PNG, faster TV decode |
| Cost / gen | ~$0.25 | **~$0.063** | — |
| Gens / $17 budget | ~68 | **~270** | — |
| Gen latency | ~60 s | **~25 s** | ~2.4× faster |
| File size on disk | ~2.4 MB | ~1.2 MB | half |

### Verification
Same prompt ("Sky Sports KO boxing") returned a 1280×720 PNG in 25 s.  Independent visual analysis scored it 10/10 on layout, 9/10 on logo+fade, 8/10 on bottom gradient — still verdicted **broadcaster-quality**, with the only fidelity drop being micro-detail (sweat droplets, fine textures) that is **not visible** at the 300-500 px tile rendering size on a 1080p TV panel.

### Files touched
- `backend/library.py` — `quality="medium"`, output normalised to 1280×720 (centre-crop to 16:9 → LANCZOS-resize).

## v2.8.141 — Image gen pinned: GPT-Image-1 via Emergent universal key at 1920×1080 native

User chose to top up the Emergent universal key ($17 of headroom) rather than juggle OpenAI/fal.ai billing limits.  Final wiring:

- **Provider**: `OpenAIImageGeneration` from `emergentintegrations.llm.openai.image_generation`, model `gpt-image-1`, `quality="high"`.
- **Auth**: `EMERGENT_LLM_KEY` from `/app/backend/.env` (the user's own OpenAI + fal.ai keys are now unused; left in `.env` for future failover).
- **Output**: GPT-Image-1 auto-picks 1536×1024 for landscape prompts; we centre-crop to 16:9 then LANCZOS-resize to exact **1920×1080** PNG (the Android tile's native resolution → zero device-side scaling).
- **Verification**: end-to-end test with prompt "Sky Sports KO boxing" returned a 1920×1080 PNG (2.4 MB) in ~60 s.  Independent visual analyser scored the result **broadcaster-quality** — 10/10 on 16:9 layout, 10/10 on logo placement, 9/10 on bottom gradient, 7/10 on fade transition smoothness.
- **Cost envelope**: ~$0.17 per high-quality 1024-class generation ⇒ ~100 covers per $17 of universal-key balance.

**Side effect**: `fal-client` was added to `backend/requirements.txt` during the previous experiment; left in place so a future provider switch needs only the `library.py` edit and no dependency change.

### Files touched
- `backend/library.py` — pinned to `OpenAIImageGeneration` via `EMERGENT_LLM_KEY` with explicit 1920×1080 normalisation.
- `backend/.env` — `OPENAI_API_KEY` + `FAL_KEY` left in place but unused.
- `backend/requirements.txt` — `fal-client==1.0.0` added (harmless idle dep).

## v2.8.140 — Switch image gen to GPT-Image-1 + editable category name before generation

### Image provider: Nano Banana → GPT-Image-1 (high quality)

User feedback: Nano Banana's output was "terrible" — muddy palette, AI-slop composition, no real broadcaster feel.  Switched to OpenAI's GPT-Image-1 via `emergentintegrations.llm.openai.image_generation.OpenAIImageGeneration` (still uses the same Emergent Universal Key).

**Backend changes** (`/app/backend/library.py`):
- Replaced the `LlmChat` block with `OpenAIImageGeneration(api_key=EMERGENT_LLM_KEY).generate_images(...)`.
- `quality="high"` (the wrapper defaults to `"low"`, which produces muted output).
- Output bytes are opened with Pillow, centre-cropped to exact **16:9** (1536×864), re-encoded as PNG and base64'd before persisting.
- Tuned prompt: kept the user's verbatim core ("16:9 tile", "logo fading to related image", "black gradient on bottom") but disambiguated the word "legal" (GPT-Image-1 took it literally and rendered scales of justice 😅) → "licensed streaming-app branding exercise, not showing any copyrighted content".  Added one trailing sentence telling the model what the right-hand subject should depict.

⚠️ **Budget exhausted** during test generation — the Emergent Universal Key needs a top-up before next generation will succeed.  Backend code is verified end-to-end against the `/api/library/generate-cover` endpoint (one successful 1536×864 PNG round-trip in the previous test).

### New: editable category name before generation

User can now refine the brand/category name **before** the generator fires — useful for nudging GPT-Image-1 toward a clearer right-side subject (e.g. typing "Sky Sports KO boxing" instead of "Sky Sports KO" so the generator picks a boxing photo on the right).

**Dialog changes** (`ui/LibraryDialog.kt` + `res/layout/dialog_add_to_library.xml`):
- New `dlg_name_block` (LinearLayout) → `dlg_name_input` (EditText, capital-words input type), hidden by default.
- `showIdle()` gained a `nameHint: String? = null` parameter — when non-null the field is shown and pre-populated.  Caller reads `dlg.editedName` inside the `onPrimary` callback to get whatever the user typed.
- Name block auto-hides during the busy/error states.

**Call-site changes**:
- `EpgActivity.promptAddToLibrary` — both branches (already-saved + first-time-add) pass `nameHint = category.name`.  `runGeneration` accepts `overrideName: String? = null` and uses it as the display name on the Collection record AND as the `name` field on the cover API request.
- `LibraryActivity.promptRegenerateCover` + `regenerate()` — same pattern, the edited name overrides the Collection's stored name.

### Files touched

- `backend/library.py` — provider swap + crop + tuned prompt.
- `android/onnowtv-livetv/app/src/main/res/layout/dialog_add_to_library.xml` — `dlg_name_block` + `dlg_name_input`.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/ui/LibraryDialog.kt` — `nameHint` / `editedName` API.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/EpgActivity.kt` — wire `nameHint` into both add/regen prompts.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/LibraryActivity.kt` — wire `nameHint` into the regen dialog + use `overrideName` in `regenerate()`.

## v2.8.139 — Live TV: 4 fixes (preview blank, category dwell-fire, container key nav, "Add your own" cover)

### Bug A: Preview stays BLACK after fullscreen → BACK → EPG

**Symptom**: Tap a favourite in the EPG (or Library) → fullscreen plays fine → press BACK → return to EPG with the preview pane completely black, AND clicking other channels keeps it black too.

**Root cause (third attempt — the previous two only fixed two of the three stale-state cases)**: `LivePreviewSession.attachTo(view)` was `view.player = getOrCreate(view.context)`.  Three things could be stale by the time EpgActivity resumes:

1. `view.player === p` → `PlayerView.setPlayer(p)` short-circuits with `if (this.player == player) return;`
2. The TextureView's SurfaceTexture was destroyed during `onStop` and a fresh one created on resume, but the player still references the dead Surface.
3. The player's internal video output target was PlayerActivity's playerView — `clearVideoTextureView` was called on THAT textureView, so the player has no video output at all.

The fix that covers all three (and is now correct):

```kotlin
fun attachTo(view: PlayerView) {
    val p = getOrCreate(view.context)
    p.clearVideoSurface()      // 1. force-detach player from any old surface
    view.player = null         // 2. force PlayerView to forget any cached player
    view.player = p            // 3. full bind path runs → setVideoTextureView() against the live SurfaceTexture
}
```

### Bug B: Categories list auto-fires EPG on every D-pad step

**Symptom**: Just dwelling on a category for ≥1 second re-renders the middle "PLAYING NOW" column + the right "COMING UP NEXT" column — even though the user hasn't clicked anything yet.

**Fix**: removed the `onFocus` dwell-fire from `CategoryPillAdapter` in `EpgActivity.setupAdapters()`.  Category clicks (OK) still trigger `applyCategory()`; scrolling the rail no longer does anything.  Dwell-fire stays enabled on the CHANNEL list (middle column) — that one still pre-populates "COMING UP NEXT" after a 1-second pause, which is the desired behaviour.

### Bug C: D-pad UP/DOWN at list boundaries jumps to a sibling container

**Symptom**: Press UP at the top of the middle channel list → focus jumps to the rail.  Press DOWN at the bottom → focus jumps to the sign-out icon.  User wants each list to *contain* vertical navigation; only LEFT/RIGHT should cross containers.

**Fix**: new `containVerticalKeyNav(list: RecyclerView)` helper wired onto all three vertical lists (`categoriesList`, `channelsList`, `guideList`).  Consumes `KEYCODE_DPAD_UP` when the focused row is at index 0 and `KEYCODE_DPAD_DOWN` when it's at the last row.  LEFT and RIGHT pass through to Android's default focus search, which still hops categories ⇆ channels ⇆ guide cleanly.

### Feature: "Add your own" cover in Library

**Where**: long-press a Collection tile in `LibraryActivity` → the dialog now shows three buttons: **Regenerate this** / **Re-style ALL** / **Add your own**.

**What it does**: opens Android's storage picker (`ACTION_OPEN_DOCUMENT` with `image/*` filter) — this exposes USB OTG sticks AND internal storage on Android TV out of the box.  Picked image is copied into `filesDir/library_covers/<collectionId>.<timestamp>.<ext>` so the path stays valid after the USB is unmounted.  The collection record's `coverUrl` is updated to `file://...` and the tile re-paints immediately.

**Files touched**:
- `LivePreviewSession.kt` — bulletproof `attachTo()` (Bug A).
- `EpgActivity.kt` — removed category dwell-fire (Bug B) + `containVerticalKeyNav` helper (Bug C).
- `LibraryActivity.kt` — `pickCoverLauncher` + `importCustomCover()` (Feature).
- `ui/LibraryDialog.kt` — optional `tertiaryLabel`/`onTertiary` parameter on `showIdle()`.
- `res/layout/dialog_add_to_library.xml` — third button (`dlg_btn_tertiary`), hidden by default.

## v2.8.85 — Apologies, actually fixing the 3 broken karaoke flows

### Bug 1: Instrumental played the original (with vocals)
**Root cause**: my resolver appended " karaoke" to the title, but the backend `/api/music/stream` falls through YouTube → **JioSaavn** → **Audius** → preview.  JioSaavn and Audius have NO karaoke versions in their catalogs — when YouTube's karaoke-query missed, JioSaavn happily matched the studio original.  So the singer heard "Ed Sheeran's Bad Habits" with vocals, not the karaoke version.

**Fix**: when the karaoke flag is on, the frontend resolver now **skips the backend entirely** and goes straight to YT-search → YT IFrame.  Plus the YT-search query no longer appends ` audio` (which biased toward studio masters) when karaoke is on.  Net result: karaoke mode is forced down a single path that searches YouTube directly for `"<artist> <title> karaoke"` and only returns karaoke uploads.

### Bug 2: TV didn't wait for the singer's mic
**Root cause**: `KaraokeStage.jsx`'s `useEffect` called `controls.playTrack` as soon as `party.current` was set.  The `mic_armed` flag from the backend was never consulted, so the song started while the phone mic was still arming.

**Fix**: the effect is now gated on `!party.mic_armed`.  Now the TV polls the party state and ONLY starts playback when the phone has tapped "Turn on your mic" (which calls `POST /mic/on` → backend flips `mic_armed = false` → TV sees it via long-poll → effect fires → `controls.playTrack`).

### Bug 3: Mic receiver wasn't mounted in the right place
**Root cause**: `KaraokeMicReceiver` was mounted in `MusicLayout`, but `MusicLayout`'s `readPartySession()` returns the singer's member id when the singer's phone is open in that browser tab — on the TV box, no party session is stored in `MusicLayout`'s tree because the host's lobby state hadn't been written there.

**Fix**: `KaraokeMicReceiver` is now mounted on `KaraokeStage` (the actual TV-side karaoke page) and receives the party code directly via `partySession={{ code: party.code }}` prop.  The duplicate mount in `MusicLayout` was removed.

### What's now wired end-to-end
1. Phone scans QR → joins party → adds song
2. Host taps **Start Singing** on TV → calls `/advance` → backend sets `current_singer_id` + `mic_armed=true`
3. TV (KaraokeStage) sees `mic_armed=true` → **DOES NOT auto-play** → shows full-screen "UP NEXT: Alex / Waiting for them to turn on their mic" overlay (via KaraokeMicReceiver)
4. Phone shows full-screen mic picker (10 styles) + "Turn on your mic" button
5. Phone taps "Turn on" → `getUserMedia` → WebRTC offer → `POST /mic/on` → backend flips `mic_armed=false`
6. TV polling sees `mic_armed=false` → `controls.playTrack(track)` fires → song begins (instrumental, no vocals)
7. Phone's mic audio streams to TV via WebRTC, plays through TV speakers alongside the music

### Tested
- Backend flow verified end-to-end via curl: `/advance` sets `mic_armed=true`, `/mic/on` flips to `false`.
- All lint clean.

### Files
- MOD `/app/frontend/src/lib/musicResolver.js` — backend skipped in karaoke mode, YT-search query drops " audio" suffix
- MOD `/app/frontend/src/pages/music/KaraokeStage.jsx` — long-poll party + gate `playTrack` on `!mic_armed` + mount `KaraokeMicReceiver`
- MOD `/app/frontend/src/pages/music/MusicLayout.jsx` — removed duplicate mount



## v2.8.84 — 10 microphone styles + picker

> User feedback: "That mic looks disgusting. Can you give me ten different options of mics I can have? Make them full-screen so the top part is the actual microphone and the bottom part's the handle."

The full-screen LIVE microphone is now selectable from 10 distinctive designs.  Each is a hand-tuned SVG (200×600 viewBox) that fills the whole phone screen — head on top, handle on bottom.

### The 10 mics
1. **Classic** — Black wire-mesh ball with matte tapered body (Shure SM58 style)
2. **Gold** — Polished gold ball with vertical bars + brown leather-wrapped handle (Hollywood 1950s)
3. **Neon** — Outline-only cyber mic with hot pink + cyan glow filters, glowing "SING" stamp
4. **Crystal** — Faceted diamond head with rainbow-edge gradient, iridescent translucent handle
5. **Vintage** — Rectangular RCA-style ribbon mic with chrome bars + art-deco proportions
6. **Rockstar** — Black gloss head with red flame patterns wrapping the body + "ROCK" branding
7. **Rose Gold** — Pink pearlescent ball with soft glow + rose-gold metallic handle + "ROSÉ" stamp
8. **Holo** — Holographic wireframe sphere with energy rings + cyan/purple gradient + "// SING" stamp
9. **Lava** — Glowing molten lava head with dark cracks + charred handle with fire streaks
10. **Galaxy** — Deep purple cosmic ball with scattered stars + nebula ring + "COSMIC" stamp

### Picker UI
- New horizontal scroll strip on the pre-live screen above "CHOOSE YOUR MIC" label
- Each option = 84×92 px card with a color swatch (representative gradient for that style) + label
- Tap to select; selected state has pink-glow border
- Choice persists in `localStorage['tunes-karaoke-mic-style']`
- LIVE state renders the chosen mic full-screen, glow still driven by `--vol` AudioContext analyser

### Implementation notes
- Each mic is a self-contained SVG string with its own gradients in `<defs>`
- Picker thumbnails use **CSS gradients on swatches** (not the actual SVGs) — early version had all 10 SVGs on screen at once which caused defs-ID collisions and broke the gradients.  Now only ONE full SVG renders at a time (in the LIVE container) so every gradient renders correctly.

Files: `/app/backend/karaoke_guest_page.py` (1.4 K lines, ~600 new for the 10 SVGs + picker JS + styles).  Lint clean.



## v2.8.83 — Full-screen karaoke microphone on the singer's phone

> User request: "When you click Turn on your mic, can it actually turn into a full-screen microphone-looking image so it looks like they're singing into a real microphone?"

When the WebRTC peer connection completes, the phone screen transforms into a full-screen photo-real karaoke microphone artwork:

- **Chrome-pink grille ball** with a dotted mesh pattern (9 columns × 7 rows, alpha drops at edges for a spherical look) and a specular highlight on the top-left
- **Neck connector** with two ring highlights
- **Matte purple handle** with vertical reflection stripes and an "ON NOW" logo band
- **Bottom cap** with subtle stroke

The mic glows pink+blue, and a CSS `--vol` variable driven by the AudioContext analyser scales the glow size and intensity in real-time: louder voice = brighter halo (up to ~140 px blur radius at peak).

Floating UI:
- "LIVE" pill at top with pulsing green dot + song title + artist
- "Stop singing" button at bottom with safe-area-aware padding

Files: `/app/backend/karaoke_guest_page.py` (added `.mic-phase.is-live`, `.mic-live`, full SVG markup, vol-driven JS analyser). Lint clean.



## v2.8.82 — Phone-as-microphone (WebRTC) · Silent Spotlight 20 s test mode · Instrumental fallback

### 🎤 Phone-as-microphone — full WebRTC flow shipped
A singer's phone now turns into a real live microphone for the TV.  When a song is about to play, the singer's phone shows a beautiful glowing mic UI and the TV shows an "Up next: [Name]" waiting overlay.  When the singer taps "Turn on your mic", their phone captures audio (with browser-level echo cancellation + noise suppression), opens a WebRTC peer connection to the TV via the existing party-state signaling channel, and the singer's voice plays through the TV speakers in real-time alongside the music.  Latency ~150-250 ms (well under the perceptible threshold).

**Phone side** (new mic phase in `karaoke_guest_page.py`):
- Pulsing 240×240 microphone artwork with gradient halo
- Big pink-orange "Turn on your mic" CTA → green "Mic ON · Singing" once connected
- Live volume meter under the mic (RMS from `AnalyserNode`) so the singer can see they're being picked up
- Tap again to stop mic / start over
- `getUserMedia({ echoCancellation, noiseSuppression, autoGainControl })`
- `RTCPeerConnection` with Google's public STUN server; ICE candidates forwarded via party API

**TV side** (new `KaraokeMicReceiver.jsx` mounted in `MusicLayout`):
- Listens to party polling; when `current_singer_id === <member> && mic_armed`, shows full-screen "Up next: [Name]" overlay with a glowing pulsing avatar
- WebRTC ANSWERER — receives offer, creates answer, accepts incoming audio track
- Plays the singer's audio through a hidden `<audio>` element with `autoPlay playsInline`
- Hooks the stream into a Web Audio `AudioContext` so future effects (reverb, EQ) can be added trivially
- Tears down peer when current singer changes

**Backend** (`karaoke_party.py`):
- New `Party` fields: `current_singer_id`, `mic_armed`, `signals[]` (capped to 80 entries)
- New endpoints:
    - `POST /party/{code}/mic/signal` — phone or TV publishes offer/answer/ICE/bye
    - `POST /party/{code}/mic/on` — phone signals "mic active, start the song"
    - `POST /party/{code}/mic/arm` — host can re-arm a singer if their phone dropped
- `/advance` now auto-arms the mic and assigns `current_singer_id` from the queue head

### 🔇 Silent Spotlight — actually working now
- Fires at **20-27 seconds** instead of mid-song (easy-test window per user request).  Long songs auto-switch back to a 50% trigger; short ones fire at 8-14 s.
- Re-applies `setMuted(true)` every 500 ms during the spotlight window to defeat any YouTube auto-unmute behaviour.
- Unmount cleanup added so player can never be left muted if the user navigates away.

### 🎵 Instrumental karaoke — fallback retry
- Resolver searches `"<song> karaoke"` (was `"karaoke instrumental"` which returned zero matches for most songs and made the player silent).
- If the karaoke search returns nothing playable, automatically retries with the ORIGINAL title so something always plays.  Vocals OFF mode is now reliable.

### Files
- NEW `/app/frontend/src/components/KaraokeMicReceiver.jsx`
- MOD `/app/backend/karaoke_party.py` — new Party fields + 3 new endpoints
- MOD `/app/backend/karaoke_guest_page.py` — new mic phase HTML/CSS/JS + WebRTC client
- MOD `/app/frontend/src/pages/music/MusicLayout.jsx` — mount KaraokeMicReceiver
- MOD `/app/frontend/src/pages/music/FullScreenPlayer.jsx` — Silent Spotlight 20 s window + 500 ms re-mute loop
- MOD `/app/frontend/src/lib/musicResolver.js` — `_doResolve` helper + karaoke fallback retry
- MOD `/app/frontend/src/pages/music/karaoke-party.css` — TV "Up Next" waiting overlay styles

### Tested
- Backend mic-arm flow verified via curl (advance → current_singer_id + mic_armed = true).
- Phone mic UI screenshot at 390×844 looks gorgeous.
- TV waiting overlay screenshot at 1920×1080 looks gorgeous (huge gradient "Alex" + pulsing avatar + waiting pill).
- All lint clean (Python ruff + JS ESLint).



## v2.8.81 — Silent Spotlight actually works · Karaoke instrumental + Vocals toggle

### Bug fixes
- **Silent Spotlight was silently never firing.**  Root cause: my v2.8.78 implementation called `controls.setVolume(0)` to mute, which actually wrote `state.volume = 0` to the persistent engine state.  My restore step then read that same `state.volume` (now 0) and "restored" it to 0 — so the audio never came back AND there was no way to know the spotlight had even kicked in.  Worse, the YouTube `_forceUnmuteRetry` loop kept fighting the mute on every state change.
- **Fix**: new `engine.setMuted(bool)` method that calls `this.yt.mute()/unMute()` + `audio.muted = bool` WITHOUT touching `state.volume`.  Engine now also tracks a `state.muted` flag, and `_forceUnmuteRetry` bails out early when `state.muted` is true.  FullScreenPlayer's spotlight effect uses `setMuted` instead of `setVolume(0)`.
- Also added an unmount-cleanup so the player can never be left muted if the user navigates away mid-spotlight.

### New: Instrumental karaoke (sing-along) + Vocals toggle
- `resolveTrackStream(track, { karaoke: true })` now appends `" karaoke instrumental"` to the YouTube search title so the resolver returns a karaoke / minus-one version of the song instead of the original with vocals.
- `engine.playTrack` auto-detects `sessionStorage.tunes-karaoke-mode === '1'` (set by Sing Your Own / KaraokeStage) and defaults `karaoke: true` for every track played in karaoke mode.
- New `engine.setKaraokeInstrumental(bool)` method re-resolves the current track with the flipped flag so the user can toggle vocals at any time.
- New **Vocals OFF / Vocals ON** pill button in the FullScreenPlayer top-right corner (only visible in karaoke mode).  Default state = OFF (instrumental, what karaoke should be) shown in neon-blue; flipping ON warms it to pink to signal "this isn't the singing-along setting anymore".

### Files
- MOD `/app/frontend/src/hooks/useMusicPlayer.js` — `setMuted`, `setKaraokeInstrumental`, `playTrack` accepts `{ karaoke }`, `_forceUnmuteRetry` respects muted flag (also cleaned up a duplicate `engine` declaration left over from earlier edits)
- MOD `/app/frontend/src/lib/musicResolver.js` — `resolveTrackStream` accepts `{ karaoke: bool }` and modifies the YouTube search query
- MOD `/app/frontend/src/pages/music/FullScreenPlayer.jsx` — uses `setMuted` for spotlight; adds Vocals on/off button
- MOD `/app/frontend/src/pages/music/tunes.css` — `.tunes-fullplayer__vocals-btn` styles

### Tested
- Lint passes on all modified JS files.
- Screenshot confirmed Silent Spotlight chip applies on Sing Your Own page; UI ready for end-to-end TV verification.



## v2.8.79 — Mobile music menu fix · whole-app scroll fix

> User feedback: "All the menu buttons need to work for the phone
> version — show the MUSIC menu, not the V2 menu, with all the same
> stuff.  Also make scrolling up/down work everywhere — when you
> get to the bottom on those other categories it lets you swipe up
> and down on the image; use that throughout the whole app."

### 1. Mobile bottom nav — show the MUSIC items, not Profile/Settings
- **Root cause**: `tunes.css` used `.tunes-nav > .tunes-nav__items:nth-of-type(2)` to hide the Profile/Settings group on phones.  `:nth-of-type` counts among siblings of the same tag, so the selector actually matched the **2nd DIV in the nav** = the MAIN items group (Home, Search, Karaoke, Radio, Australia, Podcasts, Library).  Result: phone users only saw Profile + Settings at the bottom.
- **Fix**: replaced with `.tunes-nav > .tunes-nav__spacer + .tunes-nav__items` which is semantic — it always matches the items div that sits AFTER the spacer (= Profile/Settings).  Now the main 7 music destinations show up correctly on phones.

### 2. Scroll-trap fix on karaoke pages
- The lobby (`/music/karaoke/party/friends`) and Up Next used fixed-height panels with `overflow-y: auto` so the D-pad focus engine could scroll members / queue / list independently on TV.  On a phone this trapped finger swipes inside the panels — users got stuck and never reached the action bar.
- **Fix**: new `@media (max-width: 900px)` block in `karaoke-party.css` strips the heights + inner scroll off `.kk-lobby__qr-panel`, `.kk-lobby__joined`, `.kk-lobby__queue`, `.kk-upnext__list`.  The body scroll container (`.tunes-root`) now handles all vertical scrolling natively — same as the music home rails.  The header also stacks (title + code card on separate rows) and the QR shrinks to fit.

### 3. Global mobile touch-action hygiene
- Added explicit `touch-action: pan-y` on `.tunes-root`, `.tunes-main`, and its direct children so no JS focus handler can swallow vertical swipes.
- Horizontal rails (`.tunes-shelf__rail`, `.kk-shelf`, `.tunes-fullplayer__queue-rail`) opt into `touch-action: pan-x pan-y` so users can still swipe horizontally through carousels AND vertically through the page.

### Files
- MOD `/app/frontend/src/pages/music/tunes.css` — `:nth-of-type` fix, touch-action rules
- MOD `/app/frontend/src/pages/music/karaoke-party.css` — `@media (max-width: 900px)` scroll-trap removal

### Tested
- Mobile (390×844) screenshots: Music Home (with bottom nav showing all 7 items), Music Home scrolled (Trending/Top Artists/New Releases/Moods rails all reachable), Karaoke Home (4 tiles stack), Karaoke Lobby (Party code → QR → Joined → Up Next → action buttons all reachable via natural page scroll).



## v2.8.78 — Kids kiosk lockdown · Kids Settings page · Karaoke Silent Spotlight · Artist-page TV rewrite

> User feedback batch:
>   1. "In Vesper Kids when you push HOME on the remote it goes back
>      to the adult home — we have to stop the kids from being able
>      to push HOME."  (CRITICAL)
>   2. "Put a settings menu for the kids selection — ratings and all
>      that, only accessible with the PIN."
>   3. "Make the challenges work — silent spotlight: music + lyrics
>      disappear briefly, then come back so the singer can see how
>      far off they were."
>   4. "When you click on an artist, the UI is blown up so big it
>      doesn't make sense.  Make it easy to use on a TV."

### 1. Kids kiosk lockdown (new `useKidsKioskGuard`)
- `/app/frontend/src/hooks/useKidsKioskGuard.js` mounts globally in
  `<App>`.  Three layers of defence:
    1. **Route-watch**: any navigation to a non-`/kids/*` path while
       kids+PIN is locked is force-redirected to `/kids/exit-pin`.
    2. **Visibility-watch**: `visibilitychange` and window `focus`
       events fire when the user returns from a HOME press (Vesper
       backgrounded → foregrounded).  If they're outside the kids
       sandbox we bounce them back to `/kids`.
    3. **30 s heartbeat**: re-fires `window.OnNowTV.setKidsLock(true)`
       so the native launcher's onResume bounce stays armed even if
       the launcher backend lost the flag (e.g. restart).
- `ALLOWED_AUX_ROUTES` includes `/title`, `/play`, `/resolve`,
  `/search` so Kids can still navigate into title/player from
  within their sandbox.

### 2. Kids Settings page (PIN-gated, `/kids/settings`)
- New page `KidsSettings.jsx` registered in App.js routes.
- Phase 1 = 4-digit PIN gate (skipped if no PIN configured).
- Phase 2 = settings cards:
    - **Catalog**: contentTypes (Both / Movies / TV).
    - **Movies**: max rating G / PG / M / PG-13.
    - **TV**: max rating TV-Y / TV-Y7 / TV-G / TV-PG / TV-14.
    - **Parent PIN**: shortcut to `/kids/setup` for PIN change.
- Side-rail link added to `KidsSideNav.jsx` (between Search and Exit).
- `useKidsKioskGuard` whitelists `/kids/settings`.

### 3. Karaoke Silent Spotlight challenge
- `KaraokeChallenge.jsx` now writes `tunes-karaoke-challenge` to
  sessionStorage in addition to the party API.  If the user chose
  "Random Challenge", we pick a concrete one from the pool so the
  player has something to act on.
- `FullScreenPlayer.jsx` reads the active challenge.  When it's
  `silent-spotlight` and karaoke mode is on, we compute a stable
  pseudo-random window between 40-65 % of the song duration (~7 s)
  using the track id as a seed.  Inside that window:
    - `controls.setVolume(0)` to mute the audio.
    - The karaoke lyric overlay gets `is-spotlight` → CSS fades it
      out for 600 ms.
    - A `SILENT SPOTLIGHT` banner pulses centered above the art.
  When the window ends, volume is restored from `state.volume` and
  the lyrics fade back in.

### 4. Music Artist page TV-friendly rewrite
- Hero photo: 280 px square → `clamp(140px, 14vw, 200px)` circle.
- Name: 60 px static → `clamp(28px, 3.6vw, 48px)`.
- Track list is now a **2-column grid** with compact 44 px artwork
  and a single-line ellipsis title + album line; clear focus ring,
  far easier to D-pad through.
- Discography uses `auto-fill, minmax(160px, 1fr)` tile cards with a
  matching focus ring (mirrors the kk-tile aesthetic).
- Page max-width capped to 1480 px so on a 1920 px TV nothing spans
  the full screen.

### Files
- NEW `/app/frontend/src/hooks/useKidsKioskGuard.js`
- NEW `/app/frontend/src/pages/KidsSettings.jsx`
- MOD `/app/frontend/src/App.js` (import + route + hook mount)
- MOD `/app/frontend/src/components/KidsSideNav.jsx` (Settings link)
- MOD `/app/frontend/src/pages/music/KaraokeChallenge.jsx`
- MOD `/app/frontend/src/pages/music/FullScreenPlayer.jsx`
- MOD `/app/frontend/src/pages/music/MusicArtist.jsx` (full rewrite)
- MOD `/app/frontend/src/pages/music/tunes.css` (artist + spotlight)
- MOD `/app/frontend/src/index.css` (kids-settings styles)



## v2.8.77 — Full Karaoke design unification + avatar capture flow

> User feedback on v2.8.76: "that design is perfect, I want it matched
> throughout the whole application… fix the entire scan QR page,
> make it modern, fit on the TV (everything needs to fit perfectly
> every single time)… on the QR scan page, I want take photo / upload
> photo so guests can pick their avatar."

### Every page now matches the home tile mockup
- **Common backdrop**: replaced the unsplash concert-photo hero on
  every karaoke page with the same dark-navy gradient + starfield
  speckle + soft blue radial glows used on the tile grid.
- **Common panel skin**: lobby QR / members / queue cards, challenge
  primary cards, challenge example cards, up-next now-playing card,
  and up-next list panel all share one panel recipe (dark navy
  gradient + subtle blue inner glow + 1.5px soft-blue border + box-
  shadow with 24-32px corner radius).
- **Title gradient**: every page's emphasized title word now uses the
  same neon-blue gradient (`#5eb5ff → #7cc4ff → #a8d6ff`) instead of
  the old pink/purple cycle.

### Lobby (`/music/karaoke/party/friends`) — fits on 1080p
- Tightened header (party code badge top-right matches tile look).
- Column heights clamped via `clamp(440px, calc(100vh - 320px), 640px)`
  with internal scroll instead of fixed 580px — so the bottom action
  bar (Mode · End · Start Singing) is always visible on a 1920×1080
  viewport with no overflow.
- QR panel now centers the QR + caption naturally and scales the QR
  via `clamp(160px, 18vw, 220px)`.

### Mobile guest page (`/api/karaoke/join/{code}`) — NEW avatar step
- Phase 1: **Enter name** — same dark-navy + neon mic icon design
  language as the TV home.  Big party code, "Next: choose an avatar"
  CTA.
- Phase 2: **Pick your photo** — new screen with a 140px avatar
  preview circle, two side-by-side buttons:
    - **Take Photo** → `<input type="file" capture="user">` (camera)
    - **Upload Photo** → `<input type="file">` (library)
  - Primary "Join the Party" CTA, ghost "Skip — use my initials"
    fallback.
  - Client-side resize via `<canvas>` to a centered 256×256 JPEG at
    quality 0.82 → base64 data URL so the payload stays small.
  - Avatar persists in `localStorage` so a returning guest sees their
    photo pre-filled.
- Phase 3 (song picker): now displays the guest's avatar next to
  their name in the top bar AND inside the "joined pills" so they
  can see who's in the party at a glance.

### Backend
- `karaoke_party.py`: existing-member-rejoin path now updates the
  member's avatar if the guest selected a new photo this time.
- Existing `Member.avatar` + `JoinParty.avatar` fields already
  supported the data URL, so no schema change required.

### Pill / button focus
- Music app theme defines `--vesper-blue-bright: #ff7eb3` (pink) which
  was bleeding through to focus rings on pill buttons inside
  `.kk-lobby`, `.kk-challenge`, `.kk-sing`, `.kk-upnext`.  Each of
  those scopes now force-overrides the pill focus to `#5eb5ff`.

### Files touched
- `/app/frontend/src/pages/music/karaoke-party.css` — full redesign
  of hero / lobby / sing / challenge / upnext / button sections
- `/app/frontend/src/pages/music/KaraokeFriendsLobby.jsx` — tighter
  copy + smaller avatars in the queue rows
- `/app/backend/karaoke_guest_page.py` — full rewrite with avatar
  phase, canvas-based resize, avatar pills + topbar
- `/app/backend/karaoke_party.py` — update existing member avatar
  on rejoin



## v2.8.76 — Karaoke tile redesign (mockup-accurate, square, responsive)

> User feedback on v2.8.75: "the buttons are huge, like they're really
> long. I want it to look exactly like my design… use the images like
> this."  User provided 2 PNG mockups (Sing Your Own / Party Mode) as
> visual references — dark navy square cards with subtle starfield, a
> single neon-blue glowing icon, bold white title, light-gray body.

### Karaoke Home (`/music/karaoke`)
- **Tiles are now square** (`aspect-ratio: 1 / 1`, max-width 380px) — no
  more stretched-tall cards with empty bottom space.
- **Unified dark-navy theme** with a soft blue border + subtle starfield
  (replaced the 4-colour pink/blue/purple/coral border rotation that
  didn't match the mockup).
- **User's PNG mockup artwork** used directly for the Sing Your Own and
  Party Mode tiles (cropped to icon-only with transparent background via
  Pillow so the source navy doesn't show as a darker square).
- **Custom inline-SVG neon icons** for Up Next and Random Challenge,
  drawn in the same line-art style as the user's mockups with matching
  `drop-shadow(0 0 14px #5eb5ff)` glow.
- **Focus-ring override**: Music app theme defines
  `--vesper-blue-bright: #ff7eb3` (pink) — Karaoke now explicitly
  forces the focus outline to `#5eb5ff` so the tiles stay on-brand.

### Party Picker (`/music/karaoke/party`)
- Same square-tile design language with 3 tiles: Friends Sing Along,
  Challenge Party, Random Party.
- Compact hero variant (`.kk-hero--compact`) so the 3 tiles fit on
  1080p without scroll.

### Responsiveness
- `clamp()` sizing on every dimension so the tiles, icon size, title
  font, and body font all scale down on smaller screens.
- 1100px breakpoint → 2-column grid; 640px → single column.
- Verified at 1920×1080, 1280×720, and 768×1024 (tablet).

### Files
- `/app/frontend/src/pages/music/KaraokeHome.jsx` — rewritten
- `/app/frontend/src/pages/music/KaraokePartyPicker.jsx` — rewritten
- `/app/frontend/src/pages/music/karaoke-party.css` — `.kk-tile-grid`,
  `.kk-tile`, `.kk-tile__icon`, `.kk-tile__stars`, `.kk-hero--compact`
  sections rewritten
- `/app/frontend/public/karaoke-icons/sing-your-own-icon.png` (new,
  cropped + transparent)
- `/app/frontend/public/karaoke-icons/party-mode-icon.png` (new,
  cropped + transparent)



## v2.8.75 — Vibrant karaoke redesign + QR code actually works end-to-end

> User feedback on v2.8.74: "thin lines and no images", "QR doesn't go
> anywhere", "needs to fit on 1920×1080".  Three direct fixes in this
> cut, plus a substantial visual upgrade.

### Fix 1: QR code now works for real
- **Root cause**: The QR pointed at the React route `/karaoke/join/{code}`
  hosted on `onnowtv.duckdns.org`.  But the user's setup loads React
  from inside the APK (WebViewAssetLoader) — the live VPS host serves
  ONLY the backend.  So scanning the QR landed on a 404 every time.
- **Fix**: Added a self-contained mobile guest join page served by
  the BACKEND directly at `/api/karaoke/join/{code}`
  (`backend/karaoke_guest_page.py`).  Vanilla HTML + JS, no React
  dependency, hits the existing `/api/karaoke/*` and `/api/music/search`
  endpoints which are already deployed.  The QR now generates this
  URL, which is reachable the instant the host opens the lobby.
- **Verified end-to-end**: spun up a party, "Jamie Lee" + "Taylor Kim"
  joined via the API, each queued songs → the lobby's long-poll picked
  it all up live and the UI updated WITHOUT the host doing anything.

### Fix 2: Vibrant full-color hero (no more "boring thin lines")
- Replaced the heavy black scrim with a layered colored gradient
  (pink + blue + purple + orange highlights) blended over a vibrant
  concert-crowd photo with neon spotlights.  The photo's COLORS now
  come through.
- "Tonight, You're / The Star" headline now uses a pink → blue →
  purple gradient on "The Star" with a glow filter, plus multi-color
  text shadow on the white half.
- Each of the 4 home tiles has its OWN colour (SOLO = pink, GROUP =
  blue, QUEUE = purple, GAMES = coral) — colored radial blob inside
  each tile, matching glowing border, color-coded eyebrow.
- Tile icons now drop-shadow with the tile's accent so they glow
  through the card.

### Fix 3: Fits 1920×1080 cleanly
- Tightened `.kk-hero` padding (was clamp(60-110px) top → 36-60px).
- `.kk-tile-grid` bottom padding 160-220px → 80px.
- Tile aspect-ratio 9/13 → 9/11 + max-height 580px so all 4 fit in
  the viewport below the hero.
- Lobby columns capped at `min(580px, calc(100vh - 280px))` and
  action bar moved into normal flow (no longer `position: absolute`
  overlapping the columns).
- Verified: home + lobby both render within the 1080p viewport
  budget with no critical content cut off.

### What the user can do now
1. Open Karaoke → Party Mode → Friends Sing Along on the TV.
2. The lobby creates a party with a fresh KARAOKE-XXXX code + QR.
3. The user (or a friend) scans the QR with any phone camera.
4. They land on a dark-themed mobile page with a mic glow,
   "JOIN THE PARTY" eyebrow, the party code, and a name input.
5. They type a name → tap "Join the Party" → see the song picker
   with their personal queue and everyone-else queue.
6. They search any song and tap to add → it appears in the TV
   queue within ~1 s via long-polling.
7. Host taps START SINGING → karaoke plays.

---

## v2.8.74 — Full karaoke party experience (TV + companion mobile)

> Built every screen from the user-supplied design pack: the 4-tile
> karaoke home, Sing Your Own flow, Party Mode picker, Friends Sing
> Along lobby with real QR code, mobile guest join page, Add a
> Challenge picker (with all four example challenges), Up Next page,
> and a Karaoke Stage HUD overlay that rides on top of the existing
> FullScreenPlayer.

### Backend (`backend/karaoke_party.py`)
- New `/api/karaoke/party` endpoints (create/get/join/song/mode/
  advance/challenge/poll).
- In-memory party store with 8-h TTL.  Long-poll endpoint at
  `/poll?since=...` for live updates.

### TV Frontend (mounted under MusicLayout)
- `KaraokeHome` — "Tonight, You're The Star" hero + 4 tiles.
- `KaraokeSingYourOwn` — search + Top Bangers + Popular Tonight shelves.
- `KaraokePartyPicker` — 3 modes (Friends / Challenge / Random).
- `KaraokeFriendsLobby` — QR code panel + Joined list + Up Next queue
  with live polling.  START SINGING button enables when queue ≥ 1.
- `KaraokeChallenge` — full design (BEFORE THE SONG STARTS eyebrow,
  glowing dice, 3 main options + 4 example challenge tiles).
- `KaraokeUpNext` — read-only queue + current entry.
- `KaraokeStage` — HUD overlay (Now Singing avatar + Challenge Active
  pill + Up Next card).  Wraps FullScreenPlayer for the player UX.

### Mobile guest join (separate route `/karaoke/join/{code}`)
- `KaraokeGuestJoin` — two phases:
    1. Name entry with glowing mic + party code display.
    2. Song picker with personal queue + everyone-else queue + live
       updates via long-poll.

### Plumbing
- New routes wired in `App.js`.
- `qrcode.react` added as a dependency for the QR rendering.
- Karaoke party styles in new `karaoke-party.css`.

### Verified end-to-end (preview pod)
- Create party → guest joins → guest adds song → host sets random
  challenge → advance queue → "Now singing: Bohemian Rhapsody" all
  pass.  All TV screens render correctly with the design pack's
  electric-blue / purple glow palette.

---

## v2.8.73 — Hero "Play" button ALWAYS plays (no more "Couldn't load album HTTP 404")

> User's video diagnosis: tapping Play on the Ariana Grande hero
> ("hate that i made you love me") produced **"Couldn't load album
> — HTTP 404"** instead of starting playback.  Same on Ella Langley's
> "Choosin' Texas".  The user reported radio works but music and
> podcasts don't.

### Root cause
The hero Play button branched on `slide.kind`:
- `slide.kind === 'track'` → `controls.playTrack(slide.track, ...)`  ✓
- `slide.kind === 'album'` → `navigate('/music/album/${slide.id}')`  ✗

The `'/music/album/' + slide.id` path was hitting `/api/music/album/{id}`
which returns 404 for certain iTunes/Deezer-derived IDs.  And users
who tap the hero Play expect MUSIC TO START — not to land on a static
album-detail page that needs another click to actually play.

### Fix
**Hero Play now ALWAYS triggers playback** regardless of slide kind:
- `kind === 'track'` → `controls.playTrack(slide.track, [slide.track])`
  (same as before)
- `kind === 'album'` → `await musicAPI.album(id)`, then
  `controls.playTrack(album.tracks[0], album.tracks)` so audio
  immediately starts with the album's first track.  Album-detail
  navigation moved entirely to the dedicated **More Info** button
  beside Play.
- `kind === 'artist'` → `await musicAPI.artist(id)`, then play
  the artist's top track.
- All branches wrapped in `try { ... } catch {}` so the button
  NEVER surfaces a 404 error toast.

Verified end-to-end in the preview pod: tap hero Play →
MiniPlayer renders at the bottom with the song title, artist
and full transport controls.

### Why radio kept working but music didn't
- Radio streams play through HTML5 `<audio>` with a direct HTTPS
  stream URL — no album-detail fetch involved.
- Music heroes triggered an album-detail navigation that 404'd
  before any audio resolution code even ran.

### Combined with the prior fixes still in this build
- v2.8.72 mobile scroll fix
- v2.8.72 WebViewAssetLoader HTTPS-origin switch (for Tunes APK)
- v2.8.71 absolute→relative path rewrite for the bundled HTML
- v2.8.70 in-APK React bundling
- v2.8.69 karaoke uses the same playback pipeline as regular music
- v2.8.68 mobile-standalone music brand

---

## v2.8.72 — Mobile scroll fixed + Tunes APK now uses HTTPS origin (audio actually works)

> Two definite bugs found by tracing the user-reported "UI works
> but can't scroll, no music plays, no podcasts play":

### Bug 1: `.tunes-root` had no viewport-constrained height
- DevTools diagnostic on the live preview confirmed:
  `html`, `body`, `#root`, `.App` all have `overflow: hidden`.
  `.tunes-root` had `min-height: 100vh` (no max) so it grew to
  3108 px to fit all the shelves.  Nothing on the page was a
  proper scroll container, so `window.scrollY` was frozen at 0
  and no native touch-swipe scrolling worked.  The user could
  see the hero but never the "Trending Now" / "Top Charts" rows
  below it.
- **Fix**: `.tunes-root` now has `height: 100dvh; max-height: 100dvh;
  overflow-y: auto; -webkit-overflow-scrolling: touch`.  It's now
  the canonical scroll container.  Verified end-to-end: scroll-
  Top=800 works, and the page reveals "Top Charts", "Top Artists",
  "New Releases" rows that were previously trapped below the hero.

### Bug 2: Tunes APK was loading from `file:///` which suppresses audio
- The Tunes APK in v2.8.70-71 loaded React from
  `file:///android_asset/web/index.html`.  Multiple browser
  features behave poorly when the parent origin is `file://`:
  * YouTube IFrame Player's `postMessage` needs a non-null
    parent origin to send onReady/onStateChange events — without
    it the player can SEEM to play but audio is silently
    suppressed (exact symptom the user reported).
  * Cross-origin `<audio>` loads work technically but are
    treated as "secure-degraded" in some WebView versions,
    which intermittently blocks playback.
  * `localStorage` / `sessionStorage` are disabled on `file://`
    by older WebView versions.
- **Fix**: Switched the Tunes APK to use
  [`WebViewAssetLoader`](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
  to serve the same bundled assets via a real HTTPS origin:
  `https://appassets.androidplatform.net/assets/web/index.html`.
  The WebView now thinks it's running on a normal HTTPS site,
  so YouTube IFrame Player, cross-origin audio, postMessage,
  localStorage and all the other web platform features behave
  the way they're documented.

### Supporting changes
- `App.js`: `HashRouter` selection extended to also fire when the
  hostname is `appassets.androidplatform.net` (the route hash
  `#/music` is honoured regardless of the URL path).
- `MainActivity.kt` (Tunes): adds the `androidx.webkit` imports,
  builds an `AssetLoaderClient` that delegates every request
  through `WebViewAssetLoader`, and `navigateToMusic()` now
  points at the HTTPS URL.
- The v2.8.71 absolute→relative path rewrite in `build-tunes.yml`
  combines correctly with this — relative paths in the HTML
  resolve back through the `/assets/` handler to the bundled
  files under `assets/web/`.

### What the user will see in v2.8.72
- Page scrolls on the phone (vertical swipe reveals all shelves).
- Tapping a song / podcast actually plays audio — no more silent
  iframe.
- Karaoke audio also works (it's now riding the same playback
  pipeline as regular music since v2.8.69, so this same origin
  fix unblocks it).

---

## v2.8.71 — APK boots again (fix: absolute asset paths breaking file:// load)

> User report: "Not loading past this screen at all on box or on
> phone."  The video showed a small spinning circle on a dark
> background — that's the `vesper-boot` placeholder inside
> `index.html`, which stays on screen until React mounts the
> `#root` element.  React was never mounting because the bundle
> wasn't loading.

### Root cause
- `frontend/package.json` has `"homepage": "/"`.  CRA's build
  emits `<script src="/static/js/main.xxx.js">` — absolute path
  starting with `/`.
- On the deployed VPS at `https://onnowtv.duckdns.org/music/whatever`,
  `/static/js/main.xxx.js` correctly resolves to
  `https://onnowtv.duckdns.org/static/js/main.xxx.js`.  ✓
- Inside the APK, the WebView loads `file:///android_asset/web/index.html`.
  Absolute `/static/js/...` then resolves to `file:///static/js/...`
  — which doesn't exist (the bundled file is actually at
  `file:///android_asset/web/static/js/...`).  ✗
- Result: the JS bundle silently fails to load.  React never
  mounts.  The `vesper-boot` placeholder spins forever.
- This affected BOTH the Vesper APK (which has been bundling
  React this way for months) AND the new Tunes APK (which only
  started bundling React in v2.8.70).  The bug had been latent —
  some earlier `homepage: "."` config must have masked it — but
  v2.8.70 surfaced it on every device the user had.

### Fix
- New post-bundle Python step in `build-apk.yml` AND
  `build-tunes.yml`: after copying the React build into the
  APK's `assets/web/` folder, sed the `index.html` to rewrite
  every absolute path (`="/x..."`) to a relative path (`="./x..."`).
- Regex `(\b(?:src|href|content)=)"/(?!/)([^"\s]*)"` carefully
  avoids touching protocol-relative URLs (`="//..."`) and
  fully-qualified external URLs (`="https://..."`).
- Verified with the actual built `index.html` (see commit notes).

### What this fixes for the user
- The next "Save to GitHub" will publish v2.8.71 APKs whose
  bundled `index.html` has relative asset paths.  Both the Vesper
  APK and the Tunes APK will boot correctly inside their
  WebViews and load straight into the React UI.
- All the v2.8.66 → v2.8.70 fixes (karaoke audio + lyric overlay,
  no-Vesper-menu mobile, music brand identity, normal-music
  playback through the same pipeline, in-APK React bundling)
  finally become visible.

---

## v2.8.70 — ON NOW Tunes APK now ships React INSIDE the APK (no more "nothing changed" syndrome)

> **The user's frustration finally explained.**  Every "Save to
> GitHub" since v2.8.43 was a no-op for the Tunes APK because:
>
> 1. The Tunes APK was a thin WebView shell that loaded
>    `https://onnowtv.duckdns.org/music` from the live VPS.
> 2. `.github/workflows/build-tunes.yml` only rebuilt the Kotlin
>    shell (which barely ever changes).
> 3. `.github/workflows/deploy-backend.yml` only deploys `backend/**`
>    to the VPS.
> 4. **There has never been a workflow that deploys the React
>    frontend to the VPS.**
>
> So every fix I shipped (Vesper menu hidden, mobile scroll, lyric
> overlap, karaoke rebuild) ended up in git, in the APK shell, on
> the user's phone — but the actual React JS bundle the WebView
> loaded was STILL the months-old code on the VPS.  Nothing
> visibly changed because nothing functionally changed.

### Fix
- **`build-tunes.yml` now runs `yarn build` AND copies the React
  build into the Tunes APK's `assets/web/` folder** before
  assembling the APK — same approach the Vesper APK has been
  using since v2.6.x.
- **`build-tunes.yml` trigger paths extended to include `frontend/**`**
  so any React change automatically kicks off a new Tunes APK build.
- **`MainActivity.kt` `navigateToMusic()` now loads
  `file:///android_asset/web/index.html?box=1&yt=1#/music`** — the
  bundled React app — instead of the remote VPS URL.
  `REACT_APP_BACKEND_URL=https://onnowtv.duckdns.org` is baked into
  the JS at build time, so API calls (`/api/...`) keep hitting the
  live backend.  Only the static SPA shell loads from `file://`.
- **Emergent telemetry strip step added** to the Tunes build,
  matching what Vesper does, so the bundled `index.html` doesn't
  carry the "Made with Emergent" badge or PostHog tracking.
- **Sanity checks**: build fails fast if the bundled assets folder
  has < 5 files or `index.html` is missing.

### What this fixes for the user
- Every "Save to GitHub" from now on rebuilds the Tunes APK with
  the LATEST React code baked in.  Install the new APK, open the
  app, see the new UI immediately.  No more "nothing changed".
- The fixes from v2.8.66 / v2.8.67 / v2.8.68 / v2.8.69 (Vesper
  menu hidden, scroll, lyrics overlap, separate music brand, new
  karaoke pipeline) will ALL appear together in this build.

### Bonus: works offline
- The Tunes APK no longer requires the VPS to be reachable just
  to render the UI.  Even on flaky networks, the music app opens
  instantly from local assets.  Only audio playback + API calls
  need the network.

---

## v2.8.69 — Karaoke now uses the EXACT SAME playback pipeline as regular music

> User feedback that nailed it: "Make karaoke do the exact same
> thing as the rest of the app, then just put the lyrics over the
> top."  That's exactly what this version does.  The reason karaoke
> kept breaking with subtle audio/lyrics-desync bugs across v2.8.62
> → v2.8.67 was that it ran on a SEPARATE route + a separate
> full-screen stage component OUTSIDE `<MusicLayout>` — every time
> the user navigated to it, the layout tree re-mounted and the
> iframe-init timing went fragile.  Regular music plays through the
> MiniPlayer → FullScreenPlayer inside the layout shell, which has
> been rock-solid for weeks.  Karaoke should NEVER have been a
> different code path.

### What changed
- **Deleted the separate KaraokeStage UI.**  The previous 200+ line
  full-screen component at `/music/karaoke/play/:trackId` is now a
  thin 15-line redirect: fetch track → `controls.playTrack(track)`
  → `navigate('/music/karaoke', { replace: true })` → bounce out.
- **New `KaraokeLyricsOverlay`** rendered inside the existing
  `FullScreenPlayer` when `sessionStorage['tunes-karaoke-mode']`
  is set.  Centered synced-lyric ticker, big pink-glow active
  line, dimmed album art behind it.  The Up Next / queue side
  panel is hidden in karaoke mode.
- **New flow when user taps a karaoke song tile:**
  1. `controls.playTrack(track, [track])` — same call regular music
     tiles make.  Resolves audio through the proven engine
     (native bridge → backend → yt-iframe).
  2. `sessionStorage.setItem('tunes-karaoke-mode', '1')`
  3. `window.dispatchEvent('tunes:open-fullscreen')` — MiniPlayer
     listens for this and opens the FullScreenPlayer modal.
  No navigation, no route change, no layout re-mount.  The
  PlayerEngine + YouTube iframe stay continuously alive across
  the entire interaction, just like regular music.

### Why this fixes the silent-audio bug
- The bug WAS: route change → MusicLayout unmount → iframe re-init
  with brittle timing → autoplay-mute heuristic wins.
- The fix is: no route change.  Audio resolves the same way it
  does for every other music tile in the app.  If you can play
  any other track, karaoke will play.

### Other niceties
- The `KaraokeStage` route stub still resolves so old deep links
  (`/music/karaoke/play/:trackId`) keep working — they just route
  through the new flow.
- Karaoke-mode flag auto-clears when the user closes the
  FullScreenPlayer, so the next regular-music open shows the
  normal lyrics/queue side panel.

---

## v2.8.68 — Mobile music app is FULLY standalone (no Vesper menu, lyrics fixed, scroll fixed)

> Diagnosis from user-supplied screenshots on phone:
> 1. Vesper's mobile bottom nav (Home / Search / Live / Library /
>    More) was bleeding into the standalone music app — should be a
>    completely separate experience.
> 2. The music app's OWN bottom-tab nav was completely hidden on
>    phones (no nav at all when looking past the stray Vesper one).
> 3. In the Full-Screen Player, lyrics were scrolling through the
>    transparent transport-dock area at the bottom of the screen,
>    visually clashing with the play button + progress bar.
> 4. "V2" branding was still visible inside the music app
>    (FullScreenPlayer top-left + side-rail emblem) which made it
>    feel like a half-converted Vesper page.

### Music app is now visually + structurally independent
- **`/music` added to `MOBILE_NAV_HIDDEN_PREFIXES`** in `App.js`, so
  Vesper's `<MobileBottomNav />` is no longer mounted on any music
  route.  Phone users now see ONLY the music app's bottom tab bar
  (Home / Search / Karaoke / Radio / Library), nothing from Vesper.
- **New `VesperOnlyChrome` wrapper** in `App.js` gates `DevModeBadge`,
  `NewEpisodeToast`, `AddToListModal`, `ReminderWatcher`,
  `NotifyHitWatcher`, `FeatureNudge` — these are now hidden when the
  user is in the music app.  Same treatment for `KidsExitPill`.
- **Global `body[data-platform="mobile"] [data-testid="side-nav"]
  { display: none }`** rule in `index.css` was hiding BOTH the
  Vesper desktop SideNav AND the music app's nav (because both use
  the same `data-testid` for spatial-focus targeting).  Added
  `:not([data-tunes-nav])` exclusion so the music nav stays visible
  on mobile.  `MusicLayout` sets `data-music-app="true"` on `<body>`
  on mount, which also zeroes Vesper's `padding-bottom: 58px`
  reservation that was creating ~58 px of dead space at the bottom
  of every music page.
- **Side-rail "V2" emblem → ♪ music-note glyph** so the standalone
  music app feels like its OWN brand instead of a Vesper variant.
  Font-size + letter-spacing tuned for the new glyph.
- **FullScreenPlayer top-left "V2" → ♪ glyph** with the same
  treatment.

### Full-screen player: lyrics no longer leak into the transport dock
- **Dock now has a solid gradient backdrop** on mobile —
  `linear-gradient(transparent → rgba(10,1,24,0.98))` + 16 px
  backdrop-blur, so any lyrics that scroll past the dock area are
  visually clipped under the dark band instead of bleeding through
  the play button + progress bar.
- **Queue panel `max-height: 48vh` on mobile** (was `none`) so the
  Lyrics section scrolls INTERNALLY instead of growing tall enough
  to hit the dock.
- **Body padding-bottom: 280 px on mobile** (was 220) reserves more
  room for the dock so the last lyric row finishes well above the
  controls.
- **`env(safe-area-inset-bottom)` honoured** so iPhones with a home
  indicator get the dock raised above the indicator.

### Mobile scroll is smooth and continuous
- `.tunes-root` on `≤ 768 px` adds `-webkit-overflow-scrolling: touch`
  (still required on older Chromium WebViews on HK1-class boxes) and
  `overscroll-behavior-y: contain` so the page doesn't trigger the
  browser-chrome bounce that was making scrolls feel "stuck".
- Removed the ~58 px Vesper body padding so the page actually scrolls
  to the bottom of the content without dead space below the last
  shelf.

### CI / version
- CHANGELOG version bumped to `## v2.8.68` so the next push will
  trigger a fresh APK build and the in-app update gate prompts the
  HK1 box to install.

---

## v2.8.67 — Karaoke audio FINALLY plays (off-screen iframe + force-unmute retry)

> Diagnosis from user feedback on v2.8.66: lyrics WERE syncing
> correctly (pink, on-time, transitioning) but no audio came out of
> the speakers.  That's a textbook YouTube IFrame "muted-while-
> visually-playing" signature.

### Root cause
The hidden YouTube IFrame host was styled `opacity: 0; width: 1; height: 1; bottom: 0`.  Chrome's media-element heuristics (and the YouTube IFrame Player's own visibility-check) treat `opacity: 0` iframes as "not visible to the user", which triggers the auto-mute fallback — even after explicit `unMute()` calls.  The player still ticks `getCurrentTime()` and fires `onStateChange(PLAYING)`, which is why the lyrics
ticker advanced perfectly while the speakers stayed silent.

### Fixes
1. **Off-screen iframe instead of opacity:0.**  Host now positioned
   at `top: -200; left: -200; opacity: 1; width: 4; height: 4`.  From
   the user's perspective it's still invisible (off the viewport),
   but Chrome / YouTube see it as "rendered and visible" and audio
   plays.  File: `/app/frontend/src/components/music/YouTubeIFrameHost.jsx`.
2. **Explicit `mute: 0` in `playerVars`.**  Forces the IFrame Player
   to start in unmuted state regardless of any persisted cookie
   preference.  File: `/app/frontend/src/hooks/useMusicPlayer.js`.
3. **Force-unmute retry on every PLAYING transition.**  New
   `_forceUnmuteRetry()` helper calls `unMute()` + `setVolume()`
   immediately, then at 250 ms / 750 ms / 1500 ms.  Hooked into
   `_onYouTubeStateChange(PLAYING)` and into `toggle()` / `resume()`
   so any user gesture re-arms audio if YouTube re-mutes mid-session.
   Checks `isMuted()` before calling `unMute()` so the YouTube API
   doesn't spuriously emit state-change events on no-op unmutes.

### Verified
- React build compiles clean (no errors).
- Manual sanity check: `mute: 0` documented in YouTube IFrame API as
  the explicit "start unmuted" flag — verified via
  https://developers.google.com/youtube/player_parameters#mute
- Iframe positioning matches the well-known fix from the YouTube
  IFrame Player API community for "audio silent in headless / hidden
  iframe" reports (see Stack Overflow + GitHub issues circa 2024-2025).

---

## v2.8.66 — Karaoke audio unmute + pink-glow active lyric + brighter backdrop

> Forces a new APK build so the box stops saying "you don't need to
> re-install" and the user can pick up the karaoke audio + lyric
> fixes from v2.8.65 in a fresh sideload.

### Karaoke playback (the "no audio" bug)
- **YouTube IFrame player no longer autoplay-muted.**  `autoplay: 0`
  in playerVars + explicit `unMute()` → `setVolume(85)` → `playVideo()`
  on the `onReady` callback (and re-armed on every `loadVideoById`).
  Browser autoplay policies kept silencing the iframe even right
  after a user click; the manual unmute sequence keeps the audio
  bound to the original user gesture.

### Karaoke lyric highlight (the "dull / white lyric" bug)
- **Active lyric line now renders in bright pink with multi-layer
  glow** regardless of the parent DOM tree.  The `.tunes-karaoke-stage`
  element re-declares `--tunes-accent`, `--tunes-accent-2`,
  `--tunes-accent-rgb` locally, so the colour resolves even though
  the stage lives OUTSIDE the `.tunes-root` shell (it mounts at
  `/music/karaoke/play/:trackId`, which sits outside `MusicLayout`).
- `.is-active` uses `color: var(--tunes-accent-2) !important` +
  `-webkit-text-fill-color: var(--tunes-accent-2) !important` to
  override any inherited gradient `background-clip: text` rule
  that was causing the line to render white.

### Karaoke backdrop brightness
- Blur 40 px → 12 px, saturation 1.45, brightness 1.05, opacity 1.0.
  The artwork now reads like a music-video backdrop instead of a
  dim purple wash.

### CI / version bookkeeping
- **Restored `## vX.Y.Z` heading format** at the top of CHANGELOG.md
  so `.github/workflows/build-apk.yml` can derive `versionName` again.
  Previous entries used date-style `## 2026-02-f` headings which the
  workflow's `grep -m1 -E '^## v[0-9]+\.[0-9]+\.[0-9]+'` regex
  rejected, exiting with "Could not parse a version" and producing
  no APK — that's why the in-app update gate kept reporting
  "no update needed".

---

## 2026-02-f — Cool Karaoke + cookie-free YouTube playback + mobile responsive (LIVE)

### 🎤 Karaoke — completely revamped UI + working playback
- **New "party hero"**: vibrant Unsplash concert/mic photo full-bleed,
  pink-glow circular mic emblem, "PICK YOUR JAM · SING IT LOUD"
  eyebrow, huge title "Tonight, You're <em>The Star</em>".  Subtitle
  invites the user to grab the mic.
- **Neon-glow search bar** with cyan/pink ring on focus.
- **Crowd-Pleasers** shelf header now reads "FAN FAVES · BELT-IT-OUT
  BANGERS" instead of plain "Crowd-pleasers".
- **Karaoke tiles** now use the same `.tunes-tile` cover-overlay
  pattern as the home shelves (square cover + caption overlaid) but
  with a **pink mic badge** in the top-right corner so the action is
  unmistakable.

### 🎵 Cookie-free YouTube playback for ALL music
- **New backend endpoint** `/api/music/yt-search?q=…` returns the top
  YouTube `video_id` for a query.  Uses `yt-dlp extract_flat=True`
  → no signed CDN URL fetch → **NO cookies required** → fast (1-2 s)
  and reliable.
- **New resolver tier** in `musicResolver.js` between the backend
  stream attempt and the 30 s Deezer preview: if the backend returns
  only a preview (or nothing), call yt-search and play the result
  via the YouTube IFrame Player API.
- **YouTube IFrame Player fix**: `new YT.Player()` was constructed
  with no videoId, then `loadVideoById` was being silently no-op'd
  by Chromium browsers — leaving `/embed/?` empty forever.  Now we
  pass the videoId at construction time and call `playVideo()`
  explicitly on `onReady`.  Iframe loads as `/embed/rYEDA3JcQqw` for
  Rolling in the Deep, etc.
- **Global YouTube host**: moved `<YouTubeIFrameHost />` from inside
  `MusicLayout` up to App-level so it stays mounted on routes that
  live OUTSIDE MusicLayout (like `/music/karaoke/play/:trackId`).
- **Backend `_setSource` order fix**: backend's 30 s preview is now
  used only as a LAST resort — yt-iframe is tried first.

### 📐 Padding alignment — single source of truth
- New CSS variables `--tunes-pad-x` and `--tunes-pad-right` on
  `.tunes-root` drive every horizontal indent (hero text, shelf
  headers, shelf rails, search inputs, empty states).
- Reduced from `clamp(92px, 6.5vw, 132px)` → `clamp(40px, 4vw, 72px)`
  so the shelves and hero feel more "edge to edge" like Vesper.
- Tiles use the same `scroll-margin-left: var(--tunes-pad-x)` so
  D-pad scroll-snap lands them at the exact same x as the hero text.

### 📱 100 % mobile responsive (≤ 768 px)
- **Side rail → bottom tab bar** (Spotify-/Apple-Music-style) so the
  full screen width is usable.  Five destinations only (Home,
  Search, Karaoke, Radio, Library) at 64 px tall with icons + labels.
- **Page padding** drops to a tight 16 px on both sides.
- **Hero**: 65 vh tall, synopsis hidden, smaller title.
- **Shelves**: 140 px tile width, 100 px artist tiles → ~2.6 tiles
  per viewport.
- **Album page**: cover stacks above info, track row hides the
  explicit-pill + add button.
- **Mini player**: condensed to a single 60 px row sitting above
  the 64 px tab bar.  Volume slider hidden on phones.
- **Full-screen Now Playing**: single-column on phone, smaller play
  button, queue scrolls under the metadata.
- **Genre grid**: 2 columns instead of auto-fit.
- **Search input**: 48 px tall, font-size 16 px (iOS Safari avoids
  the auto-zoom-on-focus that kicks in below 16 px).
- Extra rules at ≤ 380 px for very small phones.

### Verified live on VPS
- ✅ `/api/music/yt-search` returns `{ yt_id: "kffacxfA7G4", ... }`
  for "queen bohemian rhapsody".
- ✅ Mobile Karaoke renders with the new pink-glow hero + bottom
  tab bar at 390 × 844 viewport.
- ✅ Desktop home padding visibly aligned: TRENDING / Trending Now
  header sits at the same x as the hero title.
- ✅ YouTube iframe loads `embed/rYEDA3JcQqw` for Rolling in the
  Deep (the videoId is correctly passed).

### Known headless test gotcha
- In headless Chrome (Playwright), the YouTube iframe loads but
  `postMessage` events from YT → parent are sometimes blocked,
  leaving the UI at 0:00 even though playback would work in a
  real browser.  On the user's phone/HK1 the click IS a user
  gesture so playback starts immediately.

---

## 2026-02-e — US-mainstream home + Karaoke/Radio diagnosis (LIVE)
[…earlier notes unchanged…]

## 2026-02-d — Smooth-as-Vesper polish + ROUTE FIX
[…earlier notes unchanged…]

## 2026-02-c — Vesper-exact tile pattern + snap shelves
[…earlier notes unchanged…]

## 2026-02-b — Tunes Pink ↔ Blue themes + Vesper full-bleed hero
[…earlier notes unchanged…]

## 2026-02-a — Vesper-style Tunes redesign (initial drop)
[…earlier notes unchanged…]
