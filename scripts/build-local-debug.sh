#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$project_dir/../toolchains/jdk17/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$project_dir/../android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_dir/../gradle-cache}"
if [[ ! -x "$JAVA_HOME/bin/java" || ! -d "$ANDROID_HOME/platforms/android-36.1" ]]; then
  echo 'Nastavte JAVA_HOME na JDK 17 a ANDROID_HOME na SDK s platformou android-36.1.' >&2
  exit 1
fi
cd "$project_dir"
exec ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain "$@"
