import type { AllowanceCharge, CanonicalDocument, Direction, InvoiceLine, Party, TaxSubtotal } from "../../contracts/src/index.js";
import { attr, CAC, CBC, CN, escapeXml, INV, kids, localName, one, parseSecure, textOf } from "./secure.js";
import type { Document, Element } from "@xmldom/xmldom";

export type InspectResult =
  | { syntax: "CII" | "UNKNOWN"; code: "UNSUPPORTED_SYNTAX" }
  | { syntax: "UBL"; document: CanonicalDocument };

export function inspectUbl(xml: string, direction: Direction): InspectResult {
  const doc = parseSecure(xml);
  const root = doc.documentElement;
  if (!root) return { syntax: "UNKNOWN", code: "UNSUPPORTED_SYNTAX" };
  const name = localName(root);
  const ns = root.namespaceURI || "";
  if (name === "CrossIndustryInvoice" || ns.includes("CrossIndustryInvoice") || ns.includes("uncefact")) {
    return { syntax: "CII", code: "UNSUPPORTED_SYNTAX" };
  }
  if (name === "Invoice" && ns === INV) return { syntax: "UBL", document: mapInvoice(root, direction, "INVOICE") };
  if (name === "CreditNote" && ns === CN) return { syntax: "UBL", document: mapInvoice(root, direction, "CREDIT_NOTE") };
  return { syntax: "UNKNOWN", code: "UNSUPPORTED_SYNTAX" };
}

function mapInvoice(root: Element, direction: Direction, kind: "INVOICE" | "CREDIT_NOTE"): CanonicalDocument {
  const typeTag = kind === "INVOICE" ? "InvoiceTypeCode" : "CreditNoteTypeCode";
  const lineTag = kind === "INVOICE" ? "InvoiceLine" : "CreditNoteLine";
  const qtyTag = kind === "INVOICE" ? "InvoicedQuantity" : "CreditedQuantity";
  const supplier = mapParty(one(one(root, CAC, "AccountingSupplierParty") ?? root, CAC, "Party"));
  const customer = mapParty(one(one(root, CAC, "AccountingCustomerParty") ?? root, CAC, "Party"));
  const lines = kids(root, CAC, lineTag).map((line) => mapLine(line, qtyTag));
  const allowances = kids(root, CAC, "AllowanceCharge").map(mapAllowance);
  const taxTotal = one(root, CAC, "TaxTotal");
  const taxSubtotals = kids(taxTotal ?? root, CAC, "TaxSubtotal").map(mapTax);
  const totals = one(root, CAC, "LegalMonetaryTotal");
  const currency = textOf(root, CBC, "DocumentCurrencyCode") ?? "";
  const billing = one(one(root, CAC, "BillingReference") ?? root, CAC, "InvoiceDocumentReference");
  return {
    kind,
    direction,
    number: textOf(root, CBC, "ID") ?? "",
    customizationId: textOf(root, CBC, "CustomizationID") ?? "",
    profileId: textOf(root, CBC, "ProfileID") ?? "",
    typeCode: textOf(root, CBC, typeTag) ?? "",
    issueDate: textOf(root, CBC, "IssueDate") ?? "",
    dueDate: textOf(root, CBC, "DueDate"),
    deliveryDate: textOf(one(root, CAC, "Delivery"), CBC, "ActualDeliveryDate"),
    currency,
    buyerReference: textOf(root, CBC, "BuyerReference"),
    accountingCost: textOf(root, CBC, "AccountingCost"),
    paymentTermsNote: textOf(one(root, CAC, "PaymentTerms"), CBC, "Note"),
    paymentMeansCode: textOf(one(root, CAC, "PaymentMeans"), CBC, "PaymentMeansCode"),
    paymentId: textOf(one(root, CAC, "PaymentMeans"), CBC, "PaymentID"),
    payeeAccountId: textOf(one(one(root, CAC, "PaymentMeans"), CAC, "PayeeFinancialAccount"), CBC, "ID"),
    payeeAccountName: textOf(one(one(root, CAC, "PaymentMeans"), CAC, "PayeeFinancialAccount"), CBC, "Name"),
    payeeBic: textOf(one(one(one(root, CAC, "PaymentMeans"), CAC, "PayeeFinancialAccount"), CAC, "FinancialInstitutionBranch"), CBC, "ID"),
    supplier,
    customer,
    lines,
    allowances,
    taxSubtotals,
    taxAmount: moneyText(taxTotal, "TaxAmount") ?? "0.00",
    lineExtensionAmount: moneyText(totals, "LineExtensionAmount") ?? "0.00",
    taxExclusiveAmount: moneyText(totals, "TaxExclusiveAmount") ?? "0.00",
    taxInclusiveAmount: moneyText(totals, "TaxInclusiveAmount") ?? "0.00",
    allowanceTotalAmount: moneyText(totals, "AllowanceTotalAmount"),
    chargeTotalAmount: moneyText(totals, "ChargeTotalAmount"),
    prepaidAmount: moneyText(totals, "PrepaidAmount"),
    payableRoundingAmount: moneyText(totals, "PayableRoundingAmount"),
    payableAmount: moneyText(totals, "PayableAmount") ?? "0.00",
    precedingInvoiceNumber: textOf(billing, CBC, "ID"),
  };
}

