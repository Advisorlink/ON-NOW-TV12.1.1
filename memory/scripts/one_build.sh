#!/usr/bin/env bash
# ON NOW one-app (Vesper shell + embedded Live TV module).  Assumes the React bundle
# has already been copied into vesper-tv/app/src/main/assets by /root/v2_full.sh.
set -u
LOG=/tmp/one_build.log
exec >"$LOG" 2>&1
echo "== start $(date)"
TASK=${1:-assembleRelease}
VC=${VC:-1}
VN=${VN:-1.0.0}
[ -x /opt/native-tools/aapt2 ] && [ -d /android-sdk/platforms ] || bash /app/scripts/setup-vesper-build-env.sh
mkdir -p /android-sdk/licenses
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > /android-sdk/licenses/android-sdk-license
ls /tmp/vesper-gradle-cache/gradle.properties || bash /app/scripts/setup-vesper-build-env.sh
export GRADLE_USER_HOME=/tmp/vesper-gradle-cache
cd /app/android/onnow-one && ./gradlew "$TASK" -q -PversionCode="$VC" -PversionName="$VN" && echo "ONE BUILD OK" || { echo "ONE BUILD FAILED"; echo "== done $(date)"; exit 1; }
if [ "$TASK" = "assembleRelease" ]; then
  OUT=/app/android/onnow-one/test-artifacts; mkdir -p "$OUT"; rm -f "$OUT"/*.apk
  APK=$(ls -t /tmp/one-build/app/outputs/apk/release/*.apk | head -1)
  DEST="$OUT/reborn-play-$VN-$VC.apk"; cp "$APK" "$DEST"
  apksigner sign --ks /app/android/vesper-tv/app/onnowtv-stable-debug.keystore --ks-pass pass:onnowtv-debug --ks-key-alias onnowtv-debug --key-pass pass:onnowtv-debug \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false "$DEST" && echo "ONE RESIGNED"
  echo "ONE COPIED $DEST"
fi
echo "== done $(date)"
