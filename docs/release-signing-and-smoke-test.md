# Podpisovanie a release smoke test

Toto je kontrolný postup, nie potvrdenie publikovaného vydania. Aktuálne platí
**0 €: neaktivovať platený billing, nenasadzovať backend ani pravidlá a
nepublikovať do Play**. Stav konkrétnych overení a blokery udržiava
[stav projektu](project-status.md). Lokálne testy a debug smoke nenahrádzajú
Play-distribuovaný release ani produkčné overenie nákupov.

## Parametre zo zdrojového kódu

| Parameter | Hodnota |
| --- | --- |
| `applicationId` | `sk.bizagent.bleskovafaktura` |
| `versionName` / `versionCode` | `1.1` / `2` |
| `minSdk` / `targetSdk` / `compileSdk` | `24` / `36` / `36.1` |
| Release optimalizácia | R8 minifikácia aj resource shrinking zapnuté |
| Release App Check | `PlayIntegrityAppCheckProviderFactory`; debug provider je iba v debug source sete a závislostiach |

Hodnoty porovnaj s [app/build.gradle.kts](../app/build.gradle.kts) pri každej zmene
verzie. `versionCode` v kóde nepotvrdzuje, že Play prijme ďalší upload.

## Pôvodná podpisová identita

Repozitár a CI už očakávajú existujúci upload certifikát. Bez porovnania
s Play Console nevytváraj ani neregistruj nový náhradný kľúč. Heslá, privátny
kľúč ani obsah keystore nepatria do chatu, Git alebo build logov.

[CI workflow](../.github/workflows/android-ci.yml) kontroluje alias `upload`
a tento verejný SHA-256 fingerprint upload certifikátu:

```text
B016994403F88828911CBDC2CACF5B1834D547ECAB9CCC28BA24C43963463348
```

