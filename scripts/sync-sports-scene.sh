#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/frontend/public/sportshub-scene"
TARGET="$ROOT/android/onnowtv-livetv/app/src/main/assets/sports-scene"
mkdir -p "$TARGET"
cp "$SOURCE/"* "$TARGET/"
printf 'Bundled V2 field scene synchronized.\n'