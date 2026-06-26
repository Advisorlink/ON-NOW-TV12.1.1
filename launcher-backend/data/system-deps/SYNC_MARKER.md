# System Dependencies sync marker

This file's mtime is bumped whenever a system-dependency bundle
(WebView 138 APKM, etc.) is swapped in `data/system-deps/`.

It exists so that **every** swap of a binary system-dep also
causes a tracked text change in `launcher-backend/**`, which in
turn guarantees the `Deploy Launcher Backend to Contabo VPS`
workflow's `paths:` filter matches and the workflow auto-fires.

Without this marker, swapping an APKM that lives in
`launcher-backend/data/system-deps/` could fail to trigger the
deploy if git happened to not detect a content change on the
binary (e.g. with LFS or partial-checkout edge cases).

## Current pins

- `webview-138.apkm` — Google Chrome 138.0.7204.180 (arm-v7a)
  - Last swap: 2026-06-26 (replaced x86+x86_64 build that failed
    to install on ARM TV boxes)
