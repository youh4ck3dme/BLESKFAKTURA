import type {
  CanonicalDocument,
  DeliveryStatus,
  RequestContext,
  TenantProfile,
  ValidationIssue,
  ValidationReport,
} from "../../contracts/src/index.js";
import type { DeliveryProvider } from "../../contracts/src/index.js";
import { checkDocument } from "./domain-check.js";
import { AppError } from "./errors.js";
import { DecimalInputError } from "./money.js";
import { MemoryStore, newId, sha256, type DocumentStore, type IdempotencyState, type OutboxJob, type StoredDocument } from "./memory.js";
import { PRODUCT_POLICY_VERSION } from "./product-policy.js";

export interface ParserPort {
  inspect(xml: string, direction: CanonicalDocument["direction"]):
    | { syntax: "CII" | "UNKNOWN"; code: "UNSUPPORTED_SYNTAX" }
    | { syntax: "UBL"; document: CanonicalDocument };
  serialize(document: CanonicalDocument): string;
}

export interface ValidatorPort {
  validate(xml: Uint8Array, domainIssues: ValidationIssue[]): Promise<ValidationReport>;
}

export interface ServiceOptions {
  env: "local" | "production";
  provider: DeliveryProvider;
  parser: ParserPort;
  validator: ValidatorPort;
  store?: DocumentStore;
}

const READ_ROLES = new Set(["owner", "accountant", "sender", "reader"]);
const WRITE_ROLES = new Set(["owner", "accountant"]);
const SEND_ROLES = new Set(["owner", "accountant", "sender"]);
const IDEMPOTENCY_LEASE_MS = 60_000;

