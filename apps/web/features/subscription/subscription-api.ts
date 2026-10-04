import { apiRequest } from "../../lib/api-client";

export type PlanCode = "FREE" | "PRO";
export type SubscriptionStatus = "ACTIVE" | "PAST_DUE" | "CANCELED" | "EXPIRED" | "CHARGEBACK";
export type CheckoutStatus = "PENDING" | "COMPLETED" | "EXPIRED" | "FAILED";
export type BillingEntryType = "CHARGE" | "REFUND" | "CHARGEBACK";

export type PlanOffer = {
  readonly planCode: PlanCode;
  readonly displayName: string;
  readonly monthlyPriceMinorUnits: number;
  readonly currency: string;
  readonly monthlyProcessingMinutes: number;
  readonly maxFileSizeBytes: number;
  readonly maxDurationSeconds: number;
  readonly maxStorageBytes: number;
  readonly maxConcurrentJobs: number;
  readonly workerPriority: number;
  readonly maxWidth: number;
  readonly maxHeight: number;
  readonly retention: {
    readonly originalDays: number;
    readonly audioDays: number;
    readonly framesDays: number;
    readonly previewDays: number;
    readonly finalDays: number;
    readonly thumbnailDays: number;
    readonly failedJobArtifactDays: number;
  };
};

export type PaidSubscription = {
  readonly id: string;
  readonly planCode: PlanCode;
  readonly status: SubscriptionStatus;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly cancelAtPeriodEnd: boolean;
  readonly monthlyPriceMinorUnits: number;
  readonly currency: string;
  readonly createdAt: string;
};

export type CheckoutSession = {
  readonly id: string;
  readonly planCode: PlanCode;
  readonly status: CheckoutStatus;
  readonly checkoutUrl: string | null;
  readonly amountMinorUnits: number;
  readonly currency: string;
  readonly expiresAt: string;
  readonly createdAt: string;
  readonly completedAt: string | null;
};

export type BillingPayment = {
  readonly type: BillingEntryType;
  readonly amountMinorUnits: number;
  readonly currency: string;
  readonly occurredAt: string;
};

export type BillingReconciliation = {
  readonly currency: string;
  readonly appliedEventCount: number;
  readonly ledgerEntryCount: number;
  readonly providerNetMinorUnits: number;
  readonly ledgerNetMinorUnits: number;
  readonly discrepancyMinorUnits: number;
};

export type SubscriptionOverview = {
  readonly sandboxEnabled: boolean;
  readonly plans: readonly PlanOffer[];
  readonly currentSubscription: PaidSubscription | null;
  readonly checkoutSessions: readonly CheckoutSession[];
  readonly recentPayments: readonly BillingPayment[];
  readonly reconciliation: BillingReconciliation;
};

export const subscriptionOverviewQueryKey = "subscription-overview";

export async function fetchSubscriptionOverview(): Promise<SubscriptionOverview> {
  return parseSubscriptionOverview(await apiRequest<unknown>("/api/subscriptions"));
}

export async function createSubscriptionCheckout(
  planCode: PlanCode
): Promise<SubscriptionOverview> {
  return parseSubscriptionOverview(
    await apiRequest<unknown>("/api/subscriptions/checkout", {
      method: "POST",
      body: JSON.stringify({ planCode })
    })
  );
}

export async function cancelSubscription(id: string): Promise<SubscriptionOverview> {
  return parseSubscriptionOverview(
    await apiRequest<unknown>(`/api/subscriptions/${encodeURIComponent(id)}/cancel`, {
      method: "POST"
    })
  );
}

export async function confirmSandboxCheckout(id: string): Promise<SubscriptionOverview> {
  return parseSubscriptionOverview(
    await apiRequest<unknown>(
      `/api/subscriptions/checkouts/${encodeURIComponent(id)}/sandbox-confirm`,
      { method: "POST" }
    )
  );
}

function parseSubscriptionOverview(value: unknown): SubscriptionOverview {
  if (!isRecord(value) || typeof value.sandboxEnabled !== "boolean") {
    throw new Error("A API retornou um resumo de assinatura inválido.");
  }
  if (!Array.isArray(value.plans) || !Array.isArray(value.checkoutSessions) || !Array.isArray(value.recentPayments)) {
    throw new Error("A API retornou listas de cobrança inválidas.");
  }
  return {
    sandboxEnabled: value.sandboxEnabled,
    plans: value.plans.map(parsePlanOffer),
    currentSubscription:
      value.currentSubscription === null ? null : parsePaidSubscription(value.currentSubscription),
    checkoutSessions: value.checkoutSessions.map(parseCheckoutSession),
    recentPayments: value.recentPayments.map(parseBillingPayment),
    reconciliation: parseReconciliation(value.reconciliation)
  };
}

function parsePlanOffer(value: unknown): PlanOffer {
  if (!isRecord(value) || !isPlanCode(value.planCode) || !isRecord(value.retention)) {
    throw new Error("A API retornou um plano inválido.");
  }
  return {
    planCode: value.planCode,
    displayName: requiredString(value, "displayName"),
    monthlyPriceMinorUnits: requiredNumber(value, "monthlyPriceMinorUnits"),
    currency: requiredCurrency(value, "currency"),
    monthlyProcessingMinutes: requiredNumber(value, "monthlyProcessingMinutes"),
    maxFileSizeBytes: requiredNumber(value, "maxFileSizeBytes"),
    maxDurationSeconds: requiredNumber(value, "maxDurationSeconds"),
    maxStorageBytes: requiredNumber(value, "maxStorageBytes"),
    maxConcurrentJobs: requiredNumber(value, "maxConcurrentJobs"),
    workerPriority: requiredNumber(value, "workerPriority"),
    maxWidth: requiredNumber(value, "maxWidth"),
    maxHeight: requiredNumber(value, "maxHeight"),
    retention: {
      originalDays: requiredNumber(value.retention, "originalDays"),
      audioDays: requiredNumber(value.retention, "audioDays"),
      framesDays: requiredNumber(value.retention, "framesDays"),
      previewDays: requiredNumber(value.retention, "previewDays"),
      finalDays: requiredNumber(value.retention, "finalDays"),
      thumbnailDays: requiredNumber(value.retention, "thumbnailDays"),
      failedJobArtifactDays: requiredNumber(value.retention, "failedJobArtifactDays")
    }
  };
}

