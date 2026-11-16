# Release readiness

Stav po oprave Android integrácie na vetve `grokbranch`. Produkt nie je legislatívne certifikovaný a produkčne nedoručuje. `productionEligible` zostáva `false`. Integračný patch na vetve **nie je aplikovaný**.

Produktový commit: `183980214e01c81ed9c19389c9de61381090587f`. Android CI, ktoré dokončilo zostávajúce kontroly: `7621945f13450dd71a2fa84f2b98b2a0ea5ba7d6`.

## Spustené lokálne

Prostredie: Node v22.23.3, OpenJDK 17.0.20.1, Gradle wrapper 9.3.1. Android SDK nie je. Docker nie je. Príkazy bežali v `bleskova-efaktura-bridge` pre produktový commit.

| Príkaz | Exit | Výsledok |
| --- | --- | --- |
| `npm run typecheck` | 0 | `tsc --noEmit` bez chýb |
| `npm test` | 0 | **34/34** pass, 0 fail. Vrátane explicitného provisioningu: cudzie UID je 403, provisionované UID je 200, produkcia a wildcard UID sú odmietnuté |
| `npm audit --audit-level=high` | 0 | `found 0 vulnerabilities` |
| `./gradlew test --no-daemon --offline` | 0 | **BUILD SUCCESSFUL** v 34 s. JUnit: 11 testov, 0 failures (`EfakturaClientTest` 5, `EfakturaSessionTest` 4, `ImportOperationTest` 1, `BridgeLiveTest` 1) |
| `EFAKTURA_ENV=production` + provision UID/tenant/role | 1 | `Produkcia odmietla lokálny provisioning.` |
| `git apply --check integration/android-app.patch` | 0 | patch sadne na čistý strom. `app/` a `settings.gradle.kts` ostali bez zmeny |
| izolovaný worktree, `gradle :app:assembleDebug` | 1 | konfigurácia patchu prešla, potom `SDK location not found` |

Predošlý Gradle 8.10.2 problém test workera je opravený wrapperom 9.3.1. Handshake `Did not receive connection preamble within 10s` sa v logu stále objaví a task napriek tomu skončí úspešne. Nie je to pád testu.

`BridgeLiveTest` ide cez reálne HTTP. Oficiálna faktúra má `technicalPass=true`, slovenskú vrstvu `UNSUPPORTED` a text obsahuje `Legislatívna platnosť nebola overená.` `productionEligible=false`.

## GitHub Actions

Bridge, commit `1839802`: [run 43895650201](https://github.com/youh4ck3dme/BLESKFAKTURA/actions/runs/43895650201) **success**. Kroky `npm ci`, typecheck, `npm test`, `npm audit`, Kotlin testy a Docker build and smoke sú success.

Android, commit `7621945`: [run 43901833376](https://github.com/youh4ck3dme/BLESKFAKTURA/actions/runs/43901833376) **failure** jobu `android-patch` (7m40s). Patch sa aplikoval len v runneri. Jednotlivé kroky:

| Krok | Výsledok |
| --- | --- |
| `assembleDebug` | success, `BUILD SUCCESSFUL` v 4m 38s, vrátane `:app:packageDebug` |
| `lintDebug` | **failure**, exit 1. 14 errors, 0 z integračného patchu. `abortOnError` ostalo zapnuté |
| klasifikácia lintu | 14 známych, 0 unexpected |
| `:app:testDebugUnitTest --tests com.example.ExampleUnitTest` | success, `BUILD SUCCESSFUL` v 29s. Log počet testov nevypísal; JUnit XML prepísal nasledujúci Robolectric beh |
| Robolectric `ExampleRobolectricTest` + `InvoiceAiValidationTest` | **failure**, 2 tests completed, 2 failed. `UnsupportedOperationException` v `DefaultSdkProvider.java:170`. Robolectric 4.16.1 nebol povýšený |
| `processReleaseMainManifest` a kontrola cleartext | success, `BUILD SUCCESSFUL` v 12s. Debug merged manifest má `networkSecurityConfig`, release nie. Ani jeden nemá `usesCleartextTraffic=true`. Domény v generovanom XML: `10.0.2.2`, `127.0.0.1`, `localhost`. `cleartextTrafficPermitted=false` v base-config |

Job je červený, lebo lint a Robolectric boli vykonané a padli. Nie je to pád `assembleDebug` ani release kontroly. `continue-on-error` len zabránilo, aby sa ďalšie kroky preskočili.

Lint chyby, všetky mimo patchu:

- 13× `NewApi` v `app/src/main/java/com/example/data/InvoiceAiService.kt` (`java.time`, minSdk 24, treba API 26 alebo desugaring)
- 1× `PermissionImpliesUnsupportedChromeOsHardware` v `app/src/main/AndroidManifest.xml` (CAMERA bez `uses-feature`)

Predošlé Android behy, už nahradené: [43895650204](https://github.com/youh4ck3dme/BLESKFAKTURA/actions/runs/43895650204) (chýbajúci balík `tools`), [43896214875](https://github.com/youh4ck3dme/BLESKFAKTURA/actions/runs/43896214875) (`default_web_client_id` bez `google-services.json`), [43897386608](https://github.com/youh4ck3dme/BLESKFAKTURA/actions/runs/43897386608) (`assembleDebug` success, Robolectric pád, lint nedobehol), [43899047266](https://github.com/youh4ck3dme/BLESKFAKTURA/actions/runs/43899047266) (`assembleDebug` success, lint pád, unit testy a release manifest skipped).

## NOT_RUN

- Lokálne `assembleDebug`, `:app:lintDebug` a Android unit testy po vygenerovaní APK: **NOT_RUN**. Príkaz spustený, zastal na `SDK location not found`.
- `GreetingScreenshotTest`: **NOT_RUN**. Nie je v Android CI filtri.
- Lokálny Docker image a Docker smoke: **NOT_RUN**. `docker` nie je nainštalovaný. Vzdialený Docker smoke na run 43895650201 prebehol.
- Firebase Auth/App Check proti reálnemu projektu: **NOT_RUN**. CI `google-services.json` je necommitnutá fixture `ci-not-a-firebase-project`.
- Firebase emulator: **NOT_RUN**.
- SK ruleset ako Schematron: **NOT_RUN**. XLSX v1.13 je len súbor, vrstva ostáva `UNSUPPORTED`.
- Reálny poštár, jeho webhook a reporting FS: **NOT_RUN**.
- Aplikovanie `integration/android-app.patch` na `grokbranch`: **NOT_RUN** zámerne. `git diff d0c98fd..HEAD -- app settings.gradle.kts` je prázdny.
- Merge do `main` a produkčný deploy: **NOT_RUN** zámerne.

## Nesmie ísť von ako produkcia

Kým nie je certifikovaný poštár, SK ruleset sa nespúšťa a Firebase nie je zapojené na konkrétny projekt. Lokálny SQLite nie je distribuovaná produkčná databáza. Sandbox nesmie dostať produkčnú doručenku. `productionEligible` je vždy `false`. HMAC emulátor, sandbox a lokálny provisioning nesmú byť v produkčnom Android release. Debug cleartext je len pre loopback, `10.0.2.2` a RFC1918. Release `usesCleartextTraffic` nenastavuje.