function mapParty(party: Element | undefined): Party {
  if (!party) {
    return { legalName: "", address: { line1: "", city: "", postalCode: "", countryCode: "" } };
  }
  const address = one(party, CAC, "PostalAddress");
  const legal = one(party, CAC, "PartyLegalEntity");
  const tax = kids(party, CAC, "PartyTaxScheme").find((el) => textOf(one(el, CAC, "TaxScheme"), CBC, "ID") === "VAT");
  const endpoint = one(party, CBC, "EndpointID");
  const ids = kids(party, CAC, "PartyIdentification").map((el) => ({
    schemeId: attr(one(el, CBC, "ID"), "schemeID"),
    value: textOf(el, CBC, "ID") ?? "",
  })).filter((id) => id.value);
  const contact = one(party, CAC, "Contact");
  return {
    legalName: textOf(legal, CBC, "RegistrationName") || textOf(one(party, CAC, "PartyName"), CBC, "Name") || "",
    tradingName: textOf(one(party, CAC, "PartyName"), CBC, "Name"),
    address: {
      line1: textOf(address, CBC, "StreetName") ?? "",
      line2: textOf(address, CBC, "AdditionalStreetName"),
      city: textOf(address, CBC, "CityName") ?? "",
      postalCode: textOf(address, CBC, "PostalZone") ?? "",
      countryCode: textOf(one(address, CAC, "Country"), CBC, "IdentificationCode") ?? "",
    },
    companyId: textOf(legal, CBC, "CompanyID"),
    companyIdScheme: attr(one(legal, CBC, "CompanyID"), "schemeID"),
    vatId: textOf(tax, CBC, "CompanyID"),
    endpoint: endpoint ? { schemeId: attr(endpoint, "schemeID") ?? "", value: endpoint.textContent?.trim() ?? "" } : undefined,
    partyIds: ids,
    contactName: textOf(contact, CBC, "Name"),
    contactPhone: textOf(contact, CBC, "Telephone"),
    contactEmail: textOf(contact, CBC, "ElectronicMail"),
  };
}

function mapLine(line: Element, qtyTag: string): InvoiceLine {
  const qty = one(line, CBC, qtyTag);
  const tax = one(one(line, CAC, "Item"), CAC, "ClassifiedTaxCategory");
  return {
    id: textOf(line, CBC, "ID") ?? "",
    name: textOf(one(line, CAC, "Item"), CBC, "Name") ?? "",
    description: textOf(one(line, CAC, "Item"), CBC, "Description"),
    quantity: qty?.textContent?.trim() || "0",
    unitCode: attr(qty, "unitCode") ?? "",
    unitPrice: moneyText(one(line, CAC, "Price"), "PriceAmount") ?? "0.00",
    lineAmount: moneyText(line, "LineExtensionAmount") ?? "0.00",
    vatCategory: textOf(tax, CBC, "ID") ?? "",
    vatRate: textOf(tax, CBC, "Percent") ?? "0",
  };
}

