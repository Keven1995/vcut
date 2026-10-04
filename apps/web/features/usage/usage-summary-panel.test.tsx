import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UsageSummaryPanel } from "./usage-summary-panel";
import { fetchUsageSummary, type UsageSummary } from "./usage-api";

vi.mock("./usage-api", () => ({
  fetchUsageSummary: vi.fn(),
  usageSummaryQueryKey: "usage-summary"
}));

const summary: UsageSummary = {
  planCode: "FREE",
  periodStart: "2026-10-01T00:00:00Z",
  periodEnd: "2026-11-01T00:00:00Z",
  processingLimitMinutes: 60,
  reservedMinutes: 5,
  processedMinutes: 25,
  remainingMinutes: 30,
  storedBytes: 50 * 1024 * 1024,
  retainedBytes: 50 * 1024 * 1024,
  storageLimitBytes: 5 * 1024 * 1024 * 1024,
  renders: 2,
  estimatedCost: 0.125,
  activeJobs: 1,
  concurrentJobLimit: 1,
  recentLedger: [
    {
      operation: "TRANSCRIPTION",
      processedMinutes: 25,
      renders: 0,
      estimatedCost: 0.125,
      outcome: "SUCCEEDED",
      createdAt: "2026-10-03T12:00:00Z"
    }
  ]
};

describe("UsageSummaryPanel", () => {
  beforeEach(() => vi.mocked(fetchUsageSummary).mockResolvedValue(summary));
  afterEach(cleanup);

  it("displays quota, storage, concurrent processing and usage history", async () => {
    render(<UsageSummaryPanel />);

    expect(await screen.findByText("Consumo e limites")).toBeTruthy();
    expect(screen.getByText("25 / 60")).toBeTruthy();
    expect(screen.getByText("50.0 MB / 5.0 GB")).toBeTruthy();
    expect(screen.getByText(/jobs ativos de/)).toBeTruthy();
    expect(screen.getByText("TRANSCRIPTION")).toBeTruthy();
  });
});
