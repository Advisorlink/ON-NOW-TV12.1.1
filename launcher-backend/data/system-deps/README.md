# System-dependency APKMs

This folder hosts **pre-baked Android system-dependency bundles** that the
Launcher backend serves to every TV client at first launch via
`GET /api/system-deps/{name}.apkm`.

The most important one right now: **`webview-138.apkm`** — Android System
WebView 138.  When a Vesper TV box opens the app and its WebView major
version is < 138, the app prompts the user "WebView 138 required.  Install
now?" and the Launcher's in-house APKM installer pulls this file directly
from `/api/system-deps/webview-138.apkm` and installs it via
`PackageInstaller.Session`.

## Add a bundle

1. Place the file here using the exact filename shape `{name}.apkm`, e.g.:
   ```
   launcher-backend/data/system-deps/webview-138.apkm
   ```
2. That's it — no admin UI required, no `store.json` mapping, no restart.
   The next `GET /api/system-deps/webview-138.apkm` request returns the
   file with `Content-Type: application/vnd.android.package-archive`.

## Why not commit the .apkm to git?

GitHub rejects single files > 100 MB and warns at 50 MB.  WebView bundles
are usually 70-100 MB.  `.gitignore` excludes `*.apkm` and `*.apk` in this
folder — bundles live on the server's disk only.

## Production deploy checklist

After deploying `launcher-backend`, SCP or SFTP each `.apkm` bundle into
this folder on the production VPS, e.g.:

```bash
scp webview-138.apkm root@launcher.onnowtv.tv:/opt/launcher-backend/data/system-deps/
```

No service restart required; FastAPI serves the file straight off disk.

## Naming convention

| File                  | Served by URL                              |
|-----------------------|--------------------------------------------|
| `webview-138.apkm`    | `/api/system-deps/webview-138.apkm`        |
| `webview-139.apkm`    | `/api/system-deps/webview-139.apkm`        |
| `media-codec-aac.apk` | (rename to `.apkm` to match the endpoint)  |

Currently the public endpoint only serves the `.apkm` shape; if you need
to serve a plain `.apk` keep the extension as `.apkm` (Android's
`PackageInstaller` doesn't care, it inspects the file contents).
