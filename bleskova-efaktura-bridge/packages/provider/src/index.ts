import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import type {
  DeliveryProvider,
  DeliveryStatus,
  ProviderCapabilities,
  ProviderDeliveryState,
  ProviderLookup,
  ProviderSubmission,
  RawWebhook,
  ValidatedOutboundSubmission,
  VerifiedProviderEvent,
} from "../../contracts/src/index.js";

export class ProviderTimeoutError extends Error {
  readonly code = "PROVIDER_TIMEOUT";
  constructor() {
    super("Poskytovateľ neodpovedal. Odoslanie mohlo prejsť, nový doklad sa nevytvára.");
    this.name = "ProviderTimeoutError";
  }
}

export class WebhookRejected extends Error {
  readonly httpStatus: number;
  constructor(message: string, httpStatus = 401) {
    super(message);
    this.httpStatus = httpStatus;
  }
}

interface SandboxRecord {
  status: DeliveryStatus;
  providerMessageId: string;
  simulated: true;
  productionReceipt: false;
}

export class LocalSandboxProvider implements DeliveryProvider {
  readonly records = new Map<string, SandboxRecord>();
  readonly seenEvents = new Set<string>();

  constructor(private readonly webhookSecret: string) {}

  capabilities(): ProviderCapabilities {
    return {
      providerId: "local-sandbox",
      environment: "sandbox",
      simulated: true,
      productionReceipts: false,
      idempotentSubmit: true,
    };
  }

  async submit(input: ValidatedOutboundSubmission): Promise<ProviderSubmission> {
    const existing = this.records.get(input.documentId);
    if (existing && this.capabilities().idempotentSubmit) {
      return { ...existing, detail: "simulated-replay" };
    }
    const providerMessageId = `sandbox-${randomUUID()}`;
    const record: SandboxRecord = {
      status: input.scenario === "reject" ? "REJECTED" : "PROVIDER_ACCEPTED",
      providerMessageId,
      simulated: true,
      productionReceipt: false,
    };
    this.records.set(input.documentId, record);
    if (input.scenario === "timeout") throw new ProviderTimeoutError();
    return { ...record, detail: "simulated-sandbox-not-a-production-receipt" };
  }

  async lookup(input: ProviderLookup): Promise<ProviderDeliveryState> {
    const record = this.records.get(input.documentId);
    if (!record) {
      return { status: "UNKNOWN", simulated: true, productionReceipt: false, detail: "sandbox-no-record" };
    }
    return { ...record, detail: "simulated" };
  }

  async verifyWebhook(input: RawWebhook): Promise<VerifiedProviderEvent> {
    const header = input.headers["x-provider-signature"] ?? input.headers["X-Provider-Signature"];
    if (!header) throw new WebhookRejected("Chýba podpis webhooku.");
    const parts = Object.fromEntries(header.split(",").map((p) => p.split("=") as [string, string]));
    const timestamp = parts.t;
    const signature = parts.v1;
    if (!timestamp || !signature) throw new WebhookRejected("Podpis webhooku má zlý formát.");
    const age = Math.abs(Date.parse(input.receivedAt) - Number(timestamp) * 1000);
    if (!Number.isFinite(age) || age > 5 * 60 * 1000) throw new WebhookRejected("Webhook je mimo časového okna.");
    const expected = createHmac("sha256", this.webhookSecret).update(`${timestamp}.`).update(Buffer.from(input.rawBody)).digest("hex");
    const a = Buffer.from(expected);
    const b = Buffer.from(signature);
    if (a.length !== b.length || !timingSafeEqual(a, b)) throw new WebhookRejected("Podpis webhooku nesedí.");
    const body = JSON.parse(Buffer.from(input.rawBody).toString("utf8")) as {
      eventId: string;
      providerMessageId: string;
      documentId?: string;
      status: DeliveryStatus;
      occurredAt: string;
    };
    if (this.seenEvents.has(body.eventId)) {
      throw new WebhookRejected("DUPLICATE", 200);
    }
    this.seenEvents.add(body.eventId);
    return {
      eventId: body.eventId,
      providerMessageId: body.providerMessageId,
      documentId: body.documentId,
      status: body.status,
      occurredAt: body.occurredAt,
      simulated: true,
      productionReceipt: false,
    };
  }

  sign(raw: Buffer, timestampSec: number): string {
    const v1 = createHmac("sha256", this.webhookSecret).update(`${timestampSec}.`).update(raw).digest("hex");
    return `t=${timestampSec},v1=${v1}`;
  }
}

export class UnconfiguredProvider implements DeliveryProvider {
  capabilities(): ProviderCapabilities {
    return {
      providerId: "unconfigured",
      environment: "unconfigured",
      simulated: false,
      productionReceipts: false,
      idempotentSubmit: false,
    };
  }
  async submit(): Promise<ProviderSubmission> {
    throw new Error("PROVIDER_NOT_CONFIGURED");
  }
  async lookup(): Promise<ProviderDeliveryState> {
    throw new Error("PROVIDER_NOT_CONFIGURED");
  }
  async verifyWebhook(): Promise<VerifiedProviderEvent> {
    throw new Error("PROVIDER_NOT_CONFIGURED");
  }
}
