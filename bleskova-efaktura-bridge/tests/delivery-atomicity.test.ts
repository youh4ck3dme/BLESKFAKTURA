import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { fileURLToPath } from "node:url";
import type { CanonicalDocument, DeliveryProvider, DeliveryStatus, ProviderSubmission, TenantProfile } from "../packages/contracts/src/index.js";
import { sha256, type IdempotencyRecord, type OutboxJob } from "../packages/core/src/memory.js";
import { SqliteStore } from "../packages/core/src/sqlite.js";
import { createDocumentService, type DocumentService } from "../packages/core/src/service.js";
import { LocalSandboxProvider, ProviderTimeoutError } from "../packages/provider/src/index.js";
import { assertXmlLimits } from "../packages/xml/src/secure.js";
import { inspectUbl, serializeUbl } from "../packages/xml/src/ubl.js";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const secret = "local-only-not-a-production-secret";
const supplier: TenantProfile = {
  id: "tenant-supplier",
  legalName: "SupplierOfficialName Ltd",
  companyId: "GB983294",
  vatId: "GB1232434",
  endpoint: { schemeId: "0088", value: "9482348239847239874" },
  countryCode: "GB",
};
const supplierCtx = { uid: "uid-supplier", tenantId: supplier.id, roles: [] as string[] };

class FaultStore extends SqliteStore {
  outboxFailures = 0;
  completedSendFailures = 0;

  override addOutbox(job: OutboxJob): void {
    if (this.outboxFailures > 0) {
      this.outboxFailures -= 1;
      throw new Error("injected-outbox-failure");
    }
    super.addOutbox(job);
  }

  override putIdempotency(id: string, record: IdempotencyRecord): void {
    if (this.completedSendFailures > 0 && record.state === "COMPLETED" && id.includes("|send|")) {
      this.completedSendFailures -= 1;
      throw new Error("injected-idempotency-failure");
    }
    super.putIdempotency(id, record);
  }
}

function service(store: SqliteStore, provider?: DeliveryProvider): DocumentService {
  const created = createDocumentService({
    env: "local",
    provider: provider ?? new LocalSandboxProvider(secret),
    parser: {
      inspect(xml: string, direction: "INBOUND" | "OUTBOUND") {
        assertXmlLimits(Buffer.from(xml));
        return inspectUbl(xml, direction);
      },
      serialize: serializeUbl,
    },
    validator: {
      async validate(xml, domainIssues) {
        const failed = domainIssues.some((issue) => issue.severity === "ERROR");
        return {
          status: failed ? "INVALID" as const : "UNSUPPORTED" as const,
          rulesetVersion: "test-not-sk",
          validatedSha256: sha256(xml),
          issues: domainIssues,
          layers: [],
          productionEligible: false,
          technicalPass: !failed,
        };
      },
    },
    store,
  });
  created.seedTenant(supplier, "uid-supplier", ["owner"]);
  return created;
}

