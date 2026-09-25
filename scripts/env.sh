#!/usr/bin/env bash
# Put the local Android toolchain on PATH for the current shell.
#
#   source scripts/env.sh
#
# Everything lives under ~/android-toolchain (no system packages, no root needed).
export JAVA_HOME="$HOME/android-toolchain/jdk"
export ANDROID_HOME="$HOME/android-toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$HOME/android-toolchain/gradle-8.9/bin:$PATH"
echo "JAVA_HOME   = $JAVA_HOME"
echo "ANDROID_HOME= $ANDROID_HOME"
echo "gradle      = $(command -v gradle || echo missing)"
