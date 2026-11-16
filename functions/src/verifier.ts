import { createHash } from "node:crypto";

export const EXPECTED_PACKAGE_NAME = "sk.bizagent.bleskovafaktura";
export const FIRESTORE_DATABASE_ID =
  "ai-studio-android-bleskovf-056698ab-4e20-4c12-b4ec-52ee9d53a81d";

export type CatalogProductType = "INAPP" | "SUBS";

export interface CatalogProductSpec {
  productId: string;
  productType: CatalogProductType;
  creditsDelta: number;
  grantsSubscription: boolean;
}

export const PRODUCT_CATALOG: Record<string, CatalogProductSpec> = {
  blesk_premium_monthly: {
    productId: "blesk_premium_monthly",
    productType: "SUBS",
    creditsDelta: 0,
    grantsSubscription: true,
  },
  blesk_credits_10: {
    productId: "blesk_credits_10",
    productType: "INAPP",
    creditsDelta: 10,
    grantsSubscription: false,
  },
  blesk_credits_50: {
    productId: "blesk_credits_50",
    productType: "INAPP",
    creditsDelta: 50,
    grantsSubscription: false,
  },
  blesk_credits_100: {
    productId: "blesk_credits_100",
    productType: "INAPP",
    creditsDelta: 100,
    grantsSubscription: false,
  },
};

export interface VerifyPurchaseInput {
  uid: string;
  packageName: string;
  productId: string;
  purchaseToken: string;
}

export interface InAppPlayPurchase {
  purchaseState: number; // 0 = Purchased, 1 = Canceled, 2 = Pending
  acknowledgementState: number; // 0 = Yet to be acknowledged, 1 = Acknowledged
  consumptionState?: number; // 0 = Yet to be consumed, 1 = Consumed
  orderId?: string;
}

export interface SubscriptionPlayPurchase {
  subscriptionState: string;
  acknowledgementState?: string;
  latestOrderId?: string;
  expiryTimeMillis?: number;
  productIdFromLineItems?: string;
}

export interface PlayDeveloperClient {
  getProductPurchase(packageName: string, productId: string, purchaseToken: string): Promise<InAppPlayPurchase>;
  consumeProductPurchase(packageName: string, productId: string, purchaseToken: string): Promise<void>;
  getSubscriptionV2(packageName: string, purchaseToken: string): Promise<SubscriptionPlayPurchase>;
  acknowledgeSubscription(packageName: string, productId: string, purchaseToken: string): Promise<void>;
}

export interface TokenRecord {
  tokenHash: string;
  ownerUid: string;
  productId: string;
  productType: CatalogProductType;
  packageName: string;
  orderId: string | null;
  status: "CREDITED" | "ACTIVE" | "REVOKED" | "EXPIRED";
  creditsGranted: number;
  subscriptionExpiryMillis: number | null;
}

export interface BillingStore {
  runInTransaction<T>(
    fn: (tx: {
      getToken(tokenHash: string): Promise<TokenRecord | null>;
      setToken(tokenHash: string, record: TokenRecord): void;
      grantInAppCredits(uid: string, creditsDelta: number): Promise<void>;
      setUserSubscription(uid: string, isSubscribed: boolean, expiryMillis: number | null): Promise<void>;
    }) => Promise<T>
  ): Promise<T>;
}

export class VerificationError extends Error {
  readonly code: "unauthenticated" | "invalid-argument" | "permission-denied" | "failed-precondition";
  constructor(
    code: "unauthenticated" | "invalid-argument" | "permission-denied" | "failed-precondition",
    message: string
  ) {
    super(message);
    this.code = code;
  }
}

export interface VerifyPurchaseResult {
  status: "GRANTED" | "ALREADY_PROCESSED" | "SUBSCRIPTION_ACTIVE" | "SUBSCRIPTION_INACTIVE";
  idempotent: boolean;
  productId: string;
  creditsGranted: number;
  isSubscribed: boolean;
}

export function hashPurchaseToken(purchaseToken: string): string {
  return createHash("sha256").update(purchaseToken, "utf8").digest("hex");
}

export function validateVerifyInput(input: VerifyPurchaseInput): CatalogProductSpec {
  if (!input.uid || typeof input.uid !== "string" || input.uid.trim().length === 0) {
    throw new VerificationError("unauthenticated", "Missing authenticated Firebase UID.");
  }
  if (input.packageName !== EXPECTED_PACKAGE_NAME) {
    throw new VerificationError(
      "invalid-argument",
      `Package mismatch: expected ${EXPECTED_PACKAGE_NAME}.`
    );
  }
  const spec = PRODUCT_CATALOG[input.productId];
  if (!spec) {
    throw new VerificationError("invalid-argument", `Unknown productId: ${input.productId}.`);
  }
  const token = (input.purchaseToken || "").trim();
  if (token.length < 10 || token.length > 2048) {
    throw new VerificationError("invalid-argument", "Invalid purchaseToken format.");
  }
  return spec;
}

