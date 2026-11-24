# Blesková Faktúra

Android aplikácia pre slovenských živnostníkov: odfotenie alebo import dokladu,
AI extrakcia účtovných údajov, kontrola a uloženie dokladu, prehľad a PDF export.
Rozhranie používa Kotlin a Jetpack Compose; dáta spracúva lokálne cez Room alebo
po prihlásení cez Firebase.

## Kde začať

- [Aktuálny stav a blokery](docs/project-status.md) — dôkazy s dátumom a commitom, PASS / FAIL / NOT VERIFIED.
- [Architektúra a dátové toky](docs/architecture.md).
- [Lokálne zostavenie a testy](docs/local-verification.md).
- [Firebase, App Check a diagnostika](docs/firebase-and-troubleshooting.md).
- [Podpisovanie a release smoke test](docs/release-signing-and-smoke-test.md).
- [Backend overovania nákupov](functions/README.md).
- [Podklady pre Play Console](docs/play-console-declarations.md), [ochrana súkromia](docs/privacy-policy.html) a [vymazanie účtu](docs/account-deletion.html).

## Identita aplikácie

Zdrojom týchto hodnôt je [app/build.gradle.kts](app/build.gradle.kts);
nejde o potvrdenie stavu v Play Console.

| Parameter | Hodnota v kóde |
| --- | --- |
| `applicationId` | `sk.bizagent.bleskovafaktura` |
| Android namespace | `com.example` |
| `versionName` / `versionCode` | `1.1` / `2` |
| `minSdk` / `targetSdk` / `compileSdk` | `24` / `36` / `36.1` |
| Release optimalizácia | R8 minifikácia aj zmenšovanie zdrojov zapnuté |

## Režimy aplikácie

| Režim | Čo možno overovať |
| --- | --- |
| Debug bez Firebase konfigurácie, napríklad PR artefakt z CI | Lokálne demo rozhranie a Room. Živý AI sken ani Google prihlásenie nie sú nakonfigurované. |
| Debug s lokálnym `google-services.json` | Živé Firebase volania po správnej konfigurácii a súkromnej registrácii App Check pre danú inštaláciu. Demo kredity zostávajú lokálne. |
| Release | Play Integrity a pôvodná podpisová identita; demo prihlásenie je zablokované. Play nákupy potrebujú samostatne overený backend a distribúciu. |

AI skener používa Firebase AI Logic, model `gemini-3.5-flash-lite`, MINIMAL thinking,
limit 4096 výstupných tokenov a aplikačný deadline 60 sekúnd. Zdrojom je
[InvoiceAiService.kt](app/src/main/java/com/example/data/InvoiceAiService.kt);
aplikácia nemá automatický prechod na iný model. Vlastný Gemini API kľúč v
`BuildConfig` nie je súčasťou tohto toku.

## Rýchle lokálne overenie

Použi JDK 21, Node.js 22, pnpm 12.15.0, Android SDK 36.1 a repozitárový
Gradle wrapper 9.3.1. Presný postup vrátane Firebase emulátorov je v
[lokálnom návode](docs/local-verification.md). Testy repozitára používajú
`demo-no-project`, nepotrebujú Android AVD a nesmú pristupovať do živého projektu.

[CI workflow](.github/workflows/android-ci.yml) zostavuje debug APK, spúšťa
backend aj Android testy a lint. PR zostavenia nedostávajú živú Firebase
konfiguráciu. Zelené CI preto samo nepotvrdzuje úspešný živý sken ani pripravené
vydanie v Google Play.

## Prevádzkové hranice

Platí rozhodnutie **0 €: platený billing neaktivovať**. Lokálne overovanie
nenasadzuje Functions, Firestore rules ani aplikáciu do Play. Dostupnosť živých
AI volaní závisí od konfigurácie a aktuálnych kvót; nejde o prísľub neobmedzenej
bezplatnej prevádzky. Podrobnosti a neoverené časti patria do
[aktuálneho stavu](docs/project-status.md).

Do Git nepatria `google-services.json`, App Check tajomstvá, prihlasovacie údaje,
keystores, APK/AAB ani súkromné doklady a logy. Pri údržbe zachovaj rozpracované
používateľské zmeny a do commitu vyber iba skontrolované súbory.

### Vzor eFaktúry 2027

Na domovskej obrazovke zvoľte **eFaktúra 2027 · Pozrieť vzor**. Prehľad zobrazuje údaje z priloženého XML. Tlačidlom **Zobraziť XML** otvoríte celý pôvodný doklad aj bez pripojenia. Ide o všeobecný ilustračný príklad OpenPeppol s pôvodnými dátumami a sadzbami. Ukážka slúži na prezeranie a neodosiela doklady.

Zdroj súboru: [OpenPeppol BIS Billing 3.0, Example files](https://docs.peppol.eu/poacc/billing/3.0/files/BIS-Billing3-Examples.zip), `rules/examples/base-example.xml`.