function mapAllowance(el: Element): AllowanceCharge {
  const tax = one(el, CAC, "TaxCategory");
  return {
    charge: textOf(el, CBC, "ChargeIndicator") === "true",
    reason: textOf(el, CBC, "AllowanceChargeReason"),
    amount: moneyText(el, "Amount") ?? "0.00",
    vatCategory: textOf(tax, CBC, "ID") ?? "",
    vatRate: textOf(tax, CBC, "Percent") ?? "0",
  };
}

function mapTax(el: Element): TaxSubtotal {
  const cat = one(el, CAC, "TaxCategory");
  return {
    taxableAmount: moneyText(el, "TaxableAmount") ?? "0.00",
    taxAmount: moneyText(el, "TaxAmount") ?? "0.00",
    category: textOf(cat, CBC, "ID") ?? "",
    rate: textOf(cat, CBC, "Percent") ?? "0",
    currency: attr(one(el, CBC, "TaxableAmount"), "currencyID") ?? "",
  };
}

function moneyText(el: Element | undefined, name: string): string | undefined {
  return textOf(el, CBC, name);
}

export function serializeUbl(doc: CanonicalDocument): string {
  const root = doc.kind === "INVOICE" ? "Invoice" : "CreditNote";
  const ns = doc.kind === "INVOICE" ? INV : CN;
  const typeTag = doc.kind === "INVOICE" ? "InvoiceTypeCode" : "CreditNoteTypeCode";
  const lineTag = doc.kind === "INVOICE" ? "InvoiceLine" : "CreditNoteLine";
  const qtyTag = doc.kind === "INVOICE" ? "InvoicedQuantity" : "CreditedQuantity";
  const parts: string[] = [];
  parts.push(`<?xml version="1.0" encoding="UTF-8"?>`);
  parts.push(`<${root} xmlns="${ns}" xmlns:cac="${CAC}" xmlns:cbc="${CBC}">`);
  tag(parts, "cbc:CustomizationID", doc.customizationId);
  tag(parts, "cbc:ProfileID", doc.profileId);
  tag(parts, "cbc:ID", doc.number);
  tag(parts, "cbc:IssueDate", doc.issueDate);
  if (doc.dueDate && doc.kind === "INVOICE") tag(parts, "cbc:DueDate", doc.dueDate);
  tag(parts, `cbc:${typeTag}`, doc.typeCode);
  tag(parts, "cbc:DocumentCurrencyCode", doc.currency);
  if (doc.accountingCost) tag(parts, "cbc:AccountingCost", doc.accountingCost);
  if (doc.buyerReference) tag(parts, "cbc:BuyerReference", doc.buyerReference);
  if (doc.precedingInvoiceNumber) {
    parts.push(`<cac:BillingReference><cac:InvoiceDocumentReference><cbc:ID>${escapeXml(doc.precedingInvoiceNumber)}</cbc:ID></cac:InvoiceDocumentReference></cac:BillingReference>`);
  }
  parts.push(partyXml("AccountingSupplierParty", doc.supplier));
  parts.push(partyXml("AccountingCustomerParty", doc.customer));
  if (doc.deliveryDate) {
    parts.push(`<cac:Delivery><cbc:ActualDeliveryDate>${escapeXml(doc.deliveryDate)}</cbc:ActualDeliveryDate></cac:Delivery>`);
  }
  if (doc.paymentMeansCode) {
    parts.push(`<cac:PaymentMeans><cbc:PaymentMeansCode>${escapeXml(doc.paymentMeansCode)}</cbc:PaymentMeansCode>`);
    if (doc.paymentId) tag(parts, "cbc:PaymentID", doc.paymentId);
    if (doc.payeeAccountId) {
      parts.push(`<cac:PayeeFinancialAccount><cbc:ID>${escapeXml(doc.payeeAccountId)}</cbc:ID>`);
      if (doc.payeeAccountName) tag(parts, "cbc:Name", doc.payeeAccountName);
      if (doc.payeeBic) parts.push(`<cac:FinancialInstitutionBranch><cbc:ID>${escapeXml(doc.payeeBic)}</cbc:ID></cac:FinancialInstitutionBranch>`);
      parts.push(`</cac:PayeeFinancialAccount>`);
    }
    parts.push(`</cac:PaymentMeans>`);
  }
  if (doc.paymentTermsNote) parts.push(`<cac:PaymentTerms><cbc:Note>${escapeXml(doc.paymentTermsNote)}</cbc:Note></cac:PaymentTerms>`);
  for (const item of doc.allowances) parts.push(allowanceXml(item, doc.currency));
  parts.push(`<cac:TaxTotal><cbc:TaxAmount currencyID="${escapeXml(doc.currency)}">${escapeXml(doc.taxAmount)}</cbc:TaxAmount>`);
  for (const tax of doc.taxSubtotals) parts.push(taxXml(tax));
  parts.push(`</cac:TaxTotal>`);
  parts.push(`<cac:LegalMonetaryTotal>`);
  money(parts, "LineExtensionAmount", doc.lineExtensionAmount, doc.currency);
  money(parts, "TaxExclusiveAmount", doc.taxExclusiveAmount, doc.currency);
  money(parts, "TaxInclusiveAmount", doc.taxInclusiveAmount, doc.currency);
  if (doc.allowanceTotalAmount) money(parts, "AllowanceTotalAmount", doc.allowanceTotalAmount, doc.currency);
  if (doc.chargeTotalAmount) money(parts, "ChargeTotalAmount", doc.chargeTotalAmount, doc.currency);
  if (doc.prepaidAmount) money(parts, "PrepaidAmount", doc.prepaidAmount, doc.currency);
  if (doc.payableRoundingAmount) money(parts, "PayableRoundingAmount", doc.payableRoundingAmount, doc.currency);
  money(parts, "PayableAmount", doc.payableAmount, doc.currency);
  parts.push(`</cac:LegalMonetaryTotal>`);
  for (const line of doc.lines) parts.push(lineXml(line, lineTag, qtyTag, doc.currency));
  parts.push(`</${root}>`);
  return parts.join("");
}

