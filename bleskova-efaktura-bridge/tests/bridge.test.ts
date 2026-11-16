import assert from "node:assert/strict";
import { createHmac, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { checkDocument } from "../packages/core/src/domain-check.js";
import { AppError } from "../packages/core/src/errors.js";
import { MemoryStore, sha256 } from "../packages/core/src/memory.js";
import { createDocumentService, type DocumentService } from "../packages/core/src/service.js";
import { assertServiceAuthorization, evaluateClientRule } from "../packages/firebase/src/rules.js";
import { LocalSandboxProvider, UnconfiguredProvider } from "../packages/provider/src/index.js";
import { composeReport, runStandardsEngine } from "../packages/validation/src/engine.js";
import { assertXmlLimits } from "../packages/xml/src/secure.js";
import { inspectUbl, serializeUbl } from "../packages/xml/src/ubl.js";
import { createApi } from "../apps/api/src/server.js";
import type { CanonicalDocument, TenantProfile } from "../packages/contracts/src/index.js";
import type { IncomingMessage, ServerResponse } from "node:http";
import { Writable } from "node:stream";

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

function parser() {
  return {
    inspect(xml: string, direction: "INBOUND" | "OUTBOUND") {
      assertXmlLimits(Buffer.from(xml));
      return inspectUbl(xml, direction);
    },
    serialize: serializeUbl,
  };
}

function validator() {
  return {
    async validate(xml: Uint8Array, domainIssues: Parameters<typeof composeReport>[0]["domainIssues"]) {
      const engine = await runStandardsEngine(xml, 30000);
      return composeReport({ sha256: engine.sha256, domainIssues, engine, engineFailed: Boolean(engine.engineError) });
    },
  };
}

function service(provider = new LocalSandboxProvider(secret), env: "local" | "production" = "local"): DocumentService {
  const created = createDocumentService({ env, provider, parser: parser(), validator: validator() });
  created.seedTenant(supplier, "uid-supplier", ["owner"]);
  created.seedTenant(buyer, "uid-buyer", ["owner"]);
  created.seedTenant(buyer, "uid-reader", ["reader"]);
  return created;
}

const supplierCtx = { uid: "uid-supplier", tenantId: supplier.id, roles: ["owner"] };
const buyerCtx = { uid: "uid-buyer", tenantId: buyer.id, roles: ["owner"] };

test("official invoice keeps number, parties, lines, currency and totals", () => {
  const xml = readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"), "utf8");
  const parsed = inspectUbl(xml, "INBOUND");
  assert.equal(parsed.syntax, "UBL");
  if (parsed.syntax !== "UBL") return;
  assert.equal(parsed.document.number, "Snippet1");
  assert.equal(parsed.document.kind, "INVOICE");
  assert.equal(parsed.document.currency, "EUR");
  assert.equal(parsed.document.supplier.legalName, "SupplierOfficialName Ltd");
  assert.equal(parsed.document.customer.legalName, "Buyer Official Name");
  assert.equal(parsed.document.lines.length, 2);
  assert.equal(parsed.document.lines[1]?.quantity, "-3");
  assert.equal(parsed.document.payableAmount, "1656.25");
  assert.equal(parsed.document.taxAmount, "331.25");
  const issues = checkDocument(parsed.document);
  assert.deepEqual(issues.filter((issue) => issue.severity === "ERROR"), []);
});

test("credit note keeps type and preceding invoice", () => {
  const xml = readFileSync(path.join(root, "fixtures/peppol-official/base-creditnote-correction.xml"), "utf8");
  const parsed = inspectUbl(xml, "INBOUND");
  assert.equal(parsed.syntax, "UBL");
  if (parsed.syntax !== "UBL") return;
  assert.equal(parsed.document.kind, "CREDIT_NOTE");
  assert.equal(parsed.document.typeCode, "381");
  assert.equal(parsed.document.precedingInvoiceNumber, "Snippet1");
});

test("mixed VAT is summed per rate, not as one rate on the gross total", () => {
  const doc = mixedVat();
  const issues = checkDocument(doc);
  assert.deepEqual(issues.filter((issue) => issue.severity === "ERROR"), [], JSON.stringify(issues));
  assert.equal(doc.taxInclusiveAmount, "233.00");
  assert.equal(doc.taxAmount, "33.00");
  assert.equal(doc.taxSubtotals[0]?.taxAmount, "23.00");
  assert.equal(doc.taxSubtotals[1]?.taxAmount, "10.00");
  assert.notEqual(doc.taxAmount, "28.00");
  const single = (233 * 23) / 100;
  assert.notEqual(Number(doc.taxAmount), Math.round(single * 100) / 100);
});

test("inconsistent totals are INVALID", () => {
  const doc = mixedVat();
  doc.payableAmount = "1.00";
  const issues = checkDocument(doc);
  assert.ok(issues.some((issue) => issue.ruleId === "BR-PAYABLE"));
});

test("security rejects DTD, entities, deep XML and foreign roots", async () => {
  const api = service();
  await assert.rejects(() => api.importXml(buyerCtx, { bytes: Buffer.from(`<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY x "y">]><foo>&x;</foo>`), filename: "a.xml", idempotencyKey: "k1", direction: "INBOUND" }), (error: unknown) => error instanceof AppError && error.code === "VALIDATION_FAILED");
  let deep = "<a>";
  for (let i = 0; i < 40; i++) deep += "<a>";
  for (let i = 0; i < 40; i++) deep += "</a>";
  deep += "</a>";
  await assert.rejects(() => api.importXml(buyerCtx, { bytes: Buffer.from(deep), filename: "deep.xml", idempotencyKey: "k2", direction: "INBOUND" }), (error: unknown) => error instanceof AppError);
  const foreign = await api.importXml(buyerCtx, { bytes: Buffer.from(`<Foo xmlns="urn:example:not-ubl"/>`), filename: "f.xml", idempotencyKey: "k3", direction: "INBOUND" });
  assert.equal(foreign.code, "UNSUPPORTED_SYNTAX");
  const cii = await api.importXml(buyerCtx, { bytes: Buffer.from(`<rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"/>`), filename: "cii.xml", idempotencyKey: "k4", direction: "INBOUND" });
  assert.equal(cii.code, "UNSUPPORTED_SYNTAX");
  assert.equal(cii.document.lifecycle, "QUARANTINE");
});

test("the same XML is one logical document", async () => {
  const api = service();
  const bytes = readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"));
  const first = await api.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "same-1", direction: "INBOUND" });
  const second = await api.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "same-2", direction: "INBOUND" });
  assert.equal(second.deduplicated, true);
  assert.equal(second.document.id, first.document.id);
  assert.equal(api.list(buyerCtx).items.length, 1);
});

