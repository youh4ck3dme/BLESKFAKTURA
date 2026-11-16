#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ ! -f app/google-services.json ]]; then
  echo 'Chyba: app/google-services.json chýba. Stiahnite správnu Android konfiguráciu z Firebase.' >&2
  exit 1
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
java_major="$("$java_bin" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [[ "$java_major" != 21 ]]; then
  echo 'Použite JDK 21 (API 36 Robolectric testy ho vyžadujú).' >&2
  exit 1
fi
./gradlew :app:testInternalDebugUnitTest :app:lintInternalDebug :app:lintInternalRelease :app:assembleInternalDebug --no-daemon --console=plain "$@"
if [[ -n "${KEYSTORE_PATH:-}" && -n "${STORE_PASSWORD:-}" && -n "${KEY_PASSWORD:-}" ]]; then
  ./gradlew :app:bundleInternalRelease --no-daemon --console=plain "$@"
else
  ./gradlew :app:bundleInternalRelease -PunsignedRelease=true --no-daemon --console=plain "$@"
  echo 'AAB je NEPODPÍSANÝ: pred uploadom do Play ho podpíšte pôvodným upload kľúčom.'
fi
