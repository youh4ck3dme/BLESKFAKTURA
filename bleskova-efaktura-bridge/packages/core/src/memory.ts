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

export interface IdempotencyRecord {
  requestHash: string;
  done: boolean;
  response?: unknown;
}

export interface OutboxJob {
  id: string;
  tenantId: string;
  documentId: string;
  idempotencyKey: string;
  scenario?: string;
  attempts: number;
  leaseUntil: number;
  state: "PENDING" | "DONE";
}

export class MemoryStore {
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

  memberKey(uid: string, tenantId: string): string {
    return `${uid}|${tenantId}`;
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
}

export function sha256(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex");
}

export function newId(): string {
  return randomUUID();
}
