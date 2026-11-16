# Release readiness

Stav po opravách na vetve `grokbranch`. Produkt nie je legislatívne certifikovaný a produkčne nedoručuje. `productionEligible` zostáva `false`.

## Spustené 2026-11-16

- `npm test` v `bleskova-efaktura-bridge`: **27/27**, asi 42 s. Node v22.23.3. Z toho pôvodných 18 aj nové regresie (XML bomba v child procese, súbežný import, idempotencia po chybe, worker lease, SQLite reštart, tenant hlavička).
- Saxon-HE 12.9 + SchXslt 1.10.1: oficiálny `base-example.xml` je na XSD, EN16931 a Peppol VALID. Pokazený `PayableAmount` je EN16931 INVALID, `BR-CO-16`.
- `./gradlew test` v `android/efaktura-client`: **BUILD SUCCESSFUL**, 7 testov. 5 klientskych (vrátane `X-Tenant-Id` a vnoreného `delivery`), 1 ViewModel kľúč, 1 `BridgeLiveTest` proti reálnemu Node API (import, čítanie, originál, cudzí tenant 404, validácia, sandbox send, delivery). Live test 8.7 s. `technicalPass=true`, `productionEligible=false`, `productionReceipt=false`.
- Ručný smoke: `EFAKTURA_ENV=production` s emulátorom skončil exit 1. Lokálny `npm start` na porte 18088, `GET /health` ok, import `Snippet1` s `technicalPass=true` a `productionEligible=false`.
- `@xmldom/xmldom` je pripnutý na 0.9.12. Registry `dist-tag latest` v deň opravy bol 0.9.12, nie je deprecated.

## NOT_RUN

- Docker image: **NOT_RUN**. V prostredí nie je binárka `docker` ani daemon. Dockerfile je dvojstupňový (JDK 17 v build, JRE 17 v runtime), ale image sa tu nezostavil.
- Firebase Auth/App Check proti reálnemu projektu: **NOT_RUN**. Príklad v `docs/INTEGRATION.md` nie je zelený test. Lokálne HMAC tokeny produkcia odmieta.
- Firebase emulator (Firestore/Storage rules): **NOT_RUN**.
- Android Gradle Plugin a inštrumentačné testy: **NOT_RUN**, nie je Android SDK. Kotlin modul je JVM.
- SK ruleset ako Schematron: **NOT_RUN**. XLSX v1.13 je len súbor.
- Reálny poštár, jeho webhook a reporting FS: **NOT_RUN**.
- CII adapter: zámerne chýba.

## Nesmie ísť von ako produkcia

Kým nie je certifikovaný poštár, SK ruleset sa nespúšťa a Firebase nie je zapojené na konkrétny projekt. Lokálny SQLite nie je distribuovaná produkčná databáza. Sandbox nesmie dostať produkčnú doručenku.
