import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SubscriptionPanel } from "./subscription-panel";
import {
  cancelSubscription,
  confirmSandboxCheckout,
  createSubscriptionCheckout,
  fetchSubscriptionOverview,
  type SubscriptionOverview
} from "./subscription-api";

vi.mock("./subscription-api", () => ({
  cancelSubscription: vi.fn(),
  confirmSandboxCheckout: vi.fn(),
  createSubscriptionCheckout: vi.fn(),
  fetchSubscriptionOverview: vi.fn(),
  subscriptionOverviewQueryKey: "subscription-overview"
}));

const overview: SubscriptionOverview = {
  sandboxEnabled: true,
  plans: [
    {
      planCode: "FREE",
      displayName: "Free",
      monthlyPriceMinorUnits: 0,
      currency: "USD",
      monthlyProcessingMinutes: 60,
      maxFileSizeBytes: 512 * 1024 * 1024,
      maxDurationSeconds: 7_200,
      maxStorageBytes: 5 * 1024 * 1024 * 1024,
      maxConcurrentJobs: 1,
      workerPriority: 0,
      maxWidth: 1_920,
      maxHeight: 1_920,
      retention: {
        originalDays: 30,
        audioDays: 7,
        framesDays: 3,
        previewDays: 7,
        finalDays: 30,
        thumbnailDays: 30,
        failedJobArtifactDays: 7
      }
    },
    {
      planCode: "PRO",
      displayName: "Pro",
      monthlyPriceMinorUnits: 0,
      currency: "USD",
      monthlyProcessingMinutes: 600,
      maxFileSizeBytes: 2 * 1024 * 1024 * 1024,
      maxDurationSeconds: 7_200,
      maxStorageBytes: 50 * 1024 * 1024 * 1024,
      maxConcurrentJobs: 3,
      workerPriority: 5,
      maxWidth: 3_840,
      maxHeight: 3_840,
      retention: {
        originalDays: 90,
        audioDays: 30,
        framesDays: 14,
        previewDays: 30,
        finalDays: 90,
        thumbnailDays: 90,
        failedJobArtifactDays: 14
      }
    }
  ],
  currentSubscription: null,
  checkoutSessions: [
    {
      id: "checkout-pending",
      planCode: "PRO",
      status: "PENDING",
      checkoutUrl: "http://localhost:3000/dashboard/subscription?checkoutSessionId=checkout-pending",
      amountMinorUnits: 0,
      currency: "USD",
      expiresAt: "2026-10-04T12:30:00Z",
      createdAt: "2026-10-04T12:00:00Z",
      completedAt: null
    },
    {
      id: "checkout-failed",
      planCode: "PRO",
      status: "FAILED",
      checkoutUrl: null,
      amountMinorUnits: 0,
      currency: "USD",
      expiresAt: "2026-10-04T11:00:00Z",
      createdAt: "2026-10-04T10:30:00Z",
      completedAt: null
    }
  ],
  recentPayments: [],
  reconciliation: {
    currency: "USD",
    appliedEventCount: 0,
    ledgerEntryCount: 0,
    providerNetMinorUnits: 0,
    ledgerNetMinorUnits: 0,
    discrepancyMinorUnits: 0
  }
};

describe("SubscriptionPanel", () => {
  beforeEach(() => {
    vi.mocked(fetchSubscriptionOverview).mockResolvedValue(overview);
    vi.mocked(confirmSandboxCheckout).mockResolvedValue(overview);
    vi.mocked(createSubscriptionCheckout).mockResolvedValue(overview);
    vi.mocked(cancelSubscription).mockResolvedValue(overview);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("shows configured benefits, sandbox checkout and failed payment history", async () => {
    render(<SubscriptionPanel />);

    expect(await screen.findByRole("heading", { name: "Assinatura" })).toBeTruthy();
    expect(
      screen.getByText("Modo sandbox: pagamentos são simulados e não há cobrança real.")
    ).toBeTruthy();
    expect(screen.getByText("Gratuito")).toBeTruthy();
    expect(screen.getByText("600 minutos processados")).toBeTruthy();
    expect(screen.getByText(/Aguardando pagamento/)).toBeTruthy();
    expect(screen.getByText(/Falhou/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Simular pagamento aprovado" })).toBeTruthy();
  });

  it("confirms a sandbox checkout through the API action", async () => {
    render(<SubscriptionPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Simular pagamento aprovado" }));

    await waitFor(() => expect(confirmSandboxCheckout).toHaveBeenCalledWith("checkout-pending"));
  });

  it("shows active subscription status and sends an end-of-period cancellation request", async () => {
    vi.mocked(fetchSubscriptionOverview).mockResolvedValue({
      ...overview,
      currentSubscription: {
        id: "subscription-pro",
        planCode: "PRO",
        status: "ACTIVE",
        periodStart: "2026-10-01T00:00:00Z",
        periodEnd: "2026-11-01T00:00:00Z",
        cancelAtPeriodEnd: false,
        monthlyPriceMinorUnits: 1_200,
        currency: "USD",
        createdAt: "2026-10-01T00:00:00Z"
      }
    });

    render(<SubscriptionPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Cancelar no fim do período" }));

    await waitFor(() => expect(cancelSubscription).toHaveBeenCalledWith("subscription-pro"));
  });
});
