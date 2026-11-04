#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="$PWD/.tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BUILD_TOOLS="$PWD/.tools/build-tools/android-14"
ANDROID_JAR="$PWD/.tools/platform/android-34/android.jar"
mkdir -p build/classes build/generated build/dex artifacts
mkdir -p -m 700 .private
"$BUILD_TOOLS/aapt" package -f -m -J build/generated -M android/AndroidManifest.xml -S android/res -I "$ANDROID_JAR" -F build/base.apk
find android/src build/generated -name '*.java' > build/sources.txt
javac -J-XX:ActiveProcessorCount=2 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
JAVA_OPTS='-XX:ActiveProcessorCount=2' "$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [[ ! -f .private/debug.keystore ]]; then
  keytool -genkeypair -keystore .private/debug.keystore -storepass android -keypass android -alias dashboard -dname 'CN=T3 Dashboard Local Development' -keyalg RSA -keysize 2048 -validity 10000 -noprompt
fi
"$BUILD_TOOLS/apksigner" sign --ks .private/debug.keystore --ks-pass pass:android --key-pass pass:android --out artifacts/t3-dashboard.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" verify artifacts/t3-dashboard.apk
printf 'Built artifacts/t3-dashboard.apk\n'
