# Blesková Faktúra

Android aplikácia na evidenciu dokladov, kontrolu rozpoznaných údajov a PDF export.

## Interné testovanie

Variant `internal` používa skutočné OCR v zariadení (bundled ML Kit), lokálny profil alebo voliteľný Google účet a neponúka nákupy. Fotografie neposiela do Firebase AI. Pri lokálnom profile ostávajú doklady po odhlásení zachované až do vymazania profilu.

- Package: `sk.bizagent.bleskovafaktura`
- `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`
- JDK **21** je potrebné aj pre Robolectric testy Androidu 36.
- Firebase: `gen-lang-client-0115180408`
- Firestore: pomenovaná databáza `ai-studio-android-bleskovf-056698ab-4e20-4c12-b4ec-52ee9d53a81d` v `europe-west2`.

Pripraviť SDK a zostavu v Google Cloud Shell:

```bash
bash scripts/cloud-shell-build-internal.sh
```

V pripravenom prostredí s reálnou Firebase konfiguráciou `app/google-services.json`:

```bash
bash scripts/build-internal.sh
```

Bez `KEYSTORE_PATH`, `STORE_PASSWORD` a `KEY_PASSWORD` skript vytvorí **nepodpísaný AAB**. Pre upload nastavte tieto premenné a prípadne `KEY_ALIAS` na pôvodný upload kľúč. Privátny kľúč ani heslá nepatria do Gitu. Samotný verejný certifikát na podpisovanie nestačí.

Samostatné kontroly:

```bash
./gradlew :app:testInternalDebugUnitTest :app:lintInternalDebug :app:lintInternalRelease
./gradlew :app:connectedInternalDebugAndroidTest
python3 scripts/verify-android-artifact.py cesta/k/suboru.aab
pnpm --dir functions install --frozen-lockfile
pnpm --dir functions test
pnpm --dir functions run test:rules
```

Device testy overujú skutočné lokálne OCR, Room, editáciu, PDF, odhlásenie, zachovanie lokálneho profilu a výmaz. Google Sign-In/App Check z Play treba potvrdiť samostatným testom po uploadnutí správne podpísanej zostavy. Úspešný build nie je dôkaz úspešného Google prihlásenia.

CI používa pri chýbajúcom secrete iba nefunkčnú Firebase fixture pre izolované testy. Upload AAB vzniká len pri nastavených `GOOGLE_SERVICES_JSON`, `UPLOAD_KEYSTORE_BASE64`, `STORE_PASSWORD` a `KEY_PASSWORD`; `KEY_ALIAS` je voliteľný. Fixture nikdy nie je konfigurácia na distribúciu.

## Právne stránky a ďalšie vydanie

Pravdivé deklarácie a presný postup sú v [docs/play-console-declarations.md](docs/play-console-declarations.md). Právne HTML sú **šablóny**, kým nie sú doplnené všetky `{{...}}`. Staging nevyplnené šablóny odmietne. Publikuje sa iba izolovaný legal hosting, nie celý `docs/`.

## Produkčný variant

Variant `production` zachováva cloudové AI a Billing kód, ale **nie je pripravený na platené vydanie**. Pred ním treba aktívny billing, overenú konfiguráciu, nákupné produkty, nasadenie Functions a dôveryhodný serverový AI endpoint s kontrolou nároku. Pri sprístupnení Gemini API používateľom v EEA/CH/UK vyžadujú aktuálne podmienky Paid Services. Postup budúceho backendu je v [functions/README.md](functions/README.md).

Interný track nevyžaduje produkčný uzavretý test 12 testerov/14 dní. Podmienky interného a produkčného vydania sa posudzujú oddelene.
