# Launcher backend — VPS deployment checklist

You have a Contabo VPS at `62.84.181.66` already running the main Vesper
backend (`onnowtv.duckdns.org` → systemd `onnowtv-backend.service` on
port 8001).  These steps add the launcher backend ALONGSIDE it on a new
subdomain — they do NOT touch the Vesper backend.

## 1.  Create the DuckDNS subdomain (~2 min)

1. Sign in to <https://www.duckdns.org/domains> with the same account that
   owns `onnowtv.duckdns.org`.
2. Click **add domain** → enter `launcher-onnowtv` (DuckDNS doesn't allow
   nested `.onnowtv.duckdns.org` subdomains on the free tier — see note
   below) → press the green plus.
3. Set the IP to **62.84.181.66** and click **update ip**.

   → New endpoint: `https://launcher-onnowtv.duckdns.org`

> ⚠️ **Note:** if you'd rather keep `launcher.onnowtv.duckdns.org`
> (true subdomain), you'll need a paid DuckDNS tier OR move the apex
> domain to a real registrar.  For now, the launcher Android code's
> `DEFAULT_BASE_URL` is set to `https://launcher.onnowtv.duckdns.org`;
> tell me which hostname you end up with and I'll patch the constant.

## 2.  Copy the launcher backend to the VPS (~3 min)

```bash
# from your laptop — adjust the path if your repo lives elsewhere
scp -r /app/launcher-backend root@62.84.181.66:/opt/onnow-launcher-api
```

## 3.  Install + run via systemd (~5 min — no Docker needed)

SSH into the VPS:

```bash
ssh root@62.84.181.66

cd /opt/onnow-launcher-api
python3 -m venv venv
./venv/bin/pip install -r requirements.txt

# generate a STABLE admin token and save it
ADMIN_TOKEN=$(openssl rand -hex 32)
echo "ADMIN_TOKEN=$ADMIN_TOKEN"   # ← save this for the admin login
echo "ADMIN_TOKEN=$ADMIN_TOKEN" >> /etc/onnow-launcher.env
echo "PUBLIC_BASE_URL=https://launcher-onnowtv.duckdns.org" >> /etc/onnow-launcher.env
echo "DATA_DIR=/var/lib/onnow-launcher" >> /etc/onnow-launcher.env
mkdir -p /var/lib/onnow-launcher

cat > /etc/systemd/system/onnow-launcher.service <<'EOF'
[Unit]
Description=OnNow TV V2 Launcher Admin API
After=network.target

[Service]
Type=simple
EnvironmentFile=/etc/onnow-launcher.env
WorkingDirectory=/opt/onnow-launcher-api
ExecStart=/opt/onnow-launcher-api/venv/bin/uvicorn main:app --host 127.0.0.1 --port 8002
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now onnow-launcher.service
systemctl status onnow-launcher.service   # ← should be `active (running)`
curl -s localhost:8002/api/launcher/health  # ← should print {"ok":true,...}
```

## 4.  nginx reverse proxy + Let's Encrypt (~5 min)

The Vesper backend already uses nginx + certbot for `onnowtv.duckdns.org`.
Add a sibling server block for the launcher:

```bash
cat > /etc/nginx/sites-available/onnow-launcher <<'EOF'
server {
    listen 80;
    server_name launcher-onnowtv.duckdns.org;
    location / {
        proxy_pass         http://127.0.0.1:8002;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        client_max_body_size 100m;   # allow APK uploads up to 100 MB
    }
}
EOF
ln -s /etc/nginx/sites-available/onnow-launcher /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
certbot --nginx -d launcher-onnowtv.duckdns.org --redirect --non-interactive --agree-tos -m you@example.com
```

After certbot completes you should be able to open
`https://launcher-onnowtv.duckdns.org/admin` in any browser, paste the
`ADMIN_TOKEN` from step 3, and configure dock / wallpapers / APKs /
notifications.

## 5.  Point the launcher Android app at the new URL

Edit `/app/android/onnowtv-launcher/app/src/main/java/tv/onnow/launcher/data/LauncherRepository.kt`:

```kotlin
const val DEFAULT_BASE_URL = "https://launcher-onnowtv.duckdns.org"
```

Save to GitHub → `build-launcher.yml` produces a fresh debug APK →
sideload onto the box.  On first boot the launcher will:

- poll `/api/launcher/config` every 5 min and pull dock layout / wallpaper / accents
- poll `/api/launcher/notifications/pending?device_id=<UUID>` every 30 s and pop any broadcasts you push from `/admin`
- cache the last config to SharedPreferences so cold starts work offline

## 6.  Optional — quick-test via SSH tunnel (no DuckDNS yet)

If you want to test the admin UI from your laptop BEFORE creating the
subdomain:

```bash
ssh -L 8002:localhost:8002 root@62.84.181.66
# leave that terminal open, then in a browser:
open http://localhost:8002/admin
```

This forwards your local port 8002 to the VPS's port 8002 — admin UI
works, but the Android launcher will still get a network error until
the public URL is reachable.

---

## Health-check commands you can paste at any time

```bash
ssh root@62.84.181.66 'systemctl status onnow-launcher.service'
ssh root@62.84.181.66 'journalctl -u onnow-launcher.service -n 100'
curl -s https://launcher-onnowtv.duckdns.org/api/launcher/health
curl -s https://launcher-onnowtv.duckdns.org/api/launcher/config | python3 -m json.tool | head -40
```