test("idempotency conflict and concurrent issue do not duplicate", async () => {
  const api = service();
  const bytes = readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"));
  await api.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "idem-1", direction: "INBOUND" });
  await assert.rejects(() => api.importXml(buyerCtx, { bytes: Buffer.from("<x/>"), filename: "b.xml", idempotencyKey: "idem-1", direction: "INBOUND" }), (error: unknown) => error instanceof AppError && error.code === "IDEMPOTENCY_PAYLOAD_MISMATCH");
  const parsed = inspectUbl(bytes.toString("utf8"), "OUTBOUND");
  assert.equal(parsed.syntax, "UBL");
  if (parsed.syntax !== "UBL") return;
  const draft = await api.createDraft(supplierCtx, parsed.document, "draft-1");
  const report = await api.validateDraft(supplierCtx, String(draft.id), "val-1");
  assert.equal(report.technicalPass, true, JSON.stringify(report.issues.slice(0, 6)));
  const results = await Promise.allSettled([
    api.issueDraft(supplierCtx, String(draft.id), "issue-a", report ? undefined : undefined),
    api.issueDraft(supplierCtx, String(draft.id), "issue-b"),
  ]);
  const fulfilled = results.filter((item) => item.status === "fulfilled");
  assert.equal(fulfilled.length, 1);
  const listed = api.list(supplierCtx).items.filter((item) => item.lifecycle === "ISSUED");
  assert.equal(listed.length, 1);
});

test("tenant A cannot read tenant B", async () => {
  const api = service();
  const bytes = readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"));
  const imported = await api.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "iso-1", direction: "INBOUND" });
  assert.throws(() => api.get(supplierCtx, imported.document.id), (error: unknown) => error instanceof AppError && error.code === "NOT_FOUND");
  assert.throws(() => api.original(supplierCtx, imported.document.id), (error: unknown) => error instanceof AppError && error.code === "NOT_FOUND");
  await assert.rejects(() => api.importXml(supplierCtx, { bytes, filename: "a.xml", idempotencyKey: "iso-2", direction: "INBOUND" }), (error: unknown) => error instanceof AppError && error.code === "TENANT_MISMATCH");
});

