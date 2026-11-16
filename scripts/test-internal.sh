#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if command -v pnpm >/dev/null && [[ "$(pnpm --version)" == '12.15.0' ]]; then
  pnpm_cmd=(pnpm)
else
  pnpm_cmd=(npx --yes --registry=https://registry.npmjs.org pnpm@12.15.0)
fi
if [[ "$#" -eq 0 ]]; then
  set -- :app:testInternalDebugUnitTest :app:lintInternalDebug :app:lintInternalRelease
fi
printf -v gradle_command '%q ' ./gradlew "$@" --no-daemon --console=plain
# These are disposable local emulators, always a demo project, never production.
CI=true JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Xmx512m" "${pnpm_cmd[@]}" \
  --registry=https://registry.npmjs.org dlx firebase-tools@15.36.0 \
  emulators:exec --only firestore,auth --project demo-no-project \
  --config firebase.json "$gradle_command"
