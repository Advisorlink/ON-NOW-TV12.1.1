# Vesper Native Visual & Surface Reference Blueprint (V2 Android TV)

## 1. Grounded Viewport & DP Reference Conversion
- **Original TV Wrapper Design Viewport:** Web 1920x1080px (16:9 10-foot UI).
- **Target Native Reference Viewport:** Android TV Standard Native Canvas: 960x540dp (`mdpi` 1dp = 2px at 1080p).
- **Conversion Factor:** `1dp = 2px` (Web px -> Native dp: divide by 2).

### Specific Surface Conversions:
* **Top Navigation Bar:**
  - Web: Height 72px, Horizontal Padding 48px, Gap 16px.
  - Native DP: Height 36dp, Horizontal Padding 24dp, Item Gap 8dp.
* **Hero Billboard:**
  - Web: Height 420px, Left Inset 64px, Title Size 44px, Synopsis 16px.
  - Native DP: Height 210dp, Left Inset 32dp, Title 22sp, Synopsis 12sp.
* **Poster Tiles (Shelf Rows - 16:9 & 2:3):**
  - Standard Poster (2:3): Web 160x240px -> Native 80x120dp (Corner Radius: 6dp / 12px).
  - Landscape Card (16:9): Web 256x144px -> Native 128x72dp.
  - Focused Scale: 1.06x scale pop with 120ms ease-out, 1.5dp cyan outline (`#5DC8FF`), 2dp offset.
* **Player Control Dock (ExoPlayer Overlay):**
  - Web Dock: Height 360px, Padding 64px.
  - Native DP Dock: Height 180dp, Horizontal Padding 32dp, Bottom Padding 32dp.
  - Play/Pause Center Button: Web 80x80px -> Native 40x40dp.
  - Secondary Dock Action Buttons: Web 56x56px -> Native 28x28dp.

---

## 2. Color Palette & Typography Tokens

### Primary Electric V2 Palette:
- **Background Deep (`vesper_bg`):** `#06080F` (RGB 6, 8, 15)
- **Background Surface (`vesper_bg_dim`):** `#0A0E1A` (RGB 10, 14, 26)
- **Surface Elevation 2 (`vesper_surface`):** `#111827` / `#1A2238`
- **Signal Cyan Accent (`vesper_blue`):** `#5DC8FF` (RGB 93, 200, 255)
- **Signal Cyan Bright (`vesper_blue_bright`):** `#8DE0FF` (RGB 141, 224, 255)
- **Primary Text (`vesper_text`):** `#F5F7FA` (RGB 245, 247, 250)
- **Sub / Muted Text:** `#9BA8C2` / `#5B6580`
- **Warning / Error Red:** `#FF8B7C` / `#FF3B30`

### Typography Hierarchy (Bundled Geist Font):
- **Display Heading 1:** `Geist Bold` 30sp (60px web), tracking -0.025em.
- **Section Heading 2:** `Geist Bold` 20sp (40px web).
- **Card Title / Subsection:** `Geist SemiBold` 14sp (28px web).
- **Body Text:** `Geist Regular` 12sp (24px web).
- **Eyebrow / Tagline / Stats:** `Geist Mono` / `JetBrains Mono` 10sp, uppercase, letterSpacing 1.6sp.

---

## 3. High-Impact Native Discrepancies & Remedies

1. **Focus Ring Clipping & Shadow Overlap:**
   - *Issue:* Card containers using strict clipping chopped off the outer 2dp focus bloom and outline.
   - *Remedy:* Set `clipChildren = false` and `clipToPadding = false` on all `RecyclerView` and `LinearLayout` parents. Apply `FocusMotion.bind(view, tile=true)` to lift `z-index` and scale transform smoothly.

2. **Scrub Bar Responsiveness in Native Player:**
   - *Issue:* Firing playback seek on every D-pad press stalled video stream with continuous buffer resets.
   - *Remedy:* Debounce native scrub input by 220ms using local pending scrub target before dispatching `onSeekTo()`.

3. **In-Player Episode Swaps:**
   - *Issue:* Skipping to the next episode rendered a corner spinner over the frozen old frame, confusing users.
   - *Remedy:* Show full `LoadingScreen` overlay with episode wordmark during `swappingEpisode = true` state.

4. **Missing Functional Controls:**
   - *Rule:* Never render fake or non-functional active controls. Audio/Subtitle/Stream choice buttons must only appear or enable when options exist in stream metadata.

---

## 4. Reusable Native Resources & Dependencies

### Bundled Res / Assets:
- **Fonts:** `R.font.geist` (`res/font/geist.ttf`)
- **Icons:** `ic_play.xml`, `ic_pause.xml`, `ic_forward_10.xml`, `ic_replay_10.xml`, `ic_subtitles.xml`, `ic_audio.xml`, `ic_aspect.xml`, `ic_search.xml`, `ic_home.xml`, `ic_library.xml`, `ic_movies.xml`, `ic_tv.xml`, `ic_account.xml`.
- **Drawables:** `bg_stream_picker_card.xml`, `exit_card_bg.xml`, `exit_btn_primary.xml`, `exit_btn_secondary.xml`, `guide_scrim_gradient.xml`, `player_progress_bar.xml`.

### Kotlin Component Modules:
- `tv.onnowtv.vespernative.ui.TvUi`: Primitive UI constructors, focus states, test-id tags.
- `tv.onnowtv.vespernative.ui.FocusMotion`: TV 1.07 scale pop-out, 120ms ease-out transition.
- `tv.onnowtv.vespernative.ui.FocusBloomDrawable`: Dual-layer cached glow rings.
- `tv.onnowtv.vespernative.playback.PlayerActivity` / `PlayerOverlay.kt`: Media3/ExoPlayer overlay controller with track & stream pickers.
