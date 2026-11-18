# Stav projektu a otvorené body

Technická revízia začala 18. 11. 2026 z `origin/main` **5230a3f**. Tento dokument je
aktuálny rozcestník; počty testov a živé merania zo starších revízií nie sú automaticky
výsledkami tejto vetvy. Postup buildu je v [lokálnom návode](local-verification.md),
architektúra v [dátových tokoch](architecture.md).

## Rozsah a ochrana práce

Údržba zachováva nové rozhrania dátovej vrstvy a dashboard/pending-purchase zmeny
z `9af69da` a `5230a3f`. Pôvodný checkout na `cdf5616` obsahuje štyri nezahrnuté
drafty: `docs/RELEASE_EXECUTION_STATUS.md`, `docs/play-console-declarations.md`,
`firebase.legal.json`, `scripts/stage-legal-pages.mjs`. Zostávajú v pôvodnej pracovnej
kópii; ich kontrolné súčty a celý stav sú uložené v lokálnom overovacom zázname.
Táto vetva ich automaticky nepreberá. Play deklarácie opravuje nezávisle podľa kódu.

Rozpočet je **0 €**. Táto údržba neaktivuje billing/Blaze, nemení App Check
enforcement ani podpisové kľúče a nenasadzuje žiadnu službu, pravidlá alebo vydanie.
Živý stav projektu sa pri tejto revízii nemení a bez nového čítania konzoly sa
neoznačuje za čerstvo overený.

## Nálezy a riešenie

| ID | Priorita | Dôkaz a dôsledok | Riešenie / overenie |
| --- | --- | --- | --- |
| M-01 | P1 | Main stratil executable bits skriptov; CI skončilo exit 126 pred Android buildom | Obnovené práva, kontrola vstupných skriptov v CI |
| M-02 | P1 | Revízia ignorovala neúspešný Result uloženia a opustila formulár | Zachovať formulár, umožniť retry so stabilnou identitou; regresné testy |
| M-03 | P1 | Výnimka rezervácie mohla nechať Scanning; oneskorený starý job mohol prepísať nový stav | Ohraničené vlastníctvo skenu, spracovanie chyby a cancellation; regresné testy |
| M-04 | P1 | Firestore diagnostika obsahovala UID, e-mail, cestu a surovú SDK správu | Iba operácia a konečný kód chyby; test redakcie |
| M-05 | P2 | README opisovalo starú identitu a neexistujúci AI tok; odkazy na nezahrnutý audit | Aktuálne návody a jeden kanonický stav projektu; kontrola odkazov v CI |
| M-06 | P2 | Release helper preferoval JDK 17, kým projektové kontroly používajú 21 | Zjednotený helper; shell kontrola bez podpisovania |
| M-07 | P2 | Právne texty sľubovali PDF časovač a bezpodmienečne okamžitý výmaz | Technický opis podľa kódu, právne a prevádzkové schválenie zostáva otvorené |
| M-08 | P2 | .gitignore nechráni ďalšie .env súbory a APK mimo build priečinkov | Rozšírené ignorovanie, .env.example zostáva verzované |