function officialDocument(number?: string): CanonicalDocument {
  const parsed = inspectUbl(readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"), "utf8"), "OUTBOUND");
  assert.equal(parsed.syntax, "UBL");
  if (parsed.syntax !== "UBL") throw new Error("fixture");
  return number ? { ...parsed.document, number } : parsed.document;
}

async function issueOutbound(api: DocumentService, key: string, number?: string): Promise<string> {
  const draft = await api.createDraft(supplierCtx, officialDocument(number), `${key}-draft`);
  const report = await api.validateDraft(supplierCtx, String(draft.id), `${key}-val`);
  assert.equal(report.technicalPass, true);
  assert.equal(report.productionEligible, false);
  const issued = await api.issueDraft(supplierCtx, String(draft.id), `${key}-issue`);
  return String(issued.id);
}

function sandboxBacked(submit: DeliveryProvider["submit"], lookup: DeliveryProvider["lookup"]): { provider: DeliveryProvider; inner: LocalSandboxProvider } {
  const inner = new LocalSandboxProvider(secret);
  return {
    inner,
    provider: {
      capabilities: () => inner.capabilities(),
      submit,
      lookup,
      verifyWebhook: (input) => inner.verifyWebhook(input),
    },
  };
}

function tempFile(): { dir: string; file: string } {
  const dir = mkdtempSync(path.join(tmpdir(), "efaktura-atomic-"));
  return { dir, file: path.join(dir, "bridge.sqlite") };
}

test("failed outbox insert does not leave QUEUED without a job after restart", async () => {
  const { dir, file } = tempFile();
  const store = new FaultStore(file);
  store.outboxFailures = 1;
  const first = service(store);
  const id = await issueOutbound(first, "atom");
  await assert.rejects(() => first.send(supplierCtx, id, "send-atom"), /injected-outbox-failure/);
  assert.equal(store.getDocument(id)?.deliveryStatus, "NOT_REQUESTED");
  assert.equal(store.listOutbox().length, 0);
  assert.notEqual(store.getIdempotency(`${supplier.id}|send|${id}|send-atom`)?.state, "COMPLETED");
  assert.equal(store.countDocuments(supplier.id), 1);
  store.close();

  const restarted = new SqliteStore(file);
  const second = service(restarted);
  assert.equal(second.get(supplierCtx, id).delivery.status, "NOT_REQUESTED");
  const queued = await second.send(supplierCtx, id, "send-atom");
  assert.equal(queued.delivery.status, "QUEUED");
  assert.equal(queued.delivery.productionReceipt, false);
  assert.equal(restarted.listOutbox().length, 1);
  assert.equal(restarted.listOutbox()[0]?.state, "PENDING");
  assert.equal(restarted.listOutbox()[0]?.phase, "NEW");
  const replay = await second.send(supplierCtx, id, "send-atom");
  assert.equal(replay.id, queued.id);
  assert.equal(restarted.listOutbox().length, 1);
  restarted.close();
  rmSync(dir, { recursive: true, force: true });
});

test("idempotent send result commits in the same transaction as the outbox job", async () => {
  const { dir, file } = tempFile();
  const store = new FaultStore(file);
  store.completedSendFailures = 1;
  const first = service(store);
  const id = await issueOutbound(first, "idem");
  await assert.rejects(() => first.send(supplierCtx, id, "send-idem"), /injected-idempotency-failure/);
  assert.equal(store.getDocument(id)?.deliveryStatus, "NOT_REQUESTED");
  assert.equal(store.listOutbox().length, 0);
  assert.notEqual(store.getIdempotency(`${supplier.id}|send|${id}|send-idem`)?.state, "COMPLETED");
  store.close();

  const restarted = new SqliteStore(file);
  const second = service(restarted);
  const queued = await second.send(supplierCtx, id, "send-idem");
  assert.equal(queued.delivery.status, "QUEUED");
  assert.equal(restarted.listOutbox().length, 1);
  assert.equal(restarted.getIdempotency(`${supplier.id}|send|${id}|send-idem`)?.state, "COMPLETED");
  restarted.close();
  rmSync(dir, { recursive: true, force: true });
});

test("UNKNOWN lookup stays scheduled across backoff and restart without a second submit", async () => {
  const script: DeliveryStatus[] = ["UNKNOWN", "UNKNOWN", "PROVIDER_ACCEPTED"];
  let submits = 0;
  let lookups = 0;
  const { provider } = sandboxBacked(
    async () => {
      submits += 1;
      throw new ProviderTimeoutError();
    },
    async () => {
      const status = script[Math.min(lookups, script.length - 1)] ?? "PROVIDER_ACCEPTED";
      lookups += 1;
      return { status, simulated: true, productionReceipt: false, providerMessageId: "lookup-1", detail: status };
    },
  );
  const { dir, file } = tempFile();
  const firstStore = new SqliteStore(file);
  const first = service(firstStore, provider);
  const id = await issueOutbound(first, "unknown");
  await first.send(supplierCtx, id, "send-unknown");
  const t0 = 10_000_000;
  await first.workerTick(t0);
  assert.equal(submits, 1);
  assert.equal(lookups, 1);
  assert.equal(first.delivery(supplierCtx, id).status, "UNKNOWN");
  assert.equal(first.delivery(supplierCtx, id).productionReceipt, false);
  const job = firstStore.listOutbox()[0];
  assert.ok(job);
  assert.equal(job.state, "UNCERTAIN");
  assert.equal(job.phase, "SUBMITTED");
  assert.equal(firstStore.listOutbox().length, 1);
  const gap1 = job.leaseUntil - t0;
  assert.ok(gap1 > 0);
  const skipped = await first.workerTick(t0 + 1);
  assert.equal(skipped.processed, false);
  assert.equal(lookups, 1);
  assert.equal(submits, 1);
  firstStore.close();

  const secondStore = new SqliteStore(file);
  const second = service(secondStore, provider);
  const lease1 = secondStore.listOutbox()[0]?.leaseUntil ?? 0;
  await second.workerTick(lease1 + 1);
  assert.equal(submits, 1);
  assert.equal(lookups, 2);
  assert.equal(second.delivery(supplierCtx, id).status, "UNKNOWN");
  const job2 = secondStore.listOutbox()[0];
  assert.equal(job2?.state, "UNCERTAIN");
  assert.equal(job2?.phase, "SUBMITTED");
  const gap2 = (job2?.leaseUntil ?? 0) - (lease1 + 1);
  assert.ok(gap2 > gap1);
  await second.workerTick(lease1 + 1);
  assert.equal(lookups, 2);
  const lease2 = job2?.leaseUntil ?? 0;
  await second.workerTick(lease2 + 1);
  assert.equal(submits, 1);
  assert.equal(lookups, 3);
  assert.equal(second.delivery(supplierCtx, id).status, "PROVIDER_ACCEPTED");
  assert.equal(second.delivery(supplierCtx, id).productionReceipt, false);
  assert.equal(second.delivery(supplierCtx, id).simulated, true);
  assert.equal(secondStore.listOutbox()[0]?.state, "DONE");
  assert.equal(secondStore.countDocuments(supplier.id), 1);
  secondStore.close();
  rmSync(dir, { recursive: true, force: true });
});

test("two sqlite connections keep a newer webhook ahead of a stale worker result", async () => {
  let releaseSubmit: (value: ProviderSubmission) => void = () => undefined;
  const gate = new Promise<ProviderSubmission>((resolve) => {
    releaseSubmit = resolve;
  });
  let markStarted: () => void = () => undefined;
  const started = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  let submits = 0;
  const { provider, inner } = sandboxBacked(
    async () => {
      submits += 1;
      markStarted();
      return gate;
    },
    async () => ({ status: "UNKNOWN", simulated: true, productionReceipt: false, detail: "unused" }),
  );
  const { dir, file } = tempFile();
  const storeA = new SqliteStore(file);
  const storeB = new SqliteStore(file);
  const worker = service(storeA, provider);
  const hook = service(storeB, provider);
  const id = await issueOutbound(worker, "race");
  await worker.send(supplierCtx, id, "send-race");
  const tick = worker.workerTick();
  await started;
  assert.equal(storeB.getDocument(id)?.deliveryStatus, "SUBMITTING");
  const event = {
    eventId: randomUUID(),
    providerMessageId: "wh-newer",
    documentId: id,
    status: "DELIVERED" as const,
    occurredAt: new Date().toISOString(),
  };
  const raw = Buffer.from(JSON.stringify(event));
  const stamp = Math.floor(Date.now() / 1000);
  const receivedAt = new Date(stamp * 1000).toISOString();
  const applied = await hook.webhook("local-sandbox", raw, { "x-provider-signature": inner.sign(raw, stamp) }, receivedAt);
  assert.equal(applied.applied, true);
  assert.equal(applied.productionReceipt, false);
  const deliveredVersion = storeB.getDocument(id)?.version;
  assert.equal(storeB.getDocument(id)?.deliveryStatus, "DELIVERED");
  assert.equal(storeB.getDocument(id)?.providerMessageId, "wh-newer");
  releaseSubmit({
    providerMessageId: "submit-stale",
    status: "PROVIDER_ACCEPTED",
    simulated: true,
    productionReceipt: true,
    detail: "stale-submit",
  });
  await tick;
  assert.equal(submits, 1);
  assert.equal(storeA.getDocument(id)?.deliveryStatus, "DELIVERED");
  assert.equal(storeB.getDocument(id)?.deliveryStatus, "DELIVERED");
  assert.equal(storeA.getDocument(id)?.providerMessageId, "wh-newer");
  assert.equal(storeA.getDocument(id)?.productionReceipt, false);
  assert.equal(storeA.listOutbox()[0]?.state, "DONE");
  assert.equal(storeA.listOutbox().length, 1);
  const versionAfterWorker = storeA.getDocument(id)?.version;
  assert.equal(versionAfterWorker, deliveredVersion);

  const duplicate = { ...event, eventId: randomUUID() };
  const duplicateRaw = Buffer.from(JSON.stringify(duplicate));
  const duplicateResult = await hook.webhook("local-sandbox", duplicateRaw, { "x-provider-signature": inner.sign(duplicateRaw, stamp) }, receivedAt);
  assert.equal(duplicateResult.applied, false);
  assert.equal(duplicateResult.ignored, true);
  assert.equal(storeB.getDocument(id)?.version, versionAfterWorker);
  assert.equal(storeB.getDocument(id)?.deliveryStatus, "DELIVERED");

  const terminal = { ...event, eventId: randomUUID(), status: "REJECTED" as const, providerMessageId: "wh-reject" };
  const terminalRaw = Buffer.from(JSON.stringify(terminal));
  const terminalResult = await hook.webhook("local-sandbox", terminalRaw, { "x-provider-signature": inner.sign(terminalRaw, stamp) }, receivedAt);
  assert.equal(terminalResult.applied, false);
  assert.equal(terminalResult.ignored, true);
  assert.equal(storeA.getDocument(id)?.deliveryStatus, "DELIVERED");
  assert.equal(storeA.getDocument(id)?.providerMessageId, "wh-newer");
  assert.equal(storeA.getDocument(id)?.version, versionAfterWorker);

  await worker.workerTick(Date.now() + 60_000);
  assert.equal(submits, 1);
  storeA.close();
  storeB.close();
  rmSync(dir, { recursive: true, force: true });
});

test("worker lookup cannot leave a terminal status or replay a duplicate result", async () => {
  const lookups = new Map<string, DeliveryStatus>();
  const { provider, inner } = sandboxBacked(
    async (input) => {
      const status: DeliveryStatus = input.idempotencyKey.endsWith("fail") ? "FAILED" : "REJECTED";
      return { status, providerMessageId: `msg-${status}`, simulated: true, productionReceipt: false, detail: status };
    },
    async (input) => ({
      status: lookups.get(input.documentId) ?? "UNKNOWN",
      providerMessageId: "lookup-late",
      simulated: true,
      productionReceipt: true,
      detail: "late-lookup",
    }),
  );
  const { dir, file } = tempFile();
  const store = new SqliteStore(file);
  const api = service(store, provider);
  const rejectedId = await issueOutbound(api, "rej", "REJECT-1");
  const failedId = await issueOutbound(api, "fail", "FAIL-1");
  await api.send(supplierCtx, rejectedId, "send-rej");
  await api.send(supplierCtx, failedId, "send-fail");
  await api.workerTick();
  await api.workerTick();
  assert.equal(api.delivery(supplierCtx, rejectedId).status, "REJECTED");
  assert.equal(api.delivery(supplierCtx, failedId).status, "FAILED");
  assert.equal(api.delivery(supplierCtx, rejectedId).productionReceipt, false);
  const rejectedVersion = store.getDocument(rejectedId)?.version;
  const failedVersion = store.getDocument(failedId)?.version;
  for (const id of [rejectedId, failedId]) {
    const job = store.listOutbox().find((item) => item.documentId === id);
    assert.ok(job);
    job.state = "UNCERTAIN";
    job.phase = "SUBMITTED";
    job.leaseUntil = 0;
    store.saveOutbox(job);
  }
  lookups.set(rejectedId, "DELIVERED");
  lookups.set(failedId, "PROVIDER_ACCEPTED");
  await api.workerTick(1);
  await api.workerTick(1);
  assert.equal(store.getDocument(rejectedId)?.deliveryStatus, "REJECTED");
  assert.equal(store.getDocument(rejectedId)?.providerMessageId, "msg-REJECTED");
  assert.equal(store.getDocument(rejectedId)?.version, rejectedVersion);
  assert.equal(store.getDocument(rejectedId)?.productionReceipt, false);
  assert.equal(store.getDocument(failedId)?.deliveryStatus, "FAILED");
  assert.equal(store.getDocument(failedId)?.providerMessageId, "msg-FAILED");
  assert.equal(store.getDocument(failedId)?.version, failedVersion);

  const event = {
    eventId: randomUUID(),
    providerMessageId: "msg-FAILED",
    documentId: failedId,
    status: "FAILED" as const,
    occurredAt: new Date().toISOString(),
  };
  const raw = Buffer.from(JSON.stringify(event));
  const stamp = Math.floor(Date.now() / 1000);
  const receivedAt = new Date(stamp * 1000).toISOString();
  const duplicate = await api.webhook("local-sandbox", raw, { "x-provider-signature": inner.sign(raw, stamp) }, receivedAt);
  assert.equal(duplicate.applied, false);
  assert.equal(duplicate.ignored, true);
  assert.equal(store.getDocument(failedId)?.version, failedVersion);
  const forward = {
    ...event,
    eventId: randomUUID(),
    status: "DELIVERED" as const,
    providerMessageId: "should-not-stick",
  };
  const forwardRaw = Buffer.from(JSON.stringify(forward));
  const ignored = await api.webhook("local-sandbox", forwardRaw, { "x-provider-signature": inner.sign(forwardRaw, stamp) }, receivedAt);
  assert.equal(ignored.applied, false);
  assert.equal(store.getDocument(failedId)?.deliveryStatus, "FAILED");
  assert.equal(store.getDocument(failedId)?.version, failedVersion);
  store.close();
  rmSync(dir, { recursive: true, force: true });
});
