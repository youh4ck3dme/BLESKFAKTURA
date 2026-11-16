import path from "node:path";
import { fileURLToPath } from "node:url";
import { assertBoot, loadEnvFile, readBootConfig } from "./env.js";
import { signEmuToken } from "./emu-token.js";

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
if (config.env === "production") {
  console.error("Lokálne HMAC tokeny sú v produkcii zakázané.");
  process.exit(1);
}

const args = new Map<string, string>();
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i += 1) {
  const key = argv[i];
  if (!key?.startsWith("--")) continue;
  args.set(key.slice(2), argv[i + 1] ?? "");
}
const uid = args.get("uid") || "uid-buyer";
const tenantId = args.get("tenant") || "tenant-buyer";
const ttl = Number(args.get("ttl") || "3600");
const exp = Date.now() + ttl * 1000;
const authorization = `Bearer ${signEmuToken(config.emulatorSecret, { uid, typ: "user", exp })}`;
const appCheck = signEmuToken(config.emulatorSecret, { typ: "appcheck", exp });
process.stdout.write(`${JSON.stringify({ uid, tenantId, authorization, appCheck })}\n`);
