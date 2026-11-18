#!/usr/bin/env bash
# Local verification for BLESKFAKTURA. No cloud deployment or Play upload.
set +x
set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ID="gen-lang-client-0115180408"
readonly DB_ID="ai-studio-android-bleskovf-056698ab-4e20-4c12-b4ec-52ee9d53a81d"
readonly FIREBASE_VERSION="15.36.0"
# Registered upload certificate; recheck against Play Console before upload.
readonly UPLOAD_SHA256="B016994403F88828911CBDC2CACF5B1834D547ECAB9CCC28BA24C43963463348"
cd "$ROOT"

fail() { printf 'BLOCKED: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || fail "Missing command: $1"; }
require_space() {
  local target="$1" minimum_gib="$2" free_kib
  while [[ ! -e "$target" ]]; do target="$(dirname "$target")"; done
  free_kib="$(LC_ALL=C df -Pk "$target" | awk 'NR==2 {print $4}')"
  [[ "$free_kib" =~ ^[0-9]+$ && "$free_kib" -ge $((minimum_gib * 1024 * 1024)) ]] || \
    fail "Need at least $minimum_gib GiB free on the filesystem containing $target."
}
usage() {
  printf '%s\n' 'Usage: ./release.sh [check|backend|android|local|aab]' \
    'check: inspect configuration only (default)' \
    'backend: frozen pnpm install, TypeScript build and backend tests' \
    'android: debug APK, all debug tests and lint under Firestore/Auth emulators' \
    'local: backend + android' \
    'aab: signing preflight + local checks + release lint/bundle/signature' \
    'Cloud deployment is disabled under the existing 0 EUR decision.'
}

firebase_cli() {
  if [[ -n "${FIREBASE_BIN:-}" ]]; then
    [[ -x "$FIREBASE_BIN" ]] || fail 'FIREBASE_BIN must be an executable path.'
    "$FIREBASE_BIN" "$@"
  else
    pnpm --registry=https://registry.npmjs.org/ dlx "firebase-tools@$FIREBASE_VERSION" "$@"
  fi
}

preflight() {
  need git; need node; need pnpm
  [[ "$(git rev-parse --show-toplevel)" == "$ROOT" ]] || fail 'Place release.sh at the repository root.'
  case "$(git remote get-url origin)" in
    https://github.com/youh4ck3dme/BLESKFAKTURA|https://github.com/youh4ck3dme/BLESKFAKTURA.git|git@github.com:youh4ck3dme/BLESKFAKTURA.git) ;;
    *) fail 'Unexpected origin; inspect it locally before proceeding.' ;;
  esac
  printf 'Repository: youh4ck3dme/BLESKFAKTURA\nCloud target (not deployed): %s\n' "$PROJECT_ID"
  git branch --show-current
  git status --short
  git log -3 --oneline
  node - "$DB_ID" <<'NODE'
const fs = require('node:fs');
const assert = require('node:assert/strict');
const read = p => fs.readFileSync(p, 'utf8');
const db = process.argv[2];
assert.equal(Number(process.versions.node.split('.')[0]), 22, 'Use the declared Node 22 runtime');
const cfg = JSON.parse(read('firebase.json'));
assert.equal(cfg.functions.source, 'functions');
assert.equal(cfg.functions.runtime, 'nodejs22');
assert.ok(Array.isArray(cfg.firestore) && cfg.firestore.length === 1);
assert.equal(cfg.firestore[0].database, db);
assert.equal(cfg.firestore[0].rules, 'firestore.rules');
for (const [service, port] of [['firestore',8085],['auth',9099]]) {
  assert.equal(cfg.emulators[service].host, '127.0.0.1');
  assert.equal(cfg.emulators[service].port, port);
}
assert.ok(read('app/src/main/res/values/firebase_applet_config.xml').includes(db));
assert.match(read('app/src/main/java/com/example/data/InvoiceRepository.kt'), /FirebaseFirestore\.getInstance\(databaseId\)/);
assert.ok(read('functions/src/verifier.ts').includes(db));
assert.match(read('functions/src/index.ts'), /getFirestore\(FIRESTORE_DATABASE_ID\)/);
const gradle = read('app/build.gradle.kts');
assert.match(gradle, /applicationId\s*=\s*"sk\.bizagent\.bleskovafaktura"/);
assert.match(gradle, /keyAlias\s*=\s*"upload"/);
assert.ok(fs.existsSync('functions/pnpm-lock.yaml'));
assert.ok(JSON.parse(read('functions/package.json')).scripts.test);
for (const key of ['KEYSTORE_PATH','STORE_PASSWORD','KEY_PASSWORD']) {
  console.log(`${key}: ${process.env[key] ? 'present' : 'missing'}`);
}
console.log('PASS: named database, application identity, emulator configuration and pnpm lockfile');
NODE
  [[ -x ./gradlew && -x ./scripts/build-local-debug.sh ]] || fail 'Missing executable wrapper or debug build script.'
}

