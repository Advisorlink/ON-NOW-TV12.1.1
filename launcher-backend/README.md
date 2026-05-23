# OnNow TV V2 — Launcher Admin Backend

A completely standalone FastAPI service that drives the native Android TV
launcher (`/app/android/onnowtv-launcher/`).  No connection to the existing
`/app/backend/` streaming app — these are two separate processes that just
happen to live in the same repo.

## What it does

1. **Drives the 6 dock tiles** — label / sub / icon URL / target package / target URL / accent colour.
2. **Wallpaper manager** — upload + pick active background image.
3. **APK manifest** — list of apps the launcher offers to install (either uploaded files or remote URLs).
4. **Popup notifications** — broadcast a modal to every launcher device with a TTL.
5. **Admin dashboard** — single-page web UI at `/admin/` (token auth).
6. **Public API** — read-only endpoints the launcher device polls.

## Endpoints

### Public (no auth — launcher devices poll these)
- `GET  /api/launcher/health` — health check
- `GET  /api/launcher/config` — full snapshot (dock, wallpaper, APKs, active notifications)
- `GET  /api/launcher/notifications/pending?device_id=…` — un-seen notifications for a device
- `POST /api/launcher/ack-notification` — mark a notification as seen
- `GET  /assets/icons/{file}`, `/assets/wallpapers/{file}`, `/assets/apks/{file}` — uploaded files

### Admin (token-protected)
- `POST /api/admin/login` — exchange ADMIN_TOKEN for a 7-day session cookie
- `POST /api/admin/logout`
- `GET  /api/admin/store` — raw store JSON (for the dashboard)
- `POST /api/admin/dock` — replace all 6 dock tiles
- `POST /api/admin/wallpapers` (multipart) — upload wallpaper
- `POST /api/admin/wallpapers/active` — set active wallpaper id (or null → built-in aurora)
- `DELETE /api/admin/wallpapers/{id}`
- `POST /api/admin/apks` — register APK by remote URL
- `POST /api/admin/apks/upload` (multipart) — register APK by file upload
- `DELETE /api/admin/apks/{id}`
- `POST /api/admin/notify` — broadcast popup
- `DELETE /api/admin/notify/{id}` — withdraw a broadcast

## Run locally

```bash
cd /app/launcher-backend
pip install -r requirements.txt
ADMIN_TOKEN=$(openssl rand -hex 32) \
PUBLIC_BASE_URL="http://localhost:8002" \
uvicorn main:app --host 0.0.0.0 --port 8002 --reload
```

Then open `http://localhost:8002/admin` and sign in with the printed
ADMIN_TOKEN.

## Run in production (Docker)

```bash
docker build -t onnow-launcher-api .
docker run -d \
    --name onnow-launcher-api \
    --restart=always \
    -p 8002:8002 \
    -e ADMIN_TOKEN="$(openssl rand -hex 32)" \
    -e PUBLIC_BASE_URL="https://launcher.onnowtv.duckdns.org" \
    -v /opt/onnow-launcher-data:/data \
    onnow-launcher-api
```

### Reverse proxy (Caddy example — same Contabo VPS as the main backend)

```caddyfile
launcher.onnowtv.duckdns.org {
    encode gzip
    reverse_proxy localhost:8002
}
```

Caddy auto-fetches a Let's Encrypt cert for the new subdomain. After 30
seconds the launcher devices will start polling `https://launcher.onnowtv.duckdns.org/api/launcher/config`.

## Storage layout

```
/data/
├── store.json                  ← all dock / wallpaper / APK / notification state
├── icons/                      ← tile icon uploads
├── wallpapers/                 ← background image uploads
└── apks/                       ← uploaded APK files
```

`store.json` is the single source of truth.  Atomic-rename writes mean it's
safe to back up at any moment.  Swap the JSON file for a MongoDB / Postgres
collection later if you need multi-instance HA — the API surface won't change.

## Security

- **ADMIN_TOKEN** is the only credential.  Set it via env var to keep it stable across
  restarts (if missing, a random one is generated on boot and printed to the log).
- Token can be passed two ways:
  - `Authorization: Bearer <token>` (machine clients)
  - 7-day JWT cookie issued by `/api/admin/login` (browser dashboard)
- All admin endpoints require one of the above; public endpoints are read-only.
- Run behind HTTPS — never expose this on a public network without TLS or the token leaks.

## Linking the launcher to a different backend host

The launcher's `LauncherRepository.DEFAULT_BASE_URL` (in
`/app/android/onnowtv-launcher/app/src/main/java/tv/onnow/launcher/data/LauncherRepository.kt`)
defaults to `https://launcher.onnowtv.duckdns.org`.  Change that constant
to point at a different host, OR adopt a settings screen later that
persists the URL in SharedPreferences.
