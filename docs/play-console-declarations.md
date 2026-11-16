# Google Play Console — Návrh deklarácií pre Blesková Faktúra (`sk.bizagent.bleskovafaktura`)

Pripravené výhradne podľa reálnych dátových tokov implementovaných v kóde k `versionCode = 2` (`versionName = "1.1"`). Nepotvrdené právne/obchodné údaje (IČO vydavateľa, presné ceny v Play Console mimo fallbacku v kóde, HTTPS doména) nevyplňujte naslepo — doplňte ich až podľa reálneho nastavenia vo vašom Play Console účte.

---

## 1. Právne odkazy a hostovanie (`docs/`)
- **Názov aplikácie (<= 30 znakov):** `Blesková Faktúra` (16 znakov, bez emoji, ALL CAPS a reklamných superlatívov).
- **Privacy Policy URL (`Store listing` / `App Content`):**
  1. Publikujte priečinok `/docs/` na verejnej HTTPS doméne (GitHub Pages alebo Firebase Hosting -> napr. `https://<vaša-doména>/privacy-policy.html` a `https://<vaša-doména>/account-deletion.html`).
  2. Po zverejnení nastavte živú HTTPS URL do `<string name="privacy_policy_url">` v `app/src/main/res/values/strings.xml`, aby sa v `SettingsScreen` aktivovalo aj tlačidlo *Otvoriť online verziu (HTTPS)*. (Kým doména nie je nasadená, aplikácia zámerne neotvára mŕtvy 404 odkaz; v aplikácii je dostupný plný text Zásad ochrany osobných údajov aj kontakt `erikbabcan@gmail.com`.)
- **Delete Account URL (`App Content -> Data safety -> Account deletion`):** HTTPS URL stránky `account-deletion.html`. V aplikácii už funguje priame okamžité vymazanie v *Nastavenia účtu -> Nebezpečná zóna -> Vymazať účet a všetky dáta*.

---

## 2. Data Safety (Bezpečnosť údajov)

### Celkové parametre
- **Zbiera alebo zdieľa údaje používateľov?** Áno (zbiera prihlásenie Google, doklady, snímky pre AI extrakciu, nákupné tokeny a App Check integritu).
- **Šifrovanie pri prenose (TLS/HTTPS):** Áno (Firebase Auth, Cloud Firestore, Firebase AI Logic, Firebase Functions, Google Play Billing).
- **Spôsob vymazania údajov:** Áno — okamžite v aplikácii aj cez web (`account-deletion.html`, do 14 dní).
- **Zdieľanie údajov s tretími stranami na reklamu/marketing:** Nie.

### Konkrétne dátové typy v aplikácii
1. **Personal info -> Name, Email address, User IDs**
   - **Collected:** Áno (`FirebaseAuth` Google Sign-In cez `CredentialManager`).
   - **Required or optional:** Povinné pre cloudový účet.
   - **Purposes:** App functionality, Account management.
   - **Shared:** Nie (technický spracovateľ: Google Firebase).
2. **Financial info -> Purchase history**
   - **Collected:** Áno (`PlayBillingManager` -> Cloud Function `verifyPlayPurchase` -> `billingTokens/{sha256}` + `users/{uid}`). Čísla platobných kariet aplikácia nezbiera.
   - **Purposes:** App functionality, Fraud prevention, security, and compliance.
3. **Financial info / User-entered accounting records** *(doklady zadávané používateľom — dodávateľ, IČO/DIČ, dátumy, sumy, DPH, kategória)*
   - Ukladané v lokálnej Room DB a izolovanej podkolekcii `users/{uid}/invoices/*` v pomenovanej Firestore Enterprise DB za účelom funkčnosti aplikácie a PDF exportu.
4. **Photos and videos -> Photos**
   - **Collected (transmitted):** Áno — odfotená alebo vybraná snímka dokladu sa odošle cez Firebase AI Logic (`gemini-3.8-flash`) na jednorazové OCR/AI vyťaženie polí. Originál sa archivuje výlučne lokálne na zariadení (`filesDir/receipts/`).
   - **Purposes:** App functionality.
5. **App info and performance / Device or other IDs -> Fraud prevention & Play Integrity**
   - **Collected:** Áno (`Firebase App Check` s `PlayIntegrityAppCheckProviderFactory` v release builde).
   - **Purposes:** Fraud prevention, security, and compliance.

---

## 3. Financial Features Declaration (`App Content -> Financial features`)
Aplikácia **neposkytuje** úvery, pôžičky, investície, kryptomeny, prevody peňazí, bankové služby ani sprostredkovanie platieb medzi stranami.
- **Odporúčaná deklarácia:** Aplikácia slúži výlučne ako nástroj na **evidenciu prijatých dokladov a faktúr (accounting / expense tracking utility)**. Nevykonáva regulované finančné služby. *(Presné zaškrtávacie položky overte v aktuálnom znení Play Console v deň podania.)*

---

## 4. App Access & In-App Produkty (`Monetize -> Products`)
- **App Access (`App Content -> App access`):** Vyberte **All or some functionality in my app is restricted** (vyžaduje Google Sign-In pre prístup do účtu) a v poznámke uveďte pokyn pre recenzenta Google Play na prihlásenie vlastným testovacím Google účtom (žiadne prihlasovacie heslá netreba, overenie prebieha natívne cez Google Sign-In tlačidlo; v release builde je `signInDemoUser` zámerne zablokované).
- **Produkty Play Console (presná zhoda s `BillingProduct` a `functions/src/verifier.ts`):**
  - Subscription: `blesk_premium_monthly` (Base plan + offer token aktívne)
  - One-time consumable INAPP: `blesk_credits_10`, `blesk_credits_50`, `blesk_credits_100`
  - RTDN topic v Play Console (`Monetization setup`): Pub/Sub topic `projects/gen-lang-client-0115180408/topics/play-billing-rtdn`

---

## 5. Oprávnenia v `AndroidManifest.xml` (release)
- `android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`, `com.android.vending.BILLING` — normálne sieťové/billing oprávnenia.
- `android.permission.CAMERA` (`android:required="false"`) — runtime žiadosť iba pri fotení dokladu; galéria používa bezoprávnenkový Android Photo Picker (`PickVisualMedia`).
- `androidx.credentials` prihlásenie pridáva v manifeste play-services deklarácie `USE_BIOMETRIC`/`USE_FINGERPRINT` na systémovej vrstve prihlásenia (bez vlastného biometrického zberu dát v kóde aplikácie).
- Žiadne zakázané oprávnenia (`READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, poloha).

---

## 6. Closed Testing & Identity Checklist
- [ ] Overený vývojársky profil v Google Play Console.
- [ ] Osobný účet (ak sa na vás vzťahuje pravidlo): uzavreté testovanie s min. 12 testermi nepretržite 14 dní pred žiadosťou o Production.
- [ ] Registrovaný **upload SHA-1 / SHA-256** aj **Play App Signing SHA-1 / SHA-256** vo Firebase Console (`Project Settings -> Android app` + `App Check -> Play Integrity`).
