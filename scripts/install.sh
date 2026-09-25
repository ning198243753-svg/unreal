#!/usr/bin/env bash
# Install the freshly built debug APK to the connected device.
#   scripts/install.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/android-toolchain/sdk}/platform-tools/adb"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

[ -f "$APK" ] || { echo "APK not found; run scripts/build.sh first"; exit 1; }
"$ADB" install -r "$APK"
echo "installed. Remember to enable the module in LSPosed and add scope, then reboot."
