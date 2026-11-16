import { mkdirSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import type { TenantProfile } from "../../contracts/src/index.js";
import {
  MemoryStore,
  type ClaimedJob,
  type DocumentStore,
  type IdempotencyRecord,
  type NoteRecord,
  type OutboxJob,
  type StoredDocument,
} from "./memory.js";

interface SqlStatement {
  run(...params: unknown[]): unknown;
  get(...params: unknown[]): unknown;
  all(...params: unknown[]): unknown[];
}

interface SqlDatabase {
  exec(sql: string): void;
  prepare(sql: string): SqlStatement;
  close(): void;
}

const require = createRequire(import.meta.url);
const { DatabaseSync } = require("node:sqlite") as { DatabaseSync: new (filename: string) => SqlDatabase };

/**
 * Local durable adapter. One SQLite file with transactions and unique keys.
 * This is not a distributed production deployment.
 */
export class SqliteStore implements DocumentStore {
  private readonly db: SqlDatabase;
  private readonly memoryLock = new MemoryStore();
  private inTx = false;

  constructor(filename: string) {
    mkdirSync(path.dirname(filename), { recursive: true });
    this.db = new DatabaseSync(filename);
    this.db.exec("PRAGMA journal_mode = WAL");
    this.db.exec("PRAGMA busy_timeout = 5000");
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS tenants (id TEXT PRIMARY KEY, json TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS members (uid TEXT NOT NULL, tenant_id TEXT NOT NULL, roles TEXT NOT NULL, PRIMARY KEY (uid, tenant_id));
      CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, json TEXT NOT NULL, created_at TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS documents_tenant ON documents(tenant_id, created_at);
      CREATE TABLE IF NOT EXISTS originals (document_id TEXT PRIMARY KEY, bytes BLOB NOT NULL);
      CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, document_id TEXT NOT NULL, json TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS idempotency (
        id TEXT PRIMARY KEY,
        request_hash TEXT NOT NULL,
        state TEXT NOT NULL,
        response_json TEXT,
        error_json TEXT,
        updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS content_hashes (
        tenant_id TEXT NOT NULL,
        sha256 TEXT NOT NULL,
        document_id TEXT NOT NULL,
        PRIMARY KEY (tenant_id, sha256)
      );
      CREATE TABLE IF NOT EXISTS numbers (number_key TEXT PRIMARY KEY, document_id TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS sequences (tenant_id TEXT PRIMARY KEY, value INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS outbox (
        id TEXT PRIMARY KEY,
        json TEXT NOT NULL,
        state TEXT NOT NULL,
        lease_until INTEGER NOT NULL
      );
    `);
  }

  getTenant(id: string): TenantProfile | undefined {
    const row = this.one("SELECT json FROM tenants WHERE id = ?", id);
    return row ? JSON.parse(String(row.json)) as TenantProfile : undefined;
  }

  putTenant(tenant: TenantProfile): void {
    this.run("INSERT INTO tenants (id, json) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET json = excluded.json", tenant.id, JSON.stringify(tenant));
  }

  getMember(uid: string, tenantId: string): { roles: string[] } | undefined {
    const row = this.one("SELECT roles FROM members WHERE uid = ? AND tenant_id = ?", uid, tenantId);
    return row ? { roles: JSON.parse(String(row.roles)) as string[] } : undefined;
  }

  putMember(uid: string, tenantId: string, roles: string[]): void {
    this.run(
      "INSERT INTO members (uid, tenant_id, roles) VALUES (?, ?, ?) ON CONFLICT(uid, tenant_id) DO UPDATE SET roles = excluded.roles",
      uid,
      tenantId,
      JSON.stringify(roles),
    );
  }

  getDocument(id: string): StoredDocument | undefined {
    const row = this.one("SELECT json FROM documents WHERE id = ?", id);
    if (!row) return undefined;
    return this.hydrate(String(row.json), this.getOriginal(id));
  }

  putDocument(doc: StoredDocument): void {
    const { preparedXml, ...rest } = doc;
    this.run(
      "INSERT INTO documents (id, tenant_id, json, created_at) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET tenant_id = excluded.tenant_id, json = excluded.json, created_at = excluded.created_at",
      doc.id,
      doc.tenantId,
      JSON.stringify(rest),
      doc.createdAt,
    );
    if (preparedXml) this.putOriginal(doc.id, preparedXml);
  }

  listDocuments(tenantId: string): StoredDocument[] {
    const rows = this.db.prepare("SELECT json FROM documents WHERE tenant_id = ? ORDER BY created_at DESC").all(tenantId);
    return rows.map((row) => this.hydrate(String((row as { json: string }).json)));
  }

  listAllDocuments(): StoredDocument[] {
    return this.db.prepare("SELECT id, json FROM documents").all().map((row) => {
      const record = row as { id: string; json: string };
      return this.hydrate(record.json, this.getOriginal(record.id));
    });
  }

  countDocuments(tenantId?: string): number {
    const row = tenantId
      ? this.one("SELECT COUNT(*) AS n FROM documents WHERE tenant_id = ?", tenantId)
      : this.one("SELECT COUNT(*) AS n FROM documents");
    return Number(row?.n ?? 0);
  }

  getOriginal(id: string): Uint8Array | undefined {
    const row = this.one("SELECT bytes FROM originals WHERE document_id = ?", id);
    if (!row || !(row.bytes instanceof Uint8Array)) return undefined;
    return new Uint8Array(row.bytes);
  }

  putOriginal(id: string, bytes: Uint8Array): void {
    this.run(
      "INSERT INTO originals (document_id, bytes) VALUES (?, ?) ON CONFLICT(document_id) DO UPDATE SET bytes = excluded.bytes",
      id,
      Buffer.from(bytes),
    );
  }

  addNote(note: NoteRecord): void {
    this.run("INSERT INTO notes (id, tenant_id, document_id, json) VALUES (?, ?, ?, ?)", note.id, note.tenantId, note.documentId, JSON.stringify(note));
  }

  getIdempotency(id: string): IdempotencyRecord | undefined {
    const row = this.one("SELECT request_hash, state, response_json, error_json, updated_at FROM idempotency WHERE id = ?", id);
    if (!row) return undefined;
    return {
      requestHash: String(row.request_hash),
      state: row.state as IdempotencyRecord["state"],
      response: row.response_json ? JSON.parse(String(row.response_json)) : undefined,
      error: row.error_json ? JSON.parse(String(row.error_json)) as IdempotencyRecord["error"] : undefined,
      updatedAt: Number(row.updated_at),
    };
  }

  putIdempotency(id: string, record: IdempotencyRecord): void {
    this.run(
      `INSERT INTO idempotency (id, request_hash, state, response_json, error_json, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET request_hash = excluded.request_hash, state = excluded.state, response_json = excluded.response_json, error_json = excluded.error_json, updated_at = excluded.updated_at`,
      id,
      record.requestHash,
      record.state,
      record.response === undefined ? null : JSON.stringify(record.response),
      record.error === undefined ? null : JSON.stringify(record.error),
      record.updatedAt,
    );
  }

  getHash(tenantId: string, sha: string): string | undefined {
    const row = this.one("SELECT document_id FROM content_hashes WHERE tenant_id = ? AND sha256 = ?", tenantId, sha);
    return row ? String(row.document_id) : undefined;
  }

  putHashIfAbsent(tenantId: string, sha: string, documentId: string): string {
    this.run("INSERT INTO content_hashes (tenant_id, sha256, document_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", tenantId, sha, documentId);
    return this.getHash(tenantId, sha) ?? documentId;
  }

  getNumber(key: string): string | undefined {
    const row = this.one("SELECT document_id FROM numbers WHERE number_key = ?", key);
    return row ? String(row.document_id) : undefined;
  }

  putNumberIfAbsent(key: string, documentId: string): string {
    this.run("INSERT INTO numbers (number_key, document_id) VALUES (?, ?) ON CONFLICT DO NOTHING", key, documentId);
    return this.getNumber(key) ?? documentId;
  }

  nextSequence(tenantId: string): number {
    return this.transaction(() => {
      const row = this.one("SELECT value FROM sequences WHERE tenant_id = ?", tenantId);
      const next = Number(row?.value ?? 0) + 1;
      this.run("INSERT INTO sequences (tenant_id, value) VALUES (?, ?) ON CONFLICT(tenant_id) DO UPDATE SET value = excluded.value", tenantId, next);
      return next;
    });
  }

  addOutbox(job: OutboxJob): void {
    this.run(
      "INSERT INTO outbox (id, json, state, lease_until) VALUES (?, ?, ?, ?)",
      job.id,
      JSON.stringify(job),
      job.state,
      job.leaseUntil,
    );
  }

  claimOutbox(now: number, leaseMs: number): ClaimedJob | undefined {
    return this.transaction(() => {
      const row = this.one(
        "SELECT id, json FROM outbox WHERE state IN ('PENDING', 'UNCERTAIN') AND lease_until < ? ORDER BY id LIMIT 1",
        now,
      );
      if (!row) return undefined;
      const job = JSON.parse(String(row.json)) as OutboxJob;
      const reconcile = job.phase === "SUBMITTING" || job.phase === "SUBMITTED" || job.state === "UNCERTAIN";
      job.leaseUntil = now + leaseMs;
      job.attempts += 1;
      this.saveOutbox(job);
      return { job, reconcile };
    });
  }

  saveOutbox(job: OutboxJob): void {
    this.run(
      "UPDATE outbox SET json = ?, state = ?, lease_until = ? WHERE id = ?",
      JSON.stringify(job),
      job.state,
      job.leaseUntil,
      job.id,
    );
  }

  listOutbox(): OutboxJob[] {
    return this.db.prepare("SELECT json FROM outbox ORDER BY id").all().map((row) => JSON.parse(String((row as { json: string }).json)) as OutboxJob);
  }

  lock<T>(key: string, fn: () => Promise<T>): Promise<T> {
    return this.memoryLock.lock(key, fn);
  }

  transaction<T>(fn: () => T): T {
    if (this.inTx) return fn();
    this.inTx = true;
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const value = fn();
      this.db.exec("COMMIT");
      return value;
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    } finally {
      this.inTx = false;
    }
  }

  close(): void {
    this.db.close();
  }

  private hydrate(json: string, bytes?: Uint8Array): StoredDocument {
    const doc = JSON.parse(json) as StoredDocument;
    if (bytes) doc.preparedXml = bytes;
    return doc;
  }

  private run(sql: string, ...params: unknown[]): void {
    this.db.prepare(sql).run(...params);
  }

  private one(sql: string, ...params: unknown[]): Record<string, unknown> | undefined {
    return this.db.prepare(sql).get(...params) as Record<string, unknown> | undefined;
  }
}