function parsePaidSubscription(value: unknown): PaidSubscription {
  if (!isRecord(value) || !isPlanCode(value.planCode) || !isSubscriptionStatus(value.status)) {
    throw new Error("A API retornou um estado de assinatura inválido.");
  }
  return {
    id: requiredString(value, "id"),
    planCode: value.planCode,
    status: value.status,
    periodStart: requiredString(value, "periodStart"),
    periodEnd: requiredString(value, "periodEnd"),
    cancelAtPeriodEnd: requiredBoolean(value, "cancelAtPeriodEnd"),
    monthlyPriceMinorUnits: requiredNumber(value, "monthlyPriceMinorUnits"),
    currency: requiredCurrency(value, "currency"),
    createdAt: requiredString(value, "createdAt")
  };
}

function parseCheckoutSession(value: unknown): CheckoutSession {
  if (!isRecord(value) || !isPlanCode(value.planCode) || !isCheckoutStatus(value.status)) {
    throw new Error("A API retornou uma sessão de checkout inválida.");
  }
  const checkoutUrl = value.checkoutUrl;
  const completedAt = value.completedAt;
  if (
    (checkoutUrl !== null && typeof checkoutUrl !== "string") ||
    (completedAt !== null && typeof completedAt !== "string")
  ) {
    throw new Error("A API retornou dados de checkout inválidos.");
  }
  if (typeof checkoutUrl === "string") {
    const parsedUrl = new URL(checkoutUrl);
    if (parsedUrl.protocol !== "https:" && parsedUrl.protocol !== "http:") {
      throw new Error("A API retornou uma URL de checkout inválida.");
    }
  }
  return {
    id: requiredString(value, "id"),
    planCode: value.planCode,
    status: value.status,
    checkoutUrl,
    amountMinorUnits: requiredNumber(value, "amountMinorUnits"),
    currency: requiredCurrency(value, "currency"),
    expiresAt: requiredString(value, "expiresAt"),
    createdAt: requiredString(value, "createdAt"),
    completedAt
  };
}

function parseBillingPayment(value: unknown): BillingPayment {
  if (!isRecord(value) || !isBillingEntryType(value.type)) {
    throw new Error("A API retornou um registro financeiro inválido.");
  }
  return {
    type: value.type,
    amountMinorUnits: requiredNumber(value, "amountMinorUnits"),
    currency: requiredCurrency(value, "currency"),
    occurredAt: requiredString(value, "occurredAt")
  };
}

function parseReconciliation(value: unknown): BillingReconciliation {
  if (!isRecord(value)) {
    throw new Error("A API retornou dados de conciliação inválidos.");
  }
  return {
    currency: requiredCurrency(value, "currency"),
    appliedEventCount: requiredNumber(value, "appliedEventCount"),
    ledgerEntryCount: requiredNumber(value, "ledgerEntryCount"),
    providerNetMinorUnits: requiredNumber(value, "providerNetMinorUnits"),
    ledgerNetMinorUnits: requiredNumber(value, "ledgerNetMinorUnits"),
    discrepancyMinorUnits: requiredNumber(value, "discrepancyMinorUnits")
  };
}

function requiredString(value: Record<string, unknown>, field: string): string {
  const item = value[field];
  if (typeof item !== "string") {
    throw new Error(`A API retornou o campo ${field} inválido.`);
  }
  return item;
}

function requiredNumber(value: Record<string, unknown>, field: string): number {
  const item = value[field];
  if (typeof item !== "number" || !Number.isFinite(item)) {
    throw new Error(`A API retornou o campo ${field} inválido.`);
  }
  return item;
}

function requiredBoolean(value: Record<string, unknown>, field: string): boolean {
  const item = value[field];
  if (typeof item !== "boolean") {
    throw new Error(`A API retornou o campo ${field} inválido.`);
  }
  return item;
}

function requiredCurrency(value: Record<string, unknown>, field: string): string {
  const currency = requiredString(value, field);
  if (!/^[A-Z]{3}$/.test(currency)) {
    throw new Error(`A API retornou o campo ${field} inválido.`);
  }
  try {
    new Intl.NumberFormat("pt-BR", { style: "currency", currency });
  } catch {
    throw new Error(`A API retornou o campo ${field} inválido.`);
  }
  return currency;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isPlanCode(value: unknown): value is PlanCode {
  return value === "FREE" || value === "PRO";
}

function isSubscriptionStatus(value: unknown): value is SubscriptionStatus {
  return value === "ACTIVE" || value === "PAST_DUE" || value === "CANCELED" || value === "EXPIRED" || value === "CHARGEBACK";
}

function isCheckoutStatus(value: unknown): value is CheckoutStatus {
  return value === "PENDING" || value === "COMPLETED" || value === "EXPIRED" || value === "FAILED";
}

function isBillingEntryType(value: unknown): value is BillingEntryType {
  return value === "CHARGE" || value === "REFUND" || value === "CHARGEBACK";
}
