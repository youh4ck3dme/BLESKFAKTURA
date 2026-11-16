# Release readiness

Stav po opravách atomického sendu, lookupu a prechodov doručenia na vetve `grokbranch`. Produkt nie je legislatívne certifikovaný a produkčne nedoručuje. `productionEligible` zostáva `false`.

## Spustené v tomto kole

- Po finálnej úprave (webhook nekopíruje starý `deliveryDetail`) prebehli tri súbory zvlášť, spolu **32/32**, exit 0: `bridge.test.js` + `delivery-atomicity.test.js` 23 testov za 42.5 s a `regressions.test.js` 9 testov za 0.4 s. Pred touto jednoriadkovou úpravou prešiel aj celý `npm test` (**32/32**, exit 0, test runner 42.1 s, príkaz vrátane `javac` a `tsc` 46 s). Node v22.23.3, JDK 17. Súčasťou je Saxon na oficiálnom `base-example.xml` a na `BR-CO-16` aj 5 nových regresií: rollback outboxu po injektovanej chybe a reštart, rollback idempotentného výsledku v tej istej transakcii, `UNKNOWN` → `UNKNOWN` → `PROVIDER_ACCEPTED` s backoffom a reštartom bez druhého submitu, dve SQLite spojenia (webhook `DELIVERED` počas submitu), terminálny lookup a duplicitný webhook.
- Kotlin JUnit XML po `./gradlew test --offline --no-daemon --rerun-tasks`: **7 testov, 0 failures, 0 errors**. `EfakturaClientTest` 5 (3.269 s), `ImportOperationTest` 1, `BridgeLiveTest` 1 (8.665 s) proti aktuálnemu `dist`. Gradle task `:test` potom skončil **exit 1**: test worker stratil IPC (`Unexpected type tag 71`, connection refused). Nie je to assertion failure a **nie je to BUILD SUCCESSFUL**.

## NOT_RUN

- Opakovaný ručný smoke `npm start`, curl a `EFAKTURA_ENV=production`: **NOT_RUN** v tomto kole.
- Docker image: **NOT_RUN**. V prostredí nie je daemon.
- Firebase Auth/App Check proti reálnemu projektu: **NOT_RUN**.
- Firebase emulator: **NOT_RUN**.
- Android Gradle Plugin a inštrumentačné testy: **NOT_RUN**, nie je Android SDK.
- SK ruleset ako Schematron: **NOT_RUN**. XLSX v1.13 je len súbor.
- Reálny poštár, jeho webhook a reporting FS: **NOT_RUN**.
- `npm audit`: **NOT_RUN** v tomto kole. Závislosti sa nemenili.
- CII adapter: zámerne chýba.

## Nesmie ísť von ako produkcia

Kým nie je certifikovaný poštár, SK ruleset sa nespúšťa a Firebase nie je zapojené na konkrétny projekt. Lokálny SQLite nie je distribuovaná produkčná databáza. Sandbox nesmie dostať produkčnú doručenku. `productionEligible` je vždy `false`.
