#!/usr/bin/env bash
# Download the whole Android build toolchain into ~/android-toolchain.
# No root / sudo required; nothing is installed system-wide.
#
#   scripts/setup-toolchain.sh
#
set -euo pipefail

BASE="$HOME/android-toolchain"
JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_VER="8.9"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VER}-bin.zip"

mkdir -p "$BASE/downloads"
cd "$BASE/downloads"

echo ">> JDK 21 (Temurin)"
[ -x "$BASE/jdk/bin/java" ] || {
  curl -sSL -o jdk21.tar.gz "$JDK_URL"
  mkdir -p "$BASE/jdk"
  tar -xzf jdk21.tar.gz -C "$BASE/jdk" --strip-components=1
}

echo ">> Android command-line tools"
if [ ! -d "$BASE/sdk/cmdline-tools/latest" ]; then
  curl -sSL -o cmdline-tools.zip "$CMDLINE_URL"
  mkdir -p "$BASE/sdk/cmdline-tools"
  unzip -q -o cmdline-tools.zip -d "$BASE/sdk/cmdline-tools"
  mv "$BASE/sdk/cmdline-tools/cmdline-tools" "$BASE/sdk/cmdline-tools/latest"
fi

export JAVA_HOME="$BASE/jdk"
export ANDROID_HOME="$BASE/sdk"
SDKM="$BASE/sdk/cmdline-tools/latest/bin/sdkmanager"

echo ">> Android SDK licenses + packages (platform 36, build-tools 36)"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null

echo ">> Gradle ${GRADLE_VER}"
if [ ! -x "$BASE/gradle-${GRADLE_VER}/bin/gradle" ]; then
  curl -sSL -o "gradle-${GRADLE_VER}-bin.zip" "$GRADLE_URL"
  unzip -q -o "gradle-${GRADLE_VER}-bin.zip" -d "$BASE"
fi

echo
echo "Done. Activate with:  source scripts/env.sh"
"$BASE/jdk/bin/java" -version 2>&1 | head -1
