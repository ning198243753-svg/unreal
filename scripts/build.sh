#!/usr/bin/env bash
# One-command build (no Android Studio required).
#
#   scripts/build.sh                 # debug APK
#   scripts/build.sh :app:assembleRelease
#   scripts/build.sh clean
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="$HOME/android-toolchain/jdk"
export ANDROID_HOME="$HOME/android-toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$HOME/android-toolchain/gradle-8.9/bin:$PATH"

cd "$ROOT"
echo "sdk.dir=$ANDROID_HOME" > local.properties

TASK="${1:-:app:assembleDebug}"
shift || true
exec gradle "$TASK" "$@" --console=plain
