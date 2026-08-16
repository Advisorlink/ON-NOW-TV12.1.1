# ON NOW Trivia — standalone Android TV app

Kiosk WebView shell (same architecture as `onnowtv-tunes`) that boots
straight into the bundled React SPA at `#/trivia`.

- The TV creates a game room against the production backend and shows
  a QR code + 4-letter room code.
- Guests scan the QR with their phones → public web route
  `/trivia/play?room=CODE` → their phone becomes the buzzer/answer pad.
- Real-time game state flows over `WS /api/trivia/ws/{code}`.
- Sound pack (lobby jingle, ticks, buzzer, stings, fanfare) is bundled
  in the React build under `sounds/trivia/` and autoplays on the box
  (`mediaPlaybackRequiresUserGesture = false`).

Built by `.github/workflows/build-trivia.yml` → GitHub release tag
`trivia-latest` (`onnowtv-trivia-debug.apk` / `onnowtv-trivia-release.apk`).
