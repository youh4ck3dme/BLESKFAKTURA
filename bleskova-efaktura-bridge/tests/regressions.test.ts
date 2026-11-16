import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { Writable } from "node:stream";
import type { IncomingMessage, ServerResponse } from "node:http";
import { createApi } from "../apps/api/src/server.js";
import { assertBoot, loadEnvFile, readBootConfig } from "../apps/api/src/env.js";
import { signEmuToken } from "../apps/api/src/emu-token.js";
import { applyLocalProvision, applyLocalSeed } from "../apps/api/src/seed.js";
import type { DeliveryProvider, TenantProfile, ValidationIssue, ValidationReport } from "../packages/contracts/src/index.js";
import { AppError } from "../packages/core/src/errors.js";
import { sha256, type DocumentStore } from "../packages/core/src/memory.js";
import { SqliteStore } from "../packages/core/src/sqlite.js";
import { createDocumentService, type DocumentService } from "../packages/core/src/service.js";
import { LocalSandboxProvider } from "../packages/provider/src/index.js";
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
const buyer: TenantProfile = {
  id: "tenant-buyer",
  legalName: "Buyer Official Name",
  companyId: "39937423947",
  vatId: "SE4598375937",
  endpoint: { schemeId: "0002", value: "FR23342" },
  countryCode: "SE",
};
const supplierCtx = { uid: "uid-supplier", tenantId: supplier.id, roles: [] as string[] };
const buyerCtx = { uid: "uid-buyer", tenantId: buyer.id, roles: [] as string[] };

function parser(inspect = inspectUbl) {
  return {
    inspect(xml: string, direction: "INBOUND" | "OUTBOUND") {
      assertXmlLimits(Buffer.from(xml));
      return inspect(xml, direction);
    },
    serialize: serializeUbl,
  };
}

function acceptingValidator() {
  return {
    async validate(xml: Uint8Array, domainIssues: ValidationIssue[]): Promise<ValidationReport> {
      const failed = domainIssues.some((issue) => issue.severity === "ERROR");
      return {
        status: failed ? "INVALID" : "UNSUPPORTED",
        rulesetVersion: "test-not-sk",
        validatedSha256: sha256(xml),
        issues: domainIssues,
        layers: [],
        productionEligible: false,
        technicalPass: !failed,
      };
    },
  };
}

function seed(api: DocumentService): void {
  api.seedTenant(supplier, "uid-supplier", ["owner"]);
  api.seedTenant(buyer, "uid-buyer", ["owner"]);
  api.seedTenant(buyer, "uid-reader", ["reader"]);
}

function api(options?: { store?: DocumentStore; provider?: DeliveryProvider; inspect?: typeof inspectUbl }): DocumentService {
  const created = createDocumentService({
    env: "local",
    provider: options?.provider ?? new LocalSandboxProvider(secret),
    parser: parser(options?.inspect),
    validator: acceptingValidator(),
    store: options?.store,
  });
  seed(created);
  return created;
}

function official(): Buffer {
  return readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"));
}

test("xml bombs are rejected in a bounded subprocess", async () => {
  const child = path.join(root, "dist/tests/xml-guard.child.js");
  for (const mode of ["deep", "wide", "xxe"]) {
    const result = await new Promise<{ code: number | null; stdout: string }>((resolve, reject) => {
      const proc = spawn(process.execPath, ["--max-old-space-size=96", child, mode], { timeout: 8000 });
      const out: Buffer[] = [];
      proc.stdout.on("data", (chunk) => out.push(Buffer.from(chunk)));
      proc.on("error", reject);
      proc.on("close", (code) => resolve({ code, stdout: Buffer.concat(out).toString("utf8") }));
    });
    assert.equal(result.code, 0, `${mode} ${result.stdout}`);
    assert.match(result.stdout, /^rejected /);
  }
});

