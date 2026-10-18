# Vesper Native vs Reference V2 Visual & Interaction Parity Blueprint

## 1. Executive Summary & Root Cause Analysis
The Native Android TV Kotlin app (`vesper-native`) had density-scaling discrepancies and per-component layout deviations when compared to the reference Vesper V2 web/TV app. Global screen DP normalization alone does not fix per-component geometry, typography hierarchy, margins, or interaction flows.

---

## 2. Page & Component Visual Corrections

### A. Home Screen & Top Navigation
- **Top Navigation Bar:**
  - **Reference V2:** Top Navigation Bar features 12 full-sized icons (Search, Home, Movies, TV, Anime, Kids, Live TV, Networks, Studios, Box Sets, Library, Settings) with 72px (36dp) height, centered alignment, and clear focused text labels below the highlighted icon.
  - **Native Issue:** Header icons were shrunk, labels cut off, and menu items misaligned.
  - **Correction Specification:** Set top nav container height to 36dp (72px @ 1080p). Active icon scale 1.1x with 2dp signal cyan underline or glow ring. Text labels display in `Geist Mono` 10sp with 8dp top offset.

- **Hero Billboard & Trailer Hover Section:**
  - **Reference V2:** Large hero billboard with title, genre tags, rating, runtime, synopsis, and 280ms hover-triggered inline trailer preview.
  - **Native Issue:** Trailer showed infinite loading spinner or failed to play inline audio/video.
  - **Correction Specification:** Implement 280ms dwell timer before triggering preview video texture via native Media3/ExoPlayer. Ensure audio un-mutes automatically on TV box focus.

- **Studios & Box Sets Sections:**
  - **Reference V2:** Renders with identical hero billboard + wide preview trailer rail layout as the Home Screen.
  - **Native Issue:** Generic grid without top hero billboard/trailer area.
  - **Correction Specification:** Align Studios and Box Sets layouts to reuse `HeroBillboard` + wide trailer preview rail layout pattern directly.

---

### B. Movie Detail Page
- **Header & Title Bar:**
  - **Reference V2:** Features `Back` and `Home` buttons adjacent on top left, title logo/heading, metadata line (`MOVIE · TT31049299`), rating/runtime, synopsis, and primary Action Buttons (Play Autoplay 1080p / Choose Stream, Add to List, Trailer).
  - **Cast & Filmography Navigation:**
    - Down D-Pad navigation moves seamlessly from Play CTA to Cast Row.
    - Focusing an Actor card updates the top hero area with Actor Name, Biography, Age, and Birthplace.
    - Selecting an Actor opens their filmography ("other movies that the actor has done").
    - Pressing Down on filmography shows "Just Like This" / Similar movies rail.
  - **Native Issue:** Misarranged header, missing actor biography swap on focus, broken similar movies down-navigation.
  - **Correction Specification:** Exact 960x540dp geometry. Header top-left: Back + Home pill buttons. Sub-eyebrow: `Geist Mono` 10sp uppercase ID string. Connect Cast lane focus listener to hero actor bio view.

---

### C. TV Shows & Series Detail Page
- **Episode List & Navigation:**
  - **Reference V2:** Plain heading below hero, Back/Home adjacent left, Season selector pills (Season 1, Season 2, Specials), followed by rich episode cards displaying Episode Number, Episode Title, Thumbnail, Runtime, Overview, and Watched Progress bar.
  - **Native Issue:** Giant yellow logo below, Home button positioned far right, number-only episode rows without titles/thumbnails.
  - **Correction Specification:** Remove oversized yellow logo. Standardize header with Back + Home top-left. Render full Episode Cards with 16:9 thumbnails, episode title, runtime, synopsis, and progress bar.

---

### D. Networks Screen & Brand Identity
- **Network Tile Styling:**
  - **Reference V2:** Branded background colors matching network identities:
    - Netflix: `#E50914`
    - HBO / Max: `#5822B4` / `#000000`
    - Disney+: `#113CCF`
    - Apple TV+: `#1C1C1E`
    - Paramount+: `#0064FF`
    - Hulu: `#1CE783`
    - Binge / Stan / Amazon Prime Video brand tones.
  - **Native Issue:** Monochromatic/grey background cards for all networks.
  - **Correction Specification:** Apply exact network hex colors to card backgrounds with subtle 1px border highlights and high-contrast brand vector logos.

---

### E. Settings Menu
- **Navigation & Layout:**
  - **Reference V2:** Compact 2-column layout. Left sidebar with 9 categories (Appearance, Home screen, Playback, Personalise, Help, Tips, What's new, Backup, Sign out) with 34px icon badges. Right content panel updating dynamically without full-page redraw.
  - **Native Issue:** Single long list with mismatched font sizes and spacing.
  - **Correction Specification:** Implement strict 2-column layout (Sidebar: 220dp, Gap: 24dp). Use Geist font hierarchy (H1 22sp, H2 16sp, Body 12sp).

---

## 3. Interaction & Motion Blueprint
1. **Focus Motion:** 1.06x scale pop on 2:3 posters and 16:9 cards with 120ms ease-out. 1.5dp cyan glow ring (`#5DC8FF`).
2. **D-Pad Directional Flow:**
   - Nav bar → Down → Hero Play CTA → Down → Cast / Season Row → Down → Episode / Recommendations Rail.
   - Left D-Pad from any content tile wraps or returns to active left nav/sidebar.
