# Google Play: pracovný návrh deklarácií

Technická revízia 18. 11. 2026 pre `sk.bizagent.bleskovafaktura`, verziu **1.1 / 2**.
Tento dokument nie je vyplnený formulár ani potvrdenie vydania. Vychádza z kódu;
vlastník musí potvrdiť reálne nasadenie, prevádzkové údaje a aktuálne položky Play
Console. [Stav projektu a blokéry](project-status.md) sú aktuálny stav overenia.

## Právne stránky a prístup recenzenta

- [Privacy návrh](privacy-policy.html) a [návrh výmazu účtu](account-deletion.html)
  vyžadujú potvrdenie prevádzkovateľa, kontaktu, právnych základov, retencie a lehôt.
- Po schválení publikovať iba tieto dve HTML stránky. Celý interný priečinok `docs/`
  sa nemá zverejniť ako právny web; nasadenie nie je súčasťou údržby.
- Verejné HTTPS URL doplniť až po kontrole živého obsahu. Resource
  `privacy_policy_url` je zatiaľ prázdny, aplikácia poskytuje lokálny informačný text.
- Release nemá demo prihlásenie. App Access musí vysvetliť skutočný postup
  prihlásenia a prístupu ku všetkým kontrolovaným funkciám. Nepredpokladať, že
  vlastný Google účet recenzenta alebo úspešné debug demo automaticky postačí.
  Postup otestovať na distribuovanom release pred podaním.

## Dáta podľa implementácie

| Tok | Technický obsah a účel | Čo overiť pred deklarovaním |
| --- | --- | --- |
| Google sign-in | UID, e-mail, meno, URL profilovej fotografie cez Firebase Auth | Provider, prístup recenzenta a privacy údaje |
| Doklady | Dodávateľ, IČO/DIČ, dátumy, sumy/DPH, mena, kategória a stav; Room a Firestore users/{uid}/invoices | Release, nasadené rules, cloudový zápis a výmaz |
| Fotografie | Kamera/Photo Picker, súkromný archív; obrázok sa odosiela Firebase AI Logic | Podmienky služby/plánu, príjemcovia a ich uchovávanie |
| Nákupy | Server overuje Play purchase token, billingTokens eviduje idempotenciu; bez čísel kariet v appke | Deployment, testovací nákup, RTDN, retencia a výmaz |
| Integrita | App Check; Play Integrity v release | Distribučný certifikát, Firebase app ID, enforcement a verdicts |
| PDF | Lokálny výkaz zdieľaný vedomou akciou používateľa | Export a cieľ zdieľania na zariadení |

Aktuálny AI model určuje `InvoiceAiService`, nie tento dokument. Cloudový prenos
nemožno deklarovať ako offline spracúvanie iba preto, že fotografia zostáva aj lokálne.

Kategórie Collected/Shared, required/optional a účely v Data Safety treba vybrať
podľa aktuálnych definícií Google Play a aktivovaných SDK. Táto tabuľka nenahrádza
rozhodnutie o zmluvných spracovateľoch alebo výnimkách z „sharing“.

## Výmaz a retencia

Výmaz účtu má viac krokov: lokálne záznamy/známe fotografie, Firestore a Firebase
Auth. Chyba siete alebo požiadavka na nové prihlásenie môže postup prerušiť.
Neoznačovať ho za bezpodmienečne okamžitý alebo overený na všetkých zariadeniach.
E-mailový odkaz nie je serverový formulár; prevádzkovateľ musí potvrdiť proces a lehotu.
`billingTokens` klientsky výmaz nemaže a automatická retencia zatiaľ nie je v kóde.
Pevnú desaťročnú lehotu bez potvrdenia neuvádzame.

## Produkty a finančné funkcie

Katalóg klienta a backendu očakáva:

- `blesk_premium_monthly` — predplatné;
- `blesk_credits_10`, `blesk_credits_50`, `blesk_credits_100` — spotrebné balíky;
- RTDN topic `play-billing-rtdn`, backend región `europe-west1`.

Existencia v kóde nepotvrdzuje produkty, ceny, dostupnosť ponúk ani živé pripísanie
kreditov. Pri rozpočte 0 € sa backend nenasadzuje a nákup sa neoznačuje za overený.
[Backendový návod](../functions/README.md) opisuje implementáciu a testové hranice.

Aplikácia eviduje doklady a výdavky. Kód neimplementuje pôžičky, investície alebo
prevody medzi používateľmi; Financial Features položky vybrať podľa aktuálneho
formulára a distribuovaných funkcií.

## Oprávnenia a podpis

Zdrojový manifest deklaruje INTERNET a CAMERA, kamera nie je povinný hardvér.
Fotoaparát má runtime žiadosť a galéria používa Photo Picker. Ďalšie oprávnenia
môžu pridať SDK; rozhoduje merged manifest finálneho release.

Pred interným vydaním overiť:

- existujúci upload certifikát a Play App Signing certifikát samostatne;
- Firebase app, fingerprints, App Check s Play Integrity;
- Google sign-in, sken/refund, export, výmaz a nákupy na release;
- aktuálne podmienky testovacieho tracku konkrétneho Play účtu;
- potvrdené právne údaje, živé HTTPS odkazy a prístup recenzenta.

Artefakt pripravovať podľa [release návodu](release-signing-and-smoke-test.md).
Aktuálne požiadavky sú v [Google Play Policy Center](https://play.google.com/about/developer-content-policy/)
a [návode Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469).
Nevytvárať nový podpisový kľúč ani automaticky potvrdzovať deklarácie.