backend() {
  require_space "$ROOT" 1
  pnpm --dir functions install --frozen-lockfile --ignore-scripts --registry=https://registry.npmjs.org/
  pnpm --dir functions test
}

select_java() {
  # Match build-local-debug.sh: use an explicit JAVA_HOME or discover javac.
  if [[ -z "${JAVA_HOME:-}" ]]; then
    local java_compiler
    java_compiler="$(command -v javac || true)"
    [[ -n "$java_compiler" ]] || fail 'Select JDK 21+ with JAVA_HOME or place javac on PATH.'
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$java_compiler")")")"
  fi
  [[ -x "$JAVA_HOME/bin/java" ]] || fail 'JAVA_HOME must point to a JDK 21+ installation.'
  local java_major
  java_major="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
  [[ "$java_major" =~ ^[0-9]+$ && "$java_major" -ge 21 ]] || fail 'Select JDK 21+ for Android SDK 36 and Firebase emulators.'
}

android() {
  require_space "$ROOT" 1
  # A lower bound to catch a full disk; cold dependency downloads need more room.
  require_space "${GRADLE_USER_HOME:-$ROOT/../gradle-cache}" 1
  select_java
  firebase_cli emulators:exec --only firestore,auth --project demo-no-project \
    --config "$ROOT/firebase.json" './scripts/build-local-debug.sh'
}

signing_preflight() {
  [[ -n "${KEYSTORE_PATH:-}" && -f "$KEYSTORE_PATH" ]] || fail 'Existing KEYSTORE_PATH is required; no replacement key will be created.'
  [[ "$KEYSTORE_PATH" == /* ]] || fail 'Use an absolute KEYSTORE_PATH so Gradle and certificate checks resolve the same file.'
  [[ -n "${STORE_PASSWORD:-}" && -n "${KEY_PASSWORD:-}" ]] || fail 'STORE_PASSWORD and KEY_PASSWORD must be set locally.'
  need keytool; need jarsigner; need openssl; need sha256sum
  local fingerprint
  fingerprint="$(keytool -exportcert -rfc -alias upload -keystore "$KEYSTORE_PATH" \
    -storepass:env STORE_PASSWORD | openssl x509 -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':\r\n')"
  [[ "$fingerprint" == "$UPLOAD_SHA256" ]] || fail 'Keystore does not match the registered upload certificate.'
}

aab() {
  require_space "$ROOT" 1
  require_space "${GRADLE_USER_HOME:-$ROOT/../gradle-cache}" 1
  select_java
  export ANDROID_HOME="${ANDROID_HOME:-$ROOT/../android-sdk}"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/../gradle-cache}"
  ./gradlew :app:lintRelease :app:bundleRelease --no-daemon --console=plain
  local bundle="$ROOT/app/build/outputs/bundle/release/app-release.aab"
  [[ -s "$bundle" ]] || fail 'Release AAB was not created.'
  jarsigner -verify -strict -verbose -certs -keystore "$KEYSTORE_PATH" \
    -storepass:env STORE_PASSWORD "$bundle"
  sha256sum "$bundle"
  ls -l "$bundle"
  printf '%s\n' 'AAB built locally. Play version availability, R8/16 KB validation and device billing E2E remain release gates.'
}

mode="${1:-check}"
[[ "$#" -le 1 ]] || { usage; exit 2; }
case "$mode" in
  -h|--help) usage; exit 0 ;;
  deploy) fail 'Cloud deployment disabled: 0 EUR / no Blaze. Other release blockers also remain.' ;;
  check|backend|android|local|aab) ;;
  *) usage; exit 2 ;;
esac
preflight
case "$mode" in
  check) ;;
  backend) backend ;;
  android) android ;;
  local) backend; android ;;
  aab) signing_preflight; backend; android; aab ;;
esac
printf '%s\n' 'Selected local checks completed. This does not approve a release or deploy any cloud resources.'
