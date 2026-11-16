# Backend Verifier — Google Play Billing (`BF-03`)

Tento priečinok obsahuje **Firebase Cloud Functions v2** pre budúce overovanie nákupov a predplatného cez Google Play Developer API (`androidpublisher` v3). Bezplatná interná zostava s OCR v zariadení tieto funkcie nepotrebuje. Samotné testy a zdrojový kód neznamenajú, že je backend nasadený alebo pripravený na predaj.

## Implementované funkcie
1. **`verifyPlayPurchase`** (`onCall`, `region: europe-west1`, `enforceAppCheck: true`):
   - Vyžaduje platný Firebase Auth token (`request.auth.uid`) aj App Check token.
   - Overuje `packageName === "sk.bizagent.bleskovafaktura"`, známy `productId` z katalógu a dĺžku/formát `purchaseToken`.
   - Pri jednorazových kreditoch (`blesk_credits_10`, `blesk_credits_50`, `blesk_credits_100`) volá `purchases.products.get`, overuje `purchaseState === 0` a následne produkt konzumuje.
   - Pri predplatnom (`blesk_premium_monthly`) volá `purchases.subscriptionsv2.get`, kontroluje stav `ACTIVE` / `IN_GRACE_PERIOD` a vykoná `acknowledge`.
   - Idempotencia a ochrana pred zneužitím: v jednej Firestore transakcii nad pomenovanou databázou `ai-studio-android-bleskovf-056698ab-4e20-4c12-b4ec-52ee9d53a81d` zapisuje SHA-256 hash tokenu do server-only kolekcie `billingTokens/{tokenHash}` (klientom zablokovanej cez `firestore.rules`). Opakované volanie tým istým používateľom je idempotentné (`ALREADY_PROCESSED`), pokus iným používateľom zlyhá s `permission-denied`.
2. **`handlePlayRtdn`** (`onMessagePublished`, Pub/Sub topic `play-billing-rtdn`):
   - Spracúva Real-time Developer Notifications (`subscriptionNotification`, `oneTimeProductNotification`, `voidedPurchaseNotification`) výhradne ako signál na opätovné overenie aktuálneho stavu nákupu cez Google Play Developer API; pri potvrdenom zrušení/expirácii deaktivuje `isSubscribed` a pri refundácii jednorazového balíka bezpečne odpočíta kredity (s orezaním `>= 0`).

## Postup nasadenia (vyžaduje váš Firebase + Play Console prístup)
1. Vo Firebase Console prepnite projekt `gen-lang-client-0115180408` na **Blaze plan**.
2. V Google Cloud aktivujte **Google Play Android Developer API** (`androidpublisher.googleapis.com`) a Pub/Sub topic `play-billing-rtdn`.
3. V Google Play Console (*Users and permissions*) pridajte servisný účet `blesk-billing@gen-lang-client-0115180408.iam.gserviceaccount.com` a obmedzte prístup na aplikáciu `sk.bizagent.bleskovafaktura`. Udeľte **View financial data, orders, and cancellation survey responses** + **Manage orders and subscriptions**. Zdieľaný administrátorský účet ani JSON súkromný kľúč nie sú potrebné.
4. Vytvorte produkty v Play Console presne s ID:
   - `blesk_premium_monthly` (predplatné)
   - `blesk_credits_10`, `blesk_credits_50`, `blesk_credits_100` (jednorazové spotrebné produkty)
5. Nainštalujte závislosti a nasaďte:
   ```bash
   cd functions
   pnpm install --frozen-lockfile --registry=https://registry.npmjs.org/
   pnpm test
   cd ..
   firebase deploy --only functions:verifyPlayPurchase,functions:handlePlayRtdn --project gen-lang-client-0115180408
   ```
6. Android klient (`PlayBillingManager`) už volá callable `verifyPlayPurchase` v regióne `europe-west1` pri nákupe aj pri obnove nepotvrdených/nekonzumovaných nákupov z Google Play.

## Väzba účtu a stav vydania (16. 11. 2026)

Klient pri začatí nákupu odosiela `obfuscatedAccountId = SHA-256("blesk-play-account-v1:" + Firebase UID)`.
Backend porovná identifikátor vrátený priamo Google Play API s prihláseným UID ešte pred prvým pripísaním.
Nákupy zo starších klientov bez tejto väzby sa automaticky nepriraďujú prvému volajúcemu.
Neznámy už spotrebovaný token sa odmietne; opakovanie známeho tokenu zostáva idempotentné a opakuje nedokončené consume.

RTDN testovacia správa nemení nároky. Pub/Sub funkcia opakuje zlyhané doručenie (`retry: true`).
Obe funkcie majú `minInstances: 0`, `maxInstances: 1`, `memory: "256MiB"`, `cpu: "gcf_gen1"` a `concurrency: 1`.
Úsporná konfigurácia je určená pre malú internú testovaciu záťaž; zlomkové CPU môže predĺžiť cold start.
Toto je kapacitný limit, nie cenový strop. Nevzťahuje sa na priame AI volania z klienta.
Testy bežia proti zostavenému JavaScriptu, takže nevyžadujú experimentálne TypeScript stripovanie v Node.
Používa sa úzky oficiálny klient `@googleapis/androidpublisher` namiesto načítania všetkých Google API.

