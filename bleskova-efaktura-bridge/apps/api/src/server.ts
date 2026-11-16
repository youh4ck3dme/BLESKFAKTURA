import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { RequestContext } from "../../../packages/contracts/src/index.js";
import { AppError } from "../../../packages/core/src/errors.js";
import type { DocumentService } from "../../../packages/core/src/service.js";

export interface ApiOptions {
  service: DocumentService;
  env: "local" | "production";
  authMode: "emulator" | "disabled";
  emulatorSecret: string;
  rateLimitPerMinute: number;
}

const hits = new Map<string, number[]>();

export function createApi(options: ApiOptions) {
  if (options.env === "production" && options.authMode !== "disabled") {
    throw new Error("Produkcia nesmie bežať s emulátorovým prihlásením.");
  }
  return async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const correlationId = req.headers["x-correlation-id"]?.toString() || randomUUID();
    try {
      const url = new URL(req.url || "/", "http://localhost");
      if (req.method === "GET" && url.pathname === "/health") {
        send(res, 200, { status: "ok", environment: options.env, correlationId });
        return;
      }
      if (req.method === "POST" && url.pathname.startsWith("/v1/providers/") && url.pathname.endsWith("/webhooks")) {
        const providerId = decodeURIComponent(url.pathname.split("/")[3] || "");
        const raw = await readBody(req, 1_500_000);
        const headers: Record<string, string | undefined> = {};
        for (const [key, value] of Object.entries(req.headers)) headers[key] = Array.isArray(value) ? value[0] : value;
        const result = await options.service.webhook(providerId, raw, headers, new Date().toISOString());
        send(res, 200, { ...result, correlationId });
        return;
      }
      const ctx = authenticate(req, options);
      rateLimit(ctx.tenantId, options.rateLimitPerMinute);
      const idem = req.headers["idempotency-key"]?.toString();
      const parts = url.pathname.split("/").filter(Boolean);
      if (parts[0] !== "v1" || parts[1] !== "tenants" || parts[2] !== ctx.tenantId) {
        throw new AppError("FORBIDDEN", 403, "Cesta tenanta nesedí s overenou identitou.");
      }
      const tail = parts.slice(3);
      if (req.method === "POST" && tail.join("/") === "documents/import") {
        if (!idem) throw new AppError("PRECONDITION_FAILED", 400, "Chýba Idempotency-Key.");
        const raw = await readBody(req, 1_500_000);
        const direction = url.searchParams.get("direction") === "OUTBOUND" ? "OUTBOUND" : "INBOUND";
        const result = await options.service.importXml({ ...ctx }, { bytes: raw, filename: "upload.xml", idempotencyKey: idem, direction });
        const status = result.code === "NUMBER_CONFLICT" ? 409 : result.code === "UNSUPPORTED_SYNTAX" ? 422 : 200;
        send(res, status, { ...result, correlationId });
        return;
      }
      if (req.method === "GET" && tail[0] === "documents" && tail.length === 1) {
        const cursor = url.searchParams.get("cursor") ?? undefined;
        const limit = Number(url.searchParams.get("limit") ?? "20");
        send(res, 200, { ...options.service.list(ctx, cursor, limit), correlationId });
        return;
      }
      if (req.method === "GET" && tail[0] === "documents" && tail[2] === "original") {
        const file = options.service.original(ctx, tail[1]!);
        res.writeHead(200, {
          "content-type": "application/xml",
          "x-content-sha256": file.sha256,
          "x-correlation-id": correlationId,
        });
        res.end(Buffer.from(file.bytes));
        return;
      }
      if (req.method === "GET" && tail[0] === "documents" && tail[2] === "archive") {
        const file = options.service.original(ctx, tail[1]!);
        const doc = options.service.get(ctx, tail[1]!);
        const zip = zipStore([
          { name: "original.xml", data: Buffer.from(file.bytes) },
          { name: "metadata.json", data: Buffer.from(JSON.stringify(doc)) },
        ]);
        res.writeHead(200, { "content-type": "application/zip", "x-correlation-id": correlationId });
        res.end(zip);
        return;
      }
      if (req.method === "GET" && tail[0] === "documents" && tail[2] === "delivery") {
        send(res, 200, { ...options.service.delivery(ctx, tail[1]!), correlationId });
        return;
      }
      if (req.method === "GET" && tail[0] === "documents" && tail.length === 2) {
        send(res, 200, { ...options.service.get(ctx, tail[1]!), correlationId });
        return;
      }
      if (req.method === "POST" && tail.join("/") === "drafts") {
        if (!idem) throw new AppError("PRECONDITION_FAILED", 400, "Chýba Idempotency-Key.");
        const body = JSON.parse(Buffer.from(await readBody(req, 1_500_000)).toString("utf8"));
        const created = await options.service.createDraft(ctx, body, idem) as unknown as Record<string, unknown>;
        send(res, 201, { ...created, correlationId });
        return;
      }
      if (req.method === "POST" && tail[0] === "drafts" && tail[2] === "validate") {
        if (!idem) throw new AppError("PRECONDITION_FAILED", 400, "Chýba Idempotency-Key.");
        const report = await options.service.validateDraft(ctx, tail[1]!, idem) as unknown as Record<string, unknown>;
        send(res, 200, { ...report, correlationId });
        return;
      }
      if (req.method === "POST" && tail[0] === "drafts" && tail[2] === "issue") {
        if (!idem) throw new AppError("PRECONDITION_FAILED", 400, "Chýba Idempotency-Key.");
        const expected = req.headers["x-expected-version"];
        const issued = await options.service.issueDraft(ctx, tail[1]!, idem, expected ? Number(expected) : undefined) as unknown as Record<string, unknown>;
        send(res, 200, { ...issued, correlationId });
        return;
      }
      if (req.method === "POST" && tail[0] === "documents" && tail[2] === "send") {
        if (!idem) throw new AppError("PRECONDITION_FAILED", 400, "Chýba Idempotency-Key.");
        const scenario = options.env === "local" ? req.headers["x-sandbox-scenario"]?.toString() : undefined;
        const queued = await options.service.send(ctx, tail[1]!, idem, scenario) as unknown as Record<string, unknown>;
        send(res, 202, { ...queued, correlationId });
        return;
      }
      if (req.method === "POST" && tail[0] === "documents" && tail[2] === "notes") {
        const body = JSON.parse(Buffer.from(await readBody(req, 20_000)).toString("utf8")) as { text: string };
        send(res, 201, { ...options.service.addNote(ctx, tail[1]!, body.text), correlationId });
        return;
      }
      throw new AppError("NOT_FOUND", 404, "Cesta neexistuje.");
    } catch (error) {
      const app = error instanceof AppError ? error : new AppError("VALIDATION_FAILED", 400, "Požiadavku sa nepodarilo spracovať.");
      if (!(error instanceof AppError)) console.error(JSON.stringify({ correlationId, code: "INTERNAL" }));
      send(res, app.httpStatus, { code: app.code, message: app.message, correlationId, issues: app.issues });
    }
  };
}

