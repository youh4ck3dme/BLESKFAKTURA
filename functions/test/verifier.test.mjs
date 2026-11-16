import { test } from "node:test";
import assert from "node:assert/strict";
import {
  EXPECTED_PACKAGE_NAME,
  VerificationError,
  hashPurchaseToken,
  processPlayRtdnNotification,
  verifyAndGrantPlayPurchase,
} from "../src/verifier.ts";

function createInMemoryStore() {
  const tokens = new Map();
  const users = new Map();
  return {
    tokens,
    users,
    async runInTransaction(fn) {
      const stagedTokens = new Map(tokens);
      const stagedUsers = new Map(users);
      const result = await fn({
        async getToken(hash) {
          return stagedTokens.get(hash) ?? null;
        },
        setToken(hash, record) {
          stagedTokens.set(hash, record);
        },
        async grantInAppCredits(uid, delta) {
          const cur = stagedUsers.get(uid) ?? { credits: 10, isSubscribed: false };
          stagedUsers.set(uid, { ...cur, credits: Math.max(0, cur.credits + delta) });
        },
        async setUserSubscription(uid, isSubscribed, expiryMillis) {
          const cur = stagedUsers.get(uid) ?? { credits: 10, isSubscribed: false };
          stagedUsers.set(uid, { ...cur, isSubscribed, expiryMillis });
        },
      });
      tokens.clear();
      for (const [k, v] of stagedTokens.entries()) tokens.set(k, v);
      users.clear();
      for (const [k, v] of stagedUsers.entries()) users.set(k, v);
      return result;
    },
  };
}

function createFakePlayClient(overrides = {}) {
  const consumed = [];
  const acknowledged = [];
  return {
    consumed,
    acknowledged,
    async getProductPurchase() {
      return {
        purchaseState: 0,
        acknowledgementState: 0,
        consumptionState: 0,
        orderId: "GPA.1234-5678-9012-34567",
      };
    },
    async consumeProductPurchase(pkg, productId, token) {
      consumed.push({ pkg, productId, token });
    },
    async getSubscriptionV2() {
      return {
        subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
        acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
        latestOrderId: "GPA.9999-0000-1111",
        expiryTimeMillis: Date.now() + 30 * 86400_000,
        productIdFromLineItems: "blesk_premium_monthly",
      };
    },
    async acknowledgeSubscription(pkg, productId, token) {
      acknowledged.push({ pkg, productId, token });
    },
    ...overrides,
  };
}

test("Rejects packageName mismatch and unknown productId", async () => {
  const store = createInMemoryStore();
  const client = createFakePlayClient();

  await assert.rejects(
    () =>
      verifyAndGrantPlayPurchase(
        {
          uid: "user_1",
          packageName: "com.fake.app",
          productId: "blesk_credits_10",
          purchaseToken: "tok_valid_1234567890",
        },
        client,
        store
      ),
    (err) => err instanceof VerificationError && err.code === "invalid-argument"
  );

  await assert.rejects(
    () =>
      verifyAndGrantPlayPurchase(
        {
          uid: "user_1",
          packageName: EXPECTED_PACKAGE_NAME,
          productId: "hacked_unlimited_free",
          purchaseToken: "tok_valid_1234567890",
        },
        client,
        store
      ),
    (err) => err instanceof VerificationError && err.code === "invalid-argument"
  );
});

test("Rejects uncompleted INAPP purchaseState (Pending or Canceled)", async () => {
  const store = createInMemoryStore();
  const client = createFakePlayClient({
    async getProductPurchase() {
      return { purchaseState: 2, acknowledgementState: 0, consumptionState: 0 };
    },
  });

  await assert.rejects(
    () =>
      verifyAndGrantPlayPurchase(
        {
          uid: "user_1",
          packageName: EXPECTED_PACKAGE_NAME,
          productId: "blesk_credits_50",
          purchaseToken: "tok_pending_1234567890",
        },
        client,
        store
      ),
    (err) => err instanceof VerificationError && err.code === "failed-precondition"
  );
  assert.equal(store.users.size, 0);
});

test("Grants INAPP credits once, ensures idempotency on retry, and blocks cross-user replay", async () => {
  const store = createInMemoryStore();
  const client = createFakePlayClient();
  const token = "tok_inapp_50_abcdef12345";

  const first = await verifyAndGrantPlayPurchase(
    {
      uid: "alice_uid",
      packageName: EXPECTED_PACKAGE_NAME,
      productId: "blesk_credits_50",
      purchaseToken: token,
    },
    client,
    store
  );
  assert.equal(first.status, "GRANTED");
  assert.equal(first.idempotent, false);
  assert.equal(first.creditsGranted, 50);
  assert.equal(store.users.get("alice_uid").credits, 60);
  assert.equal(client.consumed.length, 1);

  // Idempotent retry by same owner does not double-grant or re-consume
  const retry = await verifyAndGrantPlayPurchase(
    {
      uid: "alice_uid",
      packageName: EXPECTED_PACKAGE_NAME,
      productId: "blesk_credits_50",
      purchaseToken: token,
    },
    client,
    store
  );
  assert.equal(retry.status, "ALREADY_PROCESSED");
  assert.equal(retry.idempotent, true);
  assert.equal(retry.creditsGranted, 0);
  assert.equal(store.users.get("alice_uid").credits, 60);
  assert.equal(client.consumed.length, 1);

  // Replay attempt by another user fails with permission-denied
  await assert.rejects(
    () =>
      verifyAndGrantPlayPurchase(
        {
          uid: "attacker_uid",
          packageName: EXPECTED_PACKAGE_NAME,
          productId: "blesk_credits_50",
          purchaseToken: token,
        },
        client,
        store
      ),
    (err) => err instanceof VerificationError && err.code === "permission-denied"
  );
  assert.equal(store.users.has("attacker_uid"), false);
});

test("Activates and acknowledges SUBS and revokes via RTDN on cancellation", async () => {
  const store = createInMemoryStore();
  let subState = "SUBSCRIPTION_STATE_ACTIVE";
  const client = createFakePlayClient({
    async getSubscriptionV2() {
      return {
        subscriptionState: subState,
        acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
        latestOrderId: "GPA.SUB.001",
        expiryTimeMillis: Date.now() + 86400_000,
        productIdFromLineItems: "blesk_premium_monthly",
      };
    },
  });
  const subToken = "tok_sub_monthly_987654321";

  const activeRes = await verifyAndGrantPlayPurchase(
    {
      uid: "bob_uid",
      packageName: EXPECTED_PACKAGE_NAME,
      productId: "blesk_premium_monthly",
      purchaseToken: subToken,
    },
    client,
    store
  );
  assert.equal(activeRes.status, "SUBSCRIPTION_ACTIVE");
  assert.equal(activeRes.isSubscribed, true);
  assert.equal(store.users.get("bob_uid").isSubscribed, true);
  assert.equal(client.acknowledged.length, 1);

  // Simulate RTDN revocation
  subState = "SUBSCRIPTION_STATE_EXPIRED";
  const rtdnRes = await processPlayRtdnNotification(
    {
      packageName: EXPECTED_PACKAGE_NAME,
      subscriptionNotification: {
        notificationType: 12,
        purchaseToken: subToken,
        subscriptionId: "blesk_premium_monthly",
      },
    },
    client,
    store
  );
  assert.equal(rtdnRes.handled, true);
  assert.equal(rtdnRes.action, "SUBSCRIPTION_REVOKED");
  assert.equal(store.users.get("bob_uid").isSubscribed, false);
  assert.equal(store.tokens.get(hashPurchaseToken(subToken)).status, "REVOKED");
});
