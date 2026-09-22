#!/usr/bin/env bash
# Aegis Remote Control - Cursor Cloud Agent install phase.
#
# Idempotent dependency refresh and source-derived code generation that runs
# after the repository is checked out. It must terminate and must not start any
# long-lived services (those belong in cloud-start.sh). The base image / build
# snapshot already provides JDK 21, the Android SDK, and the native toolchain;
# this only warms Gradle/dependency caches and generates code (e.g. SQLDelight)
# against the checked-out source.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Resolve the Gradle project directory. The environment invokes this script from
# the repository root, but fall back to the script location so it also works when
# run manually from elsewhere.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
if [ -f "$PWD/remote-control-kmp/settings.gradle.kts" ]; then
  project_dir="$PWD/remote-control-kmp"
elif [ -f "$PWD/settings.gradle.kts" ]; then
  project_dir="$PWD"
else
  project_dir="$(cd "$script_dir/../.." >/dev/null 2>&1 && pwd)"
fi
cd "$project_dir"
echo "[cloud-install] project: $project_dir  ANDROID_HOME: $ANDROID_HOME"

# The Android Gradle plugin reads the SDK path from local.properties (git-ignored)
# or ANDROID_HOME. Write it defensively so IDE tooling also resolves the SDK.
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

# Warm dependency caches and generate sources without launching services. Strict
# dependency verification mirrors CI (gradle/verification-metadata.xml).
./gradlew --no-daemon --dependency-verification strict \
  :relay-server:relay-main:testClasses \
  :app-desktop:desktop-agent:testClasses \
  :app-desktop:desktop-main:testClasses \
  :app-android:compileDebugUnitTestKotlin \
  :shared:core-pairing:jvmTestClasses \
  --console=plain

echo "[cloud-install] done"
