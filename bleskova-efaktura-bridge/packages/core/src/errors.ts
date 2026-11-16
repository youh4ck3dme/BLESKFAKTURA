import type { ErrorCode, ValidationIssue } from "../../contracts/src/index.js";

export class AppError extends Error {
  readonly code: ErrorCode | string;
  readonly httpStatus: number;
  readonly issues?: ValidationIssue[];

  constructor(code: ErrorCode | string, httpStatus: number, message: string, issues?: ValidationIssue[]) {
    super(message);
    this.name = "AppError";
    this.code = code;
    this.httpStatus = httpStatus;
    this.issues = issues;
  }
}
