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

| Kontrola | Stav | Rozsah |
| --- | --- | --- |
| Backend | PASS | Lokálne: uzamknutá inštalácia, TypeScript build, 10/10 verifier testov |
| Android regresie, celý unit suite, lint, debug APK | NOT VERIFIED | Výsledok sa doplní po CI tejto vetvy |
| Dokumentačné odkazy a shell vstupy | NOT VERIFIED | Kontroly sa doplnia po dokončení úprav |
| Pôvodné drafty | NOT VERIFIED | Kontrolné súčty sa opätovne porovnajú pri odovzdaní |
| Fyzický telefón / fotoaparát / Google sign-in | NOT VERIFIED | V tejto revízii sa nespúšťajú |
| Živý cloudový ledger, Play nákup a distribuovaný release | NOT VERIFIED | Lokálne fakes/emulátory ich nenahrádzajú |
| AI v novej Appetize relácii | NOT VERIFIED | Každá relácia má vlastnú debug identitu |

Preskočený release nie je PASS. CI PR zámerne nedostáva živú Firebase konfiguráciu
ani signing secrets; jeho APK slúži na demo a automatizované lokálne testy.

## Zostávajúce funkčné obmedzenia

- Cloudová chyba rezervácie sa v repository zatiaľ mapuje na `InsufficientCredits`;
  UI preto nemusí rozlíšiť nedostatok kreditu od chyby siete/pravidiel.
- Lokálny demo ledger neuchováva perzistentné identifikátory rezervácií ako cloud.
  Obnova po násilnom ukončení procesu a súbežné cloudové zariadenia nie sú overené.
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

Vetvy po zlúčených PR #3/#4 sú kandidáti na voliteľné odstránenie po samostatnom
rozhodnutí vlastníka. Táto revízia nemení vzdialené vetvy, nastavenia ani históriu.