Príčina posledného zlyhania pôvodného main je doložená v
[CI 44245372738](https://github.com/youh4ck3dme/BLESKFAKTURA/actions/runs/44245372738).
Backend prešiel, Android sa vtedy nespustil a release bol preskočený; nejde o
úspešné Android overenie.

## Overenie tejto údržby

Prvý kompletný beh: [CI 44253693317](https://github.com/youh4ck3dme/BLESKFAKTURA/actions/runs/44253693317),
head **71aaddb3fc4ee7f97459b32b1023cc760b5db0f1**, 18. 11. 2026. Nasledujúca tabuľka
je dôkazom tejto konkrétnej revízie. Následné spresnenia hlášok a ich regresné testy
majú vlastný výsledok pri aktuálnom head v [draft PR #5](https://github.com/youh4ck3dme/BLESKFAKTURA/pull/5);
starý zelený beh sa nepreberá ako dôkaz nového kódu.

| Kontrola | Stav | Rozsah |
| --- | --- | --- |
| Backend | PASS | Lokálne: uzamknutá inštalácia, TypeScript build, 10/10 verifier testov |
| Android regresie a celý unit suite | PASS | 89 testov / 15 suites, 0 failures/errors/skipped v uvedenom CI |
| Lint a debug APK | PASS | 0 errors/fatal, 61 warnings a 1 hint; assembleDebug úspešný |
| Dokumentačné odkazy a shell vstupy | PASS | 53 lokálnych odkazov; syntax, executable bits a helper preflight; aj negatívna kontrola chýbajúceho cieľa/fragmentu |
| Pôvodné drafty | PASS | Zhodné SHA-256 štyroch súborov, git status aj cached/working diff oproti vstupu |
| Fyzický telefón / fotoaparát / Google sign-in | NOT VERIFIED | V tejto revízii sa nespúšťajú |
| Živý cloudový ledger, Play nákup a distribuovaný release | NOT VERIFIED | Lokálne fakes/emulátory ich nenahrádzajú |
| AI v novej Appetize relácii | NOT VERIFIED | Každá relácia má vlastnú debug identitu |

Preskočený release nie je PASS. CI PR zámerne nedostáva živú Firebase konfiguráciu
ani signing secrets; jeho APK slúži na demo a automatizované lokálne testy.

### Vyhodnotenie lintu

Existujúce upozornenia sa nepotláčali. Z 61 warnings je 39 návrhov novších verzií
Gradle/pluginov/závislostí; plošná aktualizácia bez kompatibilitného overenia nie je
súčasťou údržby. Ďalej sú tu 2 odporúčania AndroidX ExifInterface, 1 OldTargetApi,
6 odporúčaní ikon, 1 nepoužitý resource a 12 štýlových KTX/label odporúčaní.
Hint sa týka inicializácie poľa. Nejde o dôkaz aktuálneho pádu; prípadné zmeny SDK,
Exif podpory a cieľovej platformy potrebujú cielené regresné/runtime overenie.

### Pokrytie auditovaných oblastí

| Oblasť | Rozhodnutie a hranica dôkazu |
| --- | --- |
| Kamera a import | Zachovať TakePicture/Photo Picker a bounded decode; fyzické povolenia, odmietnutie a orientácie fotografií NOT VERIFIED |
| Validácia dát, centy a dátumy | Zachovať existujúce validácie; príslušné unit testy sú súčasťou uvedeného CI |
| AI, timeout, zrušenie a retry | Zachovať AI service; opraviť rezerváciu a vlastníctvo stavu; fakes/regresie PASS, živý cloud NOT VERIFIED |
| Uloženie a export | Opraviť neúspešný scan save a stabilné ID; PDF testy PASS, zdieľanie na telefóne NOT VERIFIED |
| Dashboard a čakajúce nákupy | Zachovať nový main a jeho regresie; žiadne pripísanie nákupu bez overenia |
| Súkromie a účet | Redakcia diagnostiky a presnejšie hlášky; retencia a kompletný serverový výmaz zostávajú blokéry |
| Backend a nákupy | Implementácia ponechaná, 10 testov PASS; deployment a skutočný nákup NOT VERIFIED |
| GitHub, CI a návody | Opravené vstupy, odkazy a historické tvrdenia; staré PR sa posudzujú samostatne |

## Zostávajúce funkčné obmedzenia

- Cloudová chyba rezervácie sa v repository zatiaľ mapuje na `InsufficientCredits`;
  UI preto nemusí rozlíšiť nedostatok kreditu od chyby siete/pravidiel.
- Lokálny demo ledger neuchováva perzistentné identifikátory rezervácií ako cloud.
  Obnova po násilnom ukončení procesu a súbežné cloudové zariadenia nie sú overené.
- Cloudový rollback má zatiaľ návratový typ Unit a môže interne spracovať chybu bez
  potvrdenia zmeny zostatku. UI preto potvrdzuje iba vyžiadanie refundu; potvrdený
  výsledok vyžaduje synchronizovaný stav účtu a živý test.
- Room zápis a synchronizácia nie sú distribuovaná transakcia. Chybu cloudového
  zápisu treba overiť aj pri obnove spojenia a snapshot listeneroch.
- Odhlásenie neodstraňuje všetky archivované fotografie. Výmaz účtu je viac krokov
  a môže pri chybe siete alebo opätovného prihlásenia zostať čiastočný; nie je to
  dôkaz kompletnej serverovej retencie alebo výmazu osirelých súborov.
- Dostupnosť konkrétneho AI modelu, kvóty a podmienky nie sú garantované lokálnym
  úspechom. Pre interné skúšky používaj syntetické doklady.

## Blokéry interného/produkčného vydania

1. **Prevádzka backendu:** nasadenie Functions a nákupný E2E test sú mimo povoleného
   rozpočtu a tejto údržby. [Backendový návod](../functions/README.md) odlišuje
   implementáciu od nasadenia. Žiadny úspešný nákup sa tu nedeklaruje.
2. **Distribúcia:** vlastník musí potvrdiť existujúce upload credentials a distribučný
   certifikát, Play nastavenia a testovací track. [Release návod](release-signing-and-smoke-test.md)
   nevyžaduje vytvorenie nového kľúča.
3. **Právne/obchodné údaje:** vlastník potvrdí prevádzkovateľa, kontakt, účely,
   retenciu, vybavovanie žiadostí a živé HTTPS stránky pred zverejnením. Právne HTML
   sú technický návrh, nie schválené vyhlásenie. Celý priečinok docs sa nepublikuje.
4. **Podmienky AI:** aktuálne [Gemini API podmienky](https://ai.google.dev/gemini-api/terms)
   obsahujú osobitné pravidlá dostupnosti klientov v EHP/Švajčiarsku/UK a spracúvania
   dát. Pred sprístupnením testerom musí vlastník overiť použiteľnosť svojho plánu
   a služby; táto revízia preto nesľubuje bezplatnú produkčnú AI prevádzku v EHP
   a neaktivuje platenú alternatívu.
5. **Dátová bezpečnosť v Play:** [návrh deklarácií](play-console-declarations.md)
   treba potvrdiť podľa skutočne distribuovaného buildu a nasadených služieb.

## GitHub inventúra a návrh ďalšieho postupu

K začiatku revízie: žiadne issues, tagy, releasy alebo milestones. Otvorené PR:

- [PR #1](https://github.com/youh4ck3dme/BLESKFAKTURA/pull/1) cieli na inú vetvu než
  main. Pred rozhodnutím porovnať pôvodný problém a dnešné API použitie; samotný
  návrh desugaringu nie je dôkazom aktuálnej runtime chyby. Nezatvára sa automaticky.
- [PR #2](https://github.com/youh4ck3dme/BLESKFAKTURA/pull/2) obsahuje širšiu zmenu
  OCR/variantov. Potrebuje samostatné architektonické a funkčné review; nehromadí sa
  do tejto údržby.

Na samostatné rozhodnutie o voliteľnom odstránení zostávajú tieto merged vetvy:

- `codex/android-release-fixes-20261117` — zlúčené cez PR #3;
- `codex/ai-deadline-privacy-jdk21-20261117` — zlúčené cez PR #4.

Nič z uvedeného sa nemaže. Údržba pridáva iba vlastnú PR vetvu; nemení existujúce
vzdialené vetvy, prístupy, ochranné nastavenia ani históriu.
