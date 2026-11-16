import { Decimal } from "decimal.js";

Decimal.set({ precision: 40, rounding: Decimal.ROUND_HALF_UP });

const DECIMAL_RE = /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/;

export class DecimalInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DecimalInputError";
  }
}

export function parseDecimal(raw: string, label: string): Decimal {
  if (typeof raw !== "string" || !DECIMAL_RE.test(raw.trim())) {
    throw new DecimalInputError(`Neplatné desatinné číslo pre ${label}. Čiarka, NaN a Infinity nie sú povolené.`);
  }
  return new Decimal(raw.trim());
}

export function formatDecimal(value: Decimal, scale: number): string {
  return value.toDecimalPlaces(scale, Decimal.ROUND_HALF_UP).toFixed(scale);
}

export function add(a: string, b: string, scale = 2): string {
  return formatDecimal(parseDecimal(a, "a").plus(parseDecimal(b, "b")), scale);
}

export function sub(a: string, b: string, scale = 2): string {
  return formatDecimal(parseDecimal(a, "a").minus(parseDecimal(b, "b")), scale);
}

export function lineNet(quantity: string, unitPrice: string): string {
  return formatDecimal(parseDecimal(quantity, "quantity").mul(parseDecimal(unitPrice, "unitPrice")), 2);
}

export function taxAmount(taxable: string, ratePercent: string): string {
  const tax = parseDecimal(taxable, "taxable").mul(parseDecimal(ratePercent, "rate")).div(100);
  return formatDecimal(tax, 2);
}

/** Absolute difference is within the supplied slack. Default 0.02 matches common Peppol u:slack usage, not a legal rounding rule. */
export function withinSlack(actual: string, expected: string, slack = "0.02"): boolean {
  const diff = parseDecimal(actual, "actual").minus(parseDecimal(expected, "expected")).abs();
  return diff.lte(parseDecimal(slack, "slack"));
}

export function isZeroScaleCurrency(code: string): boolean {
  return code === "JPY" || code === "KRW" || code === "VND" || code === "CLP";
}