export function startServer(options: ApiOptions & { port: number; host?: string }) {
  const handle = createApi(options);
  const server = createServer((req, res) => {
    void handle(req, res);
  });
  return new Promise<{ server: ReturnType<typeof createServer>; url: string }>((resolve) => {
    server.listen(options.port, options.host ?? "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : options.port;
      resolve({ server, url: `http://127.0.0.1:${port}` });
    });
  });
}

function authenticate(req: IncomingMessage, options: ApiOptions): RequestContext {
  if (options.env === "production" || options.authMode !== "emulator") {
    throw new AppError("UNAUTHORIZED", 401, "Produkčné overenie Firebase nie je v tomto zostavení nakonfigurované.");
  }
  const auth = req.headers.authorization?.toString() || "";
  const appCheck = req.headers["x-firebase-appcheck"]?.toString() || "";
  const user = verifyEmu(auth.replace(/^Bearer\s+/i, ""), options.emulatorSecret, "user");
  verifyEmu(appCheck, options.emulatorSecret, "appcheck");
  const tenantHeader = req.headers["x-tenant-id"]?.toString();
  if (!user.uid || !tenantHeader) throw new AppError("UNAUTHORIZED", 401, "Chýba identita alebo tenant.");
  return { uid: user.uid, tenantId: tenantHeader, roles: [] };
}

function verifyEmu(token: string, secret: string, typ: string): { uid?: string; typ: string; exp: number } {
  const [prefix, body, sig] = token.split(".");
  if (prefix !== "emu" || !body || !sig) throw new AppError("UNAUTHORIZED", 401, "Token bol odmietnutý.");
  const expected = createHmac("sha256", secret).update(body).digest("base64url");
  const a = Buffer.from(expected);
  const b = Buffer.from(sig);
  if (a.length !== b.length || !timingSafeEqual(a, b)) throw new AppError("UNAUTHORIZED", 401, "Token bol odmietnutý.");
  const payload = JSON.parse(Buffer.from(body, "base64url").toString("utf8")) as { uid?: string; typ: string; exp: number };
  if (payload.typ !== typ || payload.exp < Date.now()) throw new AppError("UNAUTHORIZED", 401, "Token je neplatný alebo exspirovaný.");
  return payload;
}

function rateLimit(tenantId: string, perMinute: number): void {
  const now = Date.now();
  const window = (hits.get(tenantId) ?? []).filter((stamp) => now - stamp < 60_000);
  if (window.length >= perMinute) throw new AppError("RATE_LIMITED", 429, "Príliš veľa požiadaviek.");
  window.push(now);
  hits.set(tenantId, window);
}

function readBody(req: IncomingMessage, limit: number): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > limit) {
        reject(new AppError("PAYLOAD_TOO_LARGE", 413, "Telo požiadavky je príliš veľké."));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", () => reject(new AppError("VALIDATION_FAILED", 400, "Telo požiadavky sa prerušilo.")));
  });
}

function send(res: ServerResponse, status: number, body: unknown): void {
  const raw = Buffer.from(JSON.stringify(body));
  res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  res.end(raw);
}

function crc32(buf: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of buf) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

export function zipStore(files: { name: string; data: Buffer }[]): Buffer {
  const parts: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const file of files) {
    const name = Buffer.from(file.name);
    const crc = crc32(file.data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(file.data.length, 18);
    local.writeUInt32LE(file.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    parts.push(local, name, file.data);
    const cen = Buffer.alloc(46);
    cen.writeUInt32LE(0x02014b50, 0);
    cen.writeUInt16LE(20, 4);
    cen.writeUInt16LE(20, 6);
    cen.writeUInt16LE(0, 8);
    cen.writeUInt16LE(0, 10);
    cen.writeUInt16LE(0, 12);
    cen.writeUInt16LE(0, 14);
    cen.writeUInt32LE(crc, 16);
    cen.writeUInt32LE(file.data.length, 20);
    cen.writeUInt32LE(file.data.length, 24);
    cen.writeUInt16LE(name.length, 28);
    cen.writeUInt16LE(0, 30);
    cen.writeUInt16LE(0, 32);
    cen.writeUInt16LE(0, 34);
    cen.writeUInt16LE(0, 36);
    cen.writeUInt32LE(0, 38);
    cen.writeUInt32LE(offset, 42);
    central.push(cen, name);
    offset += local.length + name.length + file.data.length;
  }
  const centralBuf = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...parts, centralBuf, end]);
}
