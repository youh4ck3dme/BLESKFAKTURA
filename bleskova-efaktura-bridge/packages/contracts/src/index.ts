export type Direction = "INBOUND" | "OUTBOUND";
export type DocumentKind = "INVOICE" | "CREDIT_NOTE";
export type ValidationStatus = "NOT_RUN" | "VALID" | "INVALID" | "UNAVAILABLE" | "UNSUPPORTED";
export type DeliveryStatus =
  | "NOT_REQUESTED"
  | "QUEUED"
  | "SUBMITTING"
  | "PROVIDER_ACCEPTED"
  | "DELIVERED"
  | "REJECTED"
  | "UNKNOWN"
  | "FAILED";

export type Lifecycle = "QUARANTINE" | "DRAFT" | "IMPORTED" | "ISSUED";

export interface Address {
  line1: string;
  line2?: string;
  city: string;
  postalCode: string;
  countryCode: string;
}

export interface EndpointId {
  schemeId: string;
  value: string;
}

export interface PartyId {
  schemeId?: string;
  value: string;
}

export interface Party {
  legalName: string;
  tradingName?: string;
  address: Address;
  companyId?: string;
  companyIdScheme?: string;
  taxId?: string;
  vatId?: string;
  endpoint?: EndpointId;
  partyIds?: PartyId[];
  contactName?: string;
  contactPhone?: string;
  contactEmail?: string;
}

export interface Money {
  amount: string;
  currency: string;
}

export interface InvoiceLine {
  id: string;
  name: string;
  description?: string;
  quantity: string;
  unitCode: string;
  unitPrice: string;
  lineAmount: string;
  vatCategory: string;
  vatRate: string;
}

export interface AllowanceCharge {
  charge: boolean;
  reason?: string;
  amount: string;
  vatCategory: string;
  vatRate: string;
}

export interface TaxSubtotal {
  taxableAmount: string;
  taxAmount: string;
  category: string;
  rate: string;
  currency: string;
}

export interface CanonicalDocument {
  kind: DocumentKind;
  direction: Direction;
  number: string;
  customizationId: string;
  profileId: string;
  typeCode: string;
  issueDate: string;
  dueDate?: string;
  deliveryDate?: string;
  currency: string;
  buyerReference?: string;
  accountingCost?: string;
  paymentTermsNote?: string;
  paymentMeansCode?: string;
  paymentId?: string;
  payeeAccountId?: string;
  payeeAccountName?: string;
  payeeBic?: string;
  supplier: Party;
  customer: Party;
  lines: InvoiceLine[];
  allowances: AllowanceCharge[];
  taxSubtotals: TaxSubtotal[];
  taxAmount: string;
  lineExtensionAmount: string;
  taxExclusiveAmount: string;
  taxInclusiveAmount: string;
  allowanceTotalAmount?: string;
  chargeTotalAmount?: string;
  prepaidAmount?: string;
  payableRoundingAmount?: string;
  payableAmount: string;
  precedingInvoiceNumber?: string;
}

export interface ValidationIssue {
  layer: "XML_SECURITY" | "XSD" | "EN16931" | "PEPPOL" | "SK" | "DOMAIN";
  severity: "ERROR" | "WARNING";
  ruleId: string;
  path?: string;
  message: string;
}

export interface LayerResult {
  layer: ValidationIssue["layer"];
  status: ValidationStatus;
  rulesetVersion: string;
}

export interface ValidationReport {
  status: ValidationStatus;
  rulesetVersion: string;
  validatedSha256: string;
  issues: ValidationIssue[];
  layers: LayerResult[];
  /** True only when every layer including SK is VALID. Sandbox is not production. */
  productionEligible: boolean;
  technicalPass: boolean;
}

export interface RequestContext {
  uid: string;
  tenantId: string;
  roles: string[];
}

export interface ProviderCapabilities {
  providerId: string;
  environment: "sandbox" | "production" | "unconfigured";
  simulated: boolean;
  productionReceipts: boolean;
  idempotentSubmit: boolean;
}

export interface ValidatedOutboundSubmission {
  tenantId: string;
  documentId: string;
  sha256: string;
  xml: Uint8Array;
  idempotencyKey: string;
  scenario?: string;
}

export interface ProviderSubmission {
  providerMessageId: string;
  status: DeliveryStatus;
  simulated: boolean;
  productionReceipt: boolean;
  detail?: string;
}

export interface ProviderLookup {
  tenantId: string;
  documentId: string;
  providerMessageId?: string;
}

export interface ProviderDeliveryState {
  status: DeliveryStatus;
  simulated: boolean;
  productionReceipt: boolean;
  providerMessageId?: string;
  detail?: string;
}

export interface RawWebhook {
  providerId: string;
  rawBody: Uint8Array;
  headers: Record<string, string | undefined>;
  receivedAt: string;
}

export interface VerifiedProviderEvent {
  eventId: string;
  providerMessageId: string;
  documentId?: string;
  status: DeliveryStatus;
  occurredAt: string;
  simulated: boolean;
  productionReceipt: boolean;
}

export interface DeliveryProvider {
  capabilities(): ProviderCapabilities;
  submit(input: ValidatedOutboundSubmission): Promise<ProviderSubmission>;
  lookup(input: ProviderLookup): Promise<ProviderDeliveryState>;
  verifyWebhook(input: RawWebhook): Promise<VerifiedProviderEvent>;
}

export interface TenantProfile {
  id: string;
  legalName: string;
  companyId?: string;
  taxId?: string;
  vatId?: string;
  endpoint?: EndpointId;
  countryCode: string;
}

export const SUPPORTED_CUSTOMIZATION_ID =
  "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0";
export const SUPPORTED_PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

export const ERROR_CODES = [
  "UNAUTHORIZED",
  "FORBIDDEN",
  "NOT_FOUND",
  "VALIDATION_FAILED",
  "IDEMPOTENCY_PAYLOAD_MISMATCH",
  "IDEMPOTENCY_IN_PROGRESS",
  "CONFLICT",
  "NUMBER_CONFLICT",
  "TENANT_MISMATCH",
  "IDENTITY_CHANGED",
  "UNSUPPORTED_PROFILE",
  "UNSUPPORTED_SCENARIO",
  "UNSUPPORTED_SYNTAX",
  "PROVIDER_NOT_CONFIGURED",
  "VALIDATOR_UNAVAILABLE",
  "PRODUCTION_GATE",
  "PAYLOAD_TOO_LARGE",
  "RATE_LIMITED",
  "CONCURRENCY_CONFLICT",
  "PRECONDITION_FAILED",
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

export interface ApiErrorBody {
  code: ErrorCode | string;
  message: string;
  correlationId: string;
  issues?: ValidationIssue[];
}
