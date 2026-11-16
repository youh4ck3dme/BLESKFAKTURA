import { createHmac, timingSafeEqual } from "node:crypto";
import { AppError } from "../../../packages/core/src/errors.js";

export interface EmuPayload {
  uid?: string;
  typ: string;
  exp: number;
}

export function signEmuToken(secret: string, payload: EmuPayload): string {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const sig = createHmac("sha256", secret).update(body).digest("base64url");
  return `emu.${body}.${sig}`;
}

export function verifyEmuToken(token: string, secret: string, typ: string): EmuPayload {
  const [prefix, body, sig] = token.split(".");
  if (prefix !== "emu" || !body || !sig) throw new AppError("UNAUTHORIZED", 401, "Token bol odmietnutý.");
  const expected = createHmac("sha256", secret).update(body).digest("base64url");
  const a = Buffer.from(expected);
  const b = Buffer.from(sig);
  if (a.length !== b.length || !timingSafeEqual(a, b)) throw new AppError("UNAUTHORIZED", 401, "Token bol odmietnutý.");
  const payload = JSON.parse(Buffer.from(body, "base64url").toString("utf8")) as EmuPayload;
  if (payload.typ !== typ || payload.exp < Date.now()) throw new AppError("UNAUTHORIZED", 401, "Token je neplatný alebo exspirovaný.");
  return payload;
}
