# Runbook

## Lokálny štart

Od čistého checkoutu. Node 22, JDK 17.

```bash
cd bleskova-efaktura-bridge
npm ci
npm test
cp .env.example .env
npm start
```

`npm start` načíta `.env`, založí SQLite v `./data/bridge.sqlite`, nasadí len lokálny syntetický seed a spustí jedného workera. Ukončenie je SIGINT/SIGTERM: server sa zavrie a store sa zatvorí. Worker drží lease, druhý tick ten istý job neodošle znova. Expirovaná lease po submite ide na lookup, nie na druhý submit.

Health: `GET /health` bez tokenu. Overené `{"status":"ok","environment":"local"}`.

Token (lokálny HMAC, nie Firebase):

```bash
npm run -s tokens -- --uid uid-buyer --tenant tenant-buyer
npm run -s tokens -- --uid uid-supplier --tenant tenant-supplier
```

Príkaz v `EFAKTURA_ENV=production` skončí chybou a token nevytvorí. Výstup neobsahuje tajomstvo, len podpísaný token.

Import oficiálnej ukážky ako odberateľ a sandbox ako dodávateľ:

```bash
TOKEN=$(npm run -s tokens -- --uid uid-buyer --tenant tenant-buyer)
AUTH=$(node -e 'const t=JSON.parse(process.argv[1]); process.stdout.write(t.authorization)' "$TOKEN")
APP=$(node -e 'const t=JSON.parse(process.argv[1]); process.stdout.write(t.appCheck)' "$TOKEN")
curl -sS -H "Authorization: $AUTH" -H "X-Firebase-AppCheck: $APP" \
  -H "X-Tenant-Id: tenant-buyer" -H "Idempotency-Key: local-import-1" \
  -H "Content-Type: application/xml" \
  --data-binary @fixtures/peppol-official/base-example.xml \
  "http://127.0.0.1:8088/v1/tenants/tenant-buyer/documents/import?direction=INBOUND"
```

Opakovanie toho istého kľúča vráti ten istý doklad. Iný obsah s tým istým kľúčom je `IDEMPOTENCY_PAYLOAD_MISMATCH`. Po deterministickej chybe sa kľúč nezasekne v `IN_PROGRESS`. Obnoviteľná chyba ide do `RETRYABLE`. Neistý výsledok poštára je `UNKNOWN` a ďalší tick robí lookup, nie druhý submit.

Sandbox issue/send/delivery prešiel aj z Kotlin klienta (`BridgeLiveTest`). `productionReceipt` bol `false`.

Mutácie potrebujú `Authorization`, `X-Firebase-AppCheck` a `X-Tenant-Id`. Cesta `/v1/tenants/{id}` musí sedieť s hlavičkou. Ani hlavička, ani cesta nie sú členstvo.

## Keď validátor spadne

Ak Java/Saxon nebeží, report je `UNAVAILABLE` a send aj issue ostanú zatvorené. Log obsahuje `correlationId` a kód, nie XML, token ani tajomstvo.

## Timeout poštára

Stav `UNKNOWN` nie je koniec jobu. Po timeoute worker uloží neistý outbox a hneď urobí lookup. Ak lookup vráti znova `UNKNOWN`, job ostane `UNCERTAIN` s rastúcim backoffom (1 s, 2 s, 4 s, …, strop 32 s) a ďalší tick pred `leaseUntil` nič neclaimne. Nevytvára sa druhý submit ani druhý doklad. Job sa uzavrie až pri konečnom stave.

`QUEUED`, outbox a idempotentný výsledok sendu sa zapisujú v jednej databázovej transakcii. Zlyhanie insertu úlohy vráti aj stav dokladu. Reštart potom retry urobí znova, nenechá `QUEUED` bez jobu.

Webhook a worker používajú tie isté pravidlá prechodu. V transakcii čítajú aktuálny riadok a zapisujú ho len cez CAS verzie `expected + 1`. Pamäťový mutex na to nestačí, lebo dve SQLite spojenia ho nezdieľajú. `DELIVERED`, `REJECTED` a `FAILED` sa automaticky nevracajú späť. Starší výsledok submitu neprepíše novší webhook. Duplicitný rovnaký stav nemení verziu. Sandbox timeout je `X-Sandbox-Scenario: timeout` a funguje iba pri `local-sandbox`.

## Retencia

Originál, idempotencia, čísla a outbox sú v lokálnom SQLite. Reštart procesu ich nestratí. Zmazanie účtu nesmie sľúbiť okamžitý výmaz účtovného archívu. SQLite súbor nie je náhrada za produkčnú infraštruktúru.

## Čoho sa nedotýkať pri release aplikácie

applicationId, upload keystore, Firebase projekt, existujúca Room schéma, kým nie je schválená migrácia z `docs/INTEGRATION.md`. Integračný patch ostáva neaplikovaný.
