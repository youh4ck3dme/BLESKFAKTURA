# Backend Verifier — Google Play Billing (`BF-03`)

Tento priečinok obsahuje produkčnú **Firebase Cloud Functions v2** implementáciu pre overovanie nákupov a predplatného cez Google Play Developer API (`androidpublisher` v3).

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
3. V Google Play Console (*Users and permissions -> Service accounts*) udeľte Cloud Functions service účtu oprávnenie **View financial data, orders, and cancellation survey responses** + **Manage orders and subscriptions**.
4. Vytvorte produkty v Play Console presne s ID:
   - `blesk_premium_monthly` (predplatné)
   - `blesk_credits_10`, `blesk_credits_50`, `blesk_credits_100` (jednorazové spotrebné produkty)
5. Nainštalujte závislosti a nasaďte:
   ```bash
   cd functions
   npm install
   npm run build
   firebase deploy --only functions --project gen-lang-client-0115180408
   ```
6. Android klient (`PlayBillingManager`) už volá callable `verifyPlayPurchase` v regióne `europe-west1` pri nákupe aj pri obnove nepotvrdených/nekonzumovaných nákupov z Google Play.

