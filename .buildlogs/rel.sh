set -x
bash /app/scripts/setup-vesper-build-env.sh
export ANDROID_HOME=/android-sdk RELEASE_STORE_FILE=/app/android/onnowtv-launcher/app/onnow-launcher-debug.keystore RELEASE_STORE_PASSWORD=onnow-launcher-debug RELEASE_KEY_ALIAS=onnow-launcher-debug RELEASE_KEY_PASSWORD=onnow-launcher-debug
timeout 2400 /app/android/vesper-tv/gradlew -p /app/android/onnowtv-livetv :app:assembleRelease -PversionCode=145 -PversionName=2.10.81 --no-daemon > /app/.buildlogs/livetv-rel.log 2>&1; echo EXIT $? >> /app/.buildlogs/livetv-rel.log
cp /app/android/onnowtv-livetv/app/build/outputs/apk/release/*.apk /app/android/onnowtv-livetv/test-artifacts/onnowtv-livetv-2.10.81-145.apk
timeout 2400 /app/android/vesper-tv/gradlew -p /app/android/onnowtv-fta-native :app:assembleRelease -PversionCode=24 -PversionName=0.1.24 --no-daemon > /app/.buildlogs/fta-rel.log 2>&1; echo EXIT $? >> /app/.buildlogs/fta-rel.log
cp /app/android/onnowtv-fta-native/app/build/outputs/apk/release/*.apk /app/android/onnowtv-fta-native/test-artifacts/onnowtv-fta-0.1.24.apk
echo ALLDONE