test("concurrent imports of the same XML create one document", async () => {
  const service = api();
  const bytes = official();
  const [first, second] = await Promise.all([
    service.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "conc-a", direction: "INBOUND" }),
    service.importXml(buyerCtx, { bytes, filename: "b.xml", idempotencyKey: "conc-b", direction: "INBOUND" }),
  ]);
  assert.equal(first.document.id, second.document.id);
  assert.equal(service.store.countDocuments(buyer.id), 1);
  assert.equal(service.store.countDocuments(supplier.id), 0);
  const downloaded = service.original(buyerCtx, first.document.id);
  assert.equal(Buffer.from(downloaded.bytes).equals(bytes), true);
  assert.equal(downloaded.sha256, sha256(bytes));
});

test("failed import can be retried and a transient error is not stuck", async () => {
  const service = api();
  const bytes = official();
  await assert.rejects(
    () => service.importXml(supplierCtx, { bytes, filename: "a.xml", idempotencyKey: "bad-tenant", direction: "INBOUND" }),
    (error: unknown) => error instanceof AppError && error.code === "TENANT_MISMATCH",
  );
  await assert.rejects(
    () => service.importXml(supplierCtx, { bytes, filename: "a.xml", idempotencyKey: "bad-tenant", direction: "INBOUND" }),
    (error: unknown) => error instanceof AppError && error.code === "TENANT_MISMATCH",
  );
  assert.equal(service.store.countDocuments(), 0);

  let left = 1;
  const flaky = api({
    inspect(xml, direction) {
      if (left > 0) {
        left -= 1;
        throw new Error("transient-parser");
      }
      return inspectUbl(xml, direction);
    },
  });
  await assert.rejects(() => flaky.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "retry-me", direction: "INBOUND" }));
  const recovered = await flaky.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "retry-me", direction: "INBOUND" });
  assert.equal(flaky.store.countDocuments(buyer.id), 1);
  assert.equal(recovered.document.sha256, sha256(bytes));

  const hash = sha256(bytes);
  service.store.putIdempotency(`${buyer.id}|import|stuck`, {
    requestHash: hash,
    state: "IN_PROGRESS",
    updatedAt: Date.now(),
  });
  await assert.rejects(
    () => service.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "stuck", direction: "INBOUND" }),
    (error: unknown) => error instanceof AppError && error.code === "IDEMPOTENCY_IN_PROGRESS",
  );
});

test("worker claims one job and an expired lease reconciles without a second submit", async () => {
  let submits = 0;
  let lookups = 0;
  const inner = new LocalSandboxProvider(secret);
  const provider: DeliveryProvider = {
    capabilities: () => inner.capabilities(),
    async submit(input) {
      submits += 1;
      return inner.submit(input);
    },
    async lookup(input) {
      lookups += 1;
      return inner.lookup(input);
    },
    verifyWebhook: (input) => inner.verifyWebhook(input),
  };
  const service = api({ provider });
  const parsed = inspectUbl(official().toString("utf8"), "OUTBOUND");
  assert.equal(parsed.syntax, "UBL");
  if (parsed.syntax !== "UBL") return;
  const draft = await service.createDraft(supplierCtx, parsed.document, "draft-w");
  await service.validateDraft(supplierCtx, String(draft.id), "val-w");
  const issued = await service.issueDraft(supplierCtx, String(draft.id), "issue-w");
  await service.send(supplierCtx, String(issued.id), "send-w");
  await service.send(supplierCtx, String(issued.id), "send-w");
  assert.equal(service.store.listOutbox().length, 1);
  const idem = service.store.getIdempotency(`${supplier.id}|send|${issued.id}|send-w`);
  assert.ok(idem);
  service.store.putIdempotency(`${supplier.id}|send|${issued.id}|send-w`, { ...idem, state: "UNCERTAIN", response: issued });
  await service.send(supplierCtx, String(issued.id), "send-w");
  assert.equal(service.store.listOutbox().length, 1);

  const [first, second] = await Promise.all([service.workerTick(), service.workerTick()]);
  assert.equal(first.processed || second.processed, true);
  assert.equal(submits, 1);
  assert.equal(service.delivery(supplierCtx, String(issued.id)).productionReceipt, false);
  assert.equal(service.store.countDocuments(supplier.id), 1);

  const job = service.store.listOutbox()[0];
  assert.ok(job);
  job.phase = "SUBMITTING";
  job.state = "PENDING";
  job.leaseUntil = 0;
  service.store.saveOutbox(job);
  const before = submits;
  await service.workerTick();
  assert.equal(submits, before);
  assert.equal(lookups >= 1, true);
  assert.equal(service.delivery(supplierCtx, String(issued.id)).productionReceipt, false);
});