export function createDocumentService(options: ServiceOptions) {
  const store = options.store ?? new MemoryStore();
  const rank: Record<DeliveryStatus, number> = {
    NOT_REQUESTED: 0,
    QUEUED: 1,
    SUBMITTING: 2,
    UNKNOWN: 3,
    PROVIDER_ACCEPTED: 4,
    DELIVERED: 5,
    REJECTED: 6,
    FAILED: 6,
  };

  function ctxOrThrow(ctx: RequestContext, roles: Set<string>): TenantProfile {
    const member = store.getMember(ctx.uid, ctx.tenantId);
    if (!member || !member.roles.some((role) => roles.has(role))) {
      throw new AppError("FORBIDDEN", 403, "Nemáte oprávnenie na túto firmu.");
    }
    const tenant = store.getTenant(ctx.tenantId);
    if (!tenant) throw new AppError("FORBIDDEN", 403, "Firma neexistuje alebo k nej nemáte prístup.");
    return tenant;
  }

  function requireSame(original: RequestContext, current: RequestContext): void {
    if (original.uid !== current.uid || original.tenantId !== current.tenantId) {
      throw new AppError("IDENTITY_CHANGED", 409, "Identita sa počas operácie zmenila. Výsledok sa neuložil na nový účet.");
    }
  }

  function classify(error: unknown): IdempotencyState {
    if (error instanceof Error && error.name === "ProviderTimeoutError") return "UNCERTAIN";
    if (error instanceof AppError && error.code === "VALIDATOR_UNAVAILABLE") return "RETRYABLE";
    if (error instanceof AppError) return "REJECTED";
    return "RETRYABLE";
  }

  async function runIdempotent<T>(tenantId: string, operation: string, key: string, requestHash: string, fn: () => Promise<T>): Promise<T> {
    const id = `${tenantId}|${operation}|${key}`;
    return store.lock(`idem|${id}`, async () => {
      const now = Date.now();
      const current = store.getIdempotency(id);
      if (current && current.requestHash !== requestHash) {
        throw new AppError("IDEMPOTENCY_PAYLOAD_MISMATCH", 409, "Rovnaký Idempotency-Key má iný obsah.");
      }
      if (current?.state === "COMPLETED" || current?.state === "UNCERTAIN") {
        return current.response as T;
      }
      if (current?.state === "REJECTED" && current.error) {
        throw new AppError(current.error.code, current.error.httpStatus, current.error.message);
      }
      if (current?.state === "IN_PROGRESS" && now - current.updatedAt < IDEMPOTENCY_LEASE_MS) {
        throw new AppError("IDEMPOTENCY_IN_PROGRESS", 409, "Rovnaká operácia ešte beží.");
      }
      store.putIdempotency(id, { requestHash, state: "IN_PROGRESS", updatedAt: now });
      try {
        const value = await fn();
        store.putIdempotency(id, { requestHash, state: "COMPLETED", response: value, updatedAt: Date.now() });
        return value;
      } catch (error) {
        const state = classify(error);
        if (state === "REJECTED" && error instanceof AppError) {
          store.putIdempotency(id, {
            requestHash,
            state,
            error: { code: error.code, httpStatus: error.httpStatus, message: error.message },
            updatedAt: Date.now(),
          });
        } else if (state === "UNCERTAIN") {
          store.putIdempotency(id, { requestHash, state, updatedAt: Date.now() });
        } else {
          store.putIdempotency(id, { requestHash, state: "RETRYABLE", updatedAt: Date.now() });
        }
        throw error;
      }
    });
  }

  function matches(party: CanonicalDocument["supplier"], tenant: TenantProfile): boolean {
    const values = [party.companyId, party.vatId, party.taxId, ...(party.partyIds ?? []).map((item) => item.value)].filter((v): v is string => Boolean(v));
    const tenantValues = [tenant.companyId, tenant.vatId, tenant.taxId].filter((v): v is string => Boolean(v));
    if (tenant.endpoint && party.endpoint && tenant.endpoint.schemeId === party.endpoint.schemeId && tenant.endpoint.value === party.endpoint.value) return true;
    return tenantValues.some((value) => values.includes(value));
  }

  function publicDoc(doc: StoredDocument) {
    return {
      id: doc.id,
      tenantId: doc.tenantId,
      version: doc.version,
      lifecycle: doc.lifecycle,
      syntax: doc.syntax,
      direction: doc.canonical?.direction,
      kind: doc.canonical?.kind,
      number: doc.canonical?.number,
      currency: doc.canonical?.currency,
      supplierName: doc.canonical?.supplier.legalName,
      customerName: doc.canonical?.customer.legalName,
      payableAmount: doc.canonical?.payableAmount,
      precedingInvoiceNumber: doc.canonical?.precedingInvoiceNumber,
      issueDate: doc.canonical?.issueDate,
      dueDate: doc.canonical?.dueDate,
      sha256: doc.sha256,
      validation: doc.validation,
      delivery: {
        status: doc.deliveryStatus,
        providerMessageId: doc.providerMessageId,
        simulated: doc.simulatedDelivery,
        productionReceipt: doc.productionReceipt,
        detail: doc.deliveryDetail,
      },
      conflictWith: doc.conflictWith,
      policyVersion: PRODUCT_POLICY_VERSION,
      createdAt: doc.createdAt,
      updatedAt: doc.updatedAt,
    };
  }

  function forceLocalReceipt(doc: StoredDocument): void {
    const caps = options.provider.capabilities();
    if (caps.simulated || !caps.productionReceipts || caps.environment !== "production") doc.productionReceipt = false;
  }

  async function validateBytes(xml: Uint8Array, canonical?: CanonicalDocument): Promise<ValidationReport> {
    let domainIssues: ValidationIssue[] = [];
    if (canonical) {
      try {
        domainIssues = checkDocument(canonical);
      } catch (error) {
        if (error instanceof DecimalInputError) {
          domainIssues = [{ layer: "DOMAIN", severity: "ERROR", ruleId: "BR-DECIMAL", message: error.message }];
        } else throw error;
      }
    }
    try {
      return await options.validator.validate(xml, domainIssues);
    } catch {
      return {
        status: "UNAVAILABLE",
        rulesetVersion: "validator-unavailable",
        validatedSha256: sha256(xml),
        issues: [{ layer: "XSD", severity: "ERROR", ruleId: "ENGINE_UNAVAILABLE", message: "Validátor nie je dostupný. Doklad nie je platný." }],
        layers: [
          { layer: "XML_SECURITY", status: "UNAVAILABLE", rulesetVersion: "n/a" },
          { layer: "XSD", status: "UNAVAILABLE", rulesetVersion: "n/a" },
          { layer: "EN16931", status: "UNAVAILABLE", rulesetVersion: "n/a" },
          { layer: "PEPPOL", status: "UNAVAILABLE", rulesetVersion: "n/a" },
          { layer: "SK", status: "UNSUPPORTED", rulesetVersion: "not-executed" },
          { layer: "DOMAIN", status: domainIssues.length ? "INVALID" : "NOT_RUN", rulesetVersion: "domain-0.1.0" },
        ],
        productionEligible: false,
        technicalPass: false,
      };
    }
  }

  return {
    store,
    publicDoc,
    seedTenant(tenant: TenantProfile, uid: string, roles: string[]): void {
      store.putTenant(tenant);
      store.putMember(uid, tenant.id, roles);
    },
    async importXml(ctx: RequestContext, input: { bytes: Uint8Array; filename: string; idempotencyKey: string; direction: "INBOUND" | "OUTBOUND"; beforeCommit?: () => RequestContext }) {
      const tenant = ctxOrThrow(ctx, WRITE_ROLES);
      const requestHash = sha256(input.bytes);
      return runIdempotent(ctx.tenantId, "import", input.idempotencyKey, requestHash, async () => {
        return store.lock(`hash|${ctx.tenantId}|${requestHash}`, async () => {
          const existingId = store.getHash(ctx.tenantId, requestHash);
          if (existingId) {
            const existing = store.getDocument(existingId);
            if (!existing || existing.tenantId !== ctx.tenantId) throw new AppError("NOT_FOUND", 404, "Doklad neexistuje.");
            return { deduplicated: true, code: undefined as string | undefined, document: publicDoc(existing) };
          }
          const xml = new TextDecoder().decode(input.bytes);
          let inspected: ReturnType<ParserPort["inspect"]>;
          try {
            inspected = options.parser.inspect(xml, input.direction);
          } catch (error) {
            if (error instanceof Error && (error.name === "XmlRejected" || (error as { ruleId?: string }).ruleId?.startsWith("XML_") === true)) {
              throw new AppError("VALIDATION_FAILED", 400, error.message);
            }
            throw error;
          }
          if (inspected.syntax !== "UBL") {
            const now = new Date().toISOString();
            const id = newId();
            const doc: StoredDocument = {
              id,
              tenantId: ctx.tenantId,
              ownerUid: ctx.uid,
              version: 1,
              lifecycle: "QUARANTINE",
              syntax: inspected.syntax,
              sha256: requestHash,
              filename: input.filename,
              deliveryStatus: "NOT_REQUESTED",
              simulatedDelivery: false,
              productionReceipt: false,
              createdAt: now,
              updatedAt: now,
            };
            if (input.beforeCommit) requireSame(ctx, input.beforeCommit());
            const bytes = new Uint8Array(input.bytes);
            store.transaction(() => {
              const winner = store.putHashIfAbsent(ctx.tenantId, requestHash, id);
              if (winner !== id) return;
              store.putDocument(doc);
              store.putOriginal(id, bytes);
            });
            const winner = store.getHash(ctx.tenantId, requestHash) ?? id;
            const stored = store.getDocument(winner) ?? doc;
            return { deduplicated: winner !== id, code: "UNSUPPORTED_SYNTAX" as const, document: publicDoc(stored) };
          }
          const canonical = { ...inspected.document, direction: input.direction };
          const side = input.direction === "INBOUND" ? canonical.customer : canonical.supplier;
          if (!matches(side, tenant)) throw new AppError("TENANT_MISMATCH", 403, "Strana dokladu nezodpovedá oprávnenej firme.");
          const validation = await validateBytes(input.bytes, canonical);
          const numberKey = `${ctx.tenantId}|${input.direction}|${canonical.number}`;
          const owner = store.getNumber(numberKey);
          let conflictWith: string | undefined;
          if (owner) {
            const previous = store.getDocument(owner);
            if (previous && previous.sha256 !== requestHash) conflictWith = owner;
          }
          if (input.beforeCommit) requireSame(ctx, input.beforeCommit());
          const now = new Date().toISOString();
          const id = newId();
          const bytes = new Uint8Array(input.bytes);
          const stored: StoredDocument = {
            id,
            tenantId: ctx.tenantId,
            ownerUid: ctx.uid,
            version: 1,
            lifecycle: "IMPORTED",
            syntax: "UBL",
            canonical,
            sha256: requestHash,
            filename: input.filename,
            validation,
            preparedXml: bytes,
            deliveryStatus: "NOT_REQUESTED",
            simulatedDelivery: false,
            productionReceipt: false,
            conflictWith,
            createdAt: now,
            updatedAt: now,
          };
          const raced = store.transaction(() => {
            const again = store.getHash(ctx.tenantId, requestHash);
            if (again) return again;
            const reserved = store.putHashIfAbsent(ctx.tenantId, requestHash, id);
            if (reserved !== id) return reserved;
            store.putDocument(stored);
            store.putOriginal(id, bytes);
            if (!conflictWith) store.putNumberIfAbsent(numberKey, id);
            return undefined;
          });
          if (raced) {
            const existing = store.getDocument(raced);
            if (!existing) throw new AppError("NOT_FOUND", 404, "Doklad neexistuje.");
            return { deduplicated: true, code: undefined as string | undefined, document: publicDoc(existing) };
          }
          if (conflictWith) {
            throw new AppError("NUMBER_CONFLICT", 409, "Rovnaké číslo faktúry má iný obsah. Pôvodný doklad zostal nezmenený.", validation.issues);
          }
          return { deduplicated: false, code: undefined as string | undefined, document: publicDoc(stored) };
        });
      });
    },
    list(ctx: RequestContext, cursor?: string, limit = 20) {
      ctxOrThrow(ctx, READ_ROLES);
      const size = Math.min(Math.max(limit, 1), 100);
      const all = store.listDocuments(ctx.tenantId);
      let start = 0;
      if (cursor) {
        const raw = Buffer.from(cursor, "base64url").toString("utf8");
        start = all.findIndex((doc) => doc.id === raw) + 1;
      }
      const slice = all.slice(start, start + size);
      const next = start + size < all.length ? Buffer.from(slice[slice.length - 1]!.id).toString("base64url") : undefined;
      return { items: slice.map(publicDoc), nextCursor: next };
    },
    get(ctx: RequestContext, id: string) {
      ctxOrThrow(ctx, READ_ROLES);
      const doc = store.getDocument(id);
      if (!doc || doc.tenantId !== ctx.tenantId) throw new AppError("NOT_FOUND", 404, "Doklad neexistuje.");
      return publicDoc(doc);
    },
    original(ctx: RequestContext, id: string) {
      ctxOrThrow(ctx, READ_ROLES);
      const doc = store.getDocument(id);
      const bytes = store.getOriginal(id);
      if (!doc || doc.tenantId !== ctx.tenantId || !bytes) throw new AppError("NOT_FOUND", 404, "Originál neexistuje.");
      return { bytes, sha256: doc.sha256, filename: doc.filename };
    },
    addNote(ctx: RequestContext, id: string, text: string) {
      ctxOrThrow(ctx, WRITE_ROLES);
      const doc = store.getDocument(id);
      if (!doc || doc.tenantId !== ctx.tenantId) throw new AppError("NOT_FOUND", 404, "Doklad neexistuje.");
      const note = { id: newId(), tenantId: ctx.tenantId, documentId: id, text, createdAt: new Date().toISOString() };
      store.addNote(note);
      return note;
    },
    async createDraft(ctx: RequestContext, draft: CanonicalDocument, idempotencyKey: string) {
      const tenant = ctxOrThrow(ctx, WRITE_ROLES);
      const payload = Buffer.from(JSON.stringify(draft));
      return runIdempotent(ctx.tenantId, "draft", idempotencyKey, sha256(payload), async () => {
        if (draft.direction !== "OUTBOUND") throw new AppError("UNSUPPORTED_SCENARIO", 422, "Draft v MVP je len vydaný doklad.");
        if (!matches(draft.supplier, tenant)) throw new AppError("TENANT_MISMATCH", 403, "Dodávateľ draftu nezodpovedá firme.");
        const now = new Date().toISOString();
        const id = newId();
        const stored: StoredDocument = {
          id,
          tenantId: ctx.tenantId,
          ownerUid: ctx.uid,
          version: 1,
          lifecycle: "DRAFT",
          syntax: "UBL",
          canonical: draft,
          sha256: sha256(payload),
          filename: "draft.json",
          deliveryStatus: "NOT_REQUESTED",
          simulatedDelivery: false,
          productionReceipt: false,
          createdAt: now,
          updatedAt: now,
        };
        store.putDocument(stored);
        return publicDoc(stored);
      });
    },
    async validateDraft(ctx: RequestContext, id: string, idempotencyKey: string) {
      ctxOrThrow(ctx, WRITE_ROLES);
      return store.lock(`doc|${id}`, async () => {
        const doc = mustDraft(store, ctx, id);
        const xmlText = options.parser.serialize(doc.canonical!);
        const xml = new TextEncoder().encode(xmlText);
        const hash = sha256(xml);
        return runIdempotent(ctx.tenantId, `validate|${id}|${hash}`, idempotencyKey, hash, async () => {
          const report = await validateBytes(xml, doc.canonical);
          doc.validation = report;
          doc.preparedXml = xml;
          doc.sha256 = hash;
          doc.version += 1;
          doc.updatedAt = new Date().toISOString();
          store.putDocument(doc);
          store.putOriginal(id, xml);
          return report;
        });
      });
    },
    async issueDraft(ctx: RequestContext, id: string, idempotencyKey: string, expectedVersion?: number) {
      const tenant = ctxOrThrow(ctx, WRITE_ROLES);
      return runIdempotent(ctx.tenantId, `issue|${id}`, idempotencyKey, `issue|${id}|${expectedVersion ?? ""}`, async () => {
        return store.lock(`doc|${id}`, async () => {
          const doc = mustDraft(store, ctx, id);
          if (expectedVersion !== undefined && doc.version !== expectedVersion) {
            throw new AppError("CONCURRENCY_CONFLICT", 409, "Verzia dokladu sa medzitým zmenila.");
          }
          if (!doc.validation?.technicalPass || !doc.preparedXml || doc.validation.validatedSha256 !== sha256(doc.preparedXml)) {
            throw new AppError("PRECONDITION_FAILED", 409, "Pred vystavením treba aktuálnu technickú validáciu toho istého XML.");
          }
          const number = doc.canonical!.number || nextNumber(store, tenant.id);
          const numberKey = `${ctx.tenantId}|OUTBOUND|${number}`;
          const owner = store.putNumberIfAbsent(numberKey, id);
          if (owner !== id) throw new AppError("NUMBER_CONFLICT", 409, "Číslo dokladu je už obsadené.");
          doc.canonical = { ...doc.canonical!, number };
          doc.lifecycle = "ISSUED";
          doc.version += 1;
          doc.updatedAt = new Date().toISOString();
          doc.sha256 = sha256(doc.preparedXml);
          store.putDocument(doc);
          return publicDoc(doc);
        });
      });
    },
    async send(ctx: RequestContext, id: string, idempotencyKey: string, scenario?: string) {
      ctxOrThrow(ctx, SEND_ROLES);
      const hash = sha256(Buffer.from(`${id}|${scenario ?? ""}`));
      return runIdempotent(ctx.tenantId, `send|${id}`, idempotencyKey, hash, async () => {
        return store.lock(`doc|${id}`, async () => {
          const doc = store.getDocument(id);
          if (!doc || doc.tenantId !== ctx.tenantId) throw new AppError("NOT_FOUND", 404, "Doklad neexistuje.");
          if (doc.lifecycle !== "ISSUED" || doc.canonical?.direction !== "OUTBOUND") {
            throw new AppError("PRECONDITION_FAILED", 409, "Odoslať možno len vystavený odberateľský doklad.");
          }
          if (doc.deliveryStatus !== "NOT_REQUESTED" && doc.deliveryStatus !== "FAILED") {
            return publicDoc(doc);
          }
          const caps = options.provider.capabilities();
          if (options.env === "production" && (caps.environment !== "production" || caps.simulated || caps.providerId === "local-sandbox")) {
            throw new AppError("PRODUCTION_GATE", 409, "Produkčné odoslanie nie je nakonfigurované. Sandbox nie je produkčný poštár.");
          }
          if (caps.environment === "unconfigured") throw new AppError("PROVIDER_NOT_CONFIGURED", 409, "Poštár nie je nakonfigurovaný.");
          if (!doc.validation?.technicalPass || !doc.preparedXml || doc.validation.validatedSha256 !== doc.sha256) {
            if (doc.validation?.status === "UNAVAILABLE" || !doc.validation) {
              throw new AppError("VALIDATOR_UNAVAILABLE", 409, "Odoslanie je blokované, lebo validátor alebo pravidlá neboli dostupné.");
            }
            throw new AppError("VALIDATION_FAILED", 409, "Odoslanie je blokované, kým ten istý hash neprejde technickou validáciou.");
          }
          if (options.env === "production" && !doc.validation.productionEligible) {
            throw new AppError("PRODUCTION_GATE", 409, "Chýba úplná validácia vrátane slovenských pravidiel. Produkčné odoslanie je zatvorené.");
          }
          doc.deliveryStatus = "QUEUED";
          doc.version += 1;
          doc.updatedAt = new Date().toISOString();
          doc.productionReceipt = false;
          store.putDocument(doc);
          const job: OutboxJob = {
            id: newId(),
            tenantId: ctx.tenantId,
            documentId: id,
            idempotencyKey,
            scenario,
            attempts: 0,
            leaseUntil: 0,
            state: "PENDING",
            phase: "NEW",
          };
          store.addOutbox(job);
          return publicDoc(doc);
        });
      });
    },
    delivery(ctx: RequestContext, id: string) {
      return this.get(ctx, id).delivery;
    },
    async workerTick(now = Date.now()) {
      const claimed = await store.lock("outbox-claim", async () => {
        const found = store.claimOutbox(now, 30_000);
        if (!found) return undefined;
        if (!found.reconcile) {
          found.job.phase = "SUBMITTING";
          store.saveOutbox(found.job);
        }
        return found;
      });
      if (!claimed) return { processed: false };
      const job = claimed.job;
      const doc = store.getDocument(job.documentId);
      if (!doc?.preparedXml) {
        job.state = "DONE";
        store.saveOutbox(job);
        return { processed: true };
      }
      const caps = options.provider.capabilities();
      try {
        if (claimed.reconcile) {
          const looked = await options.provider.lookup({ tenantId: job.tenantId, documentId: job.documentId });
          doc.deliveryStatus = looked.status;
          doc.providerMessageId = looked.providerMessageId ?? doc.providerMessageId;
          doc.simulatedDelivery = looked.simulated || caps.simulated;
          doc.productionReceipt = looked.productionReceipt === true && caps.productionReceipts && caps.environment === "production";
          doc.deliveryDetail = `reconciled:${looked.detail ?? ""}`;
          job.state = "DONE";
          job.phase = "SUBMITTED";
        } else {
          doc.deliveryStatus = "SUBMITTING";
          try {
            const submission = await options.provider.submit({
              tenantId: job.tenantId,
              documentId: job.documentId,
              sha256: doc.sha256,
              xml: doc.preparedXml,
              idempotencyKey: job.idempotencyKey,
              scenario: job.scenario,
            });
            doc.deliveryStatus = submission.status;
            doc.providerMessageId = submission.providerMessageId;
            doc.simulatedDelivery = submission.simulated || caps.simulated;
            doc.productionReceipt = submission.productionReceipt === true && caps.productionReceipts && caps.environment === "production";
            doc.deliveryDetail = submission.detail;
            job.state = "DONE";
            job.phase = "SUBMITTED";
          } catch (error) {
            if (error instanceof Error && error.name === "ProviderTimeoutError") {
              doc.deliveryStatus = "UNKNOWN";
              doc.deliveryDetail = "timeout-reconcile";
              doc.simulatedDelivery = caps.simulated;
              doc.productionReceipt = false;
              job.state = "UNCERTAIN";
              job.phase = "SUBMITTED";
              store.putDocument(doc);
              store.saveOutbox(job);
              const looked = await options.provider.lookup({ tenantId: job.tenantId, documentId: job.documentId });
              doc.deliveryStatus = looked.status;
              doc.providerMessageId = looked.providerMessageId ?? doc.providerMessageId;
              doc.simulatedDelivery = looked.simulated || caps.simulated;
              doc.productionReceipt = looked.productionReceipt === true && caps.productionReceipts && caps.environment === "production";
              doc.deliveryDetail = `reconciled:${looked.detail ?? ""}`;
              job.state = "DONE";
              job.phase = "SUBMITTED";
            } else {
              doc.deliveryStatus = "FAILED";
              doc.deliveryDetail = "failed";
              doc.productionReceipt = false;
              job.state = "DONE";
            }
          }
        }
      } finally {
        forceLocalReceipt(doc);
        doc.version += 1;
        doc.updatedAt = new Date().toISOString();
        store.putDocument(doc);
        store.saveOutbox(job);
      }
      return { processed: true };
    },
    async webhook(providerId: string, raw: Uint8Array, headers: Record<string, string | undefined>, receivedAt: string) {
      if (providerId !== options.provider.capabilities().providerId) throw new AppError("NOT_FOUND", 404, "Neznámy poskytovateľ.");
      let event;
      try {
        event = await options.provider.verifyWebhook({ providerId, rawBody: raw, headers, receivedAt });
      } catch (error) {
        if (error instanceof Error && error.message === "DUPLICATE") return { duplicate: true, applied: false };
        throw new AppError("UNAUTHORIZED", 401, "Webhook bol odmietnutý.");
      }
      const doc = event.documentId
        ? store.getDocument(event.documentId)
        : store.listAllDocuments().find((item) => item.providerMessageId === event.providerMessageId);
      if (!doc) return { duplicate: false, applied: false, ignored: true };
      if (rank[event.status] < rank[doc.deliveryStatus] || (doc.deliveryStatus === "REJECTED" && event.status === "DELIVERED")) {
        return { duplicate: false, applied: false, ignored: true, reason: "out_of_order" };
      }
      doc.deliveryStatus = event.status;
      doc.providerMessageId = event.providerMessageId;
      doc.simulatedDelivery = event.simulated;
      doc.productionReceipt = event.productionReceipt === true && options.provider.capabilities().productionReceipts && options.provider.capabilities().environment === "production";
      doc.updatedAt = new Date().toISOString();
      doc.version += 1;
      store.putDocument(doc);
      return { duplicate: false, applied: true, simulated: event.simulated, productionReceipt: doc.productionReceipt };
    },
  };
}

function mustDraft(store: DocumentStore, ctx: RequestContext, id: string): StoredDocument {
  const doc = store.getDocument(id);
  if (!doc || doc.tenantId !== ctx.tenantId) throw new AppError("NOT_FOUND", 404, "Doklad neexistuje.");
  if (doc.lifecycle !== "DRAFT" || !doc.canonical) throw new AppError("PRECONDITION_FAILED", 409, "Doklad nie je editovateľný draft.");
  return doc;
}

function nextNumber(store: DocumentStore, tenantId: string): string {
  const current = store.nextSequence(tenantId);
  return `${new Date().getUTCFullYear()}-${String(current).padStart(6, "0")}`;
}

export type DocumentService = ReturnType<typeof createDocumentService>;
