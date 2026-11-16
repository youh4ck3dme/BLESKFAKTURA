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

const ACTIVE_SUBSCRIPTION_STATES = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
]);

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
    if (playPurchase.purchaseState !== 0) {
      throw new VerificationError(
        "failed-precondition",
        `Purchase is not completed (purchaseState=${playPurchase.purchaseState}).`
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
        if (existing.status === "CREDITED") {
          return {
            status: "ALREADY_PROCESSED",
            idempotent: true,
            productId: spec.productId,
            creditsGranted: 0,
            isSubscribed: false,
          };
        }
        if (existing.status === "REVOKED") {
          throw new VerificationError("failed-precondition", "Purchase has been refunded or revoked.");
        }
      }

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

    if (!txResult.idempotent && playPurchase.consumptionState !== 1) {
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
  const isActive = ACTIVE_SUBSCRIPTION_STATES.has(sub.subscriptionState);

  const subResult = await store.runInTransaction<VerifyPurchaseResult>(async (tx) => {
    const existing = await tx.getToken(tokenHash);
    if (existing && existing.ownerUid !== input.uid) {
      throw new VerificationError(
        "permission-denied",
        "Subscription token is already bound to another user account."
      );
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
}

export async function processPlayRtdnNotification(
  payload: PlayRtdnNotification,
  playClient: PlayDeveloperClient,
  store: BillingStore
): Promise<{ handled: boolean; action: string }> {
  if (payload.packageName !== EXPECTED_PACKAGE_NAME) {
    return { handled: false, action: "IGNORED_PACKAGE" };
  }

  const subNotif = payload.subscriptionNotification;
  if (subNotif?.purchaseToken) {
    const tokenHash = hashPurchaseToken(subNotif.purchaseToken);
    const sub = await playClient.getSubscriptionV2(EXPECTED_PACKAGE_NAME, subNotif.purchaseToken);
    const isActive = ACTIVE_SUBSCRIPTION_STATES.has(sub.subscriptionState);

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

  const oneTimeNotif = payload.oneTimeProductNotification;
  // notificationType === 2 -> ONE_TIME_PRODUCT_CANCELED (refund/chargeback)
  if (oneTimeNotif?.purchaseToken && oneTimeNotif.notificationType === 2) {
    const tokenHash = hashPurchaseToken(oneTimeNotif.purchaseToken);
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

  return { handled: false, action: "UNHANDLED_NOTIFICATION" };
}
