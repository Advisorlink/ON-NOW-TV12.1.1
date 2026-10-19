#!/usr/bin/env bash
set -euo pipefail
tools="${NATIVE_TOOLS_DIR:?Set NATIVE_TOOLS_DIR to the build-tool cache path}"
if [[ -v PLATFORM_TOOLS_SOURCE ]]; then
  test -f "$PLATFORM_TOOLS_SOURCE/source.properties"
  mkdir -p "$tools/sdk/platform-tools"
  cp -a "$PLATFORM_TOOLS_SOURCE/." "$tools/sdk/platform-tools/"
fi
if [[ -x "$tools/aapt2" && -f "$tools/sdk/platforms/android-34/android.jar" && -f "$tools/sdk/build-tools/34.0.0/aapt2" ]]; then
  exit 0
fi
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
mkdir -p "$tools/sdk/platforms" "$tools/sdk/build-tools/34.0.0"
curl -fLsS 'https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip' -o "$temporary/tools.zip" & p1=$!
curl -fLsS 'https://dl.google.com/android/repository/platform-34-ext7_r03.zip' -o "$temporary/platform.zip" & p2=$!
curl -fLsS 'https://dl.google.com/android/repository/build-tools_r34-linux.zip' -o "$temporary/build.zip" & p3=$!
wait "$p1"; wait "$p2"; wait "$p3"
unzip -qo "$temporary/tools.zip" -d "$tools"
unzip -qo "$temporary/platform.zip" -d "$tools/sdk/platforms"
unzip -qo "$temporary/build.zip" -d "$temporary/build"
cp -a "$temporary/build/android-14/." "$tools/sdk/build-tools/34.0.0/"
cp "$tools/build-tools/aapt2" "$tools/aapt2"
chmod +x "$tools/aapt2"
cp "$tools/build-tools/"{aapt,aapt2,aidl,zipalign} "$tools/sdk/build-tools/34.0.0/"