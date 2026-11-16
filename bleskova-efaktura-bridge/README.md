# bleskova-efaktura-bridge

Samostatný modul elektronickej faktúry pre Bleskovú Faktúru. Nie je to nová aplikácia, nie je legislatívne certifikovaný a bez reálneho poštára produkčne nedoručuje.

Overenie zdrojov: 2026-11-16. Node 22, TypeScript 5.7, JDK 17, Saxon-HE 12.9, SchXslt 1.10.1, UBL 2.1, Peppol BIS Billing 3.0 May 2026 (schematron 3.0.21 / EN 16931 1.3.16).

## Čo beží lokálne

- Import UBL Invoice a CreditNote, karanténa CII ako `UNSUPPORTED_SYNTAX`.
- XSD + EN16931 + Peppol Schematron cez lokálny Java validátor. Slovenský XLSX FS SR v1.13 je len pripnutý, nespúšťa sa. Preto report nie je „plne validný“ a `productionEligible` je false.
- Draft, validácia, atomické vystavenie, sandbox outbox, webhook s podpisom.
- `UnconfiguredProvider` vracia chybu konfigurácie. Sandbox nikdy nerobí produkčnú doručenku.

Podporovaný profil je len `billing:01:1.0` a typy 380 / 381. Iné profily, CII a meny bez 2 desatinných miest sú odmietnuté ako nepodporované. Právny rozsah §85o (výnimky, prechodné obdobie 2026, nie každá faktúra každého používateľa) je v `packages/core/src/product-policy.ts` a nie je automatický filter.

## Príkazy

```bash
cd bleskova-efaktura-bridge
npm ci
npm test
cp .env.example .env
npm start
```

API počúva na porte 8088. Lokálny režim berie iba emulátorové HMAC tokeny z `.env.example`. Produkcia (`EFAKTURA_ENV=production`) emulátor aj sandbox odmietne a nenaštartuje ich.

Kotlin klient:

```bash
cd android/efaktura-client
gradle test
```

Android SDK v tomto prostredí nie je, preto je knižnica JVM modul s Retrofit/Moshi/KSP, nie `com.android.library`. ViewModel a Room plán sú v `examples/android-integration` a nie sú zapojené do aplikácie.

## Podporované / nepodporované

| Scenár | Stav |
| --- | --- |
| UBL Invoice 380, profil billing 01 | import, validácia, issue, sandbox |
| UBL CreditNote 381 s referenciou | import a mapovanie |
| CII | karanténa, `UNSUPPORTED_SYNTAX` |
| Profil billing 02, iné type kódy | `UNSUPPORTED_PROFILE` / `UNSUPPORTED_SCENARIO` |
| SK schematron a reporting FS | nebeží |
| Reálny poštár | port + `PROVIDER_NOT_CONFIGURED` |

Zmiešaná DPH sa počíta po sadzbách. Syntetický príklad 100 + 23 % a 100 + 10 % dáva základ 200, DPH 33.00 a brutto 233.00. Číslo „DPH 28“ zo zadania je s týmito sadzbami aritmeticky nezlučiteľné (23 + 10 = 33), modul ho preto nezaokrúhľuje jednou sadzbou na celok.
