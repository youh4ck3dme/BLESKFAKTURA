# bleskova-efaktura-bridge

Samostatný modul elektronickej faktúry pre Bleskovú Faktúru. Nie je to nová aplikácia, nie je legislatívne certifikovaný a bez reálneho poštára produkčne nedoručuje. `productionEligible` je vždy `false`.

Overenie zdrojov: 2026-11-16. Node 22, TypeScript 5.7, JDK 17, Saxon-HE 12.9, SchXslt 1.10.1, UBL 2.1, Peppol BIS Billing 3.0 May 2026 (schematron 3.0.21 / EN 16931 1.3.16). XML parser je `@xmldom/xmldom` **0.9.12** (aktuálny latest na npm registry v deň opravy).

## Čo beží lokálne

- Import UBL Invoice a CreditNote, karanténa CII ako `UNSUPPORTED_SYNTAX`.
- XSD + EN16931 + Peppol Schematron cez lokálny Java validátor. Slovenský XLSX FS SR v1.13 je len pripnutý, nespúšťa sa.
- Draft, validácia, atomické vystavenie, sandbox outbox, webhook s podpisom. Send ukladá frontu, úlohu a idempotentný výsledok v jednej transakcii. `UNKNOWN` ostáva vo fronte s backoffom. Novší webhook neprepíše starší výsledok workera.
- Lokálny SQLite súbor (`EFAKTURA_DATA_DIR`). Nie je to distribuovaná produkčná databáza.
- `UnconfiguredProvider` vracia chybu konfigurácie. Sandbox nikdy nerobí produkčnú doručenku.

Podporovaný profil je len `billing:01:1.0` a typy 380 / 381. Iné profily, CII a meny bez 2 desatinných miest sú odmietnuté. Právny rozsah §85o nie je automatický filter, pozri `packages/core/src/product-policy.ts`.

`X-Tenant-Id` musí sedieť s cestou. Členstvo uid vo firme overuje server, hlavička nie je oprávnenie. `POST /send` vracia doklad s vnoreným `delivery`.

Zmiešaná DPH sa počíta po sadzbách. 100 + 23 % a 100 + 10 % dáva základ 200, DPH **33.00** a brutto **233.00**.

## Príkazy od čistého checkoutu

Potrebné: Node 22 a JDK 17 (`java` aj `javac` sú v PATH).

```bash
cd bleskova-efaktura-bridge
npm ci
npm test
cp .env.example .env
npm start
```

V druhom termináli, kým server beží:

```bash
cd bleskova-efaktura-bridge
TOKEN=$(npm run -s tokens -- --uid uid-buyer --tenant tenant-buyer)
AUTH=$(node -e 'const t=JSON.parse(process.argv[1]); process.stdout.write(t.authorization)' "$TOKEN")
APP=$(node -e 'const t=JSON.parse(process.argv[1]); process.stdout.write(t.appCheck)' "$TOKEN")
curl -sS http://127.0.0.1:8088/health
curl -sS -H "Authorization: $AUTH" -H "X-Firebase-AppCheck: $APP" \
  -H "X-Tenant-Id: tenant-buyer" -H "Idempotency-Key: local-import-1" \
  -H "Content-Type: application/xml" \
  --data-binary @fixtures/peppol-official/base-example.xml \
  "http://127.0.0.1:8088/v1/tenants/tenant-buyer/documents/import?direction=INBOUND"
```

Seed je len lokálny a syntetický (identity z Peppol príkladu, uid `uid-buyer` / `uid-supplier` / `uid-reader`). Aktuálne UID z Androidu sa zaradí len explicitne cez `EFAKTURA_LOCAL_PROVISION_UID`, `EFAKTURA_LOCAL_PROVISION_TENANT` a `EFAKTURA_LOCAL_PROVISION_ROLE`, a len do `tenant-buyer` alebo `tenant-supplier`. Token členstvo nevytvorí. `EFAKTURA_ENV=production` emulátor, sandbox, seed aj tento provisioning odmietne a proces skončí.

Kotlin, vrátane volania reálneho API:

```bash
cd android/efaktura-client
./gradlew test
```

Wrapper je Gradle **9.3.1** (nie 8.10.2). 8.10.2 zabíjal test worker, keď na jeho socket prišlo HTTP `GET` (prvý bajt `G` = type tag 71) a worker potom hlásil connection refused. Oprava je [gradle/gradle#35175](https://github.com/gradle/gradle/issues/27801) v 9.3. Testy sa nespúšťajú ako úspech len preto, že existuje JUnit XML.

Android SDK v tomto prostredí nie je, knižnica je JVM modul. Integračný patch je v `integration/android-app.patch` a **nie je aplikovaný**. Návod: `integration/APPLY.md`. Android CI je `.github/workflows/efaktura-android.yml`: patch sa aplikuje len v jobe, potom `assembleDebug`, vybrané unit testy a `lintDebug`. `git apply --check` to nenahrádza.

## Podporované / nepodporované

| Scenár | Stav |
| --- | --- |
| UBL Invoice 380, profil billing 01 | import, validácia, issue, sandbox |
| UBL CreditNote 381 s referenciou | import a mapovanie |
| CII | karanténa, `UNSUPPORTED_SYNTAX` |
| Profil billing 02, iné type kódy | odmietnuté / nepodporované |
| SK schematron a reporting FS | nebeží |
| Reálny poštár | `PROVIDER_NOT_CONFIGURED` |
| SQLite | lokálny súbor, nie produkčný cluster |
