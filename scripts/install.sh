#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
device="${1:?Usage: scripts/install.sh DEVICE_IP:ADB_PORT}"
adb="$PWD/.tools/platform-tools/adb"
"$adb" -s "$device" install -r artifacts/t3-dashboard.apk
"$adb" -s "$device" shell run-as com.wilde.t3tv mkdir -p files
"$adb" -s "$device" shell run-as com.wilde.t3tv sh -c '"cat > files/connection.json"' < .private/connection.json
"$adb" -s "$device" shell run-as com.wilde.t3tv chmod 600 files/connection.json
"$adb" -s "$device" shell am start -n com.wilde.t3tv/.MainActivity
