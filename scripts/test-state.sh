#!/usr/bin/env bash
# Compiles and runs the plain-JDK DashboardState checks; no Android runtime needed.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVAC="$PWD/.tools/jdk/bin/javac"
JAVA="$PWD/.tools/jdk/bin/java"
[[ -x "$JAVAC" ]] || JAVAC=javac
[[ -x "$JAVA" ]] || JAVA=java
mkdir -p build/state-tests
"$JAVAC" -J-XX:ActiveProcessorCount=2 -d build/state-tests \
  android/src/com/wilde/t3tv/DashboardState.java \
  tests/com/wilde/t3tv/DashboardStateTest.java
"$JAVA" -XX:ActiveProcessorCount=2 -cp build/state-tests com.wilde.t3tv.DashboardStateTest
