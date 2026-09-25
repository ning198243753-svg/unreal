#!/usr/bin/env bash
# Read-only device inspection. Changes nothing on the phone.
#   scripts/device-info.sh
set -euo pipefail
ADB="${ANDROID_HOME:-$HOME/android-toolchain/sdk}/platform-tools/adb"

echo "=== device ==="
"$ADB" devices -l
echo "=== build ==="
"$ADB" shell getprop | grep -E "ro.build.version.(release|sdk|security_patch)|ro.product.(model|device|brand)|ro.product.cpu.abi|ro.build.display.id" | tr -d '\r' | sort
echo "=== root / selinux ==="
"$ADB" shell su -c id 2>&1 | tr -d '\r'
"$ADB" shell getenforce 2>&1 | tr -d '\r'
echo "=== kernel modules (LSPosed etc.) ==="
"$ADB" shell su -c ls /data/adb/modules 2>&1 | tr -d '\r'
echo "=== oem location packages ==="
"$ADB" shell pm list packages 2>&1 | tr -d '\r' | grep -iE "location|gnss" || true