test("identity change does not move the result", async () => {
  const api = service();
  const bytes = readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"));
  await assert.rejects(() => api.importXml(buyerCtx, {
    bytes,
    filename: "a.xml",
    idempotencyKey: "ident-1",
    direction: "INBOUND",
    beforeCommit: () => ({ uid: "uid-supplier", tenantId: supplier.id, roles: ["owner"] }),
  }), (error: unknown) => error instanceof AppError && error.code === "IDENTITY_CHANGED");
  assert.equal(api.list(buyerCtx).items.length, 0);
  assert.equal(api.list(supplierCtx).items.length, 0);
});

test("validator outage blocks send", async () => {
  const broken = createDocumentService({
    env: "local",
    provider: new LocalSandboxProvider(secret),
    parser: parser(),
    validator: { async validate() { throw new Error("down"); } },
  });
  broken.seedTenant(supplier, "uid-supplier", ["owner"]);
  const parsed = inspectUbl(readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"), "utf8"), "OUTBOUND");
  if (parsed.syntax !== "UBL") return;
  const draft = await broken.createDraft(supplierCtx, parsed.document, "d");
  const report = await broken.validateDraft(supplierCtx, String(draft.id), "v");
  assert.equal(report.status, "UNAVAILABLE");
  assert.equal(report.productionEligible, false);
  const issuedBypass = (broken.store as MemoryStore).documents.get(String(draft.id))!;
  issuedBypass.lifecycle = "ISSUED";
  issuedBypass.canonical = { ...issuedBypass.canonical!, direction: "OUTBOUND" };
  await assert.rejects(() => broken.send(supplierCtx, String(draft.id), "send"), (error: unknown) => error instanceof AppError && error.code === "VALIDATOR_UNAVAILABLE");
});

test("timeout after possible accept reconciles and does not create a second invoice", async () => {
  const api = service();
  const issued = await issueOfficial(api);
  const before = api.list(supplierCtx).items.length;
  await api.send(supplierCtx, issued.id, "send-timeout", "timeout");
  await api.workerTick();
  const delivery = api.delivery(supplierCtx, issued.id);
  assert.equal(delivery.status, "PROVIDER_ACCEPTED");
  assert.equal(delivery.productionReceipt, false);
  assert.equal(delivery.simulated, true);
  assert.equal(api.list(supplierCtx).items.length, before);
});

test("bad, duplicate and out-of-order webhooks", async () => {
  const provider = new LocalSandboxProvider(secret);
  const api = service(provider);
  const issued = await issueOfficial(api);
  await api.send(supplierCtx, issued.id, "send-ok");
  await api.workerTick();
  const messageId = api.delivery(supplierCtx, issued.id).providerMessageId;
  const event = { eventId: randomUUID(), providerMessageId: messageId, documentId: issued.id, status: "DELIVERED", occurredAt: new Date().toISOString() };
  const raw = Buffer.from(JSON.stringify(event));
  const stamp = Math.floor(Date.now() / 1000);
  const signature = provider.sign(raw, stamp);
  const ok = await api.webhook("local-sandbox", raw, { "x-provider-signature": signature }, new Date(stamp * 1000).toISOString());
  assert.equal(ok.applied, true);
  assert.equal(ok.productionReceipt, false);
  const dup = await api.webhook("local-sandbox", raw, { "x-provider-signature": signature }, new Date(stamp * 1000).toISOString());
  assert.equal(dup.duplicate, true);
  const older = { ...event, eventId: randomUUID(), status: "QUEUED" };
  const olderRaw = Buffer.from(JSON.stringify(older));
  const olderSig = provider.sign(olderRaw, stamp);
  const ignored = await api.webhook("local-sandbox", olderRaw, { "x-provider-signature": olderSig }, new Date(stamp * 1000).toISOString());
  assert.equal(ignored.ignored, true);
  await assert.rejects(() => api.webhook("local-sandbox", raw, { "x-provider-signature": "t=1,v1=dead" }, new Date().toISOString()), (error: unknown) => error instanceof AppError && error.code === "UNAUTHORIZED");
});

test("sandbox cannot produce a production receipt and production mode refuses it", async () => {
  const sandbox = new LocalSandboxProvider(secret);
  assert.equal(sandbox.capabilities().productionReceipts, false);
  const local = service(sandbox, "local");
  const issued = await issueOfficial(local);
  await local.send(supplierCtx, issued.id, "send-local");
  await local.workerTick();
  assert.equal(local.delivery(supplierCtx, issued.id).productionReceipt, false);
  const prod = service(sandbox, "production");
  const parsed = inspectUbl(readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"), "utf8"), "OUTBOUND");
  if (parsed.syntax !== "UBL") return;
  const draft = await prod.createDraft(supplierCtx, parsed.document, "prod-draft");
  await prod.validateDraft(supplierCtx, String(draft.id), "prod-val");
  const issuedProd = await prod.issueDraft(supplierCtx, String(draft.id), "prod-issue");
  await assert.rejects(() => prod.send(supplierCtx, String(issuedProd.id), "prod-send"), (error: unknown) => error instanceof AppError && error.code === "PRODUCTION_GATE");
  const bare = createDocumentService({ env: "production", provider: new UnconfiguredProvider(), parser: parser(), validator: validator() });
  bare.seedTenant(supplier, "uid-supplier", ["owner"]);
  await assert.rejects(() => bare.send(supplierCtx, "missing", "x"), (error: unknown) => error instanceof AppError);
});

test("original bytes keep the same sha256 after import and download", async () => {
  const api = service();
  const bytes = readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"));
  const imported = await api.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "hash-1", direction: "INBOUND" });
  const downloaded = api.original(buyerCtx, imported.document.id);
  assert.equal(downloaded.sha256, sha256(bytes));
  assert.equal(Buffer.from(downloaded.bytes).equals(bytes), true);
  const restored = Uint8Array.from(downloaded.bytes);
  assert.equal(sha256(restored), imported.document.sha256);
});

