# ON NOW TV TUNES

The Music app — a fully separate Android application
(`tv.onnowtv.tunes`) that boots directly into the `/music` route of
the shared ON NOW TV V2 React bundle.

## Why a separate APK?

The user explicitly asked for a separate Music app — different
package id, different launcher tile, different admin slot, different
update cadence.  This APK delivers that without duplicating the
React frontend:

```
┌─────────────────────────────────────────────────────────────┐
│ HK1 Box (tv.onnow.launcher)                                 │
│                                                             │
│  Dock tile "Music" → Intent → tv.onnowtv.tunes              │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ tv.onnowtv.tunes  (this app)                         │   │
│  │   single WebView                                     │   │
│  │   boots into  https://onnowtv.duckdns.org/music      │   │
│  │   ← same React bundle Vesper uses, /music routes     │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

Same React codebase, totally separate Android process.

## Backend

All content surfaces under `/api/music/*`, implemented in
`/app/backend/music_api.py`:

  • Deezer Public API     — catalog/search/charts/new releases/album tracks (30-s previews)
  • Radio Browser API     — 30 000+ live radio stations worldwide
  • iTunes Search API     — top podcasts + search
  • Standard RSS via feedparser — podcast episodes

No API keys required.  Cached aggressively (1 h catalog, 24 h
top-podcasts) in an in-process TTL cache.

## Build

Same CI pattern as the Launcher:

```
.github/workflows/build-tunes.yml
   trigger: push to main touching android/onnowtv-tunes/**
   publishes: tunes-latest GitHub release tag (debug + release APKs)
```

Reuses the launcher's release keystore for signing.

## Versioning

  • `versionCode` = `1 + (commit count touching android/onnowtv-tunes/)`
  • `versionName` = first `## v` heading in `/CHANGELOG.md`

## Local development

The Music routes live at `/music` inside the existing React app, so
you can iterate on the UI in the regular Vesper dev environment
(`yarn start` from `/app/frontend`) without touching this APK.
The APK only matters at deploy time.