test("sqlite survives restart and does not submit an uncertain delivery twice", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "efaktura-sqlite-"));
  const file = path.join(dir, "bridge.sqlite");
  const bytes = official();
  const firstStore = new SqliteStore(file);
  const first = api({ store: firstStore });
  const imported = await first.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "sql-1", direction: "INBOUND" });
  const parsed = inspectUbl(bytes.toString("utf8"), "OUTBOUND");
  assert.equal(parsed.syntax, "UBL");
  if (parsed.syntax !== "UBL") return;
  const draft = await first.createDraft(supplierCtx, parsed.document, "sql-draft");
  await first.validateDraft(supplierCtx, String(draft.id), "sql-val");
  const issued = await first.issueDraft(supplierCtx, String(draft.id), "sql-issue");
  await first.send(supplierCtx, String(issued.id), "sql-send");
  const pending = first.store.listOutbox()[0];
  assert.ok(pending);
  pending.phase = "SUBMITTING";
  pending.state = "PENDING";
  pending.leaseUntil = 0;
  first.store.saveOutbox(pending);
  firstStore.close();

  let submits = 0;
  let lookups = 0;
  const inner = new LocalSandboxProvider(secret);
  const provider: DeliveryProvider = {
    capabilities: () => inner.capabilities(),
    async submit(input) {
      submits += 1;
      return inner.submit(input);
    },
    async lookup(input) {
      lookups += 1;
      return inner.lookup(input);
    },
    verifyWebhook: (input) => inner.verifyWebhook(input),
  };
  const secondStore = new SqliteStore(file);
  const second = api({ store: secondStore, provider });
  const again = second.original(buyerCtx, imported.document.id);
  assert.equal(Buffer.from(again.bytes).equals(bytes), true);
  assert.equal(again.sha256, sha256(bytes));
  const replay = await second.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "sql-1", direction: "INBOUND" });
  assert.equal(replay.document.id, imported.document.id);
  assert.equal(second.store.countDocuments(buyer.id), 1);
  assert.equal(second.get(supplierCtx, String(issued.id)).number, "Snippet1");
  const resent = await second.issueDraft(supplierCtx, String(draft.id), "sql-issue");
  assert.equal(resent.id, issued.id);
  await second.workerTick();
  assert.equal(submits, 0);
  assert.equal(lookups, 1);
  assert.equal(second.delivery(supplierCtx, String(issued.id)).productionReceipt, false);
  assert.equal(second.delivery(supplierCtx, String(issued.id)).simulated, true);
  secondStore.close();
  rmSync(dir, { recursive: true, force: true });
});

test("two sqlite connections cannot claim the same outbox job", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "efaktura-claim-"));
  const file = path.join(dir, "bridge.sqlite");
  const a = new SqliteStore(file);
  const b = new SqliteStore(file);
  a.addOutbox({
    id: "job-1",
    tenantId: "t",
    documentId: "d",
    idempotencyKey: "k",
    attempts: 0,
    leaseUntil: 0,
    state: "PENDING",
    phase: "NEW",
  });
  const first = a.claimOutbox(Date.now(), 30_000);
  const second = b.claimOutbox(Date.now(), 30_000);
  assert.ok(first);
  assert.equal(second, undefined);
  a.close();
  b.close();
  rmSync(dir, { recursive: true, force: true });
});

