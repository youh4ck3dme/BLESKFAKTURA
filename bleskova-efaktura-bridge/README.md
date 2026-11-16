# bleskova-efaktura-bridge

Samostatný modul elektronickej faktúry pre Bleskovú Faktúru. Nie je to nová aplikácia, nie je legislatívne certifikovaný a bez reálneho poštára produkčne nedoručuje. `productionEligible` je vždy `false`.

Overenie zdrojov: 2026-11-16. Node 22, TypeScript 5.7, JDK 17, Saxon-HE 12.9, SchXslt 1.10.1, UBL 2.1, Peppol BIS Billing 3.0 May 2026 (schematron 3.0.21 / EN 16931 1.3.16). XML parser je `@xmldom/xmldom` **0.9.12** (aktuálny latest na npm registry v deň opravy).

## Čo beží lokálne

- Import UBL Invoice a CreditNote, karanténa CII ako `UNSUPPORTED_SYNTAX`.
- XSD + EN16931 + Peppol Schematron cez lokálny Java validátor. Slovenský XLSX FS SR v1.13 je len pripnutý, nespúšťa sa.
- Draft, validácia, atomické vystavenie, sandbox outbox, webhook s podpisom.
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

Seed je len lokálny a syntetický (identity z Peppol príkladu, uid `uid-buyer` / `uid-supplier`). `EFAKTURA_ENV=production` emulátor, sandbox aj tento seed odmietne a proces skončí. Overené 2026-11-16: health `environment=local`, import `Snippet1`, `technicalPass=true`, `productionEligible=false`.

Kotlin, vrátane volania reálneho API:

```bash
cd android/efaktura-client
./gradlew test
```

Android SDK tu nie je, knižnica je JVM modul. ViewModel a Room plán v `examples/android-integration` nie sú zapojené do aplikácie.

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
