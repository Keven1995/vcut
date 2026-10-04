import { apiRequest } from "../../lib/api-client";

export type UsageLedgerEntry = {
  readonly operation: string;
  readonly processedMinutes: number;
  readonly renders: number;
  readonly estimatedCost: number;
  readonly outcome: "SUCCEEDED" | "FAILED" | "RELEASED";
  readonly createdAt: string;
};

export type UsageSummary = {
  readonly planCode: "FREE" | "PRO";
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly processingLimitMinutes: number;
  readonly reservedMinutes: number;
  readonly processedMinutes: number;
  readonly remainingMinutes: number;
  readonly storedBytes: number;
  readonly retainedBytes: number;
  readonly storageLimitBytes: number;
  readonly renders: number;
  readonly estimatedCost: number;
  readonly activeJobs: number;
  readonly concurrentJobLimit: number;
  readonly recentLedger: readonly UsageLedgerEntry[];
};

export const usageSummaryQueryKey = "usage-summary";

export async function fetchUsageSummary(): Promise<UsageSummary> {
  const value: unknown = await apiRequest<unknown>("/api/usage");
  if (!isRecord(value) || !isPlanCode(value.planCode)) {
    throw new Error("A API retornou um resumo de uso invalido.");
  }
  const recentLedger = value.recentLedger;
  if (!Array.isArray(recentLedger)) {
    throw new Error("A API retornou um historico de uso invalido.");
  }
  const parsedLedger = recentLedger.map(parseLedgerEntry);
  const numericFields = [
    "processingLimitMinutes",
    "reservedMinutes",
    "processedMinutes",
    "remainingMinutes",
    "storedBytes",
    "retainedBytes",
    "storageLimitBytes",
    "renders",
    "estimatedCost",
    "activeJobs",
    "concurrentJobLimit"
  ] as const;
  if (
    numericFields.some((field) => typeof value[field] !== "number") ||
    typeof value.periodStart !== "string" ||
    typeof value.periodEnd !== "string"
  ) {
    throw new Error("A API retornou um resumo de uso invalido.");
  }
  return {
    planCode: value.planCode,
    periodStart: value.periodStart,
    periodEnd: value.periodEnd,
    processingLimitMinutes: value.processingLimitMinutes as number,
    reservedMinutes: value.reservedMinutes as number,
    processedMinutes: value.processedMinutes as number,
    remainingMinutes: value.remainingMinutes as number,
    storedBytes: value.storedBytes as number,
    retainedBytes: value.retainedBytes as number,
    storageLimitBytes: value.storageLimitBytes as number,
    renders: value.renders as number,
    estimatedCost: value.estimatedCost as number,
    activeJobs: value.activeJobs as number,
    concurrentJobLimit: value.concurrentJobLimit as number,
    recentLedger: parsedLedger
  };
}

function parseLedgerEntry(value: unknown): UsageLedgerEntry {
  if (
    !isRecord(value) ||
    typeof value.operation !== "string" ||
    typeof value.processedMinutes !== "number" ||
    typeof value.renders !== "number" ||
    typeof value.estimatedCost !== "number" ||
    !isOutcome(value.outcome) ||
    typeof value.createdAt !== "string"
  ) {
    throw new Error("A API retornou um registro de uso invalido.");
  }
  return {
    operation: value.operation,
    processedMinutes: value.processedMinutes,
    renders: value.renders,
    estimatedCost: value.estimatedCost,
    outcome: value.outcome,
    createdAt: value.createdAt
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isPlanCode(value: unknown): value is UsageSummary["planCode"] {
  return value === "FREE" || value === "PRO";
}

function isOutcome(value: unknown): value is UsageLedgerEntry["outcome"] {
  return value === "SUCCEEDED" || value === "FAILED" || value === "RELEASED";
}
