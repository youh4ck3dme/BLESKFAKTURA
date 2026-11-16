import { parseSecure, XmlRejected } from "../packages/xml/src/secure.js";

const mode = process.argv[2];
let xml = "<a/>";
if (mode === "deep") {
  xml = `<r>${"<a>".repeat(8000)}${"</a>".repeat(8000)}</r>`;
} else if (mode === "wide") {
  xml = `<r>${"<a/>".repeat(9000)}</r>`;
} else if (mode === "xxe") {
  xml = `<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY x "y">]><foo>&x;</foo>`;
}

try {
  parseSecure(xml);
  process.stdout.write("parsed\n");
  process.exit(2);
} catch (error) {
  if (error instanceof XmlRejected && (error.ruleId === "XML_LIMIT" || error.ruleId === "XML_DTD" || error.ruleId === "XML_XINCLUDE")) {
    process.stdout.write(`rejected ${error.ruleId}\n`);
    process.exit(0);
  }
  process.stdout.write(`${error instanceof Error ? error.name : "unknown"}\n`);
  process.exit(3);
}
