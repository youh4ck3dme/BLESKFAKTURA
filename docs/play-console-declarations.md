# Google Play Console - Blesková Faktúra

Overené 16. 11. 2026. Package: `sk.bizagent.bleskovafaktura`. Tento dokument rozlišuje bezplatnú internú zostavu a budúcu platenú verziu. Presnú verziu a výsledok testov čítajte z aktuálneho release reportu.

## 1. Interné testovanie - čo je skutočne potrebné

- Play Console > Testovanie a vydávanie > Testovanie > Interné testovanie: nahrať AAB podpísaný správnym upload kľúčom, vybrať testerov, skontrolovať a zverejniť interné vydanie.
- Interný test má limit 100 testerov. Testeri potrebujú Google účet, povolenie v zvolenom zozname a následné prihlásenie do testu cez opt-in odkaz. Nainštalujú aplikáciu z Google Play pod tým istým účtom.
- Pred prvým interným vydaním nemusí byť dokončený celý záznam v obchode. Pre výlučne interný track sa nevyžaduje Data safety formulár; požiadavky na korektné spracúvanie údajov tým nezanikajú.
- Podmienka 12 testerov počas 14 dní patrí k žiadosti o produkčný prístup na príslušnom osobnom účte, nie k spusteniu interného testu.
- Bezplatná interná zostava neponúka kredity ani nákupy. Nie je potrebné vytvoriť nákupné produkty ani nasadiť Functions iba na testovanie lokálneho flow.

