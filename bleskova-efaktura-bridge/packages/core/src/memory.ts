import { createHash, randomUUID } from "node:crypto";
import type { CanonicalDocument, TenantProfile, ValidationReport } from "../../contracts/src/index.js";
import type { DeliveryStatus } from "../../contracts/src/index.js";

export interface StoredDocument {
  id: string;
  tenantId: string;
  ownerUid: string;
  version: number;
  lifecycle: "QUARANTINE" | "DRAFT" | "IMPORTED" | "ISSUED";
  syntax: "UBL" | "CII" | "UNKNOWN";
  canonical?: CanonicalDocument;
  sha256: string;
  filename: string;
  validation?: ValidationReport;
  preparedXml?: Uint8Array;
  deliveryStatus: DeliveryStatus;
  providerMessageId?: string;
  simulatedDelivery: boolean;
  productionReceipt: boolean;
  deliveryDetail?: string;
  conflictWith?: string;
  createdAt: string;
  updatedAt: string;
}

export interface NoteRecord {
  id: string;
  tenantId: string;
  documentId: string;
  text: string;
  createdAt: string;
}

export type IdempotencyState = "IN_PROGRESS" | "COMPLETED" | "REJECTED" | "RETRYABLE" | "UNCERTAIN";

export interface IdempotencyError {
  code: string;
  httpStatus: number;
  message: string;
}

export interface IdempotencyRecord {
  requestHash: string;
  state: IdempotencyState;
  response?: unknown;
  error?: IdempotencyError;
  updatedAt: number;
}

export interface OutboxJob {
  id: string;
  tenantId: string;
  documentId: string;
  idempotencyKey: string;
  scenario?: string;
  attempts: number;
  leaseUntil: number;
  state: "PENDING" | "DONE" | "UNCERTAIN";
  phase: "NEW" | "SUBMITTING" | "SUBMITTED";
}

export interface ClaimedJob {
  job: OutboxJob;
  reconcile: boolean;
}

export interface DocumentStore {
  getTenant(id: string): TenantProfile | undefined;
  putTenant(tenant: TenantProfile): void;
  getMember(uid: string, tenantId: string): { roles: string[] } | undefined;
  putMember(uid: string, tenantId: string, roles: string[]): void;
  getDocument(id: string): StoredDocument | undefined;
  putDocument(doc: StoredDocument): void;
  /** Write only when the stored version is still expectedVersion. Join an open transaction; do not call this with a stale object from before an await. */
  saveDocumentIfVersion(doc: StoredDocument, expectedVersion: number): boolean;
  listDocuments(tenantId: string): StoredDocument[];
  listAllDocuments(): StoredDocument[];
  countDocuments(tenantId?: string): number;
  getOriginal(id: string): Uint8Array | undefined;
  putOriginal(id: string, bytes: Uint8Array): void;
  addNote(note: NoteRecord): void;
  getIdempotency(id: string): IdempotencyRecord | undefined;
  putIdempotency(id: string, record: IdempotencyRecord): void;
  getHash(tenantId: string, sha: string): string | undefined;
  putHashIfAbsent(tenantId: string, sha: string, documentId: string): string;
  getNumber(key: string): string | undefined;
  putNumberIfAbsent(key: string, documentId: string): string;
  nextSequence(tenantId: string): number;
  addOutbox(job: OutboxJob): void;
  claimOutbox(now: number, leaseMs: number): ClaimedJob | undefined;
  saveOutbox(job: OutboxJob): void;
  listOutbox(): OutboxJob[];
  lock<T>(key: string, fn: () => Promise<T>): Promise<T>;
  transaction<T>(fn: () => T): T;
  close(): void;
}

export class MemoryStore implements DocumentStore {
  readonly documents = new Map<string, StoredDocument>();
  readonly originals = new Map<string, Uint8Array>();
  readonly notes: NoteRecord[] = [];
  readonly tenants = new Map<string, TenantProfile>();
  readonly members = new Map<string, { roles: string[] }>();
  readonly idempotency = new Map<string, IdempotencyRecord>();
  readonly hashes = new Map<string, string>();
  readonly numbers = new Map<string, string>();
  readonly sequences = new Map<string, number>();
  readonly outbox: OutboxJob[] = [];
  private readonly tails = new Map<string, Promise<void>>();
  private txDepth = 0;

  memberKey(uid: string, tenantId: string): string {
    return `${uid}|${tenantId}`;
  }

  getTenant(id: string): TenantProfile | undefined {
    return this.tenants.get(id);
  }

  putTenant(tenant: TenantProfile): void {
    this.tenants.set(tenant.id, tenant);
  }

  getMember(uid: string, tenantId: string): { roles: string[] } | undefined {
    return this.members.get(this.memberKey(uid, tenantId));
  }

  putMember(uid: string, tenantId: string, roles: string[]): void {
    this.members.set(this.memberKey(uid, tenantId), { roles });
  }

  getDocument(id: string): StoredDocument | undefined {
    return this.documents.get(id);
  }

  putDocument(doc: StoredDocument): void {
    this.documents.set(doc.id, doc);
  }

