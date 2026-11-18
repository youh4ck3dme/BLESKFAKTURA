# Lokálne zostavenie a overenie

Tento postup zostaví debug APK a spustí lokálne testy. Neaktivuje billing,
nenasadzuje Functions ani Firestore rules a nezverejňuje vydanie.
Dôkazy konkrétneho commitu a neoverené oblasti sú v [stave projektu](project-status.md);
starší úspešný beh sa automaticky nevzťahuje na nový kód alebo novú inštaláciu.

## Nástroje a pracovná kópia

Konfiguráciu porovnávaj s [CI workflow](../.github/workflows/android-ci.yml)
a [Gradle wrapperom](../gradle/wrapper/gradle-wrapper.properties).

| Nástroj | Verzia používaná projektom/CI |
| --- | --- |
| Java | JDK 21 pre build aj Robolectric Android 36 testy |
| Node.js | 22 |
| pnpm | 12.15.0 |
| Gradle | 9.3.1 cez `./gradlew` |
| Android SDK | `platform-tools`, `platforms;android-36.1`, `build-tools;36.0.0` |
| Firebase CLI pre testy | `firebase-tools@15.36.0` cez `pnpm dlx` |

Pred začatím over `git status --short`, aktuálnu vetvu a bežiace buildy. Rozpracované
používateľské súbory neprepisuj ani nemaž. Nespúšťaj druhý Gradle build alebo ďalší
emulátor, ak už potrebný proces beží.

`JAVA_HOME` musí smerovať na JDK 21; jeho `bin` má byť aj v `PATH`, aby rovnakú
Javu používali Gradle a Firebase emulátory. Android SDK vyber cez
`ANDROID_HOME` alebo lokálne `sdk.dir` v necommitovanom `local.properties`.
Konkrétne cesty závisia od počítača. Globálnu inštaláciu Gradle alebo Firebase CLI
tento postup nepotrebuje.

Gradle a testovací JVM majú samostatné limity pamäte. Projekt používa jeden worker
a vypnuté paralelné zostavenie; testy majú heap 768 MiB a jeden fork naraz.
Studená cache sťahuje závislosti a vyžaduje voľné miesto aj sieť. Android AVD
nie je potrebný pre nižšie uvedené JVM/Robolectric testy.

## Rýchle kontroly dokumentácie a skriptu

Z koreňa repozitára:

```sh
python3 scripts/check-docs.py
bash -n scripts/build-local-debug.sh
```

Kontrola dokumentácie používa iba Python štandardnú knižnicu; overuje lokálne
odkazy a fragmenty bez siete. Tieto kontroly nenahrádzajú build ani runtime smoke.

## Backend a všetky Android testy

Príkazy spúšťaj z koreňa repozitára:

```sh
pnpm --dir functions install --frozen-lockfile --ignore-scripts --registry=https://registry.npmjs.org/
pnpm --dir functions test
pnpm dlx firebase-tools@15.36.0 emulators:exec \
  --only firestore,auth --project demo-no-project --config firebase.json --non-interactive \
  './gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --continue --no-daemon --max-workers=1 --no-parallel --console=plain'
```

Explicitný registry obíde prípadné nefunkčné lokálne zrkadlo bez zmeny globálnej
konfigurácie pnpm. Lockfile zostáva zmrazený.

Backend testy používajú náhrady Play klienta a úložiska. Android repository testy
potrebujú Firebase emulátory: Firestore na `127.0.0.1:8085`, Auth na
`127.0.0.1:9099`, podľa [firebase.json](../firebase.json). Vytvárajú vlastnú
pomenovanú Firebase aplikáciu pre `demo-no-project`, nezávislú od reálnej default
konfigurácie. Pomenovaná databáza je rovnaká ako v konfigurácii testov; tieto testy
nesmú prepnúť na živý projekt.