Zdroje: [Interné testovanie](https://support.google.com/googleplay/android-developer/answer/9845334), [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [Produkčný prístup](https://support.google.com/googleplay/android-developer/answer/14151465).

## 2. Firebase Auth, podpisy a App Check

- Firebase projekt: `gen-lang-client-0115180408`; Android app: `1:356987646276:android:45325d3dea6c248380e711`.
- Firestore DB: `ai-studio-android-bleskovf-056698ab-4e20-4c12-b4ec-52ee9d53a81d`, lokalita `europe-west2`. Nezamieňať ju s predvolenou databázou `(default)`.
- Authentication > Sign-in method > Google: zapnutý poskytovateľ; Android OAuth klient musí zodpovedať package a podpisu, pod ktorým je aplikácia nainštalovaná.
- Play Console > Integrita aplikácie: odlíšiť **Play App Signing certificate** od **upload certificate**. Inštaláciu z Play podpisuje Play App Signing kľúč. Upload kľúč slúži na prijatie AAB pri nahratí.
- Firebase Project settings > Android app: registrovať potrebný SHA-1 pre Google Sign-In aj SHA-256 pre App Check. Po zmenách stiahnuť aktuálny `google-services.json` do modulu `app`; neinštalovať konfiguráciu cudzieho Firebase projektu.
- Firebase App Check > Android app > Play Integrity: rovnaký Cloud projekt ako v Play Console. Zachovať vynucovanie; chybný podpis sa neopravuje vypnutím ochrany.
- Side-load debug APK s debug podpisom nie je ekvivalent inštalácie release cez Google Play. Debug provider je iba vývojový nástroj; jeho token nepublikovať.

Zdroje: [Podpisovanie](https://developer.android.com/studio/publish/app-signing), [Google Sign-In](https://firebase.google.com/docs/auth/android/google-signin), [App Check Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider).

## 3. Cloud AI - technická kvóta nie je povolenie na každú distribúciu

- Model `gemini-3.8-flash` je podporovaný a technicky dostupný aj v obmedzenom bezplatnom programe Gemini Developer API.
- Aktuálne Gemini Additional Terms však pri sprístupnení API klienta používateľom v EEA, Švajčiarsku alebo UK vyžadujú **Paid Services**. Prístup Gemini API sa za takú službu považuje cez projekt s aktívnym Cloud Billing účtom. Bezplatný Spark projekt preto nie je automaticky prípustnou cestou pre distribúciu cloudového skenovania slovenským testerom.
- Kým nie je zvolená a overená prípustná cloudová prevádzka, interná zostava má používať lokálny postup alebo testovacie doklady bez cloudového prenosu podľa aktuálnej implementácie. Nesľubovať úspešný cloudový AI test bez jeho vykonania.
- Bezplatná kvóta má limity a môže vracať HTTP 429. Použitie plateného programu vyžaduje výber aktívneho billing účtu a nastavenie nákladových kontrol. Rozpočtový alert nie je finančný strop.
- Firebase AI Logic používa vlastnú používateľskú kvótu, predvolene 100 RPM; nižšia testovacia kvóta sa nastavuje v Google Cloud Console > APIs & Services > Firebase AI Logic API > Quotas. Výsledná kapacita je obmedzená aj kvótou poskytovateľa modelu.

Zdroje: [Modely](https://firebase.google.com/docs/ai-logic/models), [Ceny](https://firebase.google.com/docs/ai-logic/pricing), [Kvóty](https://firebase.google.com/docs/ai-logic/quotas), [Gemini podmienky](https://ai.google.dev/gemini-api/terms).

## 4. Právne stránky pred zberom reálnych dát a širším vydaním

- `docs/privacy-policy.html` a `docs/account-deletion.html` sú šablóny, nie potvrdené publikované zásady. Vyplniť prevádzkovateľa, adresu, kontakt, skutočný AI režim, právne základy, príjemcov/prenosy, retenčné lehoty a postup spracovania žiadostí.
- `node scripts/stage-legal-pages.mjs` odmietne nevyriešené polia `{{...}}` a označenie návrhu. Publikovať iba dve HTML cez `firebase.legal.json`; interný priečinok `docs/` sa nikdy nepublikuje celý.
- Cieľové adresy po nasadení: `https://bleskova-faktura-legal-356987.web.app/privacy-policy.html` a `https://bleskova-faktura-legal-356987.web.app/account-deletion.html`. Overiť HTTP 200 a obsah, potom nastaviť živú adresu privacy v aplikácii a obe adresy v príslušných poliach Console.
- Výmaz účtu sa začína v nastaveniach. Dokončenie vyžaduje sieť, môže vyžadovať nové prihlásenie a musí byť potvrdené výsledkom. Neuvádzať nepodložený sľub okamžitého výmazu ani zmazania PDF presne po 24 hodinách.
- Hash nákupného tokenu spojený s UID/objednávkou je pseudonymný záznam, nie anonymný údaj. Desaťročná účtovná lehota sa nesmie automaticky priradiť všetkým technickým tokenom.

Zdroje: [User Data](https://support.google.com/googleplay/android-developer/answer/10144311), [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111).

## 5. Data safety - podklady pre closed/open/production

Výlučne interná distribúcia je z formulára oslobodená. Pred prechodom na iný track odpovede zostaviť podľa všetkých aktívnych variantov a reálnych SDK tokov:

- **Personal info:** meno, e-mail, User IDs pri Google/Firebase prihlásení; funkčnosť aplikácie a správa účtu.
- **Doklady:** údaje o dodávateľovi, dátumoch a sumách ukladané vo Firestore; podľa znenia formulára finančné údaje a obsah dokumentov. Samotná lokálna kópia bez prenosu nie je cloudový zber.
- **Photos:** označiť prenos snímok, ak daný variant používa cloudovú AI. Lokálne OCR bez odosielania nesmie byť deklarované ako rovnaký cloudový tok.
- **Device or other IDs / bezpečnostné údaje:** zohľadniť skutočný zber SDK App Check/Play Integrity.
- **Purchase history:** iba ak je zapnutá verzia zbierajúca záznamy o nákupoch; neprezentovať vypnutý internal nákupný flow ako uskutočnené platby.
- Prenos spracovateľovi sa vo formulári nemusí klasifikovať ako **Shared**, ak spĺňa výnimku pre poskytovateľa služieb. Rozhodnutie vychádza zo zmluvy a použitia, nie zo samotného názvu Firebase.
- Uviesť šifrovaný prenos, dostupnú cestu výmazu a pravdivé retenčné výnimky. Netvrdiť automaticky, že všetky údaje sú iba krátkodobo spracúvané v pamäti.

Zdroj: [Data safety formulár](https://support.google.com/googleplay/android-developer/answer/10787469).

## 6. App access, ostatné deklarácie a platená verzia

- **App access pre review:** ak je prihlásenie povinné, dodať opakovane použiteľný testovací účet a kompletný postup v angličtine do Console. Pri Google Sign-In uviesť všetky potrebné údaje a kroky. Neodkazovať recenzenta iba na vytvorenie vlastného účtu; nepridávať heslá do Gitu.
- **Financial features:** pravdivo opísať evidenciu dokladov a výdavkov; aplikácia neposkytuje úvery, investície ani prevody peňazí. Konkrétne políčka zvoliť podľa aktuálneho formulára.
- **Ads:** bez reklamných SDK v kontrolovanom kóde. **Audience/content rating/government/health:** vyplniť podľa skutočného obsahu a zamýšľaného publika, nevymýšľať kategórie.
- **CAMERA** sama nevyžaduje formulár pre široký prístup do galérie. Ten sa týka najmä `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`; používa sa systémový picker.
- Platenú verziu aktivovať až po overení backendu, väzby nákupu na účet, serverovej kontroly plateného AI nároku a nákupných E2E testov. Samotné App Check nie je kreditový paywall.
- Pripravené produktové ID: `blesk_premium_monthly`, `blesk_credits_10`, `blesk_credits_50`, `blesk_credits_100`. Pri predplatnom overiť presný mesačný base plan a oprávnenosť ponuky, nevyberať ľubovoľnú prvú ponuku. Skutočné ceny schvaľuje vlastník v Console.
- RTDN topic: `projects/gen-lang-client-0115180408/topics/play-billing-rtdn`. Doručenie testovacej správy do topicu nie je dôkaz spracovania nákupu backendom.

Zdroje: [App access](https://support.google.com/googleplay/android-developer/answer/15748846), [Oprávnenia](https://support.google.com/googleplay/android-developer/answer/16558241), [Payments policy](https://support.google.com/googleplay/android-developer/answer/10281818).
