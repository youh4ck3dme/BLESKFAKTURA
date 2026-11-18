# Firebase, App Check a riešenie chýb

## Identita a inicializácia

| Nastavenie | Očakávaná hodnota |
| --- | --- |
| Projekt | `gen-lang-client-0115180408` |
| Android applicationId | `sk.bizagent.bleskovafaktura` |
| Firebase Android app ID | `1:356987646276:android:45325d3dea6c248380e711` |
| Provider debug | `DebugAppCheckProviderFactory` |
| Provider release | `PlayIntegrityAppCheckProviderFactory` |

Pred diagnostikou porovnaj tieto údaje so skutočne nainštalovaným APK, jeho variantom,
podpisovým certifikátom a konfiguráciou Firebase. Samotný názov súboru APK nestačí.
`MainActivity.onCreate` volá `configureAppCheck` pred UI/ViewModelom; AI model sa
vytvára lazy až pri požiadavke. Firebase klientsku konfiguráciu priprav podľa
[lokálneho návodu](local-verification.md), bez vloženia tajomstiev do repozitára.

## Debug inštalácia

Firebase debug provider vytvára súkromný debug secret. Ten sa registruje v správnej
Android aplikácii v [App Check konzole](https://console.firebase.google.com/project/gen-lang-client-0115180408/appcheck).
Registruj secret konkrétnej testovanej inštalácie, nie náhodný alebo starý secret
z iného telefónu. Secret nie je krátkodobý vydaný App Check token.

SDK môže secret vypísať do lokálneho Logcatu. Spracuj ho súkromne na dôveryhodnom
zariadení; nekopíruj ho do issues, CI logov, screenshotov ani chatu. Do reportu
patrí iba výsledok registrácie/výmeny a bezpečný kód chyby. Debug build podporuje
aj existujúci súkromný launch extra; neposielaj jeho hodnotu cez verejnú URL ani
zdieľaný shell príkaz. Release tento mechanizmus neobsahuje.

Ak správna registrácia opraví problém, ďalšia zmena kódu nie je potrebná.
Po jednorazovom teste odstráň iba vlastnú dočasnú registráciu a over jej odstránenie.
Nemaž tokeny ostatných testerov.

## Release a Play Integrity

Porovnaj distribučný podpis APK s certifikátom evidovaným vo Firebase a Play Console.
Upload certifikát a certifikát Play App Signing môžu byť odlišné; podpis lokálneho
upload artefaktu nie je dôkazom podpisu balíka distribuovaného Google Play.
Over aj pôvod inštalácie a požadované Integrity verdicts. Nevypínaj enforcement,
neznižuj požadované verdicts a nepridávaj debug fallback na obídenie chyby.
Postup odovzdania je v [release návode](release-signing-and-smoke-test.md).

## Diagnostický postup

| Prejav | Čo overiť | Čo z neho nemožno vyvodiť |
| --- | --- | --- |
| Chýbajúca attestation | Firebase konfigurácia, provider a poradie prvého volania | Že stačí vytvoriť ľubovoľný token |
| „Token is invalid“ / odmietnutie | Projekt/app ID, variant, registrácia, podpis a bezpečný SDK/HTTP kód | Že token iba chýba |
| Expirovaný token | Hodiny zariadenia, sieť a obnova SDK tokenu | Že treba vypnúť enforcement |
| HTTP 429 / kvóta | Aktuálna kvóta a plán v konzole, ohraničené opakovanie | Že je povolený upgrade plánu |
| Timeout AI | Sieť, dostupnosť modelu, lokálny deadline a cancellation | Že zlyhal App Check |
| Chyba uloženia/synchronizácie | Zachovaná revízia, lokálny a cloudový výsledok zvlášť | Že kredit treba odpočítať znovu |

Pri skene so syntetickým dokladom zaznamenaj stav kreditu pred rezerváciou,
po nej a po odmietnutí/timeout. Potvrď jedno vrátenie a žiadne zdvojenie pri retry.
Pri úspechu over revíziu a uloženie. Výsledky vždy odlíš pre demo Room, izolované
emulátory a živý cloud. Neuvádzaj surové SDK správy alebo obsah dokladu.

AI vrstva vracia sanitizované chybové stavy a zachová cancellation. Firestore
diagnostika zapisuje iba operáciu a konečný SDK kód. To nie je záruka redakcie
všetkých logov tretích SDK; pred zdieľaním vždy skontroluj celý výstup.

## Appetize na iPhone

Appetize streamuje Android zariadenie do prehliadača; APK sa neinštaluje do iOS.
Nová relácia začína čerstvou inštaláciou, preto sa nespoliehaj na predošlý debug
secret ani stav kreditov. Spustenie dashboardu nepreukazuje nový AI sken.
Do verejného odkazu nedávaj API token, debug secret ani ADB tunel. Minúty a dĺžku
relácie kontroluj podľa aktuálneho bezplatného plánu; platený plán nie je autorizovaný.

## Overené oficiálne zdroje

Kontrolované 18. 11. 2026; zmena dokumentácie poskytovateľa nie je súhlasom na
zmenu našej infraštruktúry alebo aktiváciu billingu.

- [Firebase debug provider](https://firebase.google.com/docs/app-check/android/debug-provider)
- [Play Integrity provider](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
- [App Check pre Firebase AI Logic](https://firebase.google.com/docs/ai-logic/app-check)
- [Appetize: stav medzi reláciami](https://support.appetize.io/how-do-i-maintain-app-state-between-sessions)
- [Appetize: aktuálne plány](https://appetize.io/pricing)
- [Gemini API: podmienky spracúvania a dostupnosti](https://ai.google.dev/gemini-api/terms)
