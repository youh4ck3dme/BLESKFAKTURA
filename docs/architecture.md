# Architektúra a dátové toky

Technický opis vetvy odvodenej z `5230a3f` (18. 11. 2026). Výsledky vykonaných
kontrol a blokéry patria do [stavu projektu](project-status.md).

## Android

`MainActivity` inicializuje Firebase/App Check pred vytvorením Compose UI a
`InvoiceViewModel`. Navigácia prepína prihlásenie, dashboard, skener, cenník a nastavenia.
ViewModel závisí od `InvoiceDataGateway`, `BillingGateway` a `InvoiceAiExtractor`;
testy používajú fakes, produkčné implementácie sú `InvoiceRepository`,
`PlayBillingManager` a `InvoiceAiService`.

`namespace = com.example` je Kotlin/Android namespace; identita inštalovanej aplikácie
je `applicationId = sk.bizagent.bleskovafaktura`. Zmena namespace nie je súčasťou údržby.

## Skenovanie a uloženie

1. `ScanScreen` žiada povolenie fotoaparátu pri fotení; `TakePicture` zapisuje cez
   `FileProvider`. Galéria používa systémový Photo Picker.
2. `ReceiptImageProcessor` dekóduje obrázok s obmedzeným rozlíšením a upraví orientáciu.
3. ViewModel rezervuje kredit pre konkrétneho vlastníka a spustí AI extrakciu.
4. Firebase AI Logic dostane obrázok a požiadavku na štruktúrovaný JSON.
   `InvoiceAiService` aktuálne používa `gemini-3.5-flash-lite`, MINIMAL thinking,
   limit 4096 výstupných tokenov a lokálny deadline 60 sekúnd.
5. Platná odpoveď prejde do revízie; pri neúspechu sa vyžiada uvoľnenie rezervácie. Zrušenie sa nesmie
   zameniť za úspech ani zverejniť výsledok predchádzajúceho skenu do nového skenu.
6. Používateľ upraví a potvrdí údaje. Fotografia sa archivuje lokálne, zápis ide cez
   repository. Revízia sa zatvorí po úspešnom výsledku uloženia; pri chybe zostane na opakovanie.

Kredit sa účtuje za úspešnú AI analýzu pri prechode do revízie, nie až za uloženie
dokladu. Oprava chyby uloženia nesmie vytvoriť druhý sken ani druhé odpočítanie kreditu.

## Ukladanie a synchronizácia

Room databáza `bleskova_faktura_db` má schému verzie 2 a migráciu 1 → 2.
Faktúry a štatistiky sú oddelené podľa `userId`; sumy majú celočíselné centové polia
a kompatibilné pôvodné Double polia. Fotografie sú v súkromnom `filesDir/receipts`.

Cloudový účet používa Firebase Auth a pomenovanú Firestore databázu určenú resource
`firestore_database_id`. Synchronizované štruktúrované doklady sú v
`users/{uid}/invoices/{invoiceId}`; fotografia sa do Firestore neodosiela.
Repository drží lokálnu kópiu a používa snapshot listenery. Zápis do Room a cloudový
zápis nie sú jedna distribuovaná transakcia; zlyhanie synchronizácie treba čítať
spolu s upozornením `syncError`, nie ako záruku dokončeného cloudového zápisu.

Demo prihlásenie je dostupné iba v debug variante. Používa lokálneho používateľa,
Room a počiatočných 10 kreditov. Nakonfigurovaný debug môže aj v demo režime volať
živé AI; demo prihlásenie samo osebe neznamená offline OCR.

## Kredity a nákupy

Cloudová rezervácia používa Firestore transakciu, `activeReservationId` a kontrolu
vlastníka. Lokálny demo ledger používa Room a mutex; nepreukazuje tým správanie
produkčných pravidiel, súbežných zariadení ani obnovy po páde procesu.

`PlayBillingManager` načíta ceny z Google Play, sleduje čakajúce/neoverené nákupy a
posiela nákup na callable `verifyPlayPurchase`. Stav z klienta nepripisuje kredity
bez serverového overenia. Hash väzby Firebase UID sa porovná s údajom vráteným
Google Play. Backend eviduje tokeny idempotentne; RTDN opätovne overuje stav v Play.
Podrobnosti a testy sú v [backendovom návode](../functions/README.md).

