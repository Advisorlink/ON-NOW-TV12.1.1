#!/usr/bin/env bash
# migrate-addons.sh — copy every active addon from one ON NOW TV V2
# backend to another, so the new backend's content set instantly
# matches the old.  Idempotent: re-installs are upserts.
#
# Usage:
#   ./migrate-addons.sh https://old-backend.example.com https://new-backend.example.com
#
# Why this exists: when the production backend moved from the
# Emergent preview pod to the Contabo VPS, only the four addons in
# `SUGGESTED_ADDONS` got auto-seeded.  The user's Plex addon
# (which was returning the playable HTTPS streams) was left behind,
# so every Play button gave "playback error" until we noticed.
# This script is the one-line fix for any future migration.
set -euo pipefail

OLD="${1:-}"
NEW="${2:-}"
if [ -z "$OLD" ] || [ -z "$NEW" ]; then
    echo "Usage: $0 <old-backend-url> <new-backend-url>" >&2
    exit 1
fi

echo "[1/3] Pulling addon list from $OLD ..."
ADDONS_JSON=$(curl -fsS -m 15 "$OLD/api/addons")
COUNT=$(echo "$ADDONS_JSON" | python3 -c "import json,sys;print(len(json.load(sys.stdin)))")
echo "      Found $COUNT addon(s)."

echo "[2/3] Installing each on $NEW ..."
echo "$ADDONS_JSON" | python3 -c "
import json, sys, subprocess
data = json.load(sys.stdin)
new = '$NEW'
for a in data:
    url = a.get('url','').rstrip('/') + '/manifest.json'
    name = a.get('name','?')
    print(f'    • {name}')
    r = subprocess.run(
        ['curl','-s','-m','30','-o','/dev/null','-w','%{http_code}',
         '-X','POST', f'{new}/api/addons/install',
         '-H','Content-Type: application/json',
         '-d', json.dumps({'url': url})],
        capture_output=True, text=True,
    )
    print(f'        → HTTP {r.stdout}')
"

echo "[3/3] Verifying new backend ..."
curl -fsS -m 8 "$NEW/api/addons" | python3 -c "
import json, sys
print('   Addons now installed:')
for a in json.load(sys.stdin):
    print(f'      • {a.get(\"name\")}')"
echo "Done."
