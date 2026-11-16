import type { CanonicalDocument, ValidationIssue } from "../../contracts/src/index.js";
import { SUPPORTED_CUSTOMIZATION_ID, SUPPORTED_PROFILE_ID } from "../../contracts/src/index.js";
import { AppError } from "./errors.js";
import { add, isZeroScaleCurrency, lineNet, parseDecimal, sub, taxAmount, withinSlack } from "./money.js";

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const CURRENCY = /^[A-Z]{3}$/;
const ENDPOINT_SCHEME = /^\d{4}$/;

export function checkDocument(doc: CanonicalDocument): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  const err = (ruleId: string, message: string, path?: string) => {
    issues.push({ layer: "DOMAIN", severity: "ERROR", ruleId, message, path });
  };

  if (doc.customizationId !== SUPPORTED_CUSTOMIZATION_ID) {
    err("UNSUPPORTED_PROFILE", "CustomizationID nie je podporovaný Peppol BIS Billing 3.0 profil.", "customizationId");
  }
  if (doc.profileId !== SUPPORTED_PROFILE_ID) {
    err("UNSUPPORTED_PROFILE", "ProfileID nie je podporovaný proces billing:01:1.0. Iné profily vrátane billing:02 sa v MVP neakceptujú ako podporované.", "profileId");
  }
  if (doc.kind === "INVOICE" && doc.typeCode !== "380") {
    err("UNSUPPORTED_SCENARIO", `InvoiceTypeCode ${doc.typeCode} nie je v MVP podporovaný.`, "typeCode");
  }
  if (doc.kind === "CREDIT_NOTE" && doc.typeCode !== "381") {
    err("UNSUPPORTED_SCENARIO", `CreditNoteTypeCode ${doc.typeCode} nie je v MVP podporovaný.`, "typeCode");
  }
  if (!doc.number.trim()) err("BR-NUMBER", "Chýba číslo dokladu.", "number");
  if (!ISO_DATE.test(doc.issueDate)) err("BR-DATE", "Dátum vystavenia musí byť YYYY-MM-DD.", "issueDate");
  if (doc.dueDate && !ISO_DATE.test(doc.dueDate)) err("BR-DATE", "Dátum splatnosti musí byť YYYY-MM-DD.", "dueDate");
  if (!doc.dueDate && !doc.paymentTermsNote) {
    err("BR-DUE", "Chýba splatnosť aj platobné podmienky. Modul ich nedopĺňa.", "dueDate");
  }
  if (doc.kind === "CREDIT_NOTE" && !doc.precedingInvoiceNumber) {
    err("BR-CN-REF", "Dobropis v tomto MVP vyžaduje referenciu na pôvodnú faktúru.", "precedingInvoiceNumber");
  }
  if (!CURRENCY.test(doc.currency) || isZeroScaleCurrency(doc.currency)) {
    err("UNSUPPORTED_SCENARIO", `Mena ${doc.currency} nie je v MVP podporovaná (očakáva sa ISO kód s 2 desatinnými miestami).`, "currency");
  }
  if (!doc.lines.length) err("BR-LINES", "Doklad nemá položky.", "lines");
  if (doc.lines.length > 1000) err("BR-LIMIT", "Príliš veľa položiek.", "lines");

  for (const party of [doc.supplier, doc.customer]) {
    if (party.endpoint && !ENDPOINT_SCHEME.test(party.endpoint.schemeId)) {
      err("BR-ENDPOINT", "Schéma endpointu musí byť štvorciferný EAS kód.", "endpoint.schemeId");
    }
  }

  let lineSum = "0.00";
  try {
    for (const line of doc.lines) {
      const expected = lineNet(line.quantity, line.unitPrice);
      if (!withinSlack(line.lineAmount, expected)) {
        err("BR-LINE-AMOUNT", `Riadok ${line.id}: súčet množstvo×cena ${expected} nesedí s lineAmount ${line.lineAmount}.`, `lines/${line.id}`);
      }
      lineSum = add(lineSum, line.lineAmount);
    }
    if (!withinSlack(doc.lineExtensionAmount, lineSum)) {
      err("BR-SUM-LINES", `Súčet riadkov ${lineSum} nesedí s lineExtensionAmount ${doc.lineExtensionAmount}.`, "lineExtensionAmount");
    }
    let allowance = "0.00";
    let charge = "0.00";
    for (const item of doc.allowances) {
      if (item.charge) charge = add(charge, item.amount);
      else allowance = add(allowance, item.amount);
    }
    const exclusive = sub(add(lineSum, charge), allowance);
    if (!withinSlack(doc.taxExclusiveAmount, exclusive)) {
      err("BR-EXCLUSIVE", `Základ dane ${doc.taxExclusiveAmount} nesedí s riadkami a zľavami/príplatkami ${exclusive}.`, "taxExclusiveAmount");
    }
    const byKey = new Map<string, { taxable: string; rate: string }>();
    const bump = (category: string, rate: string, amount: string, sign: 1 | -1) => {
      const key = `${category}|${rate}`;
      const cur = byKey.get(key) ?? { taxable: "0.00", rate };
      const next = sign === 1 ? add(cur.taxable, amount) : sub(cur.taxable, amount);
      byKey.set(key, { taxable: next, rate });
    };
    for (const line of doc.lines) bump(line.vatCategory, line.vatRate, line.lineAmount, 1);
    for (const item of doc.allowances) bump(item.vatCategory, item.vatRate, item.amount, item.charge ? 1 : -1);
    let taxSum = "0.00";
    for (const stated of doc.taxSubtotals) {
      const key = `${stated.category}|${stated.rate}`;
      const calc = byKey.get(key);
      if (!calc) {
        err("BR-TAX-CAT", `Daňová kategória ${stated.category}/${stated.rate} nemá položky.`, "taxSubtotals");
        continue;
      }
      if (!withinSlack(stated.taxableAmount, calc.taxable)) {
        err("BR-TAX-BASE", `Základ ${stated.category}/${stated.rate} ${stated.taxableAmount} nesedí s položkami ${calc.taxable}.`, "taxSubtotals");
      }
      const expectedTax = taxAmount(calc.taxable, calc.rate);
      if (!withinSlack(stated.taxAmount, expectedTax)) {
        err("BR-TAX-AMOUNT", `DPH ${stated.category}/${stated.rate} ${stated.taxAmount} nesedí s výpočtom ${expectedTax}. Celok sa nepočíta jednou sadzbou.`, "taxSubtotals");
      }
      taxSum = add(taxSum, stated.taxAmount);
      byKey.delete(key);
    }
    if (byKey.size) {
      err("BR-TAX-MISSING", "Chýba rozpis dane pre niektorú kategóriu na riadkoch.", "taxSubtotals");
    }
    if (!withinSlack(doc.taxAmount, taxSum)) {
      err("BR-TAX-TOTAL", `Celková DPH ${doc.taxAmount} nesedí so súčtom kategórií ${taxSum}.`, "taxAmount");
    }
    const inclusive = add(doc.taxExclusiveAmount, doc.taxAmount);
    if (!withinSlack(doc.taxInclusiveAmount, inclusive)) {
      err("BR-INCLUSIVE", `Suma s DPH ${doc.taxInclusiveAmount} nesedí s ${inclusive}.`, "taxInclusiveAmount");
    }
    const prepaid = doc.prepaidAmount ?? "0.00";
    const rounding = doc.payableRoundingAmount ?? "0.00";
    const payable = add(sub(doc.taxInclusiveAmount, prepaid), rounding);
    if (!withinSlack(doc.payableAmount, payable)) {
      err("BR-PAYABLE", `Suma na úhradu ${doc.payableAmount} nesedí s ${payable}.`, "payableAmount");
    }
  } catch (error) {
    if (error instanceof AppError) throw error;
    err("BR-DECIMAL", error instanceof Error ? error.message : "Chyba desatinnej aritmetiky.");
  }
  return issues;
}

export function assertDecimalStrings(values: string[]): void {
  for (const value of values) parseDecimal(value, "amount");
}