Fingerprint je kontrolná hodnota v CI, nie dôkaz, že je súkromný kľúč dostupný.
Treba ho porovnať s aktuálnym upload certifikátom v Play Console.
Upload kľúč a distribučný `App signing key` sú dve rozdielne identity:
Google pri Play App Signing podpisuje distribuované APK svojim app signing kľúčom.
Pre integrácie sa preto overujú príslušné certifikáty, nie iba lokálny upload podpis.
Pozri [oficiálny návod Android](https://developer.android.com/studio/publish/app-signing).

Lokálny release build používa existujúce premenné:

| Premenná | Význam |
| --- | --- |
| `KEYSTORE_PATH` | Absolútna cesta k pôvodnému upload keystore mimo Git |
| `STORE_PASSWORD` | Heslo ku keystore zo schváleného bezpečného úložiska |
| `KEY_PASSWORD` | Heslo k aliasu `upload` |

Nevkladaj hodnoty hesiel do príkazov uložených v histórii. Pri chýbajúcom alebo
nezhodnom pôvodnom kľúči označ podpisovanie **NOT VERIFIED** a build vynechaj.
Prípadný oficiálny reset upload kľúča je samostatná operácia; tento návod ho nevykonáva.

## Lokálny build a kontrola artefaktov

Najprv priprav JDK 21 a SDK podľa [lokálneho návodu](local-verification.md).
Pre funkčné živé Firebase musí existovať správna lokálna konfigurácia;
`google-services.json` sa necommitne. Keď sú pôvodná identita aj premenné bezpečne
dostupné a nebeží iný Gradle build, lokálny kontrolný príkaz je:

```sh
./gradlew :app:lintRelease :app:bundleRelease :app:assembleRelease \
  --no-daemon --max-workers=1 --no-parallel --console=plain
```

Po úspešnom zostavení over podpisy a zaznamenaj hashe. Pre príkaz `apksigner`
nastav `ANDROID_HOME` na adresár s rovnakým SDK aj vtedy, keď Gradle používa
iba `sdk.dir` z `local.properties`:

```sh
: "${ANDROID_HOME:?Nastavte ANDROID_HOME na adresár Android SDK}"
bundle=app/build/outputs/bundle/release/app-release.aab
apk=app/build/outputs/apk/release/app-release.apk
jarsigner -verify -strict -keystore "$KEYSTORE_PATH" -storepass:env STORE_PASSWORD "$bundle"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$apk"
sha256sum "$bundle" "$apk"
```

Tieto príkazy neoverujú inštaláciu z Google Play ani úspech Play Integrity.
Kompatibilitu 16 KB stránok kontroluj na konkrétnom výslednom artefakte a
príslušnom runtime podľa [oficiálneho návodu Android](https://developer.android.com/guide/practices/page-sizes).
Samotné `extractNativeLibs=false` alebo úspešný debug build ju nepotvrdzujú.
Bez aktuálneho binárneho a runtime dôkazu ju neoznačuj PASS.

## Podmienky signed release v CI

Release krok beží iba na push, keď existujú `GOOGLE_SERVICES_JSON`,
`UPLOAD_KEYSTORE_BASE64`, `STORE_PASSWORD` a `KEY_PASSWORD`; konfigurácia musí
zodpovedať správnemu projektu a Android app ID a upload fingerprint musí sedieť.
PR tieto secrets na release build nedostáva.

Workflow zostaví a overí AAB aj APK a zverejní CI artefakt
`bleskova-faktura-release-signed` iba pri úspešnom release kroku. To nie je upload
do Play. Bez splnenia podmienok sa release zrozumiteľne preskočí; úspešný debug
job nemožno označiť za podpisové ani release overenie.

## Smoke checklist a hranice dôkazov

Na každom výsledku uveď commit, variant, hash artefaktu, pôvod inštalácie,
Android verziu, dátum a PASS / FAIL / NOT VERIFIED. Používaj syntetické doklady;
do protokolu neukladaj App Check tajomstvá, auth tokeny ani raw HTTP telá.

| Kontrola | Akceptačné kritérium a rozsah |
| --- | --- |
| Identita | Nainštalovaný variant, applicationId, Firebase app ID a skutočný certifikát zodpovedajú zamýšľanej distribúcii. |
| Prihlásenie | Google prihlásenie funguje pre danú distribučnú identitu; demo tlačidlo v release chýba. |
| App Check | Release používa Play Integrity a chránené volanie je prijaté bez debug fallbacku a bez vypnutia enforcementu. |
| Kamera/import | Povolenie aj jeho odmietnutie sú zvládnuté bez pádu; import a zachytenie čitateľného testovacieho dokladu fungujú. |
| AI a kredity | Na testovacom účte bez predplatného úspech vytvorí validované údaje a spotrebuje jeden kredit; chyba/odmietnutie vráti rezerváciu správnemu účtu. Overiť aj zrušenie, opakovanie, zmenu účtu a samostatne režim predplatiteľa. |
| Uloženie a export | Kontrola údajov, uloženie, detail, dostupnosť lokálnej snímky a PDF export fungujú bez úniku údajov. |
| Cloud synchronizácia | Pri skutočnom Firebase účte sa účtovné údaje a kreditový stav obnovia na druhom zariadení. Lokálna snímka má pri neprítomnosti korektný fallback. |
| Play nákup | Licenčný tester dokončí nákup cez Play; nárok vznikne až po serverovom overení a opakované overenie ho nepripíše dvakrát. PENDING sa nepripisuje. |
| Predplatné/RTDN/výmaz | Obnova, zmena alebo zrušenie predplatného, opakované RTDN a výmaz účtu majú overený výsledok na testovacom účte. |

Lokálny demo smoke s Room nedokazuje produkčné Firestore účtovanie. Appetize
fresh inštalácia potrebuje vlastný debug App Check dôkaz a nepotvrdzuje release
Play Integrity ani Play nákup. Pri aktuálne blokovanom backend nasadení zostávajú
súvisiace cloudové a Play kontroly **NOT VERIFIED**; nepreskakujú sa vypnutím ochrany.

[Backend návod](../functions/README.md) popisuje implementáciu a bezplatné lokálne
testy. [Firebase diagnostika](firebase-and-troubleshooting.md) rieši konfiguráciu
a App Check bez publikovania či zdieľania tajomstiev.