Aktuálne blokery a živé dôkazy sú v `docs/RELEASE_EXECUTION_STATUS.md`; lokálny úspech testov nedokazuje nasadenie ani uskutočnený testovací nákup.

## Overenie bez cloudových zmien

Z koreňa repozitára:

```bash
pnpm --dir functions install --frozen-lockfile --registry=https://registry.npmjs.org/
pnpm --dir functions test
JAVA_TOOL_OPTIONS=-Xmx512m pnpm --dir functions run test:rules
```

Rules testy potrebujú JDK 21 a používajú izolovaný projekt `demo-bleskova-faktura` na lokálnom Firestore emulátore. Overujú izoláciu účtov, odmietnutie falošných nárokov, synchronizáciu 12 dokladov bez odpočtu kreditov a vymazanie dokladov/profilu vlastníkom. Neoverujú Firebase Auth odvolanie relácie ani živú cloudovú databázu. `functions/pnpm-workspace.yaml` výslovne zakazuje nepotrebné inštalačné skripty dvoch závislostí, takže `pnpm 12` môže dokončiť reprodukovateľný frozen install bez interaktívneho schvaľovania.

Overovanie nákupu teraz vyžaduje existujúci app profil. Oneskorené RTDN po jeho vymazaní vráti `DELETED_ACCOUNT` a nevytvorí účet znovu. Zmazanie app účtu samo osebe neruší predplatné v Google Play; klient musí používateľa vopred odkázať na správu odberu.

## Budúce platené nasadenie cez Cloud Shell

Nasledujúce kroky patria až k samostatnému zapnutiu platenej prevádzky. **Nespúšťať ich kvôli bezplatnej internej zostave.** Pred nimi musí vlastník v konzole pripojiť aktívny billing účet, zvoliť rozpočet a schváliť platené služby. Budget alert nie je pevný cenový strop.

```bash
export BF_PROJECT=gen-lang-client-0115180408
export BF_RUNTIME_SA="blesk-billing@${BF_PROJECT}.iam.gserviceaccount.com"
gcloud config set project "$BF_PROJECT"
gcloud billing projects describe "$BF_PROJECT"
gcloud services enable cloudfunctions.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com run.googleapis.com eventarc.googleapis.com \
  pubsub.googleapis.com androidpublisher.googleapis.com --project "$BF_PROJECT"

# Vytvoriť iba raz; ak už existuje, stačí `describe`.
gcloud iam service-accounts create blesk-billing \
  --display-name="Blesk - overovanie nakupov" --project "$BF_PROJECT"
gcloud iam service-accounts describe "$BF_RUNTIME_SA" --project "$BF_PROJECT"
gcloud projects add-iam-policy-binding "$BF_PROJECT" \
  --member="serviceAccount:${BF_RUNTIME_SA}" --role=roles/datastore.user

# Tento topic už bol v projekte vytvorený. Najprv overiť jeho existenciu.
gcloud pubsub topics describe play-billing-rtdn --project "$BF_PROJECT"
gcloud pubsub topics add-iam-policy-binding play-billing-rtdn \
  --member="serviceAccount:google-play-developer-notifications@system.gserviceaccount.com" \
  --role=roles/pubsub.publisher --project "$BF_PROJECT"

# V checkout-e s balíčkom functions; Node.js 22 a pnpm musia byť dostupné.
pnpm --dir functions install --frozen-lockfile --registry=https://registry.npmjs.org/
pnpm --dir functions test
pnpm --registry=https://registry.npmjs.org/ dlx firebase-tools@15.36.0 deploy \
  --only functions:verifyPlayPurchase,functions:handlePlayRtdn --project "$BF_PROJECT"
gcloud functions describe verifyPlayPurchase --gen2 --region=europe-west1 \
  --project "$BF_PROJECT" --format='yaml(state,serviceConfig.serviceAccountEmail,serviceConfig.uri)'
gcloud functions describe handlePlayRtdn --gen2 --region=europe-west1 \
  --project "$BF_PROJECT" --format='yaml(state,serviceConfig.serviceAccountEmail,eventTrigger)'
```

`roles/datastore.user` sa týmto príkazom udeľuje na projektovej úrovni; nie je to obmedzenie na jedinú kolekciu. Runtime účet nedostáva Owner, Editor ani správu IAM. Pri nasadení potrebuje nasadzujúci používateľ oprávnenie `iam.serviceAccounts.actAs` pre tento účet; ak chýba, vlastník mu môže na tomto konkrétnom účte udeliť `roles/iam.serviceAccountUser`. Ďalšie runtime roly sa nemajú pridávať len podľa odhadu.

V Play Console potom samostatne nastaviť presné produkty/base plan, licenčných testerov a RTDN topic `projects/gen-lang-client-0115180408/topics/play-billing-rtdn`. V oboch funkciách je uvedený rovnaký runtime účet; po deployi ho treba potvrdiť výpisom `gcloud functions describe`.

**Produkčný platený OCR/AI tok zostáva blokovaný:** priame volanie modelu z upraveného klienta nie je dôveryhodná kreditová hranica. Pred predajom skenov treba nasadiť autentifikovaný serverový endpoint, ktorý overí Auth/App Check, v transakcii rezervuje nárok, zavolá platené OCR/AI a dokončí alebo vráti rezerváciu. Android musí používať tento endpoint. Samotný nákupný verifier uvedený vyššie túto serverovú kreditovú kontrolu nenahrádza.
