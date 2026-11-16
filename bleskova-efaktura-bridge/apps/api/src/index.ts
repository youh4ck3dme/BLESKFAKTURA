import { composeReport, runStandardsEngine } from "../../../packages/validation/src/engine.js";
import { inspectUbl, serializeUbl } from "../../../packages/xml/src/ubl.js";
import { assertXmlLimits } from "../../../packages/xml/src/secure.js";
import { LocalSandboxProvider, UnconfiguredProvider } from "../../../packages/provider/src/index.js";
import { createDocumentService } from "../../../packages/core/src/service.js";
import { startServer } from "./server.js";

const env = process.env.EFAKTURA_ENV === "production" ? "production" : "local";
const authMode = process.env.EFAKTURA_AUTH_MODE === "emulator" ? "emulator" : "disabled";
const providerName = process.env.EFAKTURA_PROVIDER ?? (env === "production" ? "unconfigured" : "local-sandbox");
if (env === "production" && (authMode === "emulator" || providerName === "local-sandbox")) {
  console.error("Produkcia odmietla emulátor alebo sandbox provider.");
  process.exit(1);
}
const provider = providerName === "local-sandbox"
  ? new LocalSandboxProvider(process.env.EFAKTURA_SANDBOX_WEBHOOK_SECRET || "local-sandbox-webhook-not-production")
  : new UnconfiguredProvider();
const service = createDocumentService({
  env,
  provider,
  parser: {
    inspect(xml, direction) {
      assertXmlLimits(Buffer.from(xml));
      return inspectUbl(xml, direction);
    },
    serialize: serializeUbl,
  },
  validator: {
    async validate(xml, domainIssues) {
      const engine = await runStandardsEngine(xml, Number(process.env.EFAKTURA_VALIDATOR_TIMEOUT_MS ?? "20000"));
      if (engine.engineError) throw new Error(engine.engineError);
      return composeReport({ sha256: engine.sha256, domainIssues, engine });
    },
  },
});
const port = Number(process.env.EFAKTURA_PORT ?? "8088");
const started = await startServer({
  service,
  env,
  authMode,
  emulatorSecret: process.env.EFAKTURA_EMULATOR_SECRET || "local-only-not-a-production-secret",
  rateLimitPerMinute: 120,
  port,
  host: "0.0.0.0",
});
console.log(JSON.stringify({ listening: started.url }));
