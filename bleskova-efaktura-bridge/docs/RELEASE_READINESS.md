# Release readiness

Stav k implementácii na vetve `grokbranch`. Produkt nie je legislatívne certifikovaný a produkčne nedoručuje.

## Spustené

- `npm test` v `bleskova-efaktura-bridge`: 18/18 prešlo (Node 22).
- Súčasťou je skutočný Saxon-HE 12.9 + SchXslt 1.10.1 beh na oficiálnom `base-example.xml` (XSD, EN16931, Peppol = VALID) a na tom istom doklade s pokazeným `PayableAmount` (EN16931 INVALID, `BR-CO-16`).
- Round-trip oficiálnej faktúry cez serializer prešiel technickou validáciou (`technicalPass=true`) a sandbox issue/send neoznačil `productionReceipt`.
- Kotlin `gradle test` v `android/efaktura-client`: BUILD SUCCESSFUL, 4 testy (401/403/429 bez opakovania mutácie, desatinný string, transport, cancellation). Gradle 8.10.2, Kotlin 2.2.10, JDK 17.

## NOT_RUN / blokery

- Firebase Auth/App Check proti reálnemu projektu: NOT_RUN. Lokálne sú len HMAC tokeny, ktoré produkcia odmieta.
- Firebase emulator (Firestore/Storage rules): NOT_RUN. V repe sú `firestore.rules` a `storage.rules` a unit test sémantiky. Admin SDK autorizácia je v službe otestovaná na pamäťovom store, nie cez emulátor.
- Android Gradle Plugin a inštrumentačné testy: NOT_RUN, v prostredí nie je Android SDK.
- SK ruleset ako Schematron: NOT_RUN. XLSX v1.13 je pripnutý, nespustený. `productionEligible=false`.
- Reálny poštár, webhook jeho dokumentácie, reporting FS: NOT_RUN.
- CII adapter: zámerne chýba, syntax ide do karantény.

## Nesmie ísť von ako produkcia

Kým nie je vybraný certifikovaný poštár, SK ruleset sa nespúšťa a Firebase produkčné overenie nie je zapojené na konkrétny projekt.