const IMMEDIATE_ACTIVE_SUBSCRIPTION_STATES = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
]);

export function isSubscriptionEntitled(
  sub: SubscriptionPlayPurchase,
  nowMs: number = Date.now()
): boolean {
  if (IMMEDIATE_ACTIVE_SUBSCRIPTION_STATES.has(sub.subscriptionState)) {
    return true;
  }
  // Canceled auto-renew retains access until expiryTimeMillis if expiry is still in the future.
  if (
    sub.subscriptionState === "SUBSCRIPTION_STATE_CANCELED" &&
    typeof sub.expiryTimeMillis === "number" &&
    sub.expiryTimeMillis > nowMs
  ) {
    return true;
  }
  return false;
}

export async function verifyAndGrantPlayPurchase(
  input: VerifyPurchaseInput,
  playClient: PlayDeveloperClient,
  store: BillingStore
): Promise<VerifyPurchaseResult> {
  const spec = validateVerifyInput(input);
  const token = input.purchaseToken.trim();
  const tokenHash = hashPurchaseToken(token);

  if (spec.productType === "INAPP") {
    const playPurchase = await playClient.getProductPurchase(input.packageName, spec.productId, token);
    if (playPurchase.purchaseState === 2) {
      throw new VerificationError(
        "failed-precondition",
        "Purchase is pending payment confirmation in Google Play (purchaseState=2)."
      );
    }
    if (playPurchase.purchaseState !== 0) {
      throw new VerificationError(
        "failed-precondition",
        `Purchase is canceled or incomplete (purchaseState=${playPurchase.purchaseState}).`
      );
    }

    const txResult = await store.runInTransaction<VerifyPurchaseResult>(async (tx) => {
      const existing = await tx.getToken(tokenHash);
      if (existing) {
        if (existing.ownerUid !== input.uid) {
          throw new VerificationError(
            "permission-denied",
            "Purchase token is already bound to another user account."
          );
        }
        if (existing.status === "REVOKED") {
          throw new VerificationError("failed-precondition", "Purchase has been refunded or revoked.");
        }
        if (existing.status === "CREDITED") {
          return {
            status: "ALREADY_PROCESSED",
            idempotent: true,
            productId: spec.productId,
            creditsGranted: 0,
            isSubscribed: false,
          };
        }
      }

      // Order invariant: commit Firestore entitlement + tokenHash BEFORE Play consume
      // so credits are never lost if consume fails mid-flight; retries re-consume below.
      await tx.grantInAppCredits(input.uid, spec.creditsDelta);
      tx.setToken(tokenHash, {
        tokenHash,
        ownerUid: input.uid,
        productId: spec.productId,
        productType: "INAPP",
        packageName: input.packageName,
        orderId: playPurchase.orderId ?? null,
        status: "CREDITED",
        creditsGranted: spec.creditsDelta,
        subscriptionExpiryMillis: null,
      });

      return {
        status: "GRANTED",
        idempotent: false,
        productId: spec.productId,
        creditsGranted: spec.creditsDelta,
        isSubscribed: false,
      };
    });

    // Safe recovery: even on an idempotent retry, consume in Google Play if Play still shows unconsumed.
    if (playPurchase.consumptionState !== 1) {
      await playClient.consumeProductPurchase(input.packageName, spec.productId, token);
    }
    return txResult;
  }

  // Subscription verification (SUBS) via Play subscriptionsv2.get
  const sub = await playClient.getSubscriptionV2(input.packageName, token);
  if (sub.productIdFromLineItems && sub.productIdFromLineItems !== spec.productId) {
    throw new VerificationError(
      "invalid-argument",
      `Subscription product mismatch (${sub.productIdFromLineItems}).`
    );
  }
  const isActive = isSubscriptionEntitled(sub);

  const subResult = await store.runInTransaction<VerifyPurchaseResult>(async (tx) => {
    const existing = await tx.getToken(tokenHash);
    if (existing) {
      if (existing.ownerUid !== input.uid) {
        throw new VerificationError(
          "permission-denied",
          "Subscription token is already bound to another user account."
        );
      }
      if (existing.status === "REVOKED" && !isActive) {
        throw new VerificationError("failed-precondition", "Subscription purchase was revoked.");
      }
    }

    await tx.setUserSubscription(input.uid, isActive, sub.expiryTimeMillis ?? null);
    tx.setToken(tokenHash, {
      tokenHash,
      ownerUid: input.uid,
      productId: spec.productId,
      productType: "SUBS",
      packageName: input.packageName,
      orderId: sub.latestOrderId ?? null,
      status: isActive ? "ACTIVE" : "EXPIRED",
      creditsGranted: 0,
      subscriptionExpiryMillis: sub.expiryTimeMillis ?? null,
    });

    return {
      status: isActive ? "SUBSCRIPTION_ACTIVE" : "SUBSCRIPTION_INACTIVE",
      idempotent: Boolean(existing && existing.status === (isActive ? "ACTIVE" : "EXPIRED")),
      productId: spec.productId,
      creditsGranted: 0,
      isSubscribed: isActive,
    };
  });

  if (isActive && sub.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING") {
    await playClient.acknowledgeSubscription(input.packageName, spec.productId, token);
  }
  return subResult;
}

