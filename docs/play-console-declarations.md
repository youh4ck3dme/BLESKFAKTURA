# Google Play Console — Návrh deklarácií pre Blesková Faktúra (`sk.bizagent.bleskovafaktura`)

Pripravené výhradne podľa reálnych dátových tokov implementovaných v kóde k `versionCode = 2` (`versionName = "1.1"`).

---

## 1. Zákaznícke a právne odkazy (Store Listing & App Content)
- **Názov aplikácie (<= 30 znakov):** `Blesková Faktúra` (16 znakov, bez emoji, ALL CAPS a reklamných superlatívov).
- **Privacy Policy URL:** Nahrať `/docs/privacy-policy.html` na verejnú HTTPS doménu (napr. Firebase Hosting alebo GitHub Pages) a doplniť tento odkaz aj do `SettingsScreen.kt` (tlačidlo Zásady ochrany osobných údajov).
- **Delete Account URL (App Content -> Data safety -> Account deletion):** Nahrať `/docs/account-deletion.html` na verejnú HTTPS doménu. V aplikácii už existuje priame vymazanie v *Nastavenia -> Vymazať účet a všetky dáta*.

---

## 2. Data Safety (Bezpečnosť údajov)

### Celkové parametre
- **Zbiera alebo zdieľa údaje používateľov?** Áno (zbiera prihlásenie Google, doklady, snímky pre AI extrakciu a nákupné záznamy).
- **Šifrovanie pri prenose (TLS/HTTPS):** Áno (Firebase Auth, Firestore, Firebase AI Logic, Play Billing).
- **Spôsob vymazania údajov:** Áno — používateľ môže požiadať o vymazanie v aplikácii aj cez web (`account-deletion.html`).
- **Zdieľanie údajov s tretími stranami na reklamu/marketing:** Nie.

### Konkrétne dátové typy v aplikácii
1. **Personal info -> Name, Email address, User IDs**
   - **Collected:** Áno (`FirebaseAuth` Google Sign-In cez `CredentialManager`).
   - **Required or optional:** Povinné pre cloudový účet.
   - **Purposes:** App functionality, Account management.
   - **Shared:** Nie (iba technickí spracovatelia Google Firebase).
2. **Financial info -> Purchase history** *(keď budú aktivované nákupy v produkcii)*
   - **Collected:** Áno (`PlayBillingManager` + serverový verifier `billingTokens`).
   - **Purposes:** App functionality, Fraud prevention, security, and compliance.
3. **Financial info / User-entered accounting records** *(faktúry zadávané používateľom — dodávateľ, IČO/DIČ, sumy, DPH, dátumy)*
   - Poznámka Play Console: ukladané vo vlastnom izolovanom priestore `users/{uid}/invoices/*` za účelom funkčnosti aplikácie (evidencia nákladov a export PDF).
4. **Photos and videos -> Photos**
   - **Collected (transmitted):** Áno — vybraná alebo odfotená snímka dokladu sa odošle cez Firebase AI Logic (`gemini-3.8-flash`) na jednorazové OCR/AI vyťaženie polí. Archivácia originálu ostáva lokálne na zariadení (`filesDir/receipts/`).
   - **Purposes:** App functionality.
5. **App info and performance -> Fraud prevention / App Check tokens**
   - **Collected:** Áno (`Firebase App Check` s reCAPTCHA Enterprise v release builde).
   - **Purposes:** Fraud prevention, security, and compliance.

---

## 3. Financial Features Declaration (App Content -> Financial features)
Aplikácia **neposkytuje** úvery, pôžičky, investície, kryptomeny, prevody peňazí, bankové služby ani sprostredkovanie platieb medzi stranami.
- **Odporúčaná odpoveď v Play Console:** Aplikácia slúži výlučne ako **nástroj na osobnú/podnikateľskú evidenciu prijatých dokladov a faktúr (accounting / expense tracking utility)**; neponúka žiadne z regulovaných finančných produktov uvedených v deklarácii *Financial features*.
  *(Presnú formuláciu položiek skontrolujte v aktuálnom formulári Play Console v deň podania.)*

---

## 4. Oprávnenia v `AndroidManifest.xml`
- `android.permission.INTERNET` — normálne sieťové oprávnenie (Firebase Auth, Firestore, AI Logic, Play Billing).
- `android.permission.CAMERA` (s `android:required="false"`) — vyžadované až za behu pri fotení dokladu (`TakePicture`); pri výbere z galérie sa používa bezoprávnenkový systémový Photo Picker (`PickVisualMedia`).
- Žiadne zakázané alebo citlivé oprávnenia (`READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, lokácia, kontakty).

---

## 5. Closed Testing & Identity Checklist (pred kliknutím na Production)
- [ ] Overený profil vývojára v Google Play Console (D-U-N-S pre firemný účet alebo osobná verifikácia identity).
- [ ] Ak ide o nový osobný vývojársky účet: minimálne **12 testerov prihlásených nepretržite 14 dní** v Closed Testing pred odomknutím *Apply for Production* (presné pravidlo overiť priamo v Play Console dashboarde).
- [ ] Registrovaný **upload certifikát (SHA-1 / SHA-256)** aj **Play App Signing** certifikát vo Firebase Console (`Project Settings -> Android app`), inak Google Sign-In a App Check v produkčnej distribúcii zlyhajú!
