import type { RequestContext } from "../../contracts/src/index.js";

/**
 * Semantics mirrored by firestore.rules. This is NOT the Firebase emulator.
 * Emulator execution is NOT_RUN unless firebase-tools is available.
 */
export interface RuleRequest {
  authUid?: string;
  tenantId: string;
  memberTenantIds: string[];
  path: string;
  write: boolean;
  adminSdk: boolean;
}

export function evaluateClientRule(request: RuleRequest): { allow: boolean; reason: string } {
  if (request.adminSdk) return { allow: true, reason: "admin-sdk-bypasses-rules" };
  if (!request.authUid) return { allow: false, reason: "unauthenticated" };
  if (!request.memberTenantIds.includes(request.tenantId)) return { allow: false, reason: "cross-tenant" };
  if (request.write && /\/(delivery|validation|originals)\b/.test(request.path)) {
    return { allow: false, reason: "client-cannot-write-authoritative-fields" };
  }
  if (request.write && request.path.includes("/issued/")) return { allow: false, reason: "issued-original-immutable" };
  return { allow: true, reason: "member-read-or-note" };
}

export function assertServiceAuthorization(ctx: RequestContext, ownerTenantId: string, members: Map<string, string[]>): void {
  const roles = members.get(`${ctx.uid}|${ctx.tenantId}`);
  if (!roles || ctx.tenantId !== ownerTenantId) {
    throw new Error("FORBIDDEN");
  }
}