## Súkromie a životný cyklus

PDF sa generuje lokálne a zdieľa až cez vedomú akciu používateľa. Staršie exporty
sa čistia pri ďalšom exporte a odhlásení; neexistuje 24-hodinový PDF časovač.
Odhlásenie čistí lokálnu databázovú kópiu, dočasnú kameru a PDF, ale nezaručuje
odstránenie všetkých archivovaných fotografií. Výmaz účtu maže známe lokálne
snímky, Firestore a potom Auth; pri chybe môže byť čiastočný.

Zálohovanie a prenos citlivých súborov sú obmedzené manifestom a XML pravidlami.
Tieto technické opatrenia nie sú potvrdením právnej deklarácie ani účinného
vymazania na všetkých zariadeniach; pozri [stav projektu](project-status.md).


<a id="lokalne-ocr"></a>

## Lokálne OCR

Používateľ zapína lokálne OCR výslovne na obrazovke pripravenej fotografie.
Predvolený cloudový režim zostáva zachovaný. Lokálny tok používa
`LocalReceiptOcr` a zabalený latinský model `com.google.mlkit:text-recognition:16.0.1`;
žiadny automatický prechod z lokálnej chyby do cloudového AI neexistuje.
`InvoiceViewModel` pri lokálnom čítaní nevolá rezerváciu, commit ani refund kreditu.
Vyžaduje sa aktuálny profil; release naďalej neposkytuje debug demo prihlásenie.
Uloženie do Room/Firestore sa riadi existujúcim profilom, nie voľbou OCR.

Parser podporuje EUR doklady, slovenské a vybrané medzinárodné formáty čísiel/dátumov,
rozlišuje dodávateľa a odberateľa a preferuje označenú sumu k úhrade.
Pri nejednoznačných sumách alebo dátumoch necháva pole na ručnú kontrolu.
Záporné/nulové explicitné sumy, sumy nad 1 000 000 a cudzie meny odmieta;
neprepočítava ich na EUR. DPH sa z OCR celkovej sumy neodhaduje. Revízia zachováva
neznámu sadzbu a sumu DPH ako null; používateľ môže doplniť hodnoty alebo výslovne
použiť prepočet. Varovania sú súčasťou revízie, surový rozpoznaný text sa neloguje.

Vstup parsera je ohraničený na 200 000 znakov, 3000 riadkov a 2000 znakov na riadok.
Obrázok zostáva ohraničený na 1600 px na dlhšej strane; rozmazanie, perspektíva,
drobné písmo, viac dokladov na fotografii a zložité stĺpce môžu znížiť presnosť.
Zachovanie limitu pamäte nie je dôkaz bezchybného rozpoznávania všetkých bločkov.
SDK dostáva vlastnú kópiu bitmapy. Coroutine cancellation sa propaguje; keďže
zrušenie await nezruší natívny Task, kópia a recognizer sa uvoľnia až po skončení
Tasku. Pokiaľ starý natívny Task dobieha, ďalší lokálny pokus vráti čitateľnú
chybu bez vytvorenia ďalšieho recognizera alebo kópie obrázka. Vlastný 60-sekundový deadline vracia Timeout, nadradený deadline sa propaguje.

Podľa [dokumentácie modelu](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
je model zabalený priamo v aplikácii, čo zväčšuje APK. Rozpoznávané vstupy a výsledky
zostávajú podľa [ML Kit privacy](https://developers.google.com/ml-kit/terms) v zariadení,
ale SDK môže odosielať diagnostické a prevádzkové metriky. Pred vydaním treba
zosúladiť Play Data safety podľa [ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
Tvrdenie „celá aplikácia nikdy nekomunikuje so sieťou“ preto neplatí.

Testy odlišujú parser nad syntetickým textom, SDK Task seam, ViewModel/Compose
s fake recognizerom a skutočný zabalený model v Android instrumentácii bez siete.
Natívny CI test používa syntetický tlačený doklad; nepotvrdzuje fyzický fotoaparát,
reálne účtovné doklady, Google OAuth ani cloudové pripísanie kreditu.

API30 instrumentácia nepotvrdzuje 16 KB kompatibilitu natívnych knižníc ani podpísaný Play release; tie zostávajú samostatnými release kontrolami.