export interface PlayRtdnNotification {
  packageName?: string;
  subscriptionNotification?: {
    notificationType?: number;
    purchaseToken?: string;
    subscriptionId?: string;
  };
  oneTimeProductNotification?: {
    notificationType?: number;
    purchaseToken?: string;
    sku?: string;
  };
  voidedPurchaseNotification?: {
    purchaseToken?: string;
    orderId?: string;
    productType?: number; // 1 = SUBS, 2 = INAPP
    refundType?: number;
  };
}

export async function processPlayRtdnNotification(
  payload: PlayRtdnNotification,
  playClient: PlayDeveloperClient,
  store: BillingStore
): Promise<{ handled: boolean; action: string }> {
  if (payload.packageName !== EXPECTED_PACKAGE_NAME) {
    return { handled: false, action: "IGNORED_PACKAGE" };
  }

  // RTDN is treated strictly as a wake-up signal: always re-verify authoritative state via Play Developer API.
  const subNotif = payload.subscriptionNotification;
  if (subNotif?.purchaseToken) {
    return reconcileSubscriptionFromPlay(subNotif.purchaseToken, playClient, store);
  }

  const oneTimeNotif = payload.oneTimeProductNotification;
  if (oneTimeNotif?.purchaseToken) {
    return reconcileInAppFromPlay(oneTimeNotif.purchaseToken, oneTimeNotif.sku, playClient, store);
  }

  const voidedNotif = payload.voidedPurchaseNotification;
  if (voidedNotif?.purchaseToken) {
    const tokenHash = hashPurchaseToken(voidedNotif.purchaseToken);
    const known = await store.runInTransaction(async (tx) => tx.getToken(tokenHash));
    if (!known) {
      return { handled: false, action: "UNKNOWN_TOKEN" };
    }
    if (known.productType === "SUBS" || voidedNotif.productType === 1) {
      return reconcileSubscriptionFromPlay(voidedNotif.purchaseToken, playClient, store);
    }
    return reconcileInAppFromPlay(voidedNotif.purchaseToken, known.productId, playClient, store);
  }

  return { handled: false, action: "UNHANDLED_NOTIFICATION" };
}

async function reconcileSubscriptionFromPlay(
  purchaseToken: string,
  playClient: PlayDeveloperClient,
  store: BillingStore
): Promise<{ handled: boolean; action: string }> {
  const tokenHash = hashPurchaseToken(purchaseToken);
  const sub = await playClient.getSubscriptionV2(EXPECTED_PACKAGE_NAME, purchaseToken);
  const isActive = isSubscriptionEntitled(sub);

  return store.runInTransaction(async (tx) => {
    const existing = await tx.getToken(tokenHash);
    if (!existing) {
      return { handled: false, action: "UNKNOWN_TOKEN" };
    }
    await tx.setUserSubscription(existing.ownerUid, isActive, sub.expiryTimeMillis ?? null);
    tx.setToken(tokenHash, {
      ...existing,
      status: isActive ? "ACTIVE" : "REVOKED",
      subscriptionExpiryMillis: sub.expiryTimeMillis ?? null,
    });
    return { handled: true, action: isActive ? "SUBSCRIPTION_REFRESHED" : "SUBSCRIPTION_REVOKED" };
  });
}

async function reconcileInAppFromPlay(
  purchaseToken: string,
  skuHint: string | undefined,
  playClient: PlayDeveloperClient,
  store: BillingStore
): Promise<{ handled: boolean; action: string }> {
  const tokenHash = hashPurchaseToken(purchaseToken);
  const existingPrecheck = await store.runInTransaction(async (tx) => tx.getToken(tokenHash));
  if (!existingPrecheck) {
    return { handled: false, action: "UNKNOWN_TOKEN" };
  }
  const productId = existingPrecheck.productId || skuHint;
  if (!productId || !PRODUCT_CATALOG[productId]) {
    return { handled: false, action: "UNKNOWN_SKU" };
  }

  // Always verify authoritative purchase state via Google Play Developer API
  const playPurchase = await playClient.getProductPurchase(EXPECTED_PACKAGE_NAME, productId, purchaseToken);
  if (playPurchase.purchaseState === 0) {
    return { handled: true, action: "INAPP_STILL_VALID" };
  }

  return store.runInTransaction(async (tx) => {
    const existing = await tx.getToken(tokenHash);
    if (!existing || existing.status === "REVOKED") {
      return { handled: false, action: "NOOP" };
    }
    if (existing.creditsGranted > 0) {
      await tx.grantInAppCredits(existing.ownerUid, -existing.creditsGranted);
    }
    tx.setToken(tokenHash, {
      ...existing,
      status: "REVOKED",
    });
    return { handled: true, action: "INAPP_REVOKED" };
  });
}

