# Local verification

Use Node 22, pnpm 12.15.0, JDK 21 and the checked-in Gradle 9.3.1 wrapper.
The Android SDK must include `platforms;android-36.1` and `build-tools;36.0.0`.
Select the SDK through `local.properties` or `ANDROID_HOME`.

```sh
pnpm --dir functions install --frozen-lockfile --ignore-scripts
pnpm --dir functions test
pnpm dlx firebase-tools@15.36.0 emulators:exec \
  --only firestore,auth --project demo-no-project --config firebase.json \
  './gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --max-workers=1 --no-parallel --console=plain'
```

The Firebase emulators serve the configured named database. Android repository
tests create their own Firebase app for `demo-no-project`, independent of any
real default Firebase configuration. They never select a live project from
`google-services.json`. These tests do not require an Android virtual device.

On a computer with 8 GiB RAM, run Gradle after closing other virtual machines.
The Gradle JVM and test JVM have separate heap limits. A cold dependency cache
also requires several GiB of free disk. Do not run concurrent Gradle builds.

## Firebase client configuration and device testing

`app/google-services.json` is deliberately ignored. Download the configuration
for `sk.bizagent.bleskovafaktura` from the intended Firebase project. CI accepts
it through `GOOGLE_SERVICES_JSON` on trusted pushes; pull-request artifacts
without that configuration support local demo testing only.

Debug builds use the Firebase App Check debug provider. Each installation's
SDK-generated debug secret must be registered privately for the matching app
at https://console.firebase.google.com/project/gen-lang-client-0115180408/appcheck/apps.
Do not commit a shared debug secret. Release builds retain Play Integrity.
Registering a signing certificate is separate from registering a debug secret.

Passing local tests does not establish successful live App Check, Google login,
AI extraction, or a Play purchase. Verify these using a configured installation.
Cloud billing remains disabled under the current 0 EUR decision. CI and the
local verification commands do not deploy Firebase Functions or Firestore rules.
