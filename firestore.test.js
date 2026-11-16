const { createRequire } = require("node:module");
const path = require("node:path");
const requireFunctions = createRequire(path.join(__dirname, "functions", "package.json"));
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = requireFunctions("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = "demo-bleskova-faktura";
const ALICE_UID = "alice_szco_01";
const BOB_UID = "bob_szco_02";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function starterUserDoc(uid) {
  return {
    userId: uid,
    credits: 10,
    isSubscribed: false,
    totalInvoiced: 0,
    totalInvoicedCents: 0,
    invoiceCount: 0,
    activeReservationId: null,
    createdAt: new Date(Date.now() - 1000),
    updatedAt: new Date(Date.now() - 1000),
  };
}

function validInvoiceDoc(uid) {
  return {
    userId: uid,
    supplierName: "Tesco Stores SR, a.s.",
    ico: "31322571",
    dic: "2020301142",
    issueDate: "2026-11-16",
    dueDate: "2026-11-30",
    isPaid: false,
    currency: "EUR",
    totalAmount: 45.9,
    totalAmountCents: 4590,
    vatAmount: 6.93,
    vatAmountCents: 693,
    vatRate: 20.0,
    category: "Kancelária",
    description: "Kancelárske potreby",
    createdAt: new Date(Date.now() - 1000),
    updatedAt: new Date(Date.now() - 1000),
  };
}

before(async () => {
  const rules = fs.readFileSync(path.join(__dirname, "firestore.rules"), "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied read and write on users and invoices", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("invoices").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("invoices")
      .doc("inv-1")
      .set(validInvoiceDoc(ALICE_UID))
  );
});

test("User profile create: owner gets exactly 10 starter credits and cannot self-grant subscription", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(starterUserDoc(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb
      .collection("users")
      .doc(BOB_UID)
      .set({ ...starterUserDoc(BOB_UID), isSubscribed: true })
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(BOB_UID)
      .set({ ...starterUserDoc(BOB_UID), credits: 999 })
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(BOB_UID)
      .set({ ...starterUserDoc(BOB_UID), ghostAdmin: true })
  );
});

test("User credit transitions: reserve (-1), commit, rollback (+1) succeed; arbitrary top-up fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userRef = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(userRef.set(starterUserDoc(ALICE_UID)));

  // Arbitrary credit minting fails
  await assertFails(
    userRef.update({ credits: 100, updatedAt: new Date(Date.now() - 500) })
  );

  // Reserve 1 credit
  await assertSucceeds(
    userRef.update({
      credits: 9,
      activeReservationId: "res-abc-123",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Cannot reserve a second time while reservation active
  await assertFails(
    userRef.update({
      credits: 8,
      activeReservationId: "res-abc-999",
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // Rollback restores 1 credit and clears reservation
  await assertSucceeds(
    userRef.update({
      credits: 10,
      activeReservationId: null,
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Cannot rollback twice
  await assertFails(
    userRef.update({
      credits: 11,
      activeReservationId: null,
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Forged rollback adding +5 credits even while a valid reservation is active must fail
  await assertSucceeds(
    userRef.update({
      credits: 9,
      activeReservationId: "res-forged-check",
      updatedAt: new Date(Date.now() - 50),
    })
  );
  await assertFails(
    userRef.update({
      credits: 14,
      activeReservationId: null,
      updatedAt: new Date(Date.now() - 10),
    })
  );
  await assertFails(
    userRef.update({
      credits: 10,
      activeReservationId: null,
      isSubscribed: true,
      updatedAt: new Date(Date.now() - 10),
    })
  );
});

test("Server-only billingTokens collection: clients cannot read, forge, or replay token records", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const tokenDocId = "a".repeat(64);
  const forgedRecord = {
    tokenHash: tokenDocId,
    ownerUid: ALICE_UID,
    productId: "blesk_credits_100",
    productType: "INAPP",
    packageName: "sk.bizagent.bleskovafaktura",
    status: "CREDITED",
    creditsGranted: 100,
  };

  await assertFails(unauthDb.collection("billingTokens").doc(tokenDocId).get());
  await assertFails(aliceDb.collection("billingTokens").doc(tokenDocId).get());
  await assertFails(aliceDb.collection("billingTokens").doc(tokenDocId).set(forgedRecord));
  await assertFails(aliceDb.collection("billingTokens").doc(tokenDocId).delete());
});

test("Cross-user isolation: Bob cannot read, list, update, or delete Alice invoices", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("invoices")
      .doc("inv-alice-1")
      .set(validInvoiceDoc(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("invoices")
      .doc("inv-alice-1")
      .get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("invoices").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("invoices")
      .doc("inv-alice-1")
      .update({ supplierName: "Hacked", updatedAt: new Date(Date.now() - 500) })
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("invoices")
      .doc("inv-alice-1")
      .delete()
  );
});

test("Invoice validation: rejects negative totalAmount, unknown category, and ghost fields; allows owner update", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const invRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("invoices")
    .doc("inv-valid-1");

  await assertFails(
    invRef.set({ ...validInvoiceDoc(ALICE_UID), totalAmount: -5, totalAmountCents: -500 })
  );
  await assertFails(
    invRef.set({ ...validInvoiceDoc(ALICE_UID), category: "UnknownCat" })
  );
  await assertFails(
    invRef.set({ ...validInvoiceDoc(ALICE_UID), extraGhost: "nope" })
  );

  await assertSucceeds(invRef.set(validInvoiceDoc(ALICE_UID)));
  await assertSucceeds(
    invRef.update({
      supplierName: "Tesco SK",
      isPaid: true,
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("Internal free scanning: invoices and totals sync without spending or granting credits", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userRef = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(userRef.set(starterUserDoc(ALICE_UID)));
  // More scans than the starter credit count demonstrates that normal invoice sync
  // does not depend on the optional paid scan-reservation mechanism or Functions.
  for (let i = 0; i < 12; i += 1) {
    await assertSucceeds(userRef.collection("invoices").doc(`internal-${i}`).set(validInvoiceDoc(ALICE_UID)));
  }
  await assertSucceeds(userRef.update({
    totalInvoiced: 550.8,
    totalInvoicedCents: 55080,
    invoiceCount: 12,
    updatedAt: new Date(Date.now() - 50),
  }));
  const profile = await userRef.get();
  if (profile.get("credits") !== 10 || profile.get("isSubscribed") !== false) {
    throw new Error("Free invoice sync changed the paid entitlement");
  }
});

test("Account deletion: owner can delete invoices and profile, another user cannot", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const userRef = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(userRef.set(starterUserDoc(ALICE_UID)));
  const invoiceRef = userRef.collection("invoices").doc("deletion-invoice");
  await assertSucceeds(invoiceRef.set(validInvoiceDoc(ALICE_UID)));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).delete());
  await assertSucceeds(invoiceRef.delete());
  await assertSucceeds(userRef.delete());
  const invoices = await userRef.collection("invoices").get();
  const profile = await userRef.get();
  if (!invoices.empty || profile.exists) throw new Error("Account data remained after deletion");
});
