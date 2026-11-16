import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

export interface BootConfig {
  env: "local" | "production";
  authMode: "emulator" | "disabled";
  providerName: string;
  seed: boolean;
  store: "sqlite" | "memory";
  port: number;
  host: string;
  emulatorSecret: string;
  webhookSecret: string;
  dataDir: string;
  validatorCache: string;
  validatorTimeoutMs: number;
  workerMs: number;
}

export function loadEnvFile(file: string): void {
  if (!existsSync(file)) return;
  const text = readFileSync(file, "utf8");
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = value;
  }
}

export function readBootConfig(moduleRoot: string, source: NodeJS.ProcessEnv = process.env): BootConfig {
  const env = source.EFAKTURA_ENV === "production" ? "production" : "local";
  const authMode = source.EFAKTURA_AUTH_MODE === "emulator" ? "emulator" : "disabled";
  const providerName = source.EFAKTURA_PROVIDER ?? (env === "production" ? "unconfigured" : "local-sandbox");
  const seed = source.EFAKTURA_LOCAL_SEED === "1" || (env === "local" && source.EFAKTURA_LOCAL_SEED !== "0");
  const store = source.EFAKTURA_STORE === "memory" ? "memory" : "sqlite";
  const dataDir = source.EFAKTURA_DATA_DIR || path.join(moduleRoot, "data");
  return {
    env,
    authMode,
    providerName,
    seed,
    store,
    port: Number(source.EFAKTURA_PORT ?? "8088"),
    host: readHost(source),
    emulatorSecret: source.EFAKTURA_EMULATOR_SECRET || "local-only-not-a-production-secret",
    webhookSecret: source.EFAKTURA_SANDBOX_WEBHOOK_SECRET || "local-sandbox-webhook-not-production",
    dataDir,
    validatorCache: source.EFAKTURA_VALIDATOR_CACHE || path.join(dataDir, "validator-cache"),
    validatorTimeoutMs: Number(source.EFAKTURA_VALIDATOR_TIMEOUT_MS ?? "20000"),
    workerMs: Number(source.EFAKTURA_WORKER_MS ?? "400"),
  };
}

export function assertBoot(config: BootConfig): void {
  if (config.env !== "production") return;
  if (config.authMode === "emulator" || config.providerName === "local-sandbox") {
    throw new Error("Produkcia odmietla emulátor alebo sandbox provider.");
  }
  if (config.seed) throw new Error("Produkcia odmietla lokálny seed.");
  if (config.store !== "sqlite") throw new Error("Produkcia odmietla pamäťový store.");
}

function readHost(source: NodeJS.ProcessEnv): string {
  const raw = source.EFAKTURA_HOST?.trim() || "0.0.0.0";
  if (!/^[A-Za-z0-9.:[\]-]+$/.test(raw) || raw.length > 253) {
    throw new Error("EFAKTURA_HOST je neplatný.");
  }
  return raw;
}
