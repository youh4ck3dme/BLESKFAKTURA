import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { google } from "googleapis";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import * as logger from "firebase-functions/logger";
import {
  BillingStore,
  FIRESTORE_DATABASE_ID,
  PlayDeveloperClient,
  PlayRtdnNotification,
  TokenRecord,
  VerificationError,
  processPlayRtdnNotification,
  verifyAndGrantPlayPurchase,
} from "./verifier";

initializeApp();
const db = getFirestore(FIRESTORE_DATABASE_ID);

async function getAndroidPublisherClient() {
  const auth = new google.auth.GoogleAuth({
    scopes: ["https://www.googleapis.com/auth/androidpublisher"],
  });
  return google.androidpublisher({ version: "v3", auth });
}

const playClient: PlayDeveloperClient = {
  async getProductPurchase(packageName, productId, purchaseToken) {
    const publisher = await getAndroidPublisherClient();
    const res = await publisher.purchases.products.get({
      packageName,
      productId,
      token: purchaseToken,
    });
    return {
      purchaseState: res.data.purchaseState ?? -1,
      acknowledgementState: res.data.acknowledgementState ?? 0,
      consumptionState: res.data.consumptionState ?? 0,
      orderId: res.data.orderId ?? undefined,
    };
  },

  async consumeProductPurchase(packageName, productId, purchaseToken) {
    const publisher = await getAndroidPublisherClient();
    await publisher.purchases.products.consume({
      packageName,
      productId,
      token: purchaseToken,
    });
  },

  async getSubscriptionV2(packageName, purchaseToken) {
    const publisher = await getAndroidPublisherClient();
    const res = await publisher.purchases.subscriptionsv2.get({
      packageName,
      token: purchaseToken,
    });
    const lineItem = res.data.lineItems?.[0];
    const expiryIso = lineItem?.expiryTime;
    const expiryMillis = expiryIso ? Date.parse(expiryIso) : undefined;
    return {
      subscriptionState: res.data.subscriptionState ?? "SUBSCRIPTION_STATE_UNSPECIFIED",
      acknowledgementState: res.data.acknowledgementState ?? undefined,
      latestOrderId: res.data.latestOrderId ?? undefined,
      expiryTimeMillis: Number.isFinite(expiryMillis) ? expiryMillis : undefined,
      productIdFromLineItems: lineItem?.productId ?? undefined,
    };
  },

  async acknowledgeSubscription(packageName, productId, purchaseToken) {
    const publisher = await getAndroidPublisherClient();
    await publisher.purchases.subscriptions.acknowledge({
      packageName,
      subscriptionId: productId,
      token: purchaseToken,
      requestBody: {},
    });
  },
};

const firestoreBillingStore: BillingStore = {
  async runInTransaction(fn) {
    return db.runTransaction(async (tx) => {
      const pendingTokenWrites: Array<{ refPath: string; record: TokenRecord }> = [];
      const result = await fn({
        async getToken(tokenHash) {
          const snap = await tx.get(db.collection("billingTokens").doc(tokenHash));
          return snap.exists ? (snap.data() as TokenRecord) : null;
        },
        setToken(tokenHash, record) {
          pendingTokenWrites.push({ refPath: `billingTokens/${tokenHash}`, record });
        },
        async grantInAppCredits(uid, creditsDelta) {
          const userRef = db.collection("users").doc(uid);
          const userSnap = await tx.get(userRef);
          if (!userSnap.exists) {
            const initialCredits = Math.max(0, 10 + creditsDelta);
            tx.set(userRef, {
              userId: uid,
              credits: initialCredits,
              isSubscribed: false,
              totalInvoiced: 0,
              totalInvoicedCents: 0,
              invoiceCount: 0,
              activeReservationId: null,
              createdAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            });
          } else {
            const currentCredits = Number(userSnap.get("credits") ?? 0);
            const nextCredits = Math.max(0, currentCredits + creditsDelta);
            tx.update(userRef, {
              credits: nextCredits,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
        },
        async setUserSubscription(uid, isSubscribed, expiryMillis) {
          const userRef = db.collection("users").doc(uid);
          const userSnap = await tx.get(userRef);
          if (!userSnap.exists) {
            tx.set(userRef, {
              userId: uid,
              credits: 10,
              isSubscribed,
              subscriptionExpiryMillis: expiryMillis,
              totalInvoiced: 0,
              totalInvoicedCents: 0,
              invoiceCount: 0,
              activeReservationId: null,
              createdAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            });
          } else {
            tx.update(userRef, {
              isSubscribed,
              subscriptionExpiryMillis: expiryMillis,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
        },
      });

      for (const write of pendingTokenWrites) {
        tx.set(db.doc(write.refPath), {
          ...write.record,
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      return result;
    });
  },
};

export const verifyPlayPurchase = onCall(
  {
    region: "europe-west1",
    enforceAppCheck: true,
    consumeAppCheckToken: false,
  },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) {
      throw new HttpsError("unauthenticated", "Authentication required.");
    }

    const data = (request.data ?? {}) as Record<string, unknown>;
    try {
      return await verifyAndGrantPlayPurchase(
        {
          uid,
          packageName: typeof data.packageName === "string" ? data.packageName : "",
          productId: typeof data.productId === "string" ? data.productId : "",
          purchaseToken: typeof data.purchaseToken === "string" ? data.purchaseToken : "",
        },
        playClient,
        firestoreBillingStore
      );
    } catch (err) {
      if (err instanceof VerificationError) {
        throw new HttpsError(err.code, err.message);
      }
      logger.error("verifyPlayPurchase unexpected failure", { uid });
      throw new HttpsError("internal", "Purchase verification failed.");
    }
  }
);

export const handlePlayRtdn = onMessagePublished(
  {
    topic: "play-billing-rtdn",
    region: "europe-west1",
  },
  async (event) => {
    const rawJson = event.data.message.json as PlayRtdnNotification | undefined;
    if (!rawJson) {
      logger.warn("Empty RTDN Pub/Sub message received.");
      return;
    }
    const outcome = await processPlayRtdnNotification(rawJson, playClient, firestoreBillingStore);
    logger.info("Processed Play RTDN message", outcome);
  }
);
