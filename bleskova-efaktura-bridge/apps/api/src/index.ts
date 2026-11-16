import path from "node:path";
import { fileURLToPath } from "node:url";
import { composeReport, runStandardsEngine } from "../../../packages/validation/src/engine.js";
import { inspectUbl, serializeUbl } from "../../../packages/xml/src/ubl.js";
import { assertXmlLimits } from "../../../packages/xml/src/secure.js";
import { LocalSandboxProvider, UnconfiguredProvider } from "../../../packages/provider/src/index.js";
import { createDocumentService } from "../../../packages/core/src/service.js";
import { MemoryStore, type DocumentStore } from "../../../packages/core/src/memory.js";
import { startServer } from "./server.js";
import { assertBoot, loadEnvFile, readBootConfig } from "./env.js";
import { applyLocalSeed } from "./seed.js";

const moduleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../..");
loadEnvFile(path.join(process.cwd(), ".env"));
loadEnvFile(path.join(moduleRoot, ".env"));
const config = readBootConfig(moduleRoot);
try {
  assertBoot(config);
} catch (error) {
  console.error(error instanceof Error ? error.message : "Konfigurácia bola odmietnutá.");
  process.exit(1);
}
if (config.env === "production" && (config.authMode === "emulator" || config.providerName === "local-sandbox")) {
  console.error("Produkcia odmietla emulátor alebo sandbox provider.");
  process.exit(1);
}

process.env.EFAKTURA_VALIDATOR_CACHE = config.validatorCache;
process.env.EFAKTURA_VALIDATOR_TIMEOUT_MS = String(config.validatorTimeoutMs);

const provider = config.providerName === "local-sandbox"
  ? new LocalSandboxProvider(config.webhookSecret)
  : new UnconfiguredProvider();

const store = await openStore(config.store, config.dataDir);
if (config.seed) {
  try {
    applyLocalSeed(config.env, store);
  } catch (error) {
    console.error(error instanceof Error ? error.message : "Seed bol odmietnutý.");
    process.exit(1);
  }
}

const service = createDocumentService({
  env: config.env,
  provider,
  store,
  parser: {
    inspect(xml, direction) {
      assertXmlLimits(Buffer.from(xml));
      return inspectUbl(xml, direction);
    },
    serialize: serializeUbl,
  },
  validator: {
    async validate(xml, domainIssues) {
      const engine = await runStandardsEngine(xml, config.validatorTimeoutMs);
      if (engine.engineError) throw new Error(engine.engineError);
      return composeReport({ sha256: engine.sha256, domainIssues, engine });
    },
  },
});

let stopped = false;
let ticking: Promise<unknown> = Promise.resolve();
async function workerLoop(): Promise<void> {
  while (!stopped) {
    ticking = service.workerTick().catch(() => ({ processed: false }));
    try {
      await ticking;
    } catch {
      console.error(JSON.stringify({ code: "WORKER_ERROR" }));
    }
    await new Promise((resolve) => setTimeout(resolve, config.workerMs));
  }
}
const worker = workerLoop();

const started = await startServer({
  service,
  env: config.env,
  authMode: config.authMode,
  emulatorSecret: config.emulatorSecret,
  rateLimitPerMinute: 120,
  port: config.port,
  host: config.host,
});
console.log(JSON.stringify({
  listening: started.url,
  store: config.store,
  seed: config.seed,
  productionInfra: false,
  productionEligible: false,
}));

async function shutdown(): Promise<void> {
  if (stopped) return;
  stopped = true;
  const force = setTimeout(() => process.exit(0), 2_000);
  force.unref();
  started.server.closeAllConnections();
  await new Promise<void>((resolve) => started.server.close(() => resolve()));
  await ticking;
  await worker;
  store.close();
  clearTimeout(force);
  process.exit(0);
}
process.on("SIGTERM", () => { void shutdown(); });
process.on("SIGINT", () => { void shutdown(); });

async function openStore(kind: "sqlite" | "memory", dataDir: string): Promise<DocumentStore> {
  if (kind === "memory") return new MemoryStore();
  const { SqliteStore } = await import("../../../packages/core/src/sqlite.js");
  return new SqliteStore(path.join(dataDir, "bridge.sqlite"));
}
