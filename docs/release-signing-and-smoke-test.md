# BF-19 Release Signing & Release Smoke Test — Blesková Faktúra (`sk.bizagent.bleskovafaktura`)

## 1. Overené parametre vydania
- `applicationId`: `sk.bizagent.bleskovafaktura`
- `versionCode`: `2`
- `versionName`: `1.1`
- `minSdk`: `24`, `targetSdk`: `36`, `compileSdk`: `36.1`
- Release App Check: `PlayIntegrityAppCheckProviderFactory` (bez `firebase-appcheck-debug` v release)
- 16 KB page size kompatibilita natívnych `.so` (`arm64-v8a`, `x86_64`): `android:extractNativeLibs="false"` a všetky `LOAD` segmenty majú zarovnanie `0x4000` (16 384 B).

---

## 2. Podpisová identita (`BF-19`) — dve striktne odlíšené situácie

### A) Aplikácia EŠTE NIKDY nebola nahraná na Google Play (prvé vytvorenie upload kľúča)
Vygenerujte jediný krát upload keystore mimo repozitára (nikdy ho necommitujte do Gitu):

```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -alias upload \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Odporúčané CI/CD uloženie v GitHub Secrets (`.github/workflows/android-ci.yml`):
- `UPLOAD_KEYSTORE_BASE64`: `base64 -w0 my-upload-key.jks`
- `STORE_PASSWORD`: heslo ku keystore
- `KEY_PASSWORD`: heslo ku kľúču alias `upload`

Lokálny build cez env premenné (bez ukladania hesiel do kódu):
```bash
export KEYSTORE_PATH=/cesta/k/my-upload-key.jks
export STORE_PASSWORD='...'
export KEY_PASSWORD='...'
gradle :app:bundleRelease --console=plain
```

### B) Aplikácia UŽ MÁ registrovaný upload key v Google Play Console
- **Nikdy** nevytvárajte nový náhradný keystore — Play Console nové podpisy odmietne.
- Použite pôvodný `.jks` súbor a existujúce heslá cez `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_PASSWORD` (alebo požiadajte o reset upload kľúča oficiálnym postupom v *Play Console -> App integrity*).

### Kontrola výsledného `.aab` po podpísaní
```bash
# Overenie podpisu a certifikátu
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab

# Získanie SHA-1 a SHA-256 pre registráciu do Firebase Console (nikdy bez hesiel v logu)
keytool -list -v -keystore "$KEYSTORE_PATH" -alias upload

# Po zapnutí Play App Signing v Play Console -> App integrity skopírujte aj
# "App signing key certificate" SHA-1 a SHA-256 do Firebase Console!
```

---

## 3. End-to-End Release Smoke Test (na fyzickom Android zariadení s Play Store / Internal Testing)

Vykonajte po nasadení `/functions/` (Blaze) a nahraní podpísaného `.aab` do **Internal testing** stopy v Play Console (s testovacím účtom pridaným do *License testing*):

1. **Google Sign-In:** Prihláste sa reálnym Google účtom (overenie SHA-256 Play App Signing + Web Client ID). Demo tlačidlo sa v release builde nesmie zobraziť.
2. **Firebase App Check (Play Integrity):** Otvorte Firebase Console -> *App Check -> Metrics*; po prvých dopytoch musia byť overené požiadavky označené ako *Verified*.
3. **Kamera a oprávnenie:** Otvorte *Skenovať* -> *Odfotiť doklad*. Prvýkrát sa zobrazí systémová žiadosť `CAMERA`; po povolení sa otvorí fotoaparát bez `SecurityException`; po odmietnutí sa zobrazí zrozumiteľný banner bez pádu.
4. **AI sken a atómová rezervácia kreditu:** Odfoťte syntetický alebo testovací doklad -> stav prejde cez *Scanning* -> pred AI volaním sa v `users/{uid}` rezervuje 1 kredit (`activeReservationId`) -> po úspechu AI sa rezervácia potvrdí (`commitScanReservation`). Pri neplatnom obrázku (napr. selfie/stena) sa kredit vráti späť (`+1`).
5. **Uloženie a archív originálu:** Skontrolujte extrahované sumy, DPH a kategóriu -> uložte doklad -> skontrolujte, že súbor snímky existuje v detaile dokladu a formulár sa vyčistil.
6. **Firestore synchronizácia:** Odhláste sa / prihláste sa na druhom zariadení alebo po vymazaní dát aplikácie -> doklady z pomenovanej databázy `users/{uid}/invoices/*` aj zostatok kreditov sa musia automaticky obnoviť (lokálna snímka z iného zariadenia chýba korektne s fallbackom, účtovné dáta sú kompletné).
7. **Play testovací nákup (License tester):** V *Predplatné a Kredity* kúpte `blesk_credits_10` -> dokončite testovaciu platbu -> dialóg prejde cez `PendingVerification` (*Overujem nákup na serveri*).
8. **Serverová verifikácia (`verifyPlayPurchase`):** Skontrolujte Cloud Functions logy (`europe-west1`) — token sa overil voči Google Play Developer API, do `billingTokens/{sha256}` sa zapísal záznam a produkt sa skonzumoval až po zápise nároku.
9. **Pripísanie nároku:** UI zobrazí `Verified` a Firestore snapshot automaticky zvýši `credits` o `+10` bez akéhokoľvek klientského zápisu do `credits`. Opakované vyvolanie (`Obnoviť a overiť nákupy`) nevydá kredity dvakrát.
10. **Obnovenie aplikácie a predplatné/výmaz:** Zatvorte a znova otvorte aplikáciu (force-stop + launch) -> kredity ostávajú pripísané. Následne otestujte `blesk_premium_monthly` -> `isSubscribed = true` -> zrušenie v Play -> RTDN po expirácii nastaví `isSubscribed = false`. Na záver na testovacom účte otestujte *Nastavenia -> Vymazať účet a všetky dáta*.