test("leading zeros in company id survive and comma decimals are rejected", () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
    <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
      <cac:AccountingSupplierParty><cac:Party><cac:PartyLegalEntity><cbc:RegistrationName>Nula</cbc:RegistrationName><cbc:CompanyID>00001234</cbc:CompanyID></cac:PartyLegalEntity></cac:Party></cac:AccountingSupplierParty>
    </Invoice>`;
  const parsed = inspectUbl(xml, "INBOUND");
  assert.equal(parsed.syntax, "UBL");
  if (parsed.syntax !== "UBL") return;
  assert.equal(parsed.document.supplier.companyId, "00001234");
  const bad = mixedVat();
  bad.payableAmount = "233,00";
  const issues = checkDocument(bad);
  assert.ok(issues.some((issue) => issue.ruleId === "BR-DECIMAL" || issue.ruleId === "BR-PAYABLE"));
});

test("standards validator accepts the official invoice and rejects a broken payable", async () => {
  const good = readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"));
  const goodReport = await runStandardsEngine(good, 30000);
  assert.equal(goodReport.xsd.status, "VALID");
  assert.equal(goodReport.en16931.status, "VALID");
  assert.equal(goodReport.peppol.status, "VALID");
  const bad = Buffer.from(good.toString("utf8").replace(">1656.25</cbc:PayableAmount>", ">1.00</cbc:PayableAmount>"));
  const badReport = await runStandardsEngine(bad, 30000);
  assert.equal(badReport.en16931.status, "INVALID");
  assert.ok(badReport.en16931.issues.some((issue) => issue.includes("BR-CO-16")));
});

test("firestore rule semantics and admin SDK path still authorize on the server", () => {
  assert.equal(evaluateClientRule({ authUid: "a", tenantId: "b", memberTenantIds: ["a"], path: "tenants/b/documents/1", write: false, adminSdk: false }).allow, false);
  assert.equal(evaluateClientRule({ authUid: "a", tenantId: "a", memberTenantIds: ["a"], path: "tenants/a/delivery/1", write: true, adminSdk: false }).allow, false);
  assert.equal(evaluateClientRule({ authUid: "a", tenantId: "a", memberTenantIds: ["a"], path: "tenants/a/documents/1/notes/1", write: true, adminSdk: false }).allow, true);
  assert.equal(evaluateClientRule({ tenantId: "a", memberTenantIds: ["a"], path: "tenants/a/originals/1", write: true, adminSdk: true }).allow, true);
  const members = new Map<string, string[]>([["uid-a|tenant-a", ["owner"]]]);
  assert.throws(() => assertServiceAuthorization({ uid: "uid-a", tenantId: "tenant-b", roles: ["owner"] }, "tenant-b", members), /FORBIDDEN/);
  assert.doesNotThrow(() => assertServiceAuthorization({ uid: "uid-a", tenantId: "tenant-a", roles: [] }, "tenant-a", members));
});

test("http original download, notes and unconfigured provider error", async () => {
  const provider = new LocalSandboxProvider(secret);
  const api = service(provider);
  const bytes = readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"));
  const imported = await api.importXml(buyerCtx, { bytes, filename: "a.xml", idempotencyKey: "http-1", direction: "INBOUND" });
  const note = api.addNote(buyerCtx, imported.document.id, "lokálna poznámka");
  assert.equal(note.text, "lokálna poznámka");
  assert.equal(sha256(api.original(buyerCtx, imported.document.id).bytes), imported.document.sha256);
  const handler = createApi({ service: api, env: "local", authMode: "emulator", emulatorSecret: secret, rateLimitPerMinute: 20 });
  const response = await invoke(handler, "GET", `/v1/tenants/${buyer.id}/documents/${imported.document.id}/original`, tokenHeaders("uid-buyer", buyer.id));
  assert.equal(response.status, 200);
  assert.equal(response.raw.equals(bytes), true);
  const denied = await invoke(handler, "GET", `/v1/tenants/${buyer.id}/documents/${imported.document.id}`, tokenHeaders("uid-supplier", buyer.id));
  assert.equal(denied.status, 403);
});

function tokenHeaders(uid: string, tenantId: string): Record<string, string> {
  const exp = Date.now() + 60_000;
  const user = sign({ uid, typ: "user", exp });
  const app = sign({ typ: "appcheck", exp });
  return { authorization: `Bearer ${user}`, "x-firebase-appcheck": app, "x-tenant-id": tenantId };
}

function sign(payload: unknown): string {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const sig = createHmac("sha256", secret).update(body).digest("base64url");
  return `emu.${body}.${sig}`;
}

async function issueOfficial(api: DocumentService) {
  const parsed = inspectUbl(readFileSync(path.join(root, "fixtures/peppol-official/base-example.xml"), "utf8"), "OUTBOUND");
  if (parsed.syntax !== "UBL") throw new Error("fixture");
  const draft = await api.createDraft(supplierCtx, parsed.document, `draft-${randomUUID()}`);
  const report = await api.validateDraft(supplierCtx, String(draft.id), `val-${randomUUID()}`);
  assert.equal(report.technicalPass, true, JSON.stringify(report.issues.slice(0, 8)));
  const issued = await api.issueDraft(supplierCtx, String(draft.id), `issue-${randomUUID()}`);
  return issued as { id: string };
}

function mixedVat(): CanonicalDocument {
  return {
    kind: "INVOICE",
    direction: "OUTBOUND",
    number: "MIX-1",
    customizationId: "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0",
    profileId: "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0",
    typeCode: "380",
    issueDate: "2026-11-16",
    dueDate: "2026-11-30",
    currency: "EUR",
    supplier: { legalName: "Dodávateľ test", address: { line1: "Testovacia 1", city: "Testov", postalCode: "00000", countryCode: "SK" }, companyId: "00000001", vatId: "SK0000000001", endpoint: { schemeId: "0096", value: "00000001" } },
    customer: { legalName: "Odberateľ test", address: { line1: "Testovacia 2", city: "Testov", postalCode: "00000", countryCode: "SK" }, companyId: "00000002", endpoint: { schemeId: "0096", value: "00000002" } },
    lines: [
      { id: "1", name: "Položka 23", quantity: "1", unitCode: "C62", unitPrice: "100.00", lineAmount: "100.00", vatCategory: "S", vatRate: "23" },
      { id: "2", name: "Položka 10", quantity: "1", unitCode: "C62", unitPrice: "100.00", lineAmount: "100.00", vatCategory: "S", vatRate: "10" },
    ],
    allowances: [],
    taxSubtotals: [
      { taxableAmount: "100.00", taxAmount: "23.00", category: "S", rate: "23", currency: "EUR" },
      { taxableAmount: "100.00", taxAmount: "10.00", category: "S", rate: "10", currency: "EUR" },
    ],
    taxAmount: "33.00",
    lineExtensionAmount: "200.00",
    taxExclusiveAmount: "200.00",
    taxInclusiveAmount: "233.00",
    payableAmount: "233.00",
  };
}

async function invoke(handler: (req: IncomingMessage, res: ServerResponse) => Promise<void>, method: string, url: string, headers: Record<string, string>) {
  const req = new Writable({ write(_c, _e, cb) { cb(); } }) as unknown as IncomingMessage;
  req.method = method;
  req.url = url;
  req.headers = Object.fromEntries(Object.entries(headers).map(([key, value]) => [key.toLowerCase(), value]));
  const chunks: Buffer[] = [];
  let status = 0;
  const res = {
    writeHead(code: number) { status = code; return res; },
    end(body?: Buffer | string) { if (body) chunks.push(Buffer.isBuffer(body) ? body : Buffer.from(body)); },
  } as unknown as ServerResponse;
  await handler(req, res);
  return { status, raw: Buffer.concat(chunks) };
}
