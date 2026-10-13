#!/usr/bin/env bash
# Hosted GitHub runners already contain the Android toolchain. Avoid replacing
# sdkmanager (and contacting Google's repositories) when the required SDK exists.
set -euo pipefail
PLATFORM="${1:?Pass the compile SDK API level}"
BUILD_TOOLS="${2:?Pass the Android build-tools version}"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK_ROOT:?The runner must supply ANDROID_HOME or ANDROID_SDK_ROOT}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${GITHUB_ENV:?GITHUB_ENV is required}"
: "${GITHUB_PATH:?GITHUB_PATH is required}"
export ANDROID_HOME="$SDK_ROOT" ANDROID_SDK_ROOT="$SDK_ROOT"
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$SDK_ROOT" "$SDK_ROOT" >> "$GITHUB_ENV"
printf '%s\n%s\n' "$SDK_ROOT/platform-tools" "$SDK_ROOT/build-tools/$BUILD_TOOLS" >> "$GITHUB_PATH"
missing=()
[[ -f "$SDK_ROOT/platforms/android-$PLATFORM/android.jar" ]] || missing+=("platforms;android-$PLATFORM")
[[ -x "$SDK_ROOT/build-tools/$BUILD_TOOLS/aapt2" && -x "$SDK_ROOT/build-tools/$BUILD_TOOLS/apksigner" ]] || missing+=("build-tools;$BUILD_TOOLS")
[[ -x "$SDK_ROOT/platform-tools/adb" ]] || missing+=("platform-tools")
printf 'SDK root: %s\nAPI: %s\nBuild tools: %s\nMissing packages: %s\n' "$SDK_ROOT" "$PLATFORM" "$BUILD_TOOLS" "${missing[*]:-none}" | tee "$RUNNER_TEMP/android-sdk-check.log"
if (( ${#missing[@]} )); then
  SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  if [[ ! -x "$SDKMANAGER" ]]; then
    printf '::error::Hosted sdkmanager is missing: %s\n' "$SDKMANAGER"
    exit 1
  fi
  # yes commonly exits with SIGPIPE after sdkmanager closes stdin. Check the
  # consumer's exit status rather than mistaking that expected 141 for failure.
  licensed=false
  for attempt in 1 2 3; do
    set +e
    yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > "$RUNNER_TEMP/android-sdk-licenses-$attempt.log" 2>&1
    result=${PIPESTATUS[1]}
    set -e
    if [[ "$result" -eq 0 ]]; then licensed=true; break; fi
    sleep "$((attempt * 5))"
  done
  if [[ "$licensed" != true ]]; then
    printf '::error::Android license acceptance failed. See android-sdk-licenses logs.\n'
    exit 1
  fi
  installed=false
  for attempt in 1 2 3; do
    if "$SDKMANAGER" --sdk_root="$SDK_ROOT" "${missing[@]}" > "$RUNNER_TEMP/android-sdk-install-$attempt.log" 2>&1; then installed=true; break; fi
    sleep "$((attempt * 5))"
  done
  if [[ "$installed" != true ]]; then
    printf '::error::Android package installation failed. See android-sdk-install logs.\n'
    exit 1
  fi
fi
test -f "$SDK_ROOT/platforms/android-$PLATFORM/android.jar"
test -x "$SDK_ROOT/build-tools/$BUILD_TOOLS/aapt2"
test -x "$SDK_ROOT/build-tools/$BUILD_TOOLS/apksigner"
test -x "$SDK_ROOT/platform-tools/adb"
printf 'Required Android SDK packages verified.\n'