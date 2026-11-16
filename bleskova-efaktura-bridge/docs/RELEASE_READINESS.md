# Release readiness

Stav po oprave Gradle test workera a neaplikovanom Android patchi na vetve `grokbranch`. Produkt nie je legislatívne certifikovaný a produkčne nedoručuje. `productionEligible` zostáva `false`.

## Spustené v tomto kole

Prostredie: Node v22.23.3, OpenJDK 17.0.20.1, Gradle wrapper 9.3.1. Android SDK nie je. Docker nie je.

| Príkaz | Exit | Výsledok |
| --- | --- | --- |
| `npm run typecheck` | 0 | `tsc --noEmit` bez chýb |
| `npm test` | 0 | **33/33** pass, 0 fail. Vrátane host defaultu a odmietnutia `EFAKTURA_HOST=http://evil` |
| `npm audit --audit-level=high` | 0 | `found 0 vulnerabilities` |
| `./gradlew test --no-daemon` (Gradle 9.3.1) | 0 | **BUILD SUCCESSFUL** v 50 s, testy sa naozaj spustili. JUnit XML: 10 testov, 0 failures, 0 errors (`EfakturaClientTest` 5, `EfakturaSessionTest` 3, `ImportOperationTest` 1, `BridgeLiveTest` 1, 8.689 s). Druhý beh `--offline --no-daemon` bol UP-TO-DATE, tiež exit 0 |
| `EFAKTURA_ENV=production` + emulator + `local-sandbox` + seed | 1 | `Produkcia odmietla emulátor alebo sandbox provider.` |

Pred opravou ten istý strom na Gradle **8.10.2** skončil `:test` exit 1. JUnit XML sa buď nenapísal, alebo by sa nesmel brať ako úspech tasku. Príčina a log: `reports/gradle-worker-diagnosis.md`.

`BridgeLiveTest` ide cez reálne HTTP, autentifikáciu aj tenant hlavičku. Oficiálna faktúra má `technicalPass=true` a slovenskú vrstvu `UNSUPPORTED`. `productionEligible=false`.

## NOT_RUN

- Vzdialený GitHub Actions beh workflow `.github/workflows/efaktura-bridge.yml`: **NOT_RUN**. Konfigurácia je v repozitári, beh sa nespúšťal a výsledok sa nečakal.
- Docker image a Docker smoke: **NOT_RUN**. `docker: command not found`. Workflow ten krok má, lokálne sa nespustil.
- Android Gradle Plugin, `assembleDebug` a inštrumentačné testy: **NOT_RUN**. Nie je Android SDK. AGP kompatibilita patchu nie je overená.
- Firebase Auth/App Check proti reálnemu projektu: **NOT_RUN**.
- Firebase emulator: **NOT_RUN**.
- SK ruleset ako Schematron: **NOT_RUN**. XLSX v1.13 je len súbor, vrstva ostáva `UNSUPPORTED`.
- Reálny poštár, jeho webhook a reporting FS: **NOT_RUN**.
- Aplikovanie `integration/android-app.patch`: **NOT_RUN** zámerne. Aplikácia ostala bez zmeny.
- Merge do `main` a produkčný deploy: **NOT_RUN** zámerne.

## Nesmie ísť von ako produkcia

Kým nie je certifikovaný poštár, SK ruleset sa nespúšťa a Firebase nie je zapojené na konkrétny projekt. Lokálny SQLite nie je distribuovaná produkčná databáza. Sandbox nesmie dostať produkčnú doručenku. `productionEligible` je vždy `false`. HMAC emulátor a sandbox konfigurácia nesmú byť v produkčnom Android release. Patch to v release vetve drží vypnuté, ale patch sám nie je aplikovaný.