function partyXml(wrapper: string, party: Party): string {
  const endpoint = party.endpoint ? `<cbc:EndpointID schemeID="${escapeXml(party.endpoint.schemeId)}">${escapeXml(party.endpoint.value)}</cbc:EndpointID>` : "";
  const ids = (party.partyIds ?? []).map((id) => `<cac:PartyIdentification><cbc:ID${id.schemeId ? ` schemeID="${escapeXml(id.schemeId)}"` : ""}>${escapeXml(id.value)}</cbc:ID></cac:PartyIdentification>`).join("");
  const trading = party.tradingName ? `<cac:PartyName><cbc:Name>${escapeXml(party.tradingName)}</cbc:Name></cac:PartyName>` : "";
  const tax = party.vatId ? `<cac:PartyTaxScheme><cbc:CompanyID>${escapeXml(party.vatId)}</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme>` : "";
  const company = party.companyId ? `<cbc:CompanyID${party.companyIdScheme ? ` schemeID="${escapeXml(party.companyIdScheme)}"` : ""}>${escapeXml(party.companyId)}</cbc:CompanyID>` : "";
  const contact = party.contactName || party.contactPhone || party.contactEmail
    ? `<cac:Contact>${party.contactName ? `<cbc:Name>${escapeXml(party.contactName)}</cbc:Name>` : ""}${party.contactPhone ? `<cbc:Telephone>${escapeXml(party.contactPhone)}</cbc:Telephone>` : ""}${party.contactEmail ? `<cbc:ElectronicMail>${escapeXml(party.contactEmail)}</cbc:ElectronicMail>` : ""}</cac:Contact>`
    : "";
  return `<cac:${wrapper}><cac:Party>${endpoint}${ids}${trading}<cac:PostalAddress><cbc:StreetName>${escapeXml(party.address.line1)}</cbc:StreetName>${party.address.line2 ? `<cbc:AdditionalStreetName>${escapeXml(party.address.line2)}</cbc:AdditionalStreetName>` : ""}<cbc:CityName>${escapeXml(party.address.city)}</cbc:CityName><cbc:PostalZone>${escapeXml(party.address.postalCode)}</cbc:PostalZone><cac:Country><cbc:IdentificationCode>${escapeXml(party.address.countryCode)}</cbc:IdentificationCode></cac:Country></cac:PostalAddress>${tax}<cac:PartyLegalEntity><cbc:RegistrationName>${escapeXml(party.legalName)}</cbc:RegistrationName>${company}</cac:PartyLegalEntity>${contact}</cac:Party></cac:${wrapper}>`;
}