test("tenant header is not membership and production refuses local seed", async () => {
  const service = api();
  const handler = createApi({ service, env: "local", authMode: "emulator", emulatorSecret: secret, rateLimitPerMinute: 500 });
  const missing = await invoke(handler, "GET", `/v1/tenants/${buyer.id}/documents`, {
    authorization: `Bearer ${signEmuToken(secret, { uid: "uid-buyer", typ: "user", exp: Date.now() + 60_000 })}`,
    "x-firebase-appcheck": signEmuToken(secret, { typ: "appcheck", exp: Date.now() + 60_000 }),
  });
  assert.equal(missing.status, 401);
  const mismatch = await invoke(handler, "GET", `/v1/tenants/${buyer.id}/documents`, tokenHeaders("uid-buyer", supplier.id));
  assert.equal(mismatch.status, 403);
  const foreign = await invoke(handler, "GET", `/v1/tenants/${supplier.id}/documents`, tokenHeaders("uid-buyer", supplier.id));
  assert.equal(foreign.status, 403);
  const reader = await invoke(handler, "POST", `/v1/tenants/${buyer.id}/documents/import?direction=INBOUND`, tokenHeaders("uid-reader", buyer.id), Buffer.from("<a/>"), { "idempotency-key": "r1" });
  assert.equal(reader.status, 403);
  const ok = await invoke(handler, "GET", `/v1/tenants/${buyer.id}/documents`, tokenHeaders("uid-buyer", buyer.id));
  assert.equal(ok.status, 200);

  const prod = readBootConfig(root, { ...process.env, EFAKTURA_ENV: "production", EFAKTURA_AUTH_MODE: "emulator", EFAKTURA_PROVIDER: "local-sandbox", EFAKTURA_LOCAL_SEED: "1", EFAKTURA_STORE: "memory" });
  assert.throws(() => assertBoot(prod), /emulátor|sandbox|seed|pamäťový/);
  assert.throws(() => applyLocalSeed("production", service.store), /produkcii/);
  const local = new (service.store.constructor as new () => DocumentStore)();
  applyLocalSeed("local", local);
  assert.deepEqual(local.getMember("uid-buyer", "tenant-buyer")?.roles, ["owner"]);
  local.close();
});

test("bind host defaults to all interfaces and rejects junk", () => {
  assert.equal(readBootConfig(root, {}).host, "0.0.0.0");
  assert.equal(readBootConfig(root, { EFAKTURA_HOST: "127.0.0.1", EFAKTURA_PORT: "0" }).host, "127.0.0.1");
  assert.equal(readBootConfig(root, { EFAKTURA_PORT: "0" }).port, 0);
  assert.throws(() => readBootConfig(root, { EFAKTURA_HOST: "http://evil" }), /EFAKTURA_HOST/);
});

test("explicit local provision does not trust the token and production refuses it", async () => {
  assert.throws(() => readBootConfig(root, {
    EFAKTURA_ENV: "production",
    EFAKTURA_AUTH_MODE: "disabled",
    EFAKTURA_PROVIDER: "unconfigured",
    EFAKTURA_LOCAL_SEED: "0",
    EFAKTURA_STORE: "sqlite",
    EFAKTURA_LOCAL_PROVISION_UID: "android-uid",
    EFAKTURA_LOCAL_PROVISION_TENANT: "tenant-buyer",
    EFAKTURA_LOCAL_PROVISION_ROLE: "owner",
  }), /provisioning/);
  assert.throws(() => readBootConfig(root, {
    EFAKTURA_ENV: "local",
    EFAKTURA_LOCAL_PROVISION_UID: "*",
    EFAKTURA_LOCAL_PROVISION_TENANT: "tenant-buyer",
    EFAKTURA_LOCAL_PROVISION_ROLE: "owner",
  }), /UID/);
  const parsed = readBootConfig(root, {
    EFAKTURA_ENV: "local",
    EFAKTURA_LOCAL_SEED: "1",
    EFAKTURA_LOCAL_PROVISION_UID: "android-uid",
    EFAKTURA_LOCAL_PROVISION_TENANT: "tenant-buyer",
    EFAKTURA_LOCAL_PROVISION_ROLE: "owner",
  });
  assert.equal(parsed.provision?.uid, "android-uid");
  const service = api();
  applyLocalProvision("local", service.store, parsed.provision);
  const handler = createApi({ service, env: "local", authMode: "emulator", emulatorSecret: secret, rateLimitPerMinute: 50 });
  const stranger = await invoke(handler, "GET", `/v1/tenants/${buyer.id}/documents`, tokenHeaders("android-other", buyer.id));
  assert.equal(stranger.status, 403);
  const member = await invoke(handler, "GET", `/v1/tenants/${buyer.id}/documents`, tokenHeaders("android-uid", buyer.id));
  assert.equal(member.status, 200);
  assert.equal(service.store.getMember("android-other", buyer.id), undefined);
  assert.throws(() => applyLocalProvision("production", service.store, parsed.provision), /produkcii/);
  service.store.close();
});

