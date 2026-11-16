import { copyFile, mkdir, readFile, readdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const destination = new URL("../hosting/legal/", import.meta.url);
const files = ["privacy-policy.html", "account-deletion.html"];
// Legal identity, actual processing mode and retention must be supplied before
// these public pages can be staged. A template is not a published policy.
for (const file of files) {
  const source = await readFile(new URL(`../docs/${file}`, import.meta.url), "utf8");
  const unresolved = [...new Set(source.match(/\{\{[A-Z0-9_]+\}\}/g) ?? [])];
  if (unresolved.length > 0 || source.includes("NÁVRH - pred zverejnením")) {
    throw new Error(`${file}: complete the legal template before staging (${unresolved.join(", ")}).`);
  }
}
await mkdir(destination, { recursive: true });
for (const file of files) {
  await copyFile(new URL(`../docs/${file}`, import.meta.url), new URL(file, destination));
}
const staged = await readdir(destination);
if (staged.some(file => !files.includes(file))) {
  throw new Error("Hosting directory contains an unexpected file. Review before deployment.");
}
console.log(`Prepared ${files.length} legal HTML files in ${fileURLToPath(destination)}. Publication requires resolved legal data in RELEASE_EXECUTION_STATUS.md.`);
