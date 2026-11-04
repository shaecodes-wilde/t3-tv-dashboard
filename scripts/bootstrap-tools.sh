#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools
fetch() {
  local url="$1" output="$2" checksum="$3"
  curl --fail --location --retry 2 "$url" -o "$output"
  printf '%s  %s\n' "$checksum" "$output" | sha1sum -c -
}
if [[ ! -x .tools/jdk/bin/javac ]]; then
  curl --fail --location --retry 2 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' -o .tools/jdk.tar.gz
  mkdir -p .tools/jdk
  tar -xzf .tools/jdk.tar.gz --strip-components=1 -C .tools/jdk
fi
if [[ ! -f .tools/platform/android-34/android.jar ]]; then
  fetch https://dl.google.com/android/repository/platform-34-ext7_r03.zip .tools/platform.zip 1f2e9478d6a7601425ceaa553311dc43191f103d
  unzip -q .tools/platform.zip -d .tools/platform
fi
if [[ ! -x .tools/build-tools/android-14/aapt ]]; then
  fetch https://dl.google.com/android/repository/build-tools_r34-linux.zip .tools/build-tools.zip d6d58e0c6925a9e4d9a541e84cd1f405c2f9d2a9
  unzip -q .tools/build-tools.zip -d .tools/build-tools
fi
if [[ ! -x .tools/platform-tools/adb ]]; then
  curl --fail --location --retry 2 https://dl.google.com/android/repository/platform-tools-latest-linux.zip -o .tools/platform-tools.zip
  unzip -q .tools/platform-tools.zip -d .tools
fi