test("loadEnvFile does not override existing variables", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "efaktura-env-"));
  const file = path.join(dir, ".env");
  writeFileSync(file, "EFAKTURA_FROM_FILE=from-file\nEFAKTURA_PORT=9999\n");
  const previous = process.env.EFAKTURA_PORT;
  process.env.EFAKTURA_PORT = "8088";
  delete process.env.EFAKTURA_FROM_FILE;
  loadEnvFile(file);
  assert.equal(process.env.EFAKTURA_FROM_FILE, "from-file");
  assert.equal(process.env.EFAKTURA_PORT, "8088");
  delete process.env.EFAKTURA_FROM_FILE;
  if (previous === undefined) delete process.env.EFAKTURA_PORT;
  else process.env.EFAKTURA_PORT = previous;
  rmSync(dir, { recursive: true, force: true });
});

test("send returns the document envelope and sandbox receipt stays false", async () => {
  const service = api();
  const parsed = inspectUbl(official().toString("utf8"), "OUTBOUND");
  if (parsed.syntax !== "UBL") return;
  const draft = await service.createDraft(supplierCtx, parsed.document, "env-draft");
  const report = await service.validateDraft(supplierCtx, String(draft.id), "env-val");
  assert.equal(report.technicalPass, true);
  assert.equal(report.productionEligible, false);
  const issued = await service.issueDraft(supplierCtx, String(draft.id), "env-issue");
  const queued = await service.send(supplierCtx, String(issued.id), "env-send");
  assert.equal(queued.delivery.status, "QUEUED");
  assert.equal(queued.delivery.productionReceipt, false);
  assert.equal("status" in queued, false);
});

function tokenHeaders(uid: string, tenantId: string): Record<string, string> {
  const exp = Date.now() + 60_000;
  return {
    authorization: `Bearer ${signEmuToken(secret, { uid, typ: "user", exp })}`,
    "x-firebase-appcheck": signEmuToken(secret, { typ: "appcheck", exp }),
    "x-tenant-id": tenantId,
  };
}

async function invoke(
  handler: (req: IncomingMessage, res: ServerResponse) => Promise<void>,
  method: string,
  url: string,
  headers: Record<string, string>,
  body?: Buffer,
  extra?: Record<string, string>,
) {
  const req = new Writable({
    write(chunk, _enc, cb) {
      void chunk;
      cb();
    },
  }) as unknown as IncomingMessage;
  req.method = method;
  req.url = url;
  req.headers = Object.fromEntries(Object.entries({ ...headers, ...extra }).map(([key, value]) => [key.toLowerCase(), value]));
  const chunks: Buffer[] = [];
  let status = 0;
  const res = {
    writeHead(code: number) { status = code; return res; },
    end(payload?: Buffer | string) { if (payload) chunks.push(Buffer.isBuffer(payload) ? payload : Buffer.from(payload)); },
  } as unknown as ServerResponse;
  if (body) {
    queueMicrotask(() => {
      req.emit("data", body);
      req.emit("end");
    });
  } else {
    queueMicrotask(() => req.emit("end"));
  }
  await handler(req, res);
  return { status, raw: Buffer.concat(chunks) };
}
