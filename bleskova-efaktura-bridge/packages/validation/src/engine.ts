import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { LayerResult, ValidationIssue, ValidationReport, ValidationStatus } from "../../contracts/src/index.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../../../..");
export const RULES_ROOT = path.join(repoRoot, "packages/validation/rules");
export const VALIDATOR_CP = [
  path.join(repoRoot, "tools/validator/build"),
  path.join(repoRoot, "tools/validator/lib/Saxon-HE-12.9.jar"),
  path.join(repoRoot, "tools/validator/lib/xmlresolver-5.3.3.jar"),
  path.join(repoRoot, "tools/validator/lib/xmlresolver-5.3.3-data.jar"),
].join(path.delimiter);

export const RULESET = {
  verifiedOn: "2026-11-16",
  ubl: "OASIS UBL 2.1 OS xsdrt (2013-11-04), local file set",
  en16931: "CEN-EN16931-UBL.sch Schematron version 1.3.16, Peppol BIS Billing 3.0 May 2026 publication, sha256 268d4f7a2688676695e6c69cba6fba69a6802604fee12cb544a6b30ff09555a3",
  peppol: "PEPPOL-EN16931-UBL.sch May 2026 release 3.0.21, sha256 62e5b67892f12755352d78b06f63229a02cc2eccc748677c56efbc8dbcb336e3",
  sk: "FS SR XLSX Peppol BIS3 v1.13 dated 2026-10-29 is pinned but NOT executed. No official SK Schematron was compiled. sha256 002a65e6da9debd9af5131e213d6ad1bbc2adc47641b62a9311924ecc7b4cfc9",
  engine: "Saxon-HE 12.9 + SchXslt 1.10.1 + JDK Xerces via javax.xml.validation",
} as const;

export interface EngineLayer {
  status: ValidationStatus;
  issues: string[];
}

export interface EngineReport {
  sha256: string;
  xmlSecurity: EngineLayer;
  xsd: EngineLayer;
  en16931: EngineLayer;
  peppol: EngineLayer;
  engineError?: string;
}

export async function runStandardsEngine(xml: Uint8Array, timeoutMs = 20000): Promise<EngineReport> {
  const dir = await mkdtemp(path.join(tmpdir(), "efaktura-"));
  const file = path.join(dir, "doc.xml");
  await writeFile(file, xml);
  try {
    const stdout = await new Promise<string>((resolve, reject) => {
      const child = spawn("java", ["-cp", VALIDATOR_CP, "sk.bizagent.efaktura.validator.ValidatorMain", file, RULES_ROOT], {
        stdio: ["ignore", "pipe", "pipe"],
      });
      const out: Buffer[] = [];
      const err: Buffer[] = [];
      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        reject(new Error("VALIDATOR_TIMEOUT"));
      }, timeoutMs);
      child.stdout.on("data", (chunk) => out.push(Buffer.from(chunk)));
      child.stderr.on("data", (chunk) => err.push(Buffer.from(chunk)));
      child.on("error", (error) => {
        clearTimeout(timer);
        reject(error);
      });
      child.on("close", (code) => {
        clearTimeout(timer);
        const text = Buffer.concat(out).toString("utf8");
        if (!text.trim()) reject(new Error(Buffer.concat(err).toString("utf8") || `validator exit ${code}`));
        else resolve(text);
      });
    });
    return JSON.parse(stdout) as EngineReport;
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

export function composeReport(input: {
  sha256: string;
  domainIssues: ValidationIssue[];
  engine?: EngineReport;
  engineFailed?: boolean;
}): ValidationReport {
  const issues: ValidationIssue[] = [...input.domainIssues];
  const layers: LayerResult[] = [];
  const pushEngine = (layer: ValidationIssue["layer"], version: string, result?: EngineLayer) => {
    const status: ValidationStatus = !result || input.engineFailed ? "UNAVAILABLE" : result.status;
    layers.push({ layer, status, rulesetVersion: version });
    for (const message of result?.issues ?? []) {
      if (status === "VALID") continue;
      issues.push({ layer, severity: "ERROR", ruleId: message.slice(0, 40).split(" ")[0] || layer, message: message.slice(0, 500) });
    }
    if (status === "UNAVAILABLE") {
      issues.push({ layer, severity: "WARNING", ruleId: "ENGINE_UNAVAILABLE", message: "Vrstva validácie nebežala. Výsledok nie je platný doklad." });
    }
  };
  const security = input.engine?.xmlSecurity;
  pushEngine("XML_SECURITY", "local-xml-security-1", security);
  pushEngine("XSD", "UBL-2.1-xsdrt", input.engine?.xsd);
  pushEngine("EN16931", RULESET.en16931, input.engine?.en16931);
  pushEngine("PEPPOL", RULESET.peppol, input.engine?.peppol);
  layers.push({ layer: "SK", status: "UNSUPPORTED", rulesetVersion: RULESET.sk });
  issues.push({
    layer: "SK",
    severity: "WARNING",
    ruleId: "SK_RULESET_NOT_EXECUTED",
    message: "Slovenský ruleset je len pripnutý ako XLSX FS SR v1.13 a nebol spustený ako Schematron. Doklad nie je plne validný pre produkčné odoslanie.",
  });
  layers.push({
    layer: "DOMAIN",
    status: input.domainIssues.some((issue) => issue.severity === "ERROR") ? "INVALID" : "VALID",
    rulesetVersion: "domain-0.1.0",
  });
  const technical = layers.filter((layer) => layer.layer !== "SK");
  const technicalPass = technical.every((layer) => layer.status === "VALID");
  const anyInvalid = layers.some((layer) => layer.status === "INVALID") || issues.some((issue) => issue.severity === "ERROR" && issue.layer !== "SK");
  const anyUnavailable = technical.some((layer) => layer.status === "UNAVAILABLE");
  let status: ValidationStatus = "VALID";
  if (anyInvalid) status = "INVALID";
  else if (anyUnavailable) status = "UNAVAILABLE";
  else if (!technicalPass || layers.some((layer) => layer.status === "UNSUPPORTED")) status = "UNSUPPORTED";
  return {
    status,
    rulesetVersion: `${RULESET.verifiedOn}|ubl-2.1|en16931-1.3.16|peppol-3.0.21|sk-xlsx-1.13-not-executed`,
    validatedSha256: input.sha256,
    issues,
    layers,
    productionEligible: false,
    technicalPass,
  };
}
