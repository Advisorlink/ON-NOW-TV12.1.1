#!/usr/bin/env bash
# Rebuilds the vesper-tv build env after a pod reset (/opt and /tmp get wiped).
set -e
export NATIVE_TOOLS_DIR=/opt/native-tools
[ -x /opt/native-tools/aapt2 ] || bash /app/scripts/ensure-native-arm64-sdk.sh
ln -sfn /opt/native-tools/sdk /android-sdk
mkdir -p /tmp/vesper-gradle-cache
printf 'android.aapt2FromMavenOverride=/opt/native-tools/aapt2\n' > /tmp/vesper-gradle-cache/gradle.properties
echo "build env ready"
