#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  java_compiler="$(command -v javac || true)"
  if [[ -n "$java_compiler" ]]; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$java_compiler")")")"
  fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$project_dir/../android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_dir/../gradle-cache}"
if [[ ! -x "${JAVA_HOME:-}/bin/java" || ! -d "$ANDROID_HOME/platforms/android-36.1" ]]; then
  echo 'Nastavte JAVA_HOME na JDK 21 a ANDROID_HOME na SDK s platformou android-36.1.' >&2
  exit 1
fi
java_version="$("$JAVA_HOME/bin/java" -version 2>&1)"
if [[ ! "$java_version" =~ version[[:space:]]+\"([0-9]+) ]] || (( BASH_REMATCH[1] < 21 )); then
  echo 'Android SDK 36 testy vyžadujú JDK 21 alebo novšie.' >&2
  exit 1
fi
# Preserve the existing local debug identity when a legacy keystore is present.
if [[ -z "${ANDROID_USER_HOME:-}" && -f "$HOME/.android/debug.keystore" ]]; then
  export ANDROID_USER_HOME="$HOME/.android"
fi
cd "$project_dir"
exec ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain "$@"