function allowanceXml(item: AllowanceCharge, currency: string): string {
  return `<cac:AllowanceCharge><cbc:ChargeIndicator>${item.charge ? "true" : "false"}</cbc:ChargeIndicator>${item.reason ? `<cbc:AllowanceChargeReason>${escapeXml(item.reason)}</cbc:AllowanceChargeReason>` : ""}<cbc:Amount currencyID="${escapeXml(currency)}">${escapeXml(item.amount)}</cbc:Amount><cac:TaxCategory><cbc:ID>${escapeXml(item.vatCategory)}</cbc:ID><cbc:Percent>${escapeXml(item.vatRate)}</cbc:Percent><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:TaxCategory></cac:AllowanceCharge>`;
}

function taxXml(tax: TaxSubtotal): string {
  const currency = tax.currency || "EUR";
  return `<cac:TaxSubtotal><cbc:TaxableAmount currencyID="${escapeXml(currency)}">${escapeXml(tax.taxableAmount)}</cbc:TaxableAmount><cbc:TaxAmount currencyID="${escapeXml(currency)}">${escapeXml(tax.taxAmount)}</cbc:TaxAmount><cac:TaxCategory><cbc:ID>${escapeXml(tax.category)}</cbc:ID><cbc:Percent>${escapeXml(tax.rate)}</cbc:Percent><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:TaxCategory></cac:TaxSubtotal>`;
}

function lineXml(line: InvoiceLine, lineTag: string, qtyTag: string, currency: string): string {
  return `<cac:${lineTag}><cbc:ID>${escapeXml(line.id)}</cbc:ID><cbc:${qtyTag} unitCode="${escapeXml(line.unitCode)}">${escapeXml(line.quantity)}</cbc:${qtyTag}><cbc:LineExtensionAmount currencyID="${escapeXml(currency)}">${escapeXml(line.lineAmount)}</cbc:LineExtensionAmount><cac:Item>${line.description ? `<cbc:Description>${escapeXml(line.description)}</cbc:Description>` : ""}<cbc:Name>${escapeXml(line.name)}</cbc:Name><cac:ClassifiedTaxCategory><cbc:ID>${escapeXml(line.vatCategory)}</cbc:ID><cbc:Percent>${escapeXml(line.vatRate)}</cbc:Percent><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:ClassifiedTaxCategory></cac:Item><cac:Price><cbc:PriceAmount currencyID="${escapeXml(currency)}">${escapeXml(line.unitPrice)}</cbc:PriceAmount></cac:Price></cac:${lineTag}>`;
}

function tag(parts: string[], name: string, value: string): void {
  parts.push(`<${name}>${escapeXml(value)}</${name}>`);
}

function money(parts: string[], name: string, amount: string, currency: string): void {
  parts.push(`<cbc:${name} currencyID="${escapeXml(currency)}">${escapeXml(amount)}</cbc:${name}>`);
}

export function rootName(doc: Document): string {
  const root = doc.documentElement;
  if (!root) return "";
  return localName(root);
}
