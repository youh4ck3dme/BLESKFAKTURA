# Release readiness

Stav po oprave Android integrácie na vetve `grokbranch`. Produkt nie je legislatívne certifikovaný a produkčne nedoručuje. `productionEligible` zostáva `false`. Integračný patch na vetve **nie je aplikovaný**.

## Spustené v tomto kole

Prostredie: Node v22.23.3, OpenJDK 17.0.20.1, Gradle wrapper 9.3.1. Android SDK nie je. Docker nie je.

| Príkaz | Exit | Výsledok |
| --- | --- | --- |
| `npm run typecheck` | 0 | `tsc --noEmit` bez chýb |
| `npm test` | 0 | **34/34** pass, 0 fail. Vrátane explicitného provisioningu: cudzie UID je 403, provisionované UID je 200, produkcia a wildcard UID sú odmietnuté |
| `npm audit --audit-level=high` | 0 | `found 0 vulnerabilities` |
| `./gradlew test --no-daemon --offline` | 0 | **BUILD SUCCESSFUL** v 34 s, testy sa spustili. JUnit: 11 testov, 0 failures, 0 errors (`EfakturaClientTest` 5, `EfakturaSessionTest` 4, `ImportOperationTest` 1, `BridgeLiveTest` 1, 8.218 s) |
| `EFAKTURA_ENV=production` + provision UID/tenant/role | 1 | `Produkcia odmietla lokálny provisioning.` |
| `git apply --check integration/android-app.patch` | 0 | patch sadne na čistý strom. Aplikácia po kontrole ostala bez zmeny |
| izolovaný worktree, `gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` | 1 | konfigurácia patchu prešla, potom `SDK location not found`. APK, lint ani unit testy aplikácie sa nespustili |

Predošlý Gradle 8.10.2 problém test workera je opravený wrapperom 9.3.1. Handshake `Did not receive connection preamble within 10s` sa v logu stále objaví a task napriek tomu skončí úspešne. Nie je to pád testu.

`BridgeLiveTest` ide cez reálne HTTP. Oficiálna faktúra má `technicalPass=true`, slovenskú vrstvu `UNSUPPORTED` a text obsahuje `Legislatívna platnosť nebola overená.` `productionEligible=false`.

## NOT_RUN

- Lokálne `assembleDebug`, `:app:lintDebug` a Android unit testy: **NOT_RUN**. Príkaz spustený, zastal na chýbajúcom Android SDK. Nie je to zelený build.
- `GreetingScreenshotTest`: **NOT_RUN**. Nie je v Android CI filtri.
- Vzdialený GitHub Actions `.github/workflows/efaktura-android.yml` a `.github/workflows/efaktura-bridge.yml`: výsledok sa dopĺňa až po pushi tohto commitu. Kým beh neskončí, je **NOT_RUN**.
- Docker image a Docker smoke: **NOT_RUN** lokálne. `docker: command not found`. Workflow bridge ten krok má.
- Firebase Auth/App Check proti reálnemu projektu: **NOT_RUN**.
- Firebase emulator: **NOT_RUN**.
- SK ruleset ako Schematron: **NOT_RUN**. XLSX v1.13 je len súbor, vrstva ostáva `UNSUPPORTED`.
- Reálny poštár, jeho webhook a reporting FS: **NOT_RUN**.
- Aplikovanie `integration/android-app.patch` na `grokbranch`: **NOT_RUN** zámerne. Aplikácia ostala bez zmeny.
- Merge do `main` a produkčný deploy: **NOT_RUN** zámerne.

## Nesmie ísť von ako produkcia

Kým nie je certifikovaný poštár, SK ruleset sa nespúšťa a Firebase nie je zapojené na konkrétny projekt. Lokálny SQLite nie je distribuovaná produkčná databáza. Sandbox nesmie dostať produkčnú doručenku. `productionEligible` je vždy `false`. HMAC emulátor, sandbox a lokálny provisioning nesmú byť v produkčnom Android release. Debug cleartext je len pre loopback, `10.0.2.2` a RFC1918. Release `usesCleartextTraffic` nenastavuje.
