ls /android-sdk/platforms >/dev/null 2>&1 || bash /app/scripts/setup-vesper-build-env.sh
export ANDROID_HOME=/android-sdk RELEASE_STORE_FILE=/app/android/onnowtv-launcher/app/onnow-launcher-debug.keystore RELEASE_STORE_PASSWORD=onnow-launcher-debug RELEASE_KEY_ALIAS=onnow-launcher-debug RELEASE_KEY_PASSWORD=onnow-launcher-debug
timeout 2400 /app/android/vesper-tv/gradlew -p /app/android/onnowtv-livetv :app:assembleRelease -PversionCode=146 -PversionName=2.10.82 --no-daemon > /app/.buildlogs/livetv-rel.log 2>&1; echo EXIT $? >> /app/.buildlogs/livetv-rel.log
rm -f /app/android/onnowtv-livetv/test-artifacts/*.apk
cp /app/android/onnowtv-livetv/app/build/outputs/apk/release/app-release.apk /app/android/onnowtv-livetv/test-artifacts/onnowtv-livetv-2.10.82-146.apk
echo ALLDONE