Pomocník [scripts/build-local-debug.sh](../scripts/build-local-debug.sh) spúšťa
rovnaké tri Android úlohy. Sám Firebase emulátory nezapína, preto ho pri všetkých
testoch používaj až pod `emulators:exec`. Na Linuxe vie odvodiť `JAVA_HOME` z
nainštalovaného `javac`, vyžaduje JDK 21+ a má lokálne predvolené cesty na SDK
a Gradle cache vedľa checkoutu; pri inom rozložení nastav príslušné premenné.

## Výsledky a CI artefakty

| Výstup | Cesta alebo názov |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Android JUnit XML | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| HTML test report | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint reporty | `app/build/reports/lint-results-debug.*` |
| CI debug APK | `bleskova-faktura-debug-apk` |
| CI testy / lint | `bleskova-faktura-test-reports` / `bleskova-faktura-lint-reports` |

Pri každom behu zaznamenaj commit, variant, príkaz, výsledné počty testov
vrátane skipped, lint chyby/upozornenia a hash APK. Výsledok neodvodzuj iba
z existencie súboru alebo zo starého počtu testov. CI nahráva dostupné debug,
test a lint artefakty aj pri neúspechu; chýbajúci súbor nie je úspešný build.

PR joby nedostávajú živý `GOOGLE_SERVICES_JSON`, ani pri PR z rovnakého repozitára.
Na dôveryhodnom push behu je konfigurácia voliteľná a kontroluje sa projekt aj
Firebase Android app ID. Signed release krok je samostatne podmienený existujúcou
konfiguráciou a podpisovými secrets; inak sa preskočí.

## Lokálna inštalácia debug APK

Pri aktualizácii existujúcej inštalácie zachovaj pôvodný debug podpis. Ak
`$HOME/.android/debug.keystore` existuje a `ANDROID_USER_HOME` nie je nastavené,
pomocník ho použije. Pri vlastnom umiestnení nastav `ANDROID_USER_HOME` na priečinok
s pôvodným `debug.keystore`. Debug kľúč nie je upload kľúč pre Google Play.

Po dokončení jediného buildu a po pripojení zariadenia alebo spustení jedného AVD:

```sh
sha256sum app/build/outputs/apk/debug/app-debug.apk
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n sk.bizagent.bleskovafaktura/com.example.MainActivity
```

Pri chybe podpisu aplikáciu automaticky neodinštaluj: tým by sa mohli stratiť
lokálne doklady. Najprv obnov správnu podpisovú identitu. App ID a variant
nainštalovanej aplikácie over zo skutočného zariadenia, nie iba z názvu APK.

## Živé Firebase a Appetize

`app/google-services.json` je zámerne ignorovaný. Konfigurácia musí patriť aplikácii
`sk.bizagent.bleskovafaktura` v projekte `gen-lang-client-0115180408`.
Debug používa `DebugAppCheckProviderFactory`; release používa Play Integrity.
Postup diagnostiky a súkromnej registrácie konkrétnej inštalácie je v
[Firebase návode](firebase-and-troubleshooting.md).

Demo prihlásenie a Room môžu fungovať bez živého Firebase. S nakonfigurovaným
debug buildom možno volať živé AI aj z demo UI, ale jeho kreditový ledger zostáva
lokálny. Úspech tu nepotvrdzuje cloudové účtovanie, Google prihlásenie ani Play platby.

Appetize s čerstvou inštaláciou alebo vymazanými dátami musí prejsť vlastným
overením App Check. Registráciu a úspešný sken zo starého zariadenia nemožno
považovať za dôkaz pre novú reláciu. Kým konkrétna relácia nemá overenú výmenu
App Check a úspešný AI výsledok, označ živý AI sken **NOT VERIFIED**.
Appetize dokumentuje [reinštaláciu ako reset dát](https://docs.appetize.io/guides-and-samples/common-testing-scenarios);
väzba na novú inštaláciu je dôsledkom lokálneho App Check stavu.
