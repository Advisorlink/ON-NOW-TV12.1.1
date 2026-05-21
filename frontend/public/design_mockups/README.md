# ExoPlayer TV Overlay Design Mockups

This directory contains the requested design mockups for the ExoPlayer Android TV integration within the Vesper / ON NOW TV V2 app.

## Deliverables
- **`exoplayer-overlay-idle.html`**: The idle/paused state. Demonstrates the cinematic 45% left-side gradient fade, TMDB-style transparent logo positioning, H1 title, monospace metadata chips, and ellipsis synopsis.
- **`exoplayer-controls.html`**: The active playback controls state. Shows the bottom controls dock, fluid scrubber bar focus states, and the center stream-picker glass card overlay.
- **`design_guidelines.json`**: (Root level `/app/design_guidelines.json`) Comprehensive design tokens and specifications for porting to Kotlin/Android Views or Jetpack Compose.

## How to View
Since these are pure HTML/Tailwind mockup files, you can view them directly in any modern browser. The design is fixed to a 1920x1080 TV aspect ratio and scales proportionally to your window.
- Right-click the `.html` file -> Open in Browser.

## Design Rationale: Left-Fade Gradient Pattern
The left-anchored 40-45% gradient (`from-navy-900 via-navy-900/90 to-transparent`) mirrors premium VOD standards (Apple TV+, Netflix mobile, Disney+). 
**Why it works:**
1. **Legibility:** It provides necessary contrast ratio (WCAG AA) for the white/cyan text against any chaotic or bright frame of video.
2. **Context Retention:** By keeping the right 55-60% of the screen clear, the user stays visually connected to the scene they paused on. This creates immersion, as opposed to a full-screen blackout or aggressive 100% blur overlay.

## Recommendation for Kotlin Implementation
**Jetpack Compose for TV (Media3 + Compose)** is highly recommended over legacy XML Views.
- **Why:** Compose handles the complex focus states, micro-animations (like the 220ms focus scaling, ring borders), and backdrop blur (RenderEffect) much more eloquently. The `Jetpack TV Compose` library provides out-of-the-box support for D-pad navigation which mimics these mockups perfectly.
- **Legacy Fallback:** If you must use XML Views, you can achieve the blur using `RenderEffect` (Android 12+) or `RealtimeBlurView` library, and handle focus using `StateListAnimator`.