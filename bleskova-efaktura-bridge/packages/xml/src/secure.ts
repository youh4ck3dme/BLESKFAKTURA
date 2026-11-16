import { DOMParser, type Document, type Element } from "@xmldom/xmldom";

export const MAX_XML_BYTES = 1_500_000;
export const MAX_DEPTH = 32;
export const MAX_NODES = 8_000;

const CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
const CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
const INV = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
const CN = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";

export { CBC, CAC, INV, CN };

export class XmlRejected extends Error {
  readonly ruleId: string;
  constructor(ruleId: string, message: string) {
    super(message);
    this.ruleId = ruleId;
  }
}

export function assertXmlLimits(bytes: Uint8Array): string {
  if (bytes.byteLength > MAX_XML_BYTES) {
    throw new XmlRejected("XML_LIMIT", "Súbor prekračuje limit veľkosti.");
  }
  const text = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  const lower = text.toLowerCase();
  if (lower.includes("<!doctype") || lower.includes("<!entity") || lower.includes("<!element")) {
    throw new XmlRejected("XML_DTD", "DTD a entity deklarácie sú zakázané.");
  }
  if (lower.includes("<xi:include") || lower.includes("xinclude")) {
    throw new XmlRejected("XML_XINCLUDE", "XInclude je zakázaný.");
  }
  return text;
}

export function parseSecure(xml: string): Document {
  const messages: string[] = [];
  const doc = new DOMParser({
    onError(level, msg) {
      if (level === "error" || level === "fatalError") messages.push(String(msg));
    },
  }).parseFromString(xml, "text/xml");
  if (messages.length) {
    throw new XmlRejected("XML_PARSE", "XML sa nepodarilo bezpečne prečítať.");
  }
  const root = doc.documentElement;
  if (!root) throw new XmlRejected("XML_PARSE", "XML nemá koreňový prvok.");
  const nodes = { n: 0 };
  if (tooDeep(root, 1, nodes)) throw new XmlRejected("XML_LIMIT", "XML prekračuje limit hĺbky alebo počtu uzlov.");
  return doc;
}

function tooDeep(node: Element, depth: number, nodes: { n: number }): boolean {
  nodes.n += 1;
  if (depth > MAX_DEPTH || nodes.n > MAX_NODES) return true;
  for (let i = 0; i < node.childNodes.length; i++) {
    const child = node.childNodes[i];
    if (child && child.nodeType === 1 && tooDeep(child as Element, depth + 1, nodes)) return true;
  }
  return false;
}

export function localName(el: Element): string {
  return el.localName || el.nodeName.replace(/^.*:/, "");
}

export function kids(el: Element, ns: string, name: string): Element[] {
  const out: Element[] = [];
  for (let i = 0; i < el.childNodes.length; i++) {
    const child = el.childNodes[i];
    if (!child || child.nodeType !== 1) continue;
    const element = child as Element;
    if (element.namespaceURI === ns && localName(element) === name) out.push(element);
  }
  return out;
}

export function one(el: Element | undefined, ns: string, name: string): Element | undefined {
  if (!el) return undefined;
  return kids(el, ns, name)[0];
}

export function textOf(el: Element | undefined, ns: string, name: string): string | undefined {
  if (!el) return undefined;
  const child = one(el, ns, name);
  const value = child?.textContent?.trim();
  return value ? value : undefined;
}

export function attr(el: Element | undefined, name: string): string | undefined {
  const value = el?.getAttribute(name)?.trim();
  return value ? value : undefined;
}

export function escapeXml(value: string): string {
  const amp = "&" + "amp;";
  const lt = "&" + "lt;";
  const gt = "&" + "gt;";
  const quot = "&" + "quot;";
  const apos = "&" + "apos;";
  return value.replaceAll("&", amp).replaceAll("<", lt).replaceAll(">", gt).replaceAll('"', quot).replaceAll("'", apos);
}