  saveDocumentIfVersion(doc: StoredDocument, expectedVersion: number): boolean {
    return this.transaction(() => {
      const current = this.getDocument(doc.id);
      if (!current || current.version !== expectedVersion) return false;
      if (doc.version !== expectedVersion + 1) return false;
      this.putDocument(doc);
      return true;
    });
  }

  listDocuments(tenantId: string): StoredDocument[] {
    return [...this.documents.values()]
      .filter((doc) => doc.tenantId === tenantId)
      .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
  }

  listAllDocuments(): StoredDocument[] {
    return [...this.documents.values()];
  }

  countDocuments(tenantId?: string): number {
    if (!tenantId) return this.documents.size;
    let count = 0;
    for (const doc of this.documents.values()) if (doc.tenantId === tenantId) count += 1;
    return count;
  }

  getOriginal(id: string): Uint8Array | undefined {
    return this.originals.get(id);
  }

  putOriginal(id: string, bytes: Uint8Array): void {
    this.originals.set(id, bytes);
  }

  addNote(note: NoteRecord): void {
    this.notes.push(note);
  }

  getIdempotency(id: string): IdempotencyRecord | undefined {
    return this.idempotency.get(id);
  }

  putIdempotency(id: string, record: IdempotencyRecord): void {
    this.idempotency.set(id, record);
  }

  getHash(tenantId: string, sha: string): string | undefined {
    return this.hashes.get(`${tenantId}|${sha}`);
  }

  putHashIfAbsent(tenantId: string, sha: string, documentId: string): string {
    const key = `${tenantId}|${sha}`;
    const existing = this.hashes.get(key);
    if (existing) return existing;
    this.hashes.set(key, documentId);
    return documentId;
  }

  getNumber(key: string): string | undefined {
    return this.numbers.get(key);
  }

  putNumberIfAbsent(key: string, documentId: string): string {
    const existing = this.numbers.get(key);
    if (existing) return existing;
    this.numbers.set(key, documentId);
    return documentId;
  }

  nextSequence(tenantId: string): number {
    const current = (this.sequences.get(tenantId) ?? 0) + 1;
    this.sequences.set(tenantId, current);
    return current;
  }

  addOutbox(job: OutboxJob): void {
    this.outbox.push(job);
  }

  claimOutbox(now: number, leaseMs: number): ClaimedJob | undefined {
    return this.transaction(() => {
      const job = this.outbox.find((item) => (item.state === "PENDING" || item.state === "UNCERTAIN") && item.leaseUntil < now);
      if (!job) return undefined;
      const reconcile = job.phase === "SUBMITTING" || job.phase === "SUBMITTED" || job.state === "UNCERTAIN";
      job.leaseUntil = now + leaseMs;
      job.attempts += 1;
      if (!reconcile) job.phase = "SUBMITTING";
      return { job, reconcile };
    });
  }

  saveOutbox(job: OutboxJob): void {
    const index = this.outbox.findIndex((item) => item.id === job.id);
    if (index >= 0) this.outbox[index] = job;
    else this.outbox.push(job);
  }

  listOutbox(): OutboxJob[] {
    return this.outbox.map((job) => ({ ...job }));
  }

  async lock<T>(key: string, fn: () => Promise<T>): Promise<T> {
    const prev = this.tails.get(key) ?? Promise.resolve();
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.tails.set(key, prev.then(() => gate));
    await prev;
    try {
      return await fn();
    } finally {
      release();
    }
  }

  transaction<T>(fn: () => T): T {
    if (this.txDepth > 0) return fn();
    const snapshot = {
      documents: new Map([...this.documents].map(([key, doc]) => [key, structuredClone(doc)])),
      originals: new Map([...this.originals].map(([key, bytes]) => [key, new Uint8Array(bytes)])),
      notes: this.notes.map((note) => structuredClone(note)),
      idempotency: new Map([...this.idempotency].map(([key, record]) => [key, structuredClone(record)])),
      hashes: new Map(this.hashes),
      numbers: new Map(this.numbers),
      sequences: new Map(this.sequences),
      outbox: this.outbox.map((job) => structuredClone(job)),
    };
    this.txDepth += 1;
    try {
      return fn();
    } catch (error) {
      this.documents.clear();
      for (const [key, doc] of snapshot.documents) this.documents.set(key, doc);
      this.originals.clear();
      for (const [key, bytes] of snapshot.originals) this.originals.set(key, bytes);
      this.notes.splice(0, this.notes.length, ...snapshot.notes);
      this.idempotency.clear();
      for (const [key, record] of snapshot.idempotency) this.idempotency.set(key, record);
      this.hashes.clear();
      for (const [key, value] of snapshot.hashes) this.hashes.set(key, value);
      this.numbers.clear();
      for (const [key, value] of snapshot.numbers) this.numbers.set(key, value);
      this.sequences.clear();
      for (const [key, value] of snapshot.sequences) this.sequences.set(key, value);
      this.outbox.splice(0, this.outbox.length, ...snapshot.outbox);
      throw error;
    } finally {
      this.txDepth -= 1;
    }
  }

  close(): void {}
}

export function sha256(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex");
}

export function newId(): string {
  return randomUUID();
}
